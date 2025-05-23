package com.example.weathergapp

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.weathergapp.apiServices.WeatherDataRepo
import com.example.weathergapp.apiServices.apiServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// Data classes
data class CurrentWeatherUIState(
    val locationName: String = "N/A",
    val coordinates: String = "Lat: N/A, Lon: N/A",
    val observationTime: String = "N/A",
    val temperature: String = "N/A",
    val pressure: String = "N/A",
    val weatherCondition: String = "N/A",
    val weatherIcon: ImageVector = Icons.Filled.CloudOff,
    val windSpeed: String = "N/A",
    val windDirection: String = "N/A",
    val humidity: String = "N/A",
    val visibility: String = "N/A",
    val feelsLike: String = "N/A",
    val sunrise: String = "N/A",
    val sunset: String = "N/A",
    val isLoading: Boolean = true,
    val error: String? = null,
    val isOfflineData: Boolean = false
)

data class DailyForecastItem(
    val date: String,
    val dayOfWeek: String,
    val tempMin: String,
    val tempMax: String,
    val condition: String,
    val icon: ImageVector
)

data class ForecastUIState(
    val dailyForecasts: List<DailyForecastItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isOfflineData: Boolean = false
)

class MainActivity : ComponentActivity(), WeatherDataRepo.WeatherDataObserver {

    private lateinit var apiService: apiServices
    private var userInputLocation by mutableStateOf("Warsaw, PL") // Default location
    private var currentWeatherDataState by mutableStateOf(CurrentWeatherUIState())
    private var forecastDataState by mutableStateOf(ForecastUIState())
    private var forecastLoadingState by mutableStateOf(false) // Renamed from общийLoadingState
    private var errorMessage by mutableStateOf<String?>(null)
    private var selectedUnit by mutableStateOf("°C")

    private var showSettingsDialog by mutableStateOf(false)
    private var autoRefreshIntervalMinutes by mutableStateOf(SettingsManager.DEFAULT_REFRESH_INTERVAL_MINUTES)

    private var favoriteLocations by mutableStateOf(listOf<String>())
    private var showFavoritesDialog by mutableStateOf(false)

    private var showOfflineWarningBanner by mutableStateOf(false)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        apiService = apiServices(this)
        favoriteLocations = apiService.loadFavorites().toMutableList()
        autoRefreshIntervalMinutes = SettingsManager.loadRefreshInterval(this)

        // C H A N G E D
        val loadedUnit = SettingsManager.loadUnit(this)
        selectedUnit = loadedUnit
        DataManager.unit = loadedUnit

        DataManager.registerObserver(object : DataManager.UnitObserver {
            override fun onUnitChanged(newUnit: String) {
                val oldUnit = selectedUnit
                selectedUnit = newUnit

                SettingsManager.saveUnit(this@MainActivity, newUnit)
                Log.d("MainActivity", "Unit changed and saved: $newUnit")

                if (oldUnit != newUnit && userInputLocation.isNotEmpty() && !currentWeatherDataState.isLoading) {
                    if (apiService.isNetworkAvailable()) {
                        fetchWeatherData(userInputLocation, isManualRefresh = true)
                    } else {
                        loadOfflineWeatherData(userInputLocation, newUnit, showToastIfNotFound = false)
                        Toast.makeText(this@MainActivity, "Unit changed. Offline data might need online refresh for full accuracy.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })

        val lastViewedLocation = apiService.loadLastViewedLocation()
        userInputLocation = lastViewedLocation ?: favoriteLocations.firstOrNull() ?: "Warsaw, PL" // Default to Warsaw, PL

        if (apiService.isNetworkAvailable()) {
            showOfflineWarningBanner = false
            Log.d("MainActivity", "Network available. Fetching data for: $userInputLocation")
            fetchWeatherData(userInputLocation, isManualRefresh = false)
            lifecycleScope.launch {
                cacheFavoriteLocationsData()
            }
        } else {
            showOfflineWarningBanner = true
            Log.d("MainActivity", "Network unavailable. Attempting to load offline data for: $userInputLocation")
            Toast.makeText(this, "No internet connection. Displaying saved data.", Toast.LENGTH_LONG).show()
            loadOfflineWeatherData(userInputLocation, selectedUnit, showToastIfNotFound = true)
        }

        WeatherDataRepo.registerWeatherObserver(this)
        WeatherDataRepo.registerForecastObserver(this)

        setContent {
            WeatherAppScreen()

            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            LaunchedEffect(autoRefreshIntervalMinutes, userInputLocation, lifecycleOwner, apiService.isNetworkAvailable()) {
                if (autoRefreshIntervalMinutes > 0 && apiService.isNetworkAvailable()) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            Log.d("AutoRefresh", "Delaying for $autoRefreshIntervalMinutes minutes.")
                            delay(autoRefreshIntervalMinutes * 60 * 1000L)
                            if (isActive && apiService.isNetworkAvailable()) {
                                Log.d("AutoRefresh", "Interval elapsed, fetching weather data for $userInputLocation")
                                fetchWeatherData(userInputLocation, isManualRefresh = false)
                            } else if (!apiService.isNetworkAvailable()) {
                                Log.d("AutoRefresh", "Network lost, auto-refresh paused.")
                                showOfflineWarningBanner = true
                            }
                        }
                    }
                } else if (autoRefreshIntervalMinutes > 0 && !apiService.isNetworkAvailable()) {
                    Log.d("AutoRefresh", "No network, auto-refresh inactive at start.")
                    showOfflineWarningBanner = true
                }
            }
        }
    }
    private suspend fun cacheFavoriteLocationsData() {
        if (!apiService.isNetworkAvailable()) return

        Log.d("MainActivity", "Starting to cache data for favorites.")
        favoriteLocations.forEach { favLocation ->
            apiService.getData(favLocation, selectedUnit, object : apiServices.ResponseCallback {
                override fun onSuccess(result: String) {
                    try {
                        val json = JSONObject(result)
                        val locationNameFromApi = json.optString("name", favLocation)
                        apiService.saveCurrentWeatherData(locationNameFromApi, result)
                        Log.d("CacheFavorites", "Cached current weather for favorite: $locationNameFromApi")

                        val coord = json.optJSONObject("coord")
                        if (coord != null) {
                            apiService.getForecastData(coord.getDouble("lat"), coord.getDouble("lon"), selectedUnit, object : apiServices.ResponseCallback {
                                override fun onSuccess(forecastResult: String) { // Changed variable name from result to forecastResult
                                    apiService.saveForecastData(locationNameFromApi, forecastResult)
                                    Log.d("CacheFavorites", "Cached forecast for favorite: $locationNameFromApi")
                                }
                                override fun onFailure(e: IOException?) {
                                    Log.e("CacheFavorites", "Failed to fetch forecast for favorite $locationNameFromApi", e)
                                }
                            })
                        }
                    } catch (e: JSONException) {
                        Log.e("CacheFavorites", "JSON parsing error for favorite $favLocation (current weather)", e)
                    }
                }
                override fun onFailure(e: IOException?) {
                    Log.e("CacheFavorites", "Failed to fetch current weather for favorite $favLocation", e)
                }
            })
            delay(1000) // Small delay between API requests to avoid rate limiting
        }
        Log.d("MainActivity", "Finished caching data for favorites.")
    }

    private fun loadOfflineWeatherData(location: String, unit: String, showToastIfNotFound: Boolean) {
        Log.d("MainActivity", "Attempting to load offline data for: $location, unit: $unit")
        forecastLoadingState = true
        currentWeatherDataState = CurrentWeatherUIState(isLoading = true, locationName = location, isOfflineData = true)
        forecastDataState = ForecastUIState(isLoading = true, isOfflineData = true)

        val currentJsonString = apiService.loadCurrentWeatherData(location)
        val forecastJsonString = apiService.loadForecastData(location)
        var currentDataLoaded = false
        //var forecastDataLoaded = false // This variable was not used

        if (currentJsonString != null) {
            try {
                val currentJson = JSONObject(currentJsonString)
                currentWeatherDataState = parseCurrentWeatherJson(currentJson, unit).copy(isOfflineData = true)
                currentDataLoaded = true
                Log.d("MainActivity", "Successfully loaded current offline data for $location")
            } catch (e: JSONException) {
                Log.e("MainActivity", "Error parsing current offline data for $location", e)
                currentWeatherDataState = CurrentWeatherUIState(isLoading = false, error = "Error loading offline data.", locationName = location, isOfflineData = true)
            }
        } else {
            Log.d("MainActivity", "No saved current offline data for $location")
            currentWeatherDataState = CurrentWeatherUIState(isLoading = false, error = "No saved data for $location.", locationName = location, isOfflineData = true)
        }

        if (forecastJsonString != null) {
            try {
                val forecastJson = JSONObject(forecastJsonString)
                forecastDataState = parseForecastJson(forecastJson, unit).copy(isOfflineData = true)
                // forecastDataLoaded = true; // This variable was not used
                Log.d("MainActivity", "Successfully loaded forecast offline data for $location")
            } catch (e: JSONException) {
                Log.e("MainActivity", "Error parsing forecast offline data for $location", e)
                forecastDataState = ForecastUIState(isLoading = false, error = "Error loading offline forecast.", isOfflineData = true)
            }
        } else {
            Log.d("MainActivity", "No saved forecast offline data for $location")
            forecastDataState = if (currentDataLoaded) {
                ForecastUIState(isLoading = false, error = "No saved forecast for $location.", isOfflineData = true)
            } else {
                ForecastUIState(isLoading = false, error = "No saved data for $location.", isOfflineData = true)
            }
        }
        forecastLoadingState = false
        showOfflineWarningBanner = true

        if (!currentDataLoaded && showToastIfNotFound) { // Simplified condition, if current data not loaded, then forecast also not relevant for this specific location
            Toast.makeText(this, "No saved offline data for $location.", Toast.LENGTH_LONG).show()
            if (location != favoriteLocations.firstOrNull() && favoriteLocations.isNotEmpty()) {
                val firstFavorite = favoriteLocations.first()
                userInputLocation = firstFavorite
                Log.d("MainActivity", "No data for $location, trying to load for first favorite: $firstFavorite")
                loadOfflineWeatherData(firstFavorite, unit, showToastIfNotFound = false)
            } else if (favoriteLocations.isEmpty() && location != "Warsaw, PL") { // Check against new default
                userInputLocation = "Warsaw, PL"
                Log.d("MainActivity", "No data and no favorites, trying to load for Warsaw, PL")
                loadOfflineWeatherData("Warsaw, PL", unit, showToastIfNotFound = false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WeatherDataRepo.unregisterWeatherObserver(this)
        WeatherDataRepo.unregisterForecastObserver(this)
        // DataManager observer is not explicitly unregistered here, which might be a minor leak if MainActivity is destroyed and recreated often without app restart.
        // However, DataManager is an object, so it lives as long as the app.
    }

    override fun onWeatherDataUpdated() {
        val newJson = WeatherDataRepo.getWeatherDataJson()
        runOnUiThread {
            currentWeatherDataState = if (newJson != null) {
                parseCurrentWeatherJson(newJson, selectedUnit).copy(isOfflineData = !apiService.isNetworkAvailable())
            } else {
                currentWeatherDataState.copy(
                    isLoading = false,
                    error = "Failed to load current weather."
                )
            }
        }
    }

    override fun onForecastDataUpdated() {
        val newJson = WeatherDataRepo.getForecastDataJson()
        runOnUiThread {
            forecastDataState = if (newJson != null) {
                parseForecastJson(newJson, selectedUnit).copy(isOfflineData = !apiService.isNetworkAvailable())
            } else {
                forecastDataState.copy(
                    isLoading = false,
                    error = "Failed to load forecast."
                )
            }
        }
    }

    override fun onHourlyDataUpdated() { /* 10H roboty robi BRRRRRRRRR*/ }

    private fun fetchWeatherData(location: String, isManualRefresh: Boolean) {
        if (location.isBlank()) {
            runOnUiThread {
                errorMessage = "Please enter a location."
                if (isManualRefresh) Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!apiService.isNetworkAvailable()) {
            showOfflineWarningBanner = true
            Toast.makeText(this, "No internet connection. Attempting to load offline data.", Toast.LENGTH_LONG).show()
            loadOfflineWeatherData(location, selectedUnit, showToastIfNotFound = true)
            return
        }
        showOfflineWarningBanner = false
        Log.d("MainActivity", "Fetching data for: $location, Unit: $selectedUnit, Manual: $isManualRefresh")
        forecastLoadingState = true
        errorMessage = null
        currentWeatherDataState = CurrentWeatherUIState(isLoading = true, locationName = location)
        forecastDataState = ForecastUIState(isLoading = true)

        apiService.getData(location, selectedUnit, object : apiServices.ResponseCallback {
            override fun onSuccess(result: String) {
                try {
                    val json = JSONObject(result)
                    val locationNameFromApi = json.optString("name", location)
                    val country = json.optJSONObject("sys")?.optString("country", "")
                    val actualLocationName = if (country.isNullOrBlank() || locationNameFromApi.contains(country)) locationNameFromApi else "$locationNameFromApi, $country"

                    apiService.saveCurrentWeatherData(actualLocationName, result)
                    apiService.saveLastViewedLocation(actualLocationName)
                    if (userInputLocation != actualLocationName) {
                        userInputLocation = actualLocationName
                    }

                    WeatherDataRepo.setWeatherDataJson(json)
                    val coord = json.optJSONObject("coord")
                    if (coord != null) {
                        fetchForecastData(actualLocationName, coord.getDouble("lat"), coord.getDouble("lon"), isManualRefresh)
                    } else {
                        handleFetchError("Coordinates not found.", isManualRefresh)
                    }
                } catch (e: JSONException) {
                    Log.e("MainActivity", "Error parsing current weather JSON", e)
                    handleFetchError("Error parsing current weather: ${e.message}", isManualRefresh)
                }
            }

            override fun onFailure(e: IOException?) {
                Log.e("MainActivity", "Failed to fetch current weather", e)
                handleFetchError("API Error: ${e?.message ?: "Unknown error"}", isManualRefresh)
                loadOfflineWeatherData(location, selectedUnit, showToastIfNotFound = false)
            }
        })
    }

    private fun fetchForecastData(locationNameForFile: String, lat: Double, lon: Double, isManualRefresh: Boolean) {
        apiService.getForecastData(lat, lon, selectedUnit, object : apiServices.ResponseCallback {
            override fun onSuccess(result: String) {
                try {
                    apiService.saveForecastData(locationNameForFile, result)
                    WeatherDataRepo.setForecastDataJson(JSONObject(result))
                    if (isManualRefresh) {
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Weather data refreshed!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: JSONException) {
                    Log.e("MainActivity", "Error parsing forecast JSON", e)
                    handleFetchError("Error parsing forecast: ${e.message}", isManualRefresh, isForecast = true)
                } finally {
                    runOnUiThread { forecastLoadingState = false }
                }
            }

            override fun onFailure(e: IOException?) {
                Log.e("MainActivity", "Failed to fetch forecast", e)
                handleFetchError("API Error (Forecast): ${e?.message ?: "Unknown error"}", isManualRefresh, isForecast = true)
                runOnUiThread { forecastLoadingState = false }
            }
        })
    }

    private fun handleFetchError(errorMsg: String, isManualRefresh: Boolean, isForecast: Boolean = false) {
        runOnUiThread {
            errorMessage = errorMsg
            forecastLoadingState = false
            val isOffline = !apiService.isNetworkAvailable()
            if (isForecast) {
                forecastDataState = forecastDataState.copy(isLoading = false, error = errorMsg, isOfflineData = isOffline)
            } else {
                currentWeatherDataState = currentWeatherDataState.copy(
                    isLoading = false,
                    error = errorMsg,
                    locationName = userInputLocation.ifBlank { "N/A" },
                    isOfflineData = isOffline
                )
                forecastDataState = forecastDataState.copy(isLoading = false, error = "Dependency error: $errorMsg", isOfflineData = isOffline)
            }
            if (isManualRefresh || !isForecast) {
                Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isLocationFavorite(location: String): Boolean {
        return favoriteLocations.any { it.equals(location, ignoreCase = true) }
    }

    private fun toggleFavorite(location: String) {
        if (location.isBlank()) return

        val currentFavoritesMutable = favoriteLocations.toMutableList()
        val isCurrentlyFavorite = currentFavoritesMutable.any { it.equals(location, ignoreCase = true) }
        var changed = false

        if (isCurrentlyFavorite) {
            if (currentFavoritesMutable.removeAll { it.equals(location, ignoreCase = true) }) {
                Toast.makeText(this, "$location removed from favorites", Toast.LENGTH_SHORT).show()
                apiService.deleteWeatherDataForLocation(location)
                changed = true
            }
        } else {
            if (currentFavoritesMutable.none { it.equals(location, ignoreCase = true)}) {
                currentFavoritesMutable.add(location)
                Toast.makeText(this, "$location added to favorites", Toast.LENGTH_SHORT).show()
                changed = true
                if (apiService.isNetworkAvailable()) {
                    lifecycleScope.launch { cacheSingleFavoriteData(location) }
                }
            } else if (!currentFavoritesMutable.contains(location)) { // Case where casing is different
                currentFavoritesMutable.removeAll{ it.equals(location, ignoreCase = true)}
                currentFavoritesMutable.add(location) // Add with current casing
                Toast.makeText(this, "$location (casing updated) is a favorite", Toast.LENGTH_SHORT).show()
                changed = true
                if (apiService.isNetworkAvailable()) { // Re-cache if casing changed and online
                    lifecycleScope.launch { cacheSingleFavoriteData(location) }
                }
            }
        }

        if (changed) {
            apiService.saveFavorites(currentFavoritesMutable.toList())
            favoriteLocations = currentFavoritesMutable
        }
    }
    private fun cacheSingleFavoriteData(location: String) { // Renamed parameter to avoid conflict
        if (!apiService.isNetworkAvailable()) return
        Log.d("MainActivity", "Caching data for single favorite: $location")
        apiService.getData(location, selectedUnit, object : apiServices.ResponseCallback {
            override fun onSuccess(result: String) {
                try {
                    val json = JSONObject(result)
                    val locationNameFromApi = json.optString("name", location)
                    val country = json.optJSONObject("sys")?.optString("country", "")
                    val actualLocationName = if (country.isNullOrBlank() || locationNameFromApi.contains(country)) locationNameFromApi else "$locationNameFromApi, $country"

                    apiService.saveCurrentWeatherData(actualLocationName, result)
                    val coord = json.optJSONObject("coord")
                    if (coord != null) {
                        apiService.getForecastData(coord.getDouble("lat"), coord.getDouble("lon"), selectedUnit, object : apiServices.ResponseCallback {
                            override fun onSuccess(forecastResult: String) { // Changed variable name
                                apiService.saveForecastData(actualLocationName, forecastResult)
                                Log.d("CacheSingleFavorite", "Cached data for $actualLocationName")
                            }
                            override fun onFailure(e: IOException?) { Log.e("CacheSingleFavorite", "Failed to cache forecast for $actualLocationName",e) }
                        })
                    }
                } catch (e: JSONException) { Log.e("CacheSingleFavorite", "JSON error caching $location",e) }
            }
            override fun onFailure(e: IOException?) { Log.e("CacheSingleFavorite", "API error caching $location",e) }
        })
    }

    private fun deleteFavorite(location: String) {
        val currentFavoritesMutable = favoriteLocations.toMutableList()
        val removed = currentFavoritesMutable.removeAll { it.equals(location, ignoreCase = true) }

        if (removed) {
            apiService.saveFavorites(currentFavoritesMutable.toList())
            apiService.deleteWeatherDataForLocation(location)
            Toast.makeText(this, "$location removed from favorites", Toast.LENGTH_SHORT).show()
            favoriteLocations = currentFavoritesMutable
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun WeatherAppScreen() {
        var tempUserInputLocation by remember { mutableStateOf(userInputLocation) }
        val isCurrentLocationFavorite = isLocationFavorite(userInputLocation)
        val context = LocalContext.current

        LaunchedEffect(userInputLocation) {
            tempUserInputLocation = userInputLocation
        }
        LaunchedEffect(Unit) { // Initial check and listener for network status
            showOfflineWarningBanner = !apiService.isNetworkAvailable()
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("WeatherGapp") },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        actions = {
                            if (userInputLocation.isNotBlank() && !currentWeatherDataState.isLoading && currentWeatherDataState.error == null) {
                                IconButton(onClick = { toggleFavorite(userInputLocation) }) {
                                    Icon(
                                        imageVector = if (isCurrentLocationFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                        contentDescription = if (isCurrentLocationFavorite) "Remove from Favorites" else "Add to Favorites",
                                        tint = Color.Black
                                    )
                                }
                            }
                            IconButton(onClick = { showFavoritesDialog = true }) {
                                Icon(Icons.Filled.Bookmarks, contentDescription = "Favorites", tint = Color.Black)
                            }
                            IconButton(onClick = {
                                Log.d("MainActivity", "Manual refresh.")
                                if (apiService.isNetworkAvailable()) {
                                    fetchWeatherData(userInputLocation, isManualRefresh = true)
                                } else {
                                    showOfflineWarningBanner = true
                                    Toast.makeText(context, "No internet connection. Cannot refresh.", Toast.LENGTH_LONG).show()
                                    loadOfflineWeatherData(userInputLocation, selectedUnit, showToastIfNotFound = true)
                                }
                            }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.Black)
                            }
                            IconButton(onClick = { showSettingsDialog = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.Black)
                            }
                        }
                    )
                    if (showOfflineWarningBanner) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No internet connection. Data may be outdated.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(8.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedTextField(
                            value = tempUserInputLocation,
                            onValueChange = { tempUserInputLocation = it },
                            label = { Text("Enter Location") },
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                userInputLocation = tempUserInputLocation
                                fetchWeatherData(userInputLocation, isManualRefresh = true)
                            },
                            enabled = !forecastLoadingState && tempUserInputLocation.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                contentColor = Color.White,
                                containerColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("Unit:", modifier = Modifier.padding(end = 8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedUnit == "°C",
                                onClick = { DataManager.unit = "°C" },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color.Black,
                                    unselectedColor = Color.DarkGray
                                )
                            )
                            Text("Celsius", modifier = Modifier.padding(end = 16.dp, start = 4.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedUnit == "°F",
                                onClick = { DataManager.unit = "°F" },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color.Black,
                                    unselectedColor = Color.DarkGray
                                )
                            )
                            Text("Fahrenheit", modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (forecastLoadingState && currentWeatherDataState.isLoading && errorMessage == null) { // Simplified loading check
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (errorMessage != null && (!currentWeatherDataState.isOfflineData || currentWeatherDataState.locationName == "N/A")) {
                        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Error: $errorMessage",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            if (this.maxWidth < 600.dp) {
                                val pagerState = rememberPagerState(pageCount = { 3 })
                                Column(modifier = Modifier.fillMaxSize()) {
                                    HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                                        WeatherPage(page, currentWeatherDataState, forecastDataState)
                                    }
                                    Row(
                                        Modifier.height(50.dp).fillMaxWidth().padding(bottom = 8.dp),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        repeat(pagerState.pageCount) { iteration ->
                                            val color = if (pagerState.currentPage == iteration) Color.Black else Color.Gray.copy(alpha = 0.5f)
                                            Box(modifier = Modifier.padding(4.dp).clip(CircleShape).size(12.dp).background(color))
                                        }
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                    CurrentWeatherBasicInfoView(currentWeatherDataState)
                                    Spacer(Modifier.height(16.dp))
                                    CurrentWeatherDetailedInfoView(currentWeatherDataState)
                                    Spacer(Modifier.height(16.dp))
                                    ForecastView(forecastDataState)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showSettingsDialog) {
            SettingsDialog(
                currentInterval = autoRefreshIntervalMinutes,
                onDismiss = { showSettingsDialog = false },
                onSave = { newInterval ->
                    autoRefreshIntervalMinutes = newInterval
                    SettingsManager.saveRefreshInterval(this, newInterval)
                    showSettingsDialog = false
                    runOnUiThread {
                        Toast.makeText(this, "Settings saved. Auto-refresh set to $newInterval minutes (0 for disabled).", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        if (showFavoritesDialog) {
            FavoritesDialog(
                favorites = favoriteLocations.toList(),
                onDismiss = { showFavoritesDialog = false },
                onSelect = { selectedLocation ->
                    userInputLocation = selectedLocation
                    fetchWeatherData(selectedLocation, isManualRefresh = true)
                    showFavoritesDialog = false
                },
                onDelete = { locationToDelete ->
                    deleteFavorite(locationToDelete)
                }
            )
        }
    }

    @Composable
    fun WeatherPage(pageIndex: Int, currentData: CurrentWeatherUIState, forecastData: ForecastUIState) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            if (currentData.isOfflineData && pageIndex == 0) {
                Text(
                    "Displaying offline data.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
            }
            when (pageIndex) {
                0 -> CurrentWeatherBasicInfoView(data = currentData)
                1 -> CurrentWeatherDetailedInfoView(data = currentData)
                2 -> ForecastView(data = forecastData)
            }
        }
    }

    @Composable
    fun SettingsDialog(
        currentInterval: Int,
        onDismiss: () -> Unit,
        onSave: (Int) -> Unit
    ) {
        var selectedInterval by remember { mutableStateOf(currentInterval) }

        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Auto-Refresh Interval", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    SettingsManager.REFRESH_INTERVAL_OPTIONS.forEach { interval ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedInterval = interval }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (selectedInterval == interval),
                                onClick = { selectedInterval = interval },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = Color.Black,
                                    unselectedColor = Color.DarkGray
                                )
                            )
                            Text(
                                text = if (interval == 0) "Disabled" else "$interval minutes",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onSave(selectedInterval) },
                            colors = ButtonDefaults.buttonColors( // Apply black background and white text
                                containerColor = Color.Black,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun FavoritesDialog(
        favorites: List<String>,
        onDismiss: () -> Unit,
        onSelect: (String) -> Unit,
        onDelete: (String) -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Favorite Locations") },
            text = {
                if (favorites.isEmpty()) {
                    Text("You have no favorite locations yet.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(favorites) { location ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(location) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(location, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onDelete(location) }) {
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete $location from favorites", tint = Color.Black)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        )
    }
}

// Helper functions
fun formatTime(timestamp: Long, timezoneOffset: Int, format: String = "HH:mm"): String {
    return try {
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        val netDate = Date((timestamp + timezoneOffset) * 1000)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(netDate)
    } catch (e: Exception) {
        "N/A"
    }
}

fun formatDayOfWeek(timestamp: Long, timezoneOffset: Int): String {
    return try {
        val sdf = SimpleDateFormat("EEE", Locale.getDefault())
        val netDate = Date((timestamp + timezoneOffset) * 1000)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(netDate)
    } catch (e: Exception) {
        "N/A"
    }
}
fun formatDate(timestamp: Long, timezoneOffset: Int): String {
    return try {
        val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
        val netDate = Date((timestamp + timezoneOffset) * 1000)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(netDate)
    } catch (e: Exception) {
        "N/A"
    }
}

fun windDegreeToCardinal(degree: Double): String {
    val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    return directions[((degree % 360) / 22.5).roundToInt() % 16]
}

fun getWeatherIcon(iconCode: String): ImageVector {
    return when (iconCode) {
        "01d" -> Icons.Filled.WbSunny
        "01n" -> Icons.Filled.Brightness2
        "02d" -> Icons.Filled.Cloud
        "02n" -> Icons.Filled.Cloud
        "03d", "03n" -> Icons.Filled.CloudQueue
        "04d", "04n" -> Icons.Filled.CloudOff
        "09d", "09n" -> Icons.Filled.Umbrella
        "10d" -> Icons.Filled.BeachAccess
        "10n" -> Icons.Filled.Grain
        "11d", "11n" -> Icons.Filled.FlashOn
        "13d", "13n" -> Icons.Filled.AcUnit
        "50d", "50n" -> Icons.Filled.Dehaze
        else -> Icons.Filled.CloudOff
    }
}

// Parsing functions
fun parseCurrentWeatherJson(json: JSONObject, unit: String): CurrentWeatherUIState {
    try {
        val coord = json.optJSONObject("coord")
        val main = json.optJSONObject("main")
        val weatherArray = json.optJSONArray("weather")
        val wind = json.optJSONObject("wind")
        val sys = json.optJSONObject("sys")
        val dt = json.optLong("dt", System.currentTimeMillis()/1000)
        val timezoneOffset = json.optInt("timezone", 0)

        val weather = weatherArray?.optJSONObject(0)
        val locationNameFromJson = json.optString("name", "N/A")
        val country = sys?.optString("country", "")
        val displayName = if (country.isNullOrBlank() || locationNameFromJson.contains(country)) locationNameFromJson else "$locationNameFromJson, $country"

        return CurrentWeatherUIState(
            locationName = displayName,
            coordinates = "Lat: ${coord?.optDouble("lat", 0.0)?.format(2) ?: "N/A"}, Lon: ${coord?.optDouble("lon", 0.0)?.format(2) ?: "N/A"}",
            observationTime = formatTime(dt, timezoneOffset, "EEE, MMM dd, HH:mm"),
            temperature = "${main?.optDouble("temp")?.roundToInt() ?: "N/A"}$unit", // Corrected
            feelsLike = "Feels like: ${main?.optDouble("feels_like")?.roundToInt() ?: "N/A"}$unit", // Corrected
            pressure = "${main?.optInt("pressure", 0) ?: "N/A"} hPa",
            weatherCondition = weather?.optString("description", "N/A")?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } ?: "N/A",
            weatherIcon = getWeatherIcon(weather?.optString("icon", "") ?: ""),
            windSpeed = "${wind?.optDouble("speed")?.format(1) ?: "N/A"} ${if (unit == "°C") "m/s" else "mph"}",
            windDirection = windDegreeToCardinal(wind?.optDouble("deg", 0.0) ?: 0.0),
            humidity = "${main?.optInt("humidity", 0) ?: "N/A"}%",
            visibility = "${(json.optInt("visibility", 0) / 1000.0).format(1)} km",
            sunrise = formatTime(sys?.optLong("sunrise", 0) ?: 0, timezoneOffset),
            sunset = formatTime(sys?.optLong("sunset", 0) ?: 0, timezoneOffset),
            isLoading = false,
            error = null
        )
    } catch (e: JSONException) {
        Log.e("ParseCurrentWeather", "Error parsing: ${e.message}", e)
        return CurrentWeatherUIState(isLoading = false, error = "Error parsing current weather: ${e.localizedMessage}")
    }
}
fun Double.format(digits: Int) = "%.${digits}f".format(this)

fun parseForecastJson(json: JSONObject, unit: String): ForecastUIState {
    val dailyForecasts = mutableListOf<DailyForecastItem>()
    try {
        val list = json.optJSONArray("list") ?: return ForecastUIState(isLoading = false, error = "Forecast list missing")
        val city = json.optJSONObject("city")
        val timezoneOffset = city?.optInt("timezone", 0) ?: 0

        val dailyData = mutableMapOf<String, MutableList<JSONObject>>()

        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val dt = item.optLong("dt")
            val dateKey = formatDate(dt, timezoneOffset)
            dailyData.getOrPut(dateKey) { mutableListOf() }.add(item)
        }

        val sortedDates = dailyData.keys.sortedWith(compareBy {
            try { SimpleDateFormat("MMM dd", Locale.getDefault()).parse(it) } catch (e: Exception) { Date(0) }
        })

        for (dateKey in sortedDates.take(5)) {
            val dayEntries = dailyData[dateKey] ?: continue
            if (dayEntries.isEmpty()) continue

            var minTemp = Double.MAX_VALUE
            var maxTemp = Double.MIN_VALUE
            val conditionCounts = mutableMapOf<String, Int>()
            var dominantIconCode = dayEntries.first().optJSONArray("weather")?.optJSONObject(0)?.optString("icon") ?: "01d"

            for (entry in dayEntries) {
                val main = entry.optJSONObject("main")
                minTemp = minOf(minTemp, main?.optDouble("temp_min") ?: Double.MAX_VALUE)
                maxTemp = maxOf(maxTemp, main?.optDouble("temp_max") ?: Double.MIN_VALUE)
                val weather = entry.optJSONArray("weather")?.optJSONObject(0)
                val condition = weather?.optString("description") ?: "N/A"
                conditionCounts[condition] = (conditionCounts[condition] ?: 0) + 1
            }
            val representativeCondition = conditionCounts.maxByOrNull { it.value }?.key ?: dayEntries.first().optJSONArray("weather")?.optJSONObject(0)?.optString("description") ?: "N/A"
            val middayEntry = dayEntries.find { entry ->
                val hour = formatTime(entry.optLong("dt"), timezoneOffset, "HH").toIntOrNull()
                hour in 11..15
            } ?: dayEntries.first()
            dominantIconCode = middayEntry.optJSONArray("weather")?.optJSONObject(0)?.optString("icon") ?: dominantIconCode

            dailyForecasts.add(
                DailyForecastItem(
                    date = dateKey,
                    dayOfWeek = formatDayOfWeek(dayEntries.first().optLong("dt"), timezoneOffset),
                    tempMin = "${minTemp.roundToInt()}$unit", // Corrected
                    tempMax = "${maxTemp.roundToInt()}$unit", // Corrected
                    condition = representativeCondition.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    icon = getWeatherIcon(dominantIconCode)
                )
            )
        }
        return ForecastUIState(dailyForecasts = dailyForecasts, isLoading = false)
    } catch (e: JSONException) {
        Log.e("ParseForecast", "Error parsing: ${e.message}", e)
        return ForecastUIState(isLoading = false, error = "Error parsing forecast: ${e.localizedMessage}")
    }
}

// Composable Views
@Composable
fun CurrentWeatherBasicInfoView(data: CurrentWeatherUIState) {
    DataCard(title = "Current Weather - ${data.locationName}") {
        if (data.isLoading && data.error == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (data.error != null && (data.locationName == "N/A" || (!data.isOfflineData))) {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        else {
            if (data.error != null && data.isOfflineData) {
                Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            if (data.isOfflineData && data.error == null) {
                Text(
                    "Offline data (last sync: ${data.observationTime.replace("Offline: ", "")})",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = data.weatherIcon,
                    contentDescription = data.weatherCondition,
                    modifier = Modifier.size(80.dp).padding(end = 16.dp),
                    tint = Color.Black
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(data.temperature, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(data.weatherCondition, style = MaterialTheme.typography.titleMedium)
                    Text(data.feelsLike, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            InfoRow("Observation Time:", if (data.isOfflineData) data.observationTime.replace("Offline: ", "") else data.observationTime)
            InfoRow("Coordinates:", data.coordinates)
            InfoRow("Pressure:", data.pressure)
            InfoRow("Sunrise:", data.sunrise)
            InfoRow("Sunset:", data.sunset)
        }
    }
}

@Composable
fun CurrentWeatherDetailedInfoView(data: CurrentWeatherUIState) {
    DataCard(title = "Additional Details") {
        if (data.isLoading && data.error == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (data.error != null && (data.humidity == "N/A" || (!data.isOfflineData && data.error != null))) {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        else {
            if (data.error != null && data.isOfflineData) {
                Text("Error fetching full details: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            InfoRow("Wind:", "${data.windSpeed}, ${data.windDirection}")
            InfoRow("Humidity:", data.humidity)
            InfoRow("Visibility:", data.visibility)
        }
    }
}

@Composable
fun ForecastView(data: ForecastUIState) {
    DataCard(title = "5-Day Forecast") {
        if (data.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (data.error != null) {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else if (data.dailyForecasts.isEmpty()){
            Text("No forecast data available.", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } else {
            if (data.isOfflineData) {
                Text(
                    "Offline forecast.",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column {
                data.dailyForecasts.forEachIndexed { index, item ->
                    ForecastItemRow(item)
                    if (index < data.dailyForecasts.size - 1) {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ForecastItemRow(item: DailyForecastItem) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(0.8f)) {
            Text(item.dayOfWeek, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(item.date, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            imageVector = item.icon,
            contentDescription = item.condition,
            Modifier.size(40.dp).padding(horizontal = 8.dp),
            tint = Color.Black
        )
        Column(Modifier.weight(1.5f), horizontalAlignment = Alignment.Start) { Text(item.condition, style = MaterialTheme.typography.bodyMedium) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Text("${item.tempMax} / ${item.tempMin}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
    }
}

@Composable
fun DataCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth().padding(vertical = 8.dp), RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
            HorizontalDivider(Modifier.padding(bottom = 12.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, icon: ImageVector? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, label, Modifier.size(20.dp).padding(end = 8.dp), tint = Color.Black) }
        Text("$label ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}