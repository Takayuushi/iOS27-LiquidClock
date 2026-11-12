package com.example.ios27clock

import android.util.Log

internal object ClockDiagnostics {
    private const val TAG = "LiquidClockDigitLab"
    fun record(message: String) { Log.d(TAG, message) }
    fun report(tag: String, throwable: Throwable) { Log.e(TAG, tag, throwable) }
    fun reportMessage(title: String, details: String) { Log.e(TAG, "$title: $details") }
}
