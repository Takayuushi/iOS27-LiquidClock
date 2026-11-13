package com.example.ios27clock

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Supplies display text; Digit Lab injects a fixed sample, Production uses device time. */
interface RendererClockTextSource {
    fun currentTimeText(context: Context): String
}

object SystemRendererClockTextSource : RendererClockTextSource {
    override fun currentTimeText(context: Context): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }
}
