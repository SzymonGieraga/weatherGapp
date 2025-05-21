package com.example.weathergapp

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.weathergapp.apiServices.WeatherDataRepo
import com.example.weathergapp.apiServices.apiServices
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

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
    val error: String? = null
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
    val error: String? = null
)

class MainActivity : ComponentActivity(), WeatherDataRepo.WeatherDataObserver {

    private lateinit var apiService: apiServices
    private var userInputLocation by mutableStateOf("London")
    private var currentWeatherDataState by mutableStateOf(CurrentWeatherUIState())
    private var forecastDataState by mutableStateOf(ForecastUIState())
    private var общийLoadingState by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var selectedUnit by mutableStateOf("°C")

    private var showSettingsDialog by mutableStateOf(false)
    private var autoRefreshIntervalMinutes by mutableStateOf(SettingsManager.DEFAULT_REFRESH_INTERVAL_MINUTES)

    private var favoriteLocations by mutableStateOf(listOf<String>())
    private var showFavoritesDialog by mutableStateOf(false)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        apiService = apiServices(this)
        favoriteLocations = apiService.loadFavorites().toMutableList()
        WeatherDataRepo.registerWeatherObserver(this)
        WeatherDataRepo.registerForecastObserver(this)

        autoRefreshIntervalMinutes = SettingsManager.loadRefreshInterval(this)

        if (DataManager.unit.isNotBlank()) {
            selectedUnit = DataManager.unit
        } else {
            DataManager.unit = selectedUnit
        }

        DataManager.registerObserver(object : DataManager.UnitObserver {
            override fun onUnitChanged(newUnit: String) {
                val oldUnit = selectedUnit
                selectedUnit = newUnit
                if (oldUnit != newUnit && userInputLocation.isNotEmpty() && !currentWeatherDataState.isLoading) {
                    fetchWeatherData(userInputLocation, isManualRefresh = true)
                }
            }
        })

        if (currentWeatherDataState.isLoading) {
            fetchWeatherData(userInputLocation, isManualRefresh = false)
        }

        setContent {
            WeatherAppScreen()

            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(autoRefreshIntervalMinutes, userInputLocation, lifecycleOwner) {
                if (autoRefreshIntervalMinutes > 0) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        while (isActive) {
                            Log.d("AutoRefresh", "Delaying for $autoRefreshIntervalMinutes minutes.")
                            delay(autoRefreshIntervalMinutes * 60 * 1000L)
                            if (isActive) {
                                Log.d("AutoRefresh", "Interval elapsed, fetching weather data for $userInputLocation")
                                fetchWeatherData(userInputLocation, isManualRefresh = false)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WeatherDataRepo.unregisterWeatherObserver(this)
        WeatherDataRepo.unregisterForecastObserver(this)
    }

    override fun onWeatherDataUpdated() {
        val newJson = WeatherDataRepo.getWeatherDataJson()
        runOnUiThread {
            if (newJson != null) {
                currentWeatherDataState = parseCurrentWeatherJson(newJson, selectedUnit)
            } else {
                currentWeatherDataState = currentWeatherDataState.copy(
                    isLoading = false,
                    error = "Failed to load current weather."
                )
            }
        }
    }

    override fun onForecastDataUpdated() {
        val newJson = WeatherDataRepo.getForecastDataJson()
        runOnUiThread {
            if (newJson != null) {
                forecastDataState = parseForecastJson(newJson, selectedUnit)
            } else {
                forecastDataState = forecastDataState.copy(
                    isLoading = false,
                    error = "Failed to load forecast."
                )
            }
        }
    }

    override fun onHourlyDataUpdated() { /* NIE WIEM CO Z TYM SERIO*/ }

    private fun fetchWeatherData(location: String, isManualRefresh: Boolean) {
        if (location.isBlank()) {
            runOnUiThread {
                errorMessage = "Please enter a location."
                if (isManualRefresh) Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
            }
            return
        }
        Log.d("MainActivity", "Fetching weather for: $location, Unit: $selectedUnit, Manual: $isManualRefresh")
        общийLoadingState = true
        errorMessage = null
        currentWeatherDataState = CurrentWeatherUIState(isLoading = true, locationName = location)
        forecastDataState = ForecastUIState(isLoading = true)

        apiService.getData(location, selectedUnit, object : apiServices.ResponseCallback {
            override fun onSuccess(result: String) {
                try {
                    val json = JSONObject(result)
                    WeatherDataRepo.setWeatherDataJson(json)
                    val coord = json.optJSONObject("coord")
                    if (coord != null) {
                        fetchForecastData(coord.getDouble("lat"), coord.getDouble("lon"), isManualRefresh)
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
            }
        })
    }

    private fun fetchForecastData(lat: Double, lon: Double, isManualRefresh: Boolean) {
        apiService.getForecastData(lat, lon, selectedUnit, object : apiServices.ResponseCallback {
            override fun onSuccess(result: String) {
                try {
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
                    runOnUiThread { общийLoadingState = false }
                }
            }

            override fun onFailure(e: IOException?) {
                Log.e("MainActivity", "Failed to fetch forecast", e)
                handleFetchError("API Error (Forecast): ${e?.message ?: "Unknown error"}", isManualRefresh, isForecast = true)
            }
        })
    }

    private fun handleFetchError(errorMsg: String, isManualRefresh: Boolean, isForecast: Boolean = false) {
        runOnUiThread {
            errorMessage = errorMsg
            общийLoadingState = false
            if (isForecast) {
                forecastDataState = forecastDataState.copy(isLoading = false, error = errorMsg)
            } else {
                currentWeatherDataState = currentWeatherDataState.copy(
                    isLoading = false,
                    error = errorMsg,
                    locationName = if (userInputLocation.isNotBlank()) userInputLocation else "N/A"
                )
                forecastDataState = forecastDataState.copy(isLoading = false, error = "Dependency error: $errorMsg")
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
                changed = true
            }
        } else {
            if (currentFavoritesMutable.none { it.equals(location, ignoreCase = true)}) {
                currentFavoritesMutable.add(location)
                Toast.makeText(this, "$location added to favorites", Toast.LENGTH_SHORT).show()
                changed = true
            } else if (!currentFavoritesMutable.contains(location)) {
                currentFavoritesMutable.removeAll{ it.equals(location, ignoreCase = true)}
                currentFavoritesMutable.add(location)
                Toast.makeText(this, "$location (casing updated) is a favorite", Toast.LENGTH_SHORT).show()
                changed = true
            }
        }

        if (changed) {
            apiService.saveFavorites(currentFavoritesMutable.toList())
            favoriteLocations = currentFavoritesMutable
        }
    }

    // Corrected function
    private fun deleteFavorite(location: String) {
        val currentFavoritesMutable = favoriteLocations.toMutableList()
        val removed = currentFavoritesMutable.removeAll { it.equals(location, ignoreCase = true) }

        if (removed) {
            apiService.saveFavorites(currentFavoritesMutable.toList())
            Toast.makeText(this, "$location removed from favorites", Toast.LENGTH_SHORT).show()
            favoriteLocations = currentFavoritesMutable
        }
    }


    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun WeatherAppScreen() {
        var tempUserInputLocation by remember { mutableStateOf(userInputLocation) }
        val isCurrentLocationFavorite = isLocationFavorite(userInputLocation)


        LaunchedEffect(userInputLocation) {
            tempUserInputLocation = userInputLocation
        }

        Scaffold(
            topBar = {
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
                                    if (isCurrentLocationFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    contentDescription = if (isCurrentLocationFavorite) "Remove from Favorites" else "Add to Favorites"
                                )
                            }
                        }
                        IconButton(onClick = { showFavoritesDialog = true }) {
                            Icon(Icons.Filled.Bookmarks, contentDescription = "Favorites")
                        }
                        IconButton(onClick = {
                            Log.d("MainActivity", "Manual refresh triggered.")
                            fetchWeatherData(userInputLocation, isManualRefresh = true)
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
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
                        enabled = !общийLoadingState && tempUserInputLocation.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("Unit:", modifier = Modifier.padding(end = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedUnit == "°C", onClick = { DataManager.unit = "°C" })
                        Text("Celsius", modifier = Modifier.padding(end = 16.dp, start = 4.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedUnit == "°F", onClick = { DataManager.unit = "°F" })
                        Text("Fahrenheit", modifier = Modifier.padding(start = 4.dp))
                    }
                }

                if (общийLoadingState && currentWeatherDataState.isLoading && forecastDataState.isLoading && errorMessage == null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (errorMessage != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Error: $errorMessage\n${if (currentWeatherDataState.locationName != "N/A" && currentWeatherDataState.locationName != userInputLocation && !currentWeatherDataState.isLoading) "Showing last known data for ${currentWeatherDataState.locationName}" else ""}",
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    BoxWithConstraints {
                        if (this.maxWidth < 600.dp) {
                            val pagerState = rememberPagerState(pageCount = { 3 })
                            Column {
                                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                                    WeatherPage(page, currentWeatherDataState, forecastDataState)
                                }
                                Row(
                                    Modifier.height(50.dp).fillMaxWidth().padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    repeat(pagerState.pageCount) { iteration ->
                                        val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
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
                                onClick = { selectedInterval = interval }
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
                        Button(onClick = { onSave(selectedInterval) }) {
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
                                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete $location from favorites")
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
            temperature = "${main?.optDouble("temp")?.roundToInt() ?: "N/A"}°$unit",
            feelsLike = "Feels like: ${main?.optDouble("feels_like")?.roundToInt() ?: "N/A"}°$unit",
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
                    tempMin = "${minTemp.roundToInt()}°$unit",
                    tempMax = "${maxTemp.roundToInt()}°$unit",
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

@Composable
fun CurrentWeatherBasicInfoView(data: CurrentWeatherUIState) {
    DataCard(title = "Current Weather - ${data.locationName}") {
        if (data.isLoading && data.error == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (data.error != null && data.locationName == "N/A") {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        else {
            if (data.error != null) {
                Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(imageVector = data.weatherIcon, contentDescription = data.weatherCondition, modifier = Modifier.size(80.dp).padding(end = 16.dp), tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(data.temperature, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(data.weatherCondition, style = MaterialTheme.typography.titleMedium)
                    Text(data.feelsLike, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(16.dp))
            InfoRow("Time:", data.observationTime)
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
        } else if (data.error != null && data.humidity == "N/A") {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        else {
            if (data.error != null) {
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
        Icon(item.icon, item.condition, Modifier.size(40.dp).padding(horizontal = 8.dp), tint = MaterialTheme.colorScheme.primary)
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
        icon?.let { Icon(it, label, Modifier.size(20.dp).padding(end = 8.dp), tint = MaterialTheme.colorScheme.secondary) }
        Text("$label ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}