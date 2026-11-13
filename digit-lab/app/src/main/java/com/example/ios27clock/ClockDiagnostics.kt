package com.example.ios27clock

import android.util.Log

internal object ClockDiagnostics : RendererDiagnostics {
    private const val TAG = "LiquidClockDigitLab"
    override fun record(message: String) { Log.d(TAG, message) }
    override fun report(tag: String, throwable: Throwable) { Log.e(TAG, tag, throwable) }
    override fun reportMessage(title: String, details: String) { Log.e(TAG, "$title: $details") }
}
