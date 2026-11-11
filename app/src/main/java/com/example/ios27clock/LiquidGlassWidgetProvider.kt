package com.example.ios27clock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.RemoteViews
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class LiquidGlassWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val FONT_ASSET_PATH = "fonts/sf_pro_display.otf"
        private const val FONT_CACHE_NAME = "sf_pro_display.otf"
        private const val TRACKING_EM = -0.06f

        @Volatile
        private var cachedTypeface: Typeface? = null

        private val timeChangeActions = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED
        )
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateClockWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(
            context,
            appWidgetManager,
            appWidgetId,
            newOptions
        )
        updateClockWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action?.let { it in timeChangeActions } == true) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, LiquidGlassWidgetProvider::class.java)
            val widgetIds = manager.getAppWidgetIds(provider)
            onUpdate(context, manager, widgetIds)
        }
    }

    private fun updateClockWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val (widthPx, heightPx) = resolveWidgetSize(
            context,
            appWidgetManager,
            appWidgetId
        )

        val bitmap = renderClockBitmap(
            context = context,
            width = widthPx,
            height = heightPx
        )

        val views = RemoteViews(
            context.packageName,
            R.layout.widget_ios_clock
        )

        views.setImageViewBitmap(
            R.id.widget_clock_image,
            bitmap
        )

        val openApp = PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        views.setOnClickPendingIntent(R.id.widget_clock_root, openApp)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    /**
     * AppWidgetManager reports size bounds in dp. In portrait, use the
     * minimum width and maximum height; in landscape, use maximum width
     * and minimum height to fit the launcher's orientation-specific bounds.
     */
    private fun resolveWidgetSize(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ): Pair<Int, Int> {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)

        val minWidthDp = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            220
        )
        val maxWidthDp = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,
            minWidthDp
        )
        val minHeightDp = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            112
        )
        val maxHeightDp = options.getInt(
            AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
            minHeightDp
        )

        val landscape =
            context.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE

        val widthDp = if (landscape) maxWidthDp else minWidthDp
        val heightDp = if (landscape) minHeightDp else maxHeightDp
        val density = context.resources.displayMetrics.density

        val widthPx = (widthDp * density).roundToInt().coerceAtLeast(1)
        val heightPx = (heightDp * density).roundToInt().coerceAtLeast(1)

        return widthPx to heightPx
    }

    private fun renderClockBitmap(
        context: Context,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        ).apply {
            density = context.resources.displayMetrics.densityDpi
        }

        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)

        val timeText = currentTimeText(context)
        val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        ).apply {
            color = Color.WHITE
            typeface = loadClockTypeface(context)
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
            letterSpacing = TRACKING_EM
            isSubpixelText = true
            isLinearText = true
        }

        val textSizePx = calculateFittingTextSize(
            paint = paint,
            text = timeText,
            maxWidth = width * 0.94f,
            maxHeight = height * 0.82f,
            upperBound = maxOf(width, height) * 2f
        )

        paint.textSize = textSizePx
        paint.setShadowLayer(
            textSizePx * 0.018f,
            0f,
            textSizePx * 0.012f,
            Color.argb(72, 0, 0, 0)
        )

        val metrics = paint.fontMetrics
        val baseline = (height / 2f) -
            ((metrics.ascent + metrics.descent) / 2f)

        canvas.drawText(
            timeText,
            width / 2f,
            baseline,
            paint
        )

        paint.clearShadowLayer()
        return bitmap
    }

    private fun calculateFittingTextSize(
        paint: Paint,
        text: String,
        maxWidth: Float,
        maxHeight: Float,
        upperBound: Float
    ): Float {
        var low = 1f
        var high = upperBound
        var best = 1f

        repeat(28) {
            val candidate = (low + high) / 2f
            paint.textSize = candidate

            val metrics = paint.fontMetrics
            val textHeight = metrics.descent - metrics.ascent
            val textWidth = paint.measureText(text)

            if (textWidth <= maxWidth && textHeight <= maxHeight) {
                best = candidate
                low = candidate
            } else {
                high = candidate
            }
        }

        return best
    }

    private fun currentTimeText(context: Context): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    /**
     * Typeface.createFromFile requires a filesystem path. The workflow
     * copies the same OTF into assets; copy it to private storage once,
     * then reuse the Typeface for subsequent widget renders.
     */
    private fun loadClockTypeface(context: Context): Typeface {
        cachedTypeface?.let { return it }

        synchronized(this) {
            cachedTypeface?.let { return it }

            return try {
                val fontFile = File(context.filesDir, FONT_CACHE_NAME)

                if (!fontFile.exists() || fontFile.length() == 0L) {
                    context.assets.open(FONT_ASSET_PATH).use { input ->
                        fontFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                Typeface.createFromFile(fontFile).also {
                    cachedTypeface = it
                }
            } catch (exception: Exception) {
                Typeface.create("sans-serif-thin", Typeface.NORMAL).also {
                    cachedTypeface = it
                }
            }
        }
    }
}
