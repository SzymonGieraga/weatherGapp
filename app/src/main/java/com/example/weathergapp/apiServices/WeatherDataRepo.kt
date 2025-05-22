package com.example.weathergapp.apiServices


import android.util.Log
import org.json.JSONObject

object WeatherDataRepo {
    private var _weatherDataJson: JSONObject? = null
    private var _forecastDataJson: JSONObject? = null
    private val weatherObservers = mutableListOf<WeatherDataObserver>()
    private val forecastObservers = mutableListOf<WeatherDataObserver>()


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

    private fun notifyWeatherObservers() {
        weatherObservers.forEach { it.onWeatherDataUpdated() }
    }

    private fun notifyForecastObservers() {
        forecastObservers.forEach { it.onForecastDataUpdated() }
    }

}
