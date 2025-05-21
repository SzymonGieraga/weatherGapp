package com.example.weathergapp.apiServices

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.weathergapp.BuildConfig
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Callback
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject

class apiServices(private val context: Context) {

    var appKey = BuildConfig.API_KEY
    // Ensure your BuildConfig has API_KEY defined, or replace BuildConfig.API_KEY with your actual key string for testing.
    // For example: var appKey = "YOUR_ACTUAL_API_KEY"

    val apiUrl = "https://api.openweathermap.org/"

    interface ResponseCallback {
        fun onSuccess(result: String)
        fun onFailure(e: IOException?)
    }

    // Fetches 5-day/3-hour forecast data
    fun getForecastData(lat: Double, lon: Double, unit: String, callback: ResponseCallback) {
        val unitsParam = determineUnitsParameter(unit)
        val url = "${apiUrl}data/2.5/forecast?lat=$lat&lon=$lon&appid=$appKey&$unitsParam"
        Log.d("apiServices", "Requesting Forecast URL: $url")
        makeRequest(url, callback)
    }

    // Note: OpenWeatherMap's /data/2.5/forecast endpoint provides 3-hour step forecasts.
    // If you need 1-hour step forecasts, you'd typically use the "One Call API" (paid or with limitations).
    // This function, as is, will fetch the same data as getForecastData.
    fun getHourlyData(lat: Double, lon: Double, unit: String, callback: ResponseCallback) {
        val unitsParam = determineUnitsParameter(unit)
        // Using the same endpoint as forecast for now, as per original code.
        // For true distinct hourly data, a different endpoint or API version might be needed.
        val url = "${apiUrl}data/2.5/forecast?lat=$lat&lon=$lon&appid=$appKey&$unitsParam"
        Log.d("apiServices", "Requesting Hourly (Forecast) URL: $url")
        makeRequest(url, callback)
    }

    // Fetches current weather data
    fun getData(userInputs: String, unit: String, callback: ResponseCallback) {
        if (isNetworkAvailable()) {
            var url = createURL(userInputs) // This already includes appid
            if (url != null) {
                val unitsParam = determineUnitsParameter(unit)
                url = "$url&$unitsParam" // Append units parameter
                Log.d("apiServices", "Requesting Current Weather URL: $url")
                makeRequest(url, callback)
            } else {
                callback.onFailure(IOException("Invalid input or URL could not be created"))
            }
        } else {
            callback.onFailure(IOException("Network is not available"))
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
                            // Removed: WeatherDataRepo.setWeatherDataJson(jsonObj)
                            // The responsibility to set data in the repo is moved to the
                            // specific callbacks in MainActivity, based on what data was fetched.
                            callback.onSuccess(responseBody)
                        } ?: callback.onFailure(IOException("Response body is null"))
                    } else {
                        val responseBodyString = it.body?.string() // Read body for logging
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

    private fun isNetworkAvailable(): Boolean {
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

    // Creates URL for current weather data
    private fun createURL(userInputs: String): String? {
        // Regex to check if input is purely alphabetic (city name)
        if (userInputs.matches(Regex("^[a-zA-Z\\s]+$"))) { // Allow spaces in city names
            return "${apiUrl}data/2.5/weather?q=${userInputs.trim()}&appid=$appKey"
        }
        val parts = userInputs.split(",").map { it.trim() } // Trim parts
        return when {
            // Zip code, country code (e.g., "94040,us")
            parts.size == 2 && parts[0].all { it.isDigit() } && parts[1].all { it.isLetter() } && parts[1].length == 2 -> {
                "${apiUrl}data/2.5/weather?zip=${parts[0]},${parts[1]}&appid=$appKey"
            }
            // City name, country code (e.g., "London,uk")
            parts.size == 2 && parts[0].matches(Regex("^[a-zA-Z\\s]+$")) && parts[1].all { it.isLetter() } && parts[1].length == 2 -> {
                "${apiUrl}data/2.5/weather?q=${parts[0]},${parts[1]}&appid=$appKey"
            }
            // Latitude, Longitude (e.g., "35.68,139.69") - allowing for decimal points and negative signs
            parts.size == 2 && parts[0].matches(Regex("^-?\\d+(\\.\\d+)?$")) && parts[1].matches(Regex("^-?\\d+(\\.\\d+)?$")) -> {
                "${apiUrl}data/2.5/weather?lat=${parts[0]}&lon=${parts[1]}&appid=$appKey"
            }
            else -> null
        }
    }

    // This function was not used by createURL for adding units, so I've made it return the parameter string
    private fun determineUnitsParameter(unitPreference: String): String {
        return when (unitPreference) {
            "°C" -> "units=metric"
            "°F" -> "units=imperial"
            else -> "units=standard" // Kelvin by default
        }
    }

    // isUserInputValid was not used in the provided getData logic, createURL handles validation.
    // If you need it, ensure its logic aligns with createURL.
    // private fun isUserInputValid(userInputs: String): Boolean { ... }


    // File operations - these seem okay but are not directly related to the crash.
    fun saveWeatherDataToFile(filename: String, weatherData: String) {
        context.openFileOutput(filename, Context.MODE_PRIVATE).use {
            it.write(weatherData.toByteArray())
        }
    }

    fun readWeatherDataFromFile(filename: String): String? {
        return try {
            context.openFileInput(filename).use {
                it.bufferedReader().readText()
            }
        } catch (e: IOException) {
            Log.e("apiServices", "Error reading from file", e)
            null
        }
    }

    fun deleteWeatherDataForLocation(location: String) {
        val filename = "${location}_weather_data.json" // Consider sanitizing location for filename
        val file = File(context.filesDir, filename)
        if (file.exists()) {
            if (file.delete()) {
                Log.d("apiServices", "Deleted file: $filename")
            } else {
                Log.e("apiServices", "Failed to delete file: $filename")
            }
        }
    }

    fun saveFavorites(favorites: List<String>) {
        try {
            val file = File(context.filesDir, "favorites.dat")
            ObjectOutputStream(file.outputStream()).use { it.writeObject(favorites) }
        } catch (e: IOException) {
            Log.e("apiServices", "Error saving favorites to file", e)
        }
    }

    fun loadFavorites(): MutableList<String> {
        return try {
            val file = File(context.filesDir, "favorites.dat")
            if (file.exists()) {
                ObjectInputStream(file.inputStream()).use {
                    @Suppress("UNCHECKED_CAST")
                    it.readObject() as MutableList<String>
                }
            } else {
                mutableListOf()
            }
        } catch (e: Exception) { // Catch broader exceptions for deserialization issues
            Log.e("apiServices", "Error loading favorites from file", e)
            mutableListOf()
        }
    }
}
