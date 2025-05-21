package com.example.weathergapp

object DataManager {
    var editTextData: String = ""

    var unit: String = ""
    set(value) {
        field = value
        observers.forEach { it.onUnitChanged(value) }
    }

    private val observers = mutableListOf<UnitObserver>()

    fun registerObserver(observer: UnitObserver) {
        observers.add(observer)
    }

    fun unregisterObserver(observer: UnitObserver) {
        observers.remove(observer)
    }

    interface UnitObserver {
        fun onUnitChanged(newUnit: String)
    }
}
