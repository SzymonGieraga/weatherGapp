package com.example.weathergapp.apiServices

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.weathergapp.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class apiServices(private val context: Context) {

    var appKey = BuildConfig.API_KEY
    val apiUrl = "https://api.openweathermap.org/"

    private val LAST_LOCATION_FILENAME = "last_location.txt"
    private val FAVORITES_FILENAME = "favorites.dat"
    private val CURRENT_WEATHER_SUFFIX = "_current.json"
    private val FORECAST_WEATHER_SUFFIX = "_forecast.json"


    interface ResponseCallback {
        fun onSuccess(result: String)
        fun onFailure(e: IOException?)
    }

    // --- Network Functions ---
    fun getForecastData(lat: Double, lon: Double, unit: String, callback: ResponseCallback) {
        val unitsParam = determineUnitsParameter(unit)
        val url = "${apiUrl}data/2.5/forecast?lat=$lat&lon=$lon&appid=$appKey&$unitsParam"
        Log.d("apiServices", "Requesting Forecast URL: $url")
        makeRequest(url, callback)
    }

    fun getData(userInputs: String, unit: String, callback: ResponseCallback) {
        if (isNetworkAvailable()) {
            var url = createURL(userInputs)
            if (url != null) {
                val unitsParam = determineUnitsParameter(unit)
                url = "$url&$unitsParam"
                Log.d("apiServices", "Requesting Current Weather URL: $url")
                makeRequest(url, callback)
            } else {
                callback.onFailure(IOException("Invalid input or URL could not be created."))
            }
        } else {
            callback.onFailure(IOException("Network is unavailable."))
        }
    }

    private fun makeRequest(url: String, callback: ResponseCallback) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        it.body?.string()?.let { responseBody ->
                            callback.onSuccess(responseBody)
                        } ?: callback.onFailure(IOException("Response body is null."))
                    } else {
                        val responseBodyString = it.body?.string()
                        Log.e("apiServices", "Unsuccessful response for URL: $url")
                        Log.e("apiServices", "Response code: ${it.code}")
                        Log.e("apiServices", "Response message: ${it.message}")
                        Log.e("apiServices", "Response body: $responseBodyString")
                        callback.onFailure(IOException("Unsuccessful response: ${it.code} - ${it.message}. Body: $responseBodyString"))
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                Log.e("apiServices", "Request failed for URL: $url", e)
                callback.onFailure(e)
            }
        })
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    private fun createURL(userInputs: String): String? {
        // Regex to check if input is purely alphabetic (city name), allowing spaces and commas
        if (userInputs.matches(Regex("^[a-zA-Z\\s,-]+$"))) {
            return "${apiUrl}data/2.5/weather?q=${userInputs.trim()}&appid=$appKey"
        }
        val parts = userInputs.split(",").map { it.trim() }
        return when {
            // Zip code, country code (e.g., "94040,us")
            parts.size == 2 && parts[0].all { it.isDigit() } && parts[1].all { it.isLetter() } && parts[1].length == 2 -> {
                "${apiUrl}data/2.5/weather?zip=${parts[0]},${parts[1]}&appid=$appKey"
            }
            // City name, country code (e.g., "London,uk")
            parts.size == 2 && parts[0].matches(Regex("^[a-zA-Z\\s]+$")) && parts[1].all { it.isLetter() } && parts[1].length == 2 -> {
                "${apiUrl}data/2.5/weather?q=${parts[0]},${parts[1]}&appid=$appKey"
            }
            // Latitude, Longitude (e.g., "35.68,139.69")
            parts.size == 2 && parts[0].matches(Regex("^-?\\d+(\\.\\d+)?$")) && parts[1].matches(Regex("^-?\\d+(\\.\\d+)?$")) -> {
                "${apiUrl}data/2.5/weather?lat=${parts[0]}&lon=${parts[1]}&appid=$appKey"
            }
            else -> null
        }
    }

    private fun determineUnitsParameter(unitPreference: String): String {
        return when (unitPreference) {
            "°C" -> "units=metric" // Celsius
            "°F" -> "units=imperial" // Fahrenheit
            else -> "units=standard" // Kelvin by default
        }
    }

    // --- File I/O Functions ---

    private fun sanitizeFilename(location: String): String {
        return try {
            // Replace non-alphanumeric characters (except comma, hyphen, underscore) with underscore, then URL encode
            URLEncoder.encode(location.replace(Regex("[^a-zA-Z0-9,-_]"), "_"), StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            // Fallback if encoding fails (should be rare)
            location.replace(Regex("[^a-zA-Z0-9,-_]"), "_")
        }
    }

    fun saveLastViewedLocation(location: String) {
        try {
            val file = File(context.filesDir, LAST_LOCATION_FILENAME)
            file.writeText(location)
            Log.d("apiServices", "Saved last viewed location: $location")
        } catch (e: IOException) {
            Log.e("apiServices", "Error saving last viewed location", e)
        }
    }

    fun loadLastViewedLocation(): String? {
        return try {
            val file = File(context.filesDir, LAST_LOCATION_FILENAME)
            if (file.exists()) {
                val location = file.readText()
                Log.d("apiServices", "Loaded last viewed location: $location")
                location
            } else {
                null
            }
        } catch (e: IOException) {
            Log.e("apiServices", "Error loading last viewed location", e)
            null
        }
    }

    fun saveCurrentWeatherData(location: String, data: String) {
        val filename = sanitizeFilename(location) + CURRENT_WEATHER_SUFFIX
        try {
            context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                it.write(data.toByteArray())
            }
            Log.d("apiServices", "Saved current weather data for $location to $filename")
        } catch (e: IOException) {
            Log.e("apiServices", "Error saving current weather data for $location", e)
        }
    }

    fun loadCurrentWeatherData(location: String): String? {
        val filename = sanitizeFilename(location) + CURRENT_WEATHER_SUFFIX
        return try {
            if (File(context.filesDir, filename).exists()) {
                context.openFileInput(filename).use {
                    val data = it.bufferedReader().readText()
                    Log.d("apiServices", "Loaded current weather data for $location from $filename")
                    data
                }
            } else {
                Log.d("apiServices", "No saved current weather data for $location ($filename)")
                null
            }
        } catch (e: IOException) {
            Log.e("apiServices", "Error loading current weather data for $location", e)
            null
        }
    }

    fun saveForecastData(location: String, data: String) {
        val filename = sanitizeFilename(location) + FORECAST_WEATHER_SUFFIX
        try {
            context.openFileOutput(filename, Context.MODE_PRIVATE).use {
                it.write(data.toByteArray())
            }
            Log.d("apiServices", "Saved forecast data for $location to $filename")
        } catch (e: IOException) {
            Log.e("apiServices", "Error saving forecast data for $location", e)
        }
    }

    fun loadForecastData(location: String): String? {
        val filename = sanitizeFilename(location) + FORECAST_WEATHER_SUFFIX
        return try {
            if (File(context.filesDir, filename).exists()){
                context.openFileInput(filename).use {
                    val data = it.bufferedReader().readText()
                    Log.d("apiServices", "Loaded forecast data for $location from $filename")
                    data
                }
            } else {
                Log.d("apiServices", "No saved forecast data for $location ($filename)")
                null
            }
        } catch (e: IOException) {
            Log.e("apiServices", "Error loading forecast data for $location", e)
            null
        }
    }

    fun saveFavorites(favorites: List<String>) {
        try {
            val file = File(context.filesDir, FAVORITES_FILENAME)
            ObjectOutputStream(file.outputStream()).use { it.writeObject(favorites) }
        } catch (e: IOException) {
            Log.e("apiServices", "Error saving favorites", e)
        }
    }

    fun loadFavorites(): MutableList<String> {
        return try {
            val file = File(context.filesDir, FAVORITES_FILENAME)
            if (file.exists()) {
                ObjectInputStream(file.inputStream()).use {
                    @Suppress("UNCHECKED_CAST")
                    (it.readObject() as? List<String>)?.toMutableList() ?: mutableListOf()
                }
            } else {
                mutableListOf()
            }
        } catch (e: Exception) { // Catch broader exceptions for deserialization issues
            Log.e("apiServices", "Error loading favorites", e)
            mutableListOf()
        }
    }

    fun deleteWeatherDataForLocation(location: String) {
        val currentFile = File(context.filesDir, sanitizeFilename(location) + CURRENT_WEATHER_SUFFIX)
        val forecastFile = File(context.filesDir, sanitizeFilename(location) + FORECAST_WEATHER_SUFFIX)
        if (currentFile.exists()) currentFile.delete()
        if (forecastFile.exists()) forecastFile.delete()
        Log.d("apiServices", "Deleted cached weather data for $location")
    }
}
