package com.example.weathergapp

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.weathergapp.apiServices.WeatherDataRepo
import com.example.weathergapp.apiServices.apiServices
import java.io.IOException
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

// Data classes for UI states
data class CurrentWeatherUIState(
    val locationName: String = "N/A",
    val coordinates: String = "Lat: N/A, Lon: N/A",
    val observationTime: String = "N/A",
    val temperature: String = "N/A",
    val pressure: String = "N/A",
    val weatherCondition: String = "N/A",
    val weatherIcon: ImageVector = Icons.Filled.CloudOff, // Default icon
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
    private var userInputLocation by mutableStateOf("London") // Default location for initial load
    private var currentWeatherDataState by mutableStateOf(CurrentWeatherUIState())
    private var forecastDataState by mutableStateOf(ForecastUIState())
    private var loadingState by mutableStateOf(false) // Overall loading for initial fetch trigger
    private var errorMessage by mutableStateOf<String?>(null) // General error message
    private var selectedUnit by mutableStateOf("°C")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        apiService = apiServices(this)
        WeatherDataRepo.registerWeatherObserver(this)
        WeatherDataRepo.registerForecastObserver(this)
        // No separate hourly observer needed if using forecast data for it

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
                    fetchWeatherData(userInputLocation)
                }
            }
        })

        // Fetch weather for default location on initial create
        if (currentWeatherDataState.isLoading) { // Only fetch if not already loaded (e.g. on config change)
            fetchWeatherData(userInputLocation)
        }


        setContent {
            WeatherAppScreen()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WeatherDataRepo.unregisterWeatherObserver(this)
        WeatherDataRepo.unregisterForecastObserver(this)
    }

    override fun onWeatherDataUpdated() {
        val newJson = WeatherDataRepo.getWeatherDataJson()
        Log.d("MainActivity", "onWeatherDataUpdated JSON: ${newJson?.toString(2)}")
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
        Log.d("MainActivity", "onForecastDataUpdated JSON: ${newJson?.toString(2)}")
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
    override fun onHourlyDataUpdated() { /* Not used directly if hourly is part of forecast */ }


    private fun fetchWeatherData(location: String) {
        if (location.isBlank()) {
            errorMessage = "Please enter a location."
            return
        }
        Log.d("MainActivity", "Fetching weather for: $location, Unit: $selectedUnit")
        loadingState = true
        errorMessage = null
        currentWeatherDataState = CurrentWeatherUIState(isLoading = true) // Reset to loading
        forecastDataState = ForecastUIState(isLoading = true)     // Reset to loading


        apiService.getData(location, selectedUnit, object : apiServices.ResponseCallback {
            override fun onSuccess(result: String) {
                try {
                    val json = JSONObject(result)
                    Log.d("MainActivity", "Current weather API raw success: $result")
                    WeatherDataRepo.setWeatherDataJson(json) // This will trigger onWeatherDataUpdated

                    val coord = json.optJSONObject("coord")
                    if (coord != null) {
                        val lat = coord.getDouble("lat")
                        val lon = coord.getDouble("lon")
                        fetchForecastData(lat, lon) // Fetch forecast after current weather
                    } else {
                        Log.e("MainActivity", "Coordinates not found in current weather response.")
                        runOnUiThread {
                            errorMessage = "Could not get coordinates for forecast."
                            loadingState = false
                            currentWeatherDataState = currentWeatherDataState.copy(isLoading = false, error = "Coordinates missing.")
                            forecastDataState = forecastDataState.copy(isLoading = false, error = "Could not fetch: Coordinates missing.")
                        }
                    }
                } catch (e: JSONException) {
                    Log.e("MainActivity", "Error parsing current weather JSON: $result", e)
                    runOnUiThread {
                        errorMessage = "Error parsing current weather data: ${e.message}"
                        loadingState = false
                        currentWeatherDataState = currentWeatherDataState.copy(isLoading = false, error = e.message)
                    }
                }
            }

            override fun onFailure(e: IOException?) {
                Log.e("MainActivity", "Failed to fetch current weather data", e)
                runOnUiThread {
                    val errorMsg = "API Error: ${e?.message ?: "Unknown error"}"
                    errorMessage = errorMsg
                    loadingState = false
                    currentWeatherDataState = currentWeatherDataState.copy(isLoading = false, error = errorMsg)
                    forecastDataState = forecastDataState.copy(isLoading = false, error = errorMsg) // Also mark forecast as error
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun fetchForecastData(lat: Double, lon: Double) {
        apiService.getForecastData(lat, lon, selectedUnit, object : apiServices.ResponseCallback {
            override fun onSuccess(result: String) {
                try {
                    val json = JSONObject(result)
                    Log.d("MainActivity", "Forecast data API raw success: $result")
                    WeatherDataRepo.setForecastDataJson(json) // This triggers onForecastDataUpdated
                } catch (e: JSONException) {
                    Log.e("MainActivity", "Error parsing forecast JSON: $result", e)
                    runOnUiThread {
                        errorMessage = "Error parsing forecast data: ${e.message}"
                        forecastDataState = forecastDataState.copy(isLoading = false, error = e.message)
                    }
                } finally {
                    runOnUiThread {
                        loadingState = false // All data fetching attempts are complete
                    }
                }
            }

            override fun onFailure(e: IOException?) {
                Log.e("MainActivity", "Failed to fetch forecast data", e)
                runOnUiThread {
                    val errorMsg = "API Error (Forecast): ${e?.message ?: "Unknown error"}"
                    errorMessage = errorMsg
                    loadingState = false
                    forecastDataState = forecastDataState.copy(isLoading = false, error = errorMsg)
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    fun WeatherAppScreen() {
        var tempUserInputLocation by remember { mutableStateOf(userInputLocation) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("WeatherGapp") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
            ) {
                // Input and Control Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
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
                            fetchWeatherData(userInputLocation)
                        },
                        enabled = !loadingState
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                }
                // Unit Selector
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


                if (loadingState && currentWeatherDataState.isLoading && forecastDataState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (errorMessage != null && currentWeatherDataState.error != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("Error: $errorMessage", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                }
                else {
                    BoxWithConstraints {
                        val screenWidth = this.maxWidth
                        if (screenWidth < 600.dp) {
                            // Small screen: Pager
                            val pagerState = rememberPagerState(pageCount = { 3 })
                            Column {
                                HorizontalPager(
                                    state = pagerState,
                                    modifier = Modifier.weight(1f)
                                ) { page ->
                                    WeatherPage(pageIndex = page, currentWeatherDataState, forecastDataState)
                                }
                                Row(
                                    Modifier
                                        .height(50.dp)
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    repeat(pagerState.pageCount) { iteration ->
                                        val color = if (pagerState.currentPage == iteration) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                        Box(
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .clip(CircleShape)
                                                .size(12.dp)
                                                .background(color)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Large screen: Column
                            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                                CurrentWeatherBasicInfoView(currentWeatherDataState)
                                Spacer(modifier = Modifier.height(16.dp))
                                CurrentWeatherDetailedInfoView(currentWeatherDataState)
                                Spacer(modifier = Modifier.height(16.dp))
                                ForecastView(forecastDataState)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun WeatherPage(pageIndex: Int, currentData: CurrentWeatherUIState, forecastData: ForecastUIState) {
        // Use a consistent padding for all pages
        Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
            when (pageIndex) {
                0 -> CurrentWeatherBasicInfoView(data = currentData)
                1 -> CurrentWeatherDetailedInfoView(data = currentData)
                2 -> ForecastView(data = forecastData)
            }
        }
    }
}

// Helper function to format timestamp to human-readable time
fun formatTime(timestamp: Long, timezoneOffset: Int, format: String = "HH:mm"): String {
    return try {
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        val netDate = Date((timestamp + timezoneOffset) * 1000)
        sdf.timeZone = TimeZone.getTimeZone("UTC") // Important: timestamp is UTC, offset brings to local
        sdf.format(netDate)
    } catch (e: Exception) {
        "N/A"
    }
}

fun formatDayOfWeek(timestamp: Long, timezoneOffset: Int): String {
    return try {
        val sdf = SimpleDateFormat("EEE", Locale.getDefault()) // "EEE" for short day name e.g. "Mon"
        val netDate = Date((timestamp + timezoneOffset) * 1000)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(netDate)
    } catch (e: Exception) {
        "N/A"
    }
}
fun formatDate(timestamp: Long, timezoneOffset: Int): String {
    return try {
        val sdf = SimpleDateFormat("MMM dd", Locale.getDefault()) // e.g. "May 21"
        val netDate = Date((timestamp + timezoneOffset) * 1000)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        sdf.format(netDate)
    } catch (e: Exception) {
        "N/A"
    }
}


// Helper function to convert wind degrees to cardinal direction
fun windDegreeToCardinal(degree: Double): String {
    val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    return directions[((degree % 360) / 22.5).roundToInt() % 16]
}

// Helper function to map OpenWeatherMap icon codes to Material Icons
fun getWeatherIcon(iconCode: String): ImageVector {
    return when (iconCode) {
        "01d" -> Icons.Filled.WbSunny // clear sky day
        "01n" -> Icons.Filled.Brightness2 // clear sky night
        "02d" -> Icons.Filled.Cloud // few clouds day
        "02n" -> Icons.Filled.Cloud // few clouds night (use same as day for simplicity)
        "03d", "03n" -> Icons.Filled.CloudQueue // scattered clouds
        "04d", "04n" -> Icons.Filled.CloudOff // broken clouds (using CloudOff as distinct)
        "09d", "09n" -> Icons.Filled.Umbrella // shower rain (using Umbrella as general rain)
        "10d" -> Icons.Filled.BeachAccess // rain day (BeachAccess for some variety, or Umbrella)
        "10n" -> Icons.Filled.Grain // rain night (Grain for generic particle effect)
        "11d", "11n" -> Icons.Filled.FlashOn // thunderstorm
        "13d", "13n" -> Icons.Filled.AcUnit // snow
        "50d", "50n" -> Icons.Filled.Dehaze // mist
        else -> Icons.Filled.CloudOff // default
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
        val timezoneOffset = json.optInt("timezone", 0) // seconds from UTC

        val weather = weatherArray?.optJSONObject(0)

        return CurrentWeatherUIState(
            locationName = json.optString("name", "N/A"),
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

        // Group by day - Simple approach: take one forecast per day around noon or the first one.
        // A more robust approach would iterate all, find min/max temps for each day,
        // and a representative weather condition.
        val dailyData = mutableMapOf<String, MutableList<JSONObject>>()

        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val dt = item.optLong("dt")
            val dateKey = formatDate(dt, timezoneOffset) // Use "MMM dd" as key for grouping
            dailyData.getOrPut(dateKey) { mutableListOf() }.add(item)
        }

        val sortedDates = dailyData.keys.sortedWith(compareBy {
            try {
                SimpleDateFormat("MMM dd", Locale.getDefault()).parse(it)
            } catch (e: Exception) {
                Date(0) // Fallback for sorting if parse fails
            }
        })


        for (dateKey in sortedDates) {
            val dayEntries = dailyData[dateKey] ?: continue
            if (dayEntries.isEmpty()) continue

            var minTemp = Double.MAX_VALUE
            var maxTemp = Double.MIN_VALUE
            val conditionCounts = mutableMapOf<String, Int>()
            var dominantIconCode = dayEntries.first().optJSONArray("weather")?.optJSONObject(0)?.optString("icon") ?: "01d" // Default icon

            for (entry in dayEntries) {
                val main = entry.optJSONObject("main")
                minTemp = minOf(minTemp, main?.optDouble("temp_min") ?: Double.MAX_VALUE)
                maxTemp = maxOf(maxTemp, main?.optDouble("temp_max") ?: Double.MIN_VALUE)
                val weather = entry.optJSONArray("weather")?.optJSONObject(0)
                val condition = weather?.optString("description") ?: "N/A"
                conditionCounts[condition] = (conditionCounts[condition] ?: 0) + 1

                // Heuristic for icon: use icon of entry closest to midday or most frequent
                // For simplicity, let's use the icon from the first entry of the day or one with highest temp.
                // Or, more simply, the icon from an entry around midday if available.
                // Here, just using the first entry's icon for simplicity of this example.
                // A better approach would be to find the most representative icon.
            }
            // Get representative condition (most frequent)
            val representativeCondition = conditionCounts.maxByOrNull { it.value }?.key ?: dayEntries.first().optJSONArray("weather")?.optJSONObject(0)?.optString("description") ?: "N/A"
            // For icon, let's try to get one from an entry around midday (e.g. 12:00 or 15:00)
            val middayEntry = dayEntries.find { entry ->
                val dt = entry.optLong("dt")
                val hour = formatTime(dt, timezoneOffset, "HH").toIntOrNull()
                hour in 12..15 // Check if hour is between 12 PM and 3 PM
            } ?: dayEntries.first() // Fallback to the first entry

            dominantIconCode = middayEntry.optJSONArray("weather")?.optJSONObject(0)?.optString("icon") ?: dominantIconCode


            val firstEntryDt = dayEntries.first().optLong("dt")

            dailyForecasts.add(
                DailyForecastItem(
                    date = dateKey, // "MMM dd"
                    dayOfWeek = formatDayOfWeek(firstEntryDt, timezoneOffset), // "EEE"
                    tempMin = "${minTemp.roundToInt()}°$unit",
                    tempMax = "${maxTemp.roundToInt()}°$unit",
                    condition = representativeCondition.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() },
                    icon = getWeatherIcon(dominantIconCode)
                )
            )
        }

        return ForecastUIState(dailyForecasts = dailyForecasts.take(5), isLoading = false) // Take up to 5 days
    } catch (e: JSONException) {
        Log.e("ParseForecast", "Error parsing: ${e.message}", e)
        return ForecastUIState(isLoading = false, error = "Error parsing forecast: ${e.localizedMessage}")
    }
}


// Composable "Fragments" / Views

@Composable
fun CurrentWeatherBasicInfoView(data: CurrentWeatherUIState) {
    DataCard(title = "Current Weather - ${data.locationName}") {
        if (data.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (data.error != null) {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = data.weatherIcon,
                    contentDescription = data.weatherCondition,
                    modifier = Modifier.size(80.dp).padding(end = 16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(data.temperature, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(data.weatherCondition, style = MaterialTheme.typography.titleMedium)
                    Text(data.feelsLike, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            InfoRow(label = "Time:", value = data.observationTime)
            InfoRow(label = "Coordinates:", value = data.coordinates)
            InfoRow(label = "Pressure:", value = data.pressure)
            InfoRow(label = "Sunrise:", value = data.sunrise)
            InfoRow(label = "Sunset:", value = data.sunset)
        }
    }
}

@Composable
fun CurrentWeatherDetailedInfoView(data: CurrentWeatherUIState) {
    DataCard(title = "Additional Details") {
        if (data.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (data.error != null) {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error)
        } else {
            InfoRow(label = "Wind:", value = "${data.windSpeed}, ${data.windDirection}")
            InfoRow(label = "Humidity:", value = data.humidity)
            InfoRow(label = "Visibility:", value = data.visibility)
        }
    }
}

@Composable
fun ForecastView(data: ForecastUIState) {
    DataCard(title = "5-Day Forecast") {
        if (data.isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (data.error != null) {
            Text("Error: ${data.error}", color = MaterialTheme.colorScheme.error)
        } else if (data.dailyForecasts.isEmpty()){
            Text("No forecast data available.")
        }
        else {
            Column {
                data.dailyForecasts.forEach { forecastItem ->
                    ForecastItemRow(item = forecastItem)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
fun ForecastItemRow(item: DailyForecastItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(0.8f)) {
            Text(item.dayOfWeek, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(item.date, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            imageVector = item.icon,
            contentDescription = item.condition,
            modifier = Modifier.size(40.dp).padding(horizontal = 8.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1.5f), horizontalAlignment = Alignment.Start) {
            Text(item.condition, style = MaterialTheme.typography.bodyMedium)
        }
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text("${item.tempMax} / ${item.tempMin}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}


@Composable
fun DataCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, icon: ImageVector? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(20.dp).padding(end = 8.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        Text(
            text = "$label ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
