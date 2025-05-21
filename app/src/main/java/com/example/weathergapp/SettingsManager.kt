package com.example.weathergapp

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {

    private const val PREFERENCES_FILE_KEY = "com.example.weathergapp.APP_PREFERENCES"
    private const val KEY_REFRESH_INTERVAL_MINUTES = "refresh_interval_minutes"

    // Default refresh interval in minutes. 0 means disabled.
    const val DEFAULT_REFRESH_INTERVAL_MINUTES = 0
    val REFRESH_INTERVAL_OPTIONS = listOf(0, 15, 30, 60)

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
}
