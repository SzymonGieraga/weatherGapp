package com.example.weathergapp

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFERENCES_FILE_KEY = "com.example.weathergapp.APP_PREFERENCES"
    private const val KEY_REFRESH_INTERVAL_MINUTES = "refresh_interval_minutes"
    private const val KEY_UNIT = "selected_unit"

    const val DEFAULT_REFRESH_INTERVAL_MINUTES = 0
    const val DEFAULT_UNIT = "°C"

    val REFRESH_INTERVAL_OPTIONS = listOf(0, 15, 30, 60) // 0 for Disabled

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFERENCES_FILE_KEY, Context.MODE_PRIVATE)
    }

    fun saveRefreshInterval(context: Context, intervalMinutes: Int) {
        val editor = getPreferences(context).edit()
        editor.putInt(KEY_REFRESH_INTERVAL_MINUTES, intervalMinutes)
        editor.apply()
    }

    fun loadRefreshInterval(context: Context): Int {
        return getPreferences(context).getInt(KEY_REFRESH_INTERVAL_MINUTES, DEFAULT_REFRESH_INTERVAL_MINUTES)
    }

    fun saveUnit(context: Context, unit: String) {
        val editor = getPreferences(context).edit()
        editor.putString(KEY_UNIT, unit)
        editor.apply()
    }

    fun loadUnit(context: Context): String {
        return getPreferences(context).getString(KEY_UNIT, DEFAULT_UNIT) ?: DEFAULT_UNIT
    }
}