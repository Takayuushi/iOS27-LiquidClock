package com.example.ios27clock

import android.app.Application

class LiquidClockApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ClockDiagnostics.install(this)
    }
}
