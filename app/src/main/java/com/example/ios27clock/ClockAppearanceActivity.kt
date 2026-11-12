package com.example.ios27clock

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.hypot

/**
 * Full-screen Font & Color editor. The current selected wallpaper and the real
 * LiquidWallpaperRenderer stay visible behind the controls while each setting
 * is changed, so this page is a live appearance preview rather than a dialog
 * sitting over the app's explanatory home screen.
 */
class ClockAppearanceActivity : Activity() {

    companion object {
        private const val PREFS = "liquid_clock_composition"
        private const val KEY_FONT_STYLE = "clock_font_style"
        private const val KEY_FONT_WEIGHT = "clock_font_weight"
        private const val KEY_COLOR = "clock_color"
        private const val KEY_GLASS_MODE = "clock_glass_mode"

        private val FALLBACK_SWATCHES = intArrayOf(
            Color.WHITE,
            Color.rgb(204, 246, 235),
            Color.rgb(122, 226, 241),
            Color.rgb(128, 176, 248),
            Color.rgb(193, 169, 247),
            Color.rgb(225, 157, 244),
            Color.rgb(255, 183, 133),
            Color.rgb(255, 130, 150)
        )

        private const val WALLPAPER_SOURCE_FILE = "liquid_clock_wallpaper_source.img"
        private val FONT_NAMES = arrayOf("Regular", "Condensed", "Compressed", "Expanded")
        private val WIDTH_COORDINATES = intArrayOf(100, 60, 50, 132)
        private const val PANEL_COLOR = 0xFFE5F0FD.toInt()
    }

    private val preferences by lazy {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private lateinit var preview: WallpaperClockPreview
    private lateinit var fontTiles: List<TextView>
    private lateinit var fontCards: List<LinearLayout>
    private lateinit var colorTiles: List<FrameLayout>
    private var swatches: IntArray = FALLBACK_SWATCHES.copyOf()
    private val previewTypefaceCache = LinkedHashMap<Int, Typeface>()
    private var pendingWallpaperCommit = false
    private var previewRendererReleased = false
    private lateinit var weightLabel: TextView
    private lateinit var glassOption: TextView
    private lateinit var solidOption: TextView

    private var selectedStyle = 0
    private var selectedWeight = 400
    private var selectedColor = Color.WHITE
    private var glassMode = true

    private val density: Float
        get() = resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedStyle = preferences.getInt(KEY_FONT_STYLE, 0).coerceIn(0, 3)
        selectedWeight = preferences.getInt(KEY_FONT_WEIGHT, 400).coerceIn(100, 900)
        selectedColor = preferences.getInt(KEY_COLOR, Color.WHITE)
        swatches = buildWallpaperSwatches(selectedColor)
        glassMode = preferences.getBoolean(KEY_GLASS_MODE, true)

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = PANEL_COLOR
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        preview = WallpaperClockPreview(this)
        root.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val dateLabel = TextView(this).apply {
            text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date())
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            gravity = Gravity.CENTER
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x55000000)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(42),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(82)
            }
        }
        root.addView(dateLabel)

        val backButton = TextView(this).apply {
            text = "‹"
            textSize = 44f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x66000000)
            layoutParams = FrameLayout.LayoutParams(dp(54), dp(58), Gravity.TOP or Gravity.START)
                .apply { topMargin = dp(22); marginStart = dp(10) }
            setOnClickListener { finish() }
        }
        root.addView(backButton)


        root.addView(
            buildAppearancePanel(),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        setContentView(root)
    }

    private fun buildAppearancePanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
            background = panelBackground()
            elevation = dp(18).toFloat()
        }

        val header = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        }

        val globe = TextView(this).apply {
            text = "Aa"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(27, 42, 61))
            background = circleBackground(0xFFD6E6F7.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL)
        }

        val title = TextView(this).apply {
            text = "Font & Color"
            textSize = 19f
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            setTextColor(Color.rgb(10, 17, 28))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val close = TextView(this).apply {
            text = "×"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circleBackground(0xFFB5C6DA.toInt())
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)
            setOnClickListener { finish() }
        }

        header.addView(title)
        header.addView(globe)
        header.addView(close)
        panel.addView(header)

        panel.addView(sectionLabel("TYPEFACE"))
        panel.addView(buildFontRow())

        panel.addView(sectionLabel("WEIGHT"))
        weightLabel = TextView(this).apply {
            text = selectedWeight.toString()
            textSize = 13f
            setTextColor(Color.rgb(47, 57, 70))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(22)
            ).apply {
                topMargin = dp(8)
                marginStart = dp(8)
            }
        }
        panel.addView(weightLabel)

        val weightSeek = SeekBar(this).apply {
            max = 800
            progress = selectedWeight - 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(70, 70, 70))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.rgb(174, 188, 204))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply {
                topMargin = dp(1)
                marginStart = dp(7)
                marginEnd = dp(7)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedWeight = progress + 100
                    weightLabel.text = selectedWeight.toString()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    refreshFontTiles()
                    // Build the final glyph mask and bevel exactly once per drag.
                    // Rebuilding a full-screen path every 180ms caused excessive
                    // bitmap churn and could destabilize the hardware preview.
                    persistAppearance(rebuildDistanceField = true)
                }
            })
        }
        panel.addView(weightSeek)

        panel.addView(sectionLabel("COLOR"))
        panel.addView(buildColorRow())
        panel.addView(sectionLabel("MATERIAL"))
        panel.addView(buildMaterialSwitch())

        val applyButton = Button(this).apply {
            text = "Apply current"
            textSize = 15f
            isAllCaps = false
            setTextColor(Color.rgb(16, 32, 56))
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            background = resources.getDrawable(R.drawable.bg_primary_action, theme)
            stateListAnimator = null
            minHeight = dp(52)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(4)
            }
            setOnClickListener { applyCurrentWallpaper() }
        }
        panel.addView(applyButton)

        return panel
    }

    private fun buildFontRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(88)
            ).apply { topMargin = dp(6) }
        }

        val samples = mutableListOf<TextView>()
        val cards = mutableListOf<LinearLayout>()

        FONT_NAMES.forEachIndexed { index, name ->
            val sample = TextView(this).apply {
                text = "10:47"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(16, 25, 39))
                typeface = officialSfProTypeface(selectedWeight, WIDTH_COORDINATES[index])
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }
            val caption = TextView(this).apply {
                text = name
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(67, 82, 101))
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(18)
                )
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(6), dp(4), dp(5))
                background = fontTileBackground(index == selectedStyle)
                layoutParams = LinearLayout.LayoutParams(0, dp(84), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
                addView(sample)
                addView(caption)
                setOnClickListener {
                    selectedStyle = index
                    refreshFontTiles()
                    persistAppearance()
                }
            }
            samples += sample
            cards += card
        }

        fontTiles = samples
        fontCards = cards
        fontCards.forEach { card -> row.addView(card) }
        return row
    }

    private fun buildColorRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
            ).apply { topMargin = dp(8) }
        }

        colorTiles = swatches.mapIndexed { index, color ->
            FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                addView(
                    View(this@ClockAppearanceActivity),
                    FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
                )
                addView(
                    View(this@ClockAppearanceActivity),
                    FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER)
                )
                setOnClickListener {
                    selectedColor = color
                    refreshColorTiles()
                    persistAppearance()
                }
            }
        }

        colorTiles.forEach { swatch -> row.addView(swatch) }

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(1), 0, dp(1), 0)
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(62)
            )
        }
        refreshColorTiles()
        return scroll
    }

    private fun buildMaterialSwitch(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedPill(Color.WHITE, 0xFFD8E5F1.toInt())
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(212), dp(48)).apply {
                topMargin = dp(8)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        glassOption = TextView(this).apply {
            text = "Glass"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(20, 28, 38))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                glassMode = true
                refreshMaterialSwitch()
                persistAppearance()
            }
        }

        solidOption = TextView(this).apply {
            text = "Solid"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(20, 28, 38))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                glassMode = false
                refreshMaterialSwitch()
                persistAppearance()
            }
        }

        outer.addView(glassOption)
        outer.addView(solidOption)
        refreshMaterialSwitch()
        return outer
    }

    private fun applyCurrentWallpaper() {
        saveAppearancePreferences()
        // The editor preview owns the only in-app renderer. Release all its
        // buffers before Android creates the wallpaper preview surface.
        releasePreviewRenderer()
        pendingWallpaperCommit = false
        sendBroadcast(
            Intent(LiquidWallpaperService.ACTION_APPEARANCE_CHANGED)
                .setPackage(packageName)
        )

        val component = ComponentName(this, LiquidWallpaperService::class.java)
        val previewIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)

        try {
            startActivity(previewIntent)
            finish()
        } catch (exception: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                finish()
            } catch (fallbackException: Exception) {
                Toast.makeText(
                    this,
                    "Android could not open the wallpaper preview.",
                    Toast.LENGTH_LONG
                ).show()
                recreate()
            }
        }
    }

    private fun saveAppearancePreferences() {
        preferences.edit()
            .putInt(KEY_FONT_STYLE, selectedStyle)
            .putInt(KEY_FONT_WEIGHT, selectedWeight)
            .putInt(KEY_COLOR, selectedColor)
            .putBoolean(KEY_GLASS_MODE, glassMode)
            .apply()
    }

    private fun persistAppearance(rebuildDistanceField: Boolean = true) {
        saveAppearancePreferences()
        preview.reloadAppearance(rebuildDistanceField = rebuildDistanceField)
        pendingWallpaperCommit = true
    }

    private fun refreshFontTiles() {
        fontTiles.forEachIndexed { index, tile ->
            tile.typeface = officialSfProTypeface(
                selectedWeight,
                WIDTH_COORDINATES[index]
            )
            tile.setTextColor(Color.rgb(16, 25, 39))
            fontCards[index].background = fontTileBackground(index == selectedStyle)
        }
    }

    private fun sectionLabel(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 10f
        letterSpacing = 0.12f
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextColor(Color.rgb(94, 111, 132))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(16)
        ).apply {
            topMargin = dp(7)
            marginStart = dp(8)
        }
    }

    private fun officialSfProTypeface(weight: Int, width: Int): Typeface {
        val cacheKey = weight.coerceIn(1, 1000) * 1000 + width
        previewTypefaceCache[cacheKey]?.let { return it }
        val face = try {
            Typeface.Builder(assets, "fonts/SF-Pro.ttf")
                .setFontVariationSettings(
                    "'wght' ${weight.coerceIn(100, 900)}, 'wdth' ${width.coerceIn(50, 132)}"
                )
                .build()
        } catch (exception: Exception) {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }
        if (previewTypefaceCache.size >= 32) previewTypefaceCache.clear()
        previewTypefaceCache[cacheKey] = face
        return face
    }

    /**
     * Build the colour row from the currently selected wallpaper. A white
     * neutral is kept first; the remaining samples are the strongest distinct
     * saturated hues in the image, with the currently selected tint preserved.
     */
    private fun buildWallpaperSwatches(selected: Int): IntArray {
        val source = File(filesDir, WALLPAPER_SOURCE_FILE)
        val bitmap = if (source.exists() && source.length() > 0L) {
            BitmapFactory.decodeFile(
                source.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = 8
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        } else {
            null
        }

        val palette = mutableListOf(Color.WHITE)
        if (bitmap != null) {
            try {
                val bins = 24
                val weights = DoubleArray(bins)
                val red = DoubleArray(bins)
                val green = DoubleArray(bins)
                val blue = DoubleArray(bins)
                val hsv = FloatArray(3)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(
                    pixels,
                    0,
                    bitmap.width,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height
                )

                for (pixel in pixels) {
                    Color.colorToHSV(pixel, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    if (saturation < 0.22f || value < 0.18f || value > 0.985f) continue
                    val bin = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
                    val weight = saturation.toDouble() * (0.45 + value.toDouble() * 0.55)
                    weights[bin] += weight
                    red[bin] += Color.red(pixel) * weight
                    green[bin] += Color.green(pixel) * weight
                    blue[bin] += Color.blue(pixel) * weight
                }

                val orderedBins = (0 until bins).sortedByDescending { weights[it] }
                val pickedHues = mutableListOf<Float>()
                for (bin in orderedBins) {
                    if (weights[bin] <= 0.0) continue
                    val hue = (bin + 0.5f) * (360f / bins)
                    val separated = pickedHues.all { existing ->
                        val difference = abs(hue - existing)
                        minOf(difference, 360f - difference) >= 24f
                    }
                    if (!separated) continue

                    palette += Color.rgb(
                        (red[bin] / weights[bin]).toInt().coerceIn(0, 255),
                        (green[bin] / weights[bin]).toInt().coerceIn(0, 255),
                        (blue[bin] / weights[bin]).toInt().coerceIn(0, 255)
                    )
                    pickedHues += hue
                    if (palette.size >= 7) break
                }
            } catch (exception: Exception) {
                // Keep the remaining controls usable if an unusual image cannot
                // be sampled for its accent colours.
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }

        if (selected !in palette) palette += selected
        var fallbackIndex = 1
        while (palette.size < 8) {
            val fallback = FALLBACK_SWATCHES[fallbackIndex++ % FALLBACK_SWATCHES.size]
            if (fallback !in palette) palette += fallback
        }
        return palette.take(8).toIntArray()
    }

    private fun refreshColorTiles() {
        colorTiles.forEachIndexed { index, frame ->
            val selected = swatches[index] == selectedColor
            val outer = frame.getChildAt(0)
            outer.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(
                    if (selected) dp(4) else dp(1),
                    if (selected) Color.rgb(0, 133, 255) else 0x33465D75
                )
            }
            frame.getChildAt(1).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(swatches[index])
            }
        }
    }

    private fun refreshMaterialSwitch() {
        glassOption.background = if (glassMode) {
            roundedPill(0xFFD5E5F7.toInt(), Color.TRANSPARENT)
        } else null
        solidOption.background = if (!glassMode) {
            roundedPill(0xFFD5E5F7.toInt(), Color.TRANSPARENT)
        } else null
        glassOption.setTypeface(null, if (glassMode) Typeface.BOLD else Typeface.NORMAL)
        solidOption.setTypeface(null, if (!glassMode) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun fontTileBackground(selected: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(17).toFloat()
        setColor(if (selected) 0xFFDCEEFF.toInt() else Color.TRANSPARENT)
        if (selected) setStroke(dp(3), Color.rgb(0, 133, 255))
        else setStroke(dp(1), 0x22465D75)
    }

    private fun panelBackground() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xEAF5FAFF.toInt(), 0xE1E5EEFC.toInt())
    ).apply {
        cornerRadii = floatArrayOf(
            dp(32).toFloat(), dp(32).toFloat(),
            dp(32).toFloat(), dp(32).toFloat(),
            dp(0).toFloat(), dp(0).toFloat(),
            dp(0).toFloat(), dp(0).toFloat()
        )
        setStroke(dp(1), 0xAAFFFFFF.toInt())
    }

    private fun circleBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundedPill(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(24).toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun releasePreviewRenderer() {
        if (previewRendererReleased || !::preview.isInitialized) return
        preview.releaseRenderer()
        previewRendererReleased = true
    }

    override fun onDestroy() {
        releasePreviewRenderer()
        if (pendingWallpaperCommit) {
            sendBroadcast(
                Intent(LiquidWallpaperService.ACTION_APPEARANCE_CHANGED)
                    .setPackage(packageName)
            )
        }
        super.onDestroy()
    }

    private class WallpaperClockPreview(context: Context) : View(context) {
        private val renderer = LiquidWallpaperRenderer(context)
        private var renderWidth = 0
        private var renderHeight = 0

        init {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                // The editor is now the sole preview surface. Render at native
                // view resolution instead of scaling a soft, reduced bitmap.
                renderWidth = w
                renderHeight = h
                renderer.setViewport(renderWidth, renderHeight)
            }
        }

        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var draggingClock = false
        private var pinchingClock = false
        private var touchGestureCaptured = false
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var pinchStartDistance = 0f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (renderWidth <= 0 || renderHeight <= 0) return
            val seconds = SystemClock.uptimeMillis() / 1000f
            val save = canvas.save()
            canvas.scale(
                width.toFloat() / renderWidth,
                height.toFloat() / renderHeight
            )
            try {
                renderer.draw(canvas, seconds)
            } catch (memoryError: OutOfMemoryError) {
                // Keep the editor alive if a device cannot allocate a transient
                // glyph/SDF buffer; the next interaction can retry the render.
                Log.e("ClockAppearancePreview", "Preview ran out of memory while rendering.", memoryError)
            } catch (exception: RuntimeException) {
                Log.e("ClockAppearancePreview", "Preview frame failed; keeping the editor interactive.", exception)
            } finally {
                canvas.restoreToCount(save)
            }
            if (visibility == View.VISIBLE) postInvalidateDelayed(40L)
        }

        private fun renderX(viewX: Float): Float =
            viewX * renderWidth / width.coerceAtLeast(1)

        private fun renderY(viewY: Float): Float =
            viewY * renderHeight / height.coerceAtLeast(1)

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = renderX(event.getX(0)) - renderX(event.getX(1))
            val dy = renderY(event.getY(0)) - renderY(event.getY(1))
            return hypot(dx, dy)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (renderWidth <= 0 || renderHeight <= 0) return false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = renderX(event.x)
                    val y = renderY(event.y)
                    if (!renderer.isClockHit(x, y)) return false

                    activePointerId = event.getPointerId(0)
                    lastTouchX = x
                    lastTouchY = y
                    draggingClock = true
                    pinchingClock = false
                    touchGestureCaptured = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2 && draggingClock) {
                        pinchStartDistance = pointerDistance(event)
                        if (pinchStartDistance > 8f) {
                            renderer.beginScaleGesture()
                            draggingClock = false
                            pinchingClock = true
                        }
                    }
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pinchingClock && event.pointerCount >= 2) {
                        val distance = pointerDistance(event)
                        if (pinchStartDistance > 8f && distance > 8f) {
                            renderer.scaleClock(distance / pinchStartDistance)
                            invalidate()
                        }
                        return true
                    }

                    if (draggingClock) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = renderX(event.getX(pointerIndex))
                            val y = renderY(event.getY(pointerIndex))
                            renderer.moveClockBy(x - lastTouchX, y - lastTouchY)
                            lastTouchX = x
                            lastTouchY = y
                            invalidate()
                        }
                        return true
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (pinchingClock) {
                        renderer.finishGesture()
                        pinchingClock = false
                        pinchStartDistance = 0f

                        // Continue as a one-finger drag with whichever pointer
                        // remains down, so the gesture doesn't get orphaned.
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        if (remainingIndex < event.pointerCount) {
                            activePointerId = event.getPointerId(remainingIndex)
                            lastTouchX = renderX(event.getX(remainingIndex))
                            lastTouchY = renderY(event.getY(remainingIndex))
                            draggingClock = true
                        }
                        renderer.persistComposition()
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (pinchingClock) renderer.finishGesture()
                    if (draggingClock || pinchingClock) {
                        renderer.persistComposition()
                        invalidate()
                    }
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    draggingClock = false
                    pinchingClock = false
                    touchGestureCaptured = false
                    pinchStartDistance = 0f
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
            return touchGestureCaptured
        }

        fun reloadAppearance(rebuildDistanceField: Boolean = true) {
            renderer.reloadAppearance(rebuildDistanceField)
            invalidate()
        }

        fun releaseRenderer() {
            renderer.release()
        }
    }
}
