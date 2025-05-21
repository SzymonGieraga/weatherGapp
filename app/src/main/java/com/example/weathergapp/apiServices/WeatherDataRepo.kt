package com.example.weathergapp.apiServices


import android.util.Log
import org.json.JSONObject

object WeatherDataRepo {
    private var _weatherDataJson: JSONObject? = null
    private var _forecastDataJson: JSONObject? = null
    private var _hourlyDataJson: JSONObject? = null
    private val weatherObservers = mutableListOf<WeatherDataObserver>()
    private val forecastObservers = mutableListOf<WeatherDataObserver>()
    private val hourlyObservers = mutableListOf<WeatherDataObserver>()


    @Synchronized
    fun setWeatherDataJson(value: JSONObject?) {
        if (value != null) {
            _weatherDataJson = value
            notifyWeatherObservers()
        } else {
            Log.e("WeatherDataRepository", "Attempted to set null weather data JSON")
        }
    }

    @Synchronized
    fun getWeatherDataJson(): JSONObject? {
        return _weatherDataJson
    }

    @Synchronized
    fun setForecastDataJson(value: JSONObject?) {
        if (value != null) {
            _forecastDataJson = value
            notifyForecastObservers()
        } else {
            Log.e("WeatherDataRepository", "Attempted to set null forecast data JSON")
        }
    }

    @Synchronized
    fun getForecastDataJson(): JSONObject? {
        return _forecastDataJson
    }

    @Synchronized
    fun setHourlyDataJson(value: JSONObject?) {
        if (value != null) {
            _hourlyDataJson = value
            notifyHourlyObservers()
        } else {
            Log.e("WeatherDataRepository", "Attempted to set null hourly data JSON")
        }
    }

    @Synchronized
    fun getHourlyDataJson(): JSONObject? {
        return _hourlyDataJson
    }


    interface WeatherDataObserver {
        fun onWeatherDataUpdated()
        fun onForecastDataUpdated()
        fun onHourlyDataUpdated()
    }

    @Synchronized
    fun registerWeatherObserver(observer: WeatherDataObserver) {
        if (!weatherObservers.contains(observer)) {
            weatherObservers.add(observer)
        }
    }

    @Synchronized
    fun unregisterWeatherObserver(observer: WeatherDataObserver) {
        weatherObservers.remove(observer)
    }

    @Synchronized
    fun registerForecastObserver(observer: WeatherDataObserver) {
        if (!forecastObservers.contains(observer)) {
            forecastObservers.add(observer)
        }
    }

    @Synchronized
    fun unregisterForecastObserver(observer: WeatherDataObserver) {
        forecastObservers.remove(observer)
    }

    @Synchronized
    fun registerHourlyObserver(observer: WeatherDataObserver) {
        if (!hourlyObservers.contains(observer)) {
            hourlyObservers.add(observer)
        }
    }

    @Synchronized
    fun unregisterHourlyObserver(observer: WeatherDataObserver) {
        hourlyObservers.remove(observer)
    }

    private fun notifyWeatherObservers() {
        weatherObservers.forEach { it.onWeatherDataUpdated() }
    }

    private fun notifyForecastObservers() {
        forecastObservers.forEach { it.onForecastDataUpdated() }
    }

    private fun notifyHourlyObservers() {
        hourlyObservers.forEach { it.onHourlyDataUpdated() }
    }

}
