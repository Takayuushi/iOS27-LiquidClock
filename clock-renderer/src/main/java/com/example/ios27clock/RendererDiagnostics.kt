package com.example.ios27clock

import android.util.Log

/** Host-independent diagnostic contract for the shared renderer. */
interface RendererDiagnostics {
    fun record(message: String)
    fun report(tag: String, throwable: Throwable)
    fun reportMessage(title: String, details: String)
}

object LogRendererDiagnostics : RendererDiagnostics {
    private const val TAG = "LiquidClockRenderer"
    override fun record(message: String) { Log.d(TAG, message) }
    override fun report(tag: String, throwable: Throwable) { Log.e(TAG, tag, throwable) }
    override fun reportMessage(title: String, details: String) { Log.e(TAG, "$title: $details") }
}
