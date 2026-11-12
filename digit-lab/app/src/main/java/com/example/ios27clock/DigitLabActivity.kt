package com.example.ios27clock

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot

/** Standalone full-screen renderer test; no WallpaperManager calls or production storage access. */
class DigitLabActivity : Activity() {
    companion object {
        private const val REQUEST_WALLPAPER = 4101
        private const val DEFAULT_SAMPLE = "0123456789"
        private const val MAX_SAMPLE_LENGTH = 10
    }

    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    private val preferences by lazy {
        getSharedPreferences(ClockWallpaperContent.PREVIEW.compositionPreferencesName, MODE_PRIVATE)
    }

    private lateinit var renderer: LiquidWallpaperRenderer
    private lateinit var preview: RendererPreview
    private lateinit var sampleInput: EditText
    private lateinit var modeGlass: TextView
    private lateinit var modeFrosted: TextView
    private lateinit var weightValue: TextView
    private lateinit var panel: View
    private lateinit var expandButton: TextView
    private var styleButtons: List<TextView> = emptyList()
    private var glassMode = true
    private var fontStyle = 0
    private var fontWeight = 400
    private var controlsHidden = false

    private val handler = Handler(Looper.getMainLooper())
    private val applySample = Runnable {
        val value = sampleInput.text?.toString().orEmpty()
        if (isValidSample(value)) {
            renderer.setTestTimeOverride(value)
            preview.invalidate()
        }
    }
    private val applyWeight = Runnable {
        preferences.edit().putInt("clock_font_weight", fontWeight).apply()
        renderer.reloadAppearance(rebuildDistanceField = true)
        preview.invalidate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = 0xE8090E18.toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        glassMode = preferences.getBoolean("clock_glass_mode", true)
        fontStyle = preferences.getInt("clock_font_style", 0).coerceIn(0, 3)
        fontWeight = preferences.getInt("clock_font_weight", 400).coerceIn(100, 900)

        renderer = LiquidWallpaperRenderer(this, ClockWallpaperContent.PREVIEW)
        renderer.setTestTimeOverride(DEFAULT_SAMPLE)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = RendererPreview(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))

        root.addView(TextView(this).apply {
            text = "LIQUID CLOCK  •  DIGIT LAB"
            textSize = 13f
            letterSpacing = 0.09f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            gravity = Gravity.CENTER
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            layoutParams = FrameLayout.LayoutParams(-1, dp(42), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                .apply { topMargin = dp(36) }
        })

        panel = buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(-1, dp(328), Gravity.BOTTOM))

        expandButton = TextView(this).apply {
            text = "⌃"
            textSize = 28f
            setTextColor(0xFF24364B.toInt())
            gravity = Gravity.CENTER
            background = circleBackground(0xEAF0F7FF.toInt())
            elevation = dp(18).toFloat()
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(50), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(16) }
            contentDescription = "Show Digit Lab controls"
            setOnClickListener { expandControls() }
        }
        root.addView(expandButton)
        setContentView(root)
        updateModeButtons()
    }

    private fun buildPanel(): View {
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            background = roundedBackground(0xE90B1220.toInt(), dp(24))
            elevation = dp(18).toFloat()
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Wallpaper & sample"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(makeButton("Choose image") { chooseWallpaper() })
        content.addView(header)

        val sampleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sampleRow.addView(TextView(this).apply {
            text = "Digits"
            textSize = 14f
            setTextColor(0xFFE5EDF8.toInt())
        }, LinearLayout.LayoutParams(dp(58), dp(46)))
        sampleInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF9AA9BC.toInt())
            hint = "00:00 or 0123456789"
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(0xFF182438.toInt(), dp(12))
            keyListener = DigitsKeyListener.getInstance("0123456789:")
            filters = arrayOf(InputFilter.LengthFilter(MAX_SAMPLE_LENGTH))
            setText(DEFAULT_SAMPLE)
            gravity = Gravity.CENTER_VERTICAL
        }
        sampleRow.addView(sampleInput, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(sampleRow)

        sampleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                handler.removeCallbacks(applySample)
                if (isValidSample(value)) handler.postDelayed(applySample, 220L)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val presetScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(8), 0, dp(8))
        }
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "0123456789", "00:00", "11:11", "22:22", "33:33", "44:44",
            "55:55", "66:66", "77:77", "88:88", "99:99"
        ).forEach { value ->
            presetRow.addView(
                makeChip(value, value == DEFAULT_SAMPLE) {
                    sampleInput.setText(value)
                    sampleInput.setSelection(sampleInput.text.length)
                    renderer.setTestTimeOverride(value)
                    preview.invalidate()
                },
                LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(7) }
            )
        }
        presetScroll.addView(presetRow)
        content.addView(presetScroll)

        content.addView(sectionLabel("MATERIAL"))
        val materialRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeGlass = makeChip("Liquid Glass", glassMode) { setGlassMode(true) }
        modeFrosted = makeChip("Frosted Glass", !glassMode) { setGlassMode(false) }
        materialRow.addView(modeGlass, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        materialRow.addView(modeFrosted, LinearLayout.LayoutParams(0, dp(42), 1f))
        content.addView(materialRow)

        content.addView(sectionLabel("FONT WIDTH"))
        val styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val styleList = mutableListOf<TextView>()
        listOf("Regular", "Condensed", "Compressed", "Expanded").forEachIndexed { index, name ->
            val chip = makeChip(name, fontStyle == index) {
                fontStyle = index
                preferences.edit().putInt("clock_font_style", index).apply()
                renderer.reloadAppearance(rebuildDistanceField = true)
                updateStyleButtons()
                preview.invalidate()
            }
            styleList += chip
            styleRow.addView(chip, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                if (index < 3) marginEnd = dp(5)
            })
        }
        styleButtons = styleList
        content.addView(styleRow)

        val weightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weightRow.addView(sectionLabel("WEIGHT"), LinearLayout.LayoutParams(0, dp(28), 1f))
        weightValue = TextView(this).apply {
            text = fontWeight.toString()
            textSize = 12f
            setTextColor(0xFFC7D5E7.toInt())
        }
        weightRow.addView(weightValue)
        content.addView(weightRow)

        content.addView(SeekBar(this).apply {
            max = 800
            progress = fontWeight - 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    fontWeight = progress + 100
                    weightValue.text = fontWeight.toString()
                    handler.removeCallbacks(applyWeight)
                    handler.postDelayed(applyWeight, 180L)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    handler.removeCallbacks(applyWeight)
                    applyWeight.run()
                }
            })
        })

        content.addView(TextView(this).apply {
            text = "Type any digits. Drag the clock to reposition; pinch vertically for height and horizontally for width."
            textSize = 11f
            setTextColor(0xFFB4C3D5.toInt())
            setPadding(0, dp(4), 0, dp(6))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(makeButton("Hide controls") { collapseControls() })
        actions.addView(TextView(this).apply {
            text = "  Separate test app • does not apply wallpaper"
            textSize = 10f
            setTextColor(0xFF9EADC0.toInt())
            gravity = Gravity.CENTER_VERTICAL
        })
        content.addView(actions)

        scroll.addView(content)
        return scroll
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 10f
        letterSpacing = 0.12f
        setTextColor(0xFF9FB5D0.toInt())
        setPadding(0, dp(6), 0, dp(5))
    }

    private fun makeButton(label: String, action: () -> Unit) =
        makeChip(label, false, action)

    private fun makeChip(label: String, selected: Boolean, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(Color.WHITE)
            background = roundedBackground(
                if (selected) 0xFF34547A.toInt() else 0xFF1B293C.toInt(),
                dp(12)
            )
            setOnClickListener { action() }
        }

    private fun roundedBackground(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), 0x338BA6C8)
    }

    private fun circleBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun updateStyleButtons() {
        styleButtons.forEachIndexed { index, chip ->
            chip.background = roundedBackground(
                if (index == fontStyle) 0xFF34547A.toInt() else 0xFF1B293C.toInt(),
                dp(10)
            )
        }
    }

    private fun updateModeButtons() {
        modeGlass.background = roundedBackground(if (glassMode) 0xFF34547A.toInt() else 0xFF1B293C.toInt(), dp(12))
        modeFrosted.background = roundedBackground(if (!glassMode) 0xFF34547A.toInt() else 0xFF1B293C.toInt(), dp(12))
    }

    private fun setGlassMode(useGlass: Boolean) {
        glassMode = useGlass
        preferences.edit().putBoolean("clock_glass_mode", useGlass).apply()
        renderer.reloadAppearance(rebuildDistanceField = false)
        updateModeButtons()
        preview.invalidate()
    }

    private fun isValidSample(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= MAX_SAMPLE_LENGTH &&
            value.all { it in '0'..'9' || it == ':' } &&
            value.first() != ':' &&
            value.last() != ':' &&
            !value.contains("::")

    private fun chooseWallpaper() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_WALLPAPER)
    }

    @Deprecated("Legacy activity result used for broad platform compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val destination = File(filesDir, WallpaperStorage.PREVIEW_SOURCE_FILE_NAME)
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open selected image." }
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
            File(filesDir, WallpaperStorage.PREVIEW_MANUAL_FOREGROUND_FILE_NAME).delete()
            renderer.reloadWallpaper()
            preview.invalidate()
            Toast.makeText(this, "Preview image loaded; system wallpaper left unchanged.", Toast.LENGTH_SHORT).show()
        } catch (exception: Exception) {
            Toast.makeText(this, "Could not load selected image.", Toast.LENGTH_LONG).show()
        }
    }

    private fun collapseControls() {
        if (controlsHidden) return
        controlsHidden = true
        panel.animate().cancel()
        panel.animate().translationY(panel.height.toFloat()).setDuration(220L).withEndAction {
            panel.visibility = View.GONE
            expandButton.alpha = 0f
            expandButton.visibility = View.VISIBLE
            expandButton.animate().alpha(1f).setDuration(140L).start()
        }.start()
    }

    private fun expandControls() {
        if (!controlsHidden) return
        controlsHidden = false
        panel.visibility = View.VISIBLE
        panel.translationY = panel.height.toFloat()
        panel.animate().translationY(0f).setDuration(240L).start()
        expandButton.animate().alpha(0f).setDuration(120L).withEndAction {
            expandButton.visibility = View.GONE
            expandButton.alpha = 1f
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::renderer.isInitialized) renderer.release()
        super.onDestroy()
    }

    private enum class PinchAxis { HORIZONTAL, VERTICAL }

    private inner class RendererPreview(context: Activity) : View(context) {
        private var pinchAxis = PinchAxis.VERTICAL
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var pinchStartDistance = 0f
        private var draggingClock = false
        private var pinchingClock = false

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            renderer.setViewport(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            renderer.draw(canvas, SystemClock.uptimeMillis() / 1000f)
            postInvalidateDelayed(40L)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    draggingClock = renderer.isClockHit(event.x, event.y)
                    pinchingClock = false
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (draggingClock && event.pointerCount >= 2) {
                        val dx = event.getX(0) - event.getX(1)
                        val dy = event.getY(0) - event.getY(1)
                        pinchAxis = if (abs(dx) > abs(dy) * 1.15f) PinchAxis.HORIZONTAL else PinchAxis.VERTICAL
                        pinchStartDistance = if (pinchAxis == PinchAxis.HORIZONTAL) abs(dx) else hypot(dx, dy)
                        if (pinchStartDistance > dp(8)) {
                            if (pinchAxis == PinchAxis.HORIZONTAL) renderer.beginWidthScaleGesture()
                            else renderer.beginScaleGesture()
                            draggingClock = false
                            pinchingClock = true
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (pinchingClock && event.pointerCount >= 2) {
                        val dx = event.getX(0) - event.getX(1)
                        val dy = event.getY(0) - event.getY(1)
                        val distance = if (pinchAxis == PinchAxis.HORIZONTAL) abs(dx) else hypot(dx, dy)
                        if (pinchStartDistance > dp(8) && distance > dp(8)) {
                            val ratio = distance / pinchStartDistance
                            if (pinchAxis == PinchAxis.HORIZONTAL) renderer.scaleClockWidth(ratio)
                            else renderer.scaleClock(ratio)
                            invalidate()
                        }
                        return true
                    }
                    if (draggingClock) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex >= 0) {
                            val x = event.getX(pointerIndex)
                            val y = event.getY(pointerIndex)
                            renderer.moveClockBy(x - lastTouchX, y - lastTouchY)
                            lastTouchX = x
                            lastTouchY = y
                            invalidate()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (pinchingClock) finishGesture()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (draggingClock || pinchingClock) finishGesture()
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    draggingClock = false
                    pinchingClock = false
                    performClick()
                    return true
                }
            }
            return true
        }

        private fun finishGesture() {
            renderer.finishGesture()
            renderer.persistComposition()
            draggingClock = false
            pinchingClock = false
            invalidate()
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }
}
