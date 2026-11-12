package com.example.ios27clock

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * Native bottom-sheet appearance editor inspired by the iOS Lock Screen
 * Font & Color panel. All controls persist directly to the wallpaper settings.
 */
internal class ClockAppearanceDialog(
    private val activity: Activity
) {
    companion object {
        private const val PREFS = "liquid_clock_composition"
        private const val KEY_FONT_STYLE = "clock_font_style"
        private const val KEY_FONT_WEIGHT = "clock_font_weight"
        private const val KEY_COLOR = "clock_color"
        private const val KEY_GLASS_MODE = "clock_glass_mode"

        private val SWATCHES = intArrayOf(
            Color.rgb(204, 246, 235),
            Color.WHITE,
            Color.rgb(255, 255, 255),
            Color.rgb(122, 226, 241),
            Color.rgb(128, 176, 248),
            Color.rgb(193, 169, 247),
            Color.rgb(225, 157, 244),
            Color.rgb(255, 183, 133)
        )

        private val FONT_NAMES = arrayOf("Default", "Tall", "Rounded", "Condensed")
    }

    private val preferences
        get() = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)

    private val density: Float
        get() = activity.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    fun show() {
        val dialog = Dialog(activity)
        var selectedStyle = preferences.getInt(KEY_FONT_STYLE, 0).coerceIn(0, FONT_NAMES.lastIndex)
        var selectedWeight = preferences.getInt(KEY_FONT_WEIGHT, 640).coerceIn(100, 900)
        var selectedColor = preferences.getInt(KEY_COLOR, Color.WHITE)
        var glassMode = preferences.getBoolean(KEY_GLASS_MODE, true)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(26))
            background = roundedPanel()
            elevation = dp(18).toFloat()
        }

        fun persistAndRefresh() {
            preferences.edit()
                .putInt(KEY_FONT_STYLE, selectedStyle)
                .putInt(KEY_FONT_WEIGHT, selectedWeight)
                .putInt(KEY_COLOR, selectedColor)
                .putBoolean(KEY_GLASS_MODE, glassMode)
                .apply()
            activity.sendBroadcast(
                Intent(LiquidWallpaperService.ACTION_APPEARANCE_CHANGED)
                    .setPackage(activity.packageName)
            )
        }

        val header = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            )
        }
        val globe = TextView(activity).apply {
            text = "◎"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(28, 42, 60))
            background = circleBackground(Color.rgb(202, 216, 232))
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL)
        }
        val title = TextView(activity).apply {
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
        val close = TextView(activity).apply {
            text = "×"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circleBackground(Color.rgb(181, 198, 218))
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(globe)
        header.addView(close)
        root.addView(header)

        val fontRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(94)
            ).apply {
                topMargin = dp(12)
            }
        }
        val fontTiles = mutableListOf<TextView>()
        val baseFont = try {
            activity.resources.getFont(R.font.sf_pro_display)
        } catch (_: Exception) {
            Typeface.create("sans-serif", Typeface.NORMAL)
        }

        fun styleTileBackground(selected: Boolean): GradientDrawable =
            GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(if (selected) Color.rgb(222, 238, 255) else Color.TRANSPARENT)
                if (selected) {
                    setStroke(dp(3), Color.rgb(0, 133, 255))
                } else {
                    setStroke(dp(1), Color.argb(35, 40, 60, 80))
                }
            }

        fun refreshFontTiles() {
            fontTiles.forEachIndexed { index, tile ->
                tile.background = styleTileBackground(index == selectedStyle)
                tile.alpha = if (index == selectedStyle) 1f else 0.84f
            }
        }

        FONT_NAMES.forEachIndexed { index, _ ->
            val tile = TextView(activity).apply {
                text = "12"
                textSize = 37f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                typeface = when (index) {
                    2 -> Typeface.create("sans-serif-rounded", Typeface.NORMAL)
                    else -> baseFont
                }
                when (index) {
                    1 -> scaleY = 1.10f
                    2 -> scaleX = 1.02f
                    3 -> scaleX = 0.84f
                }
                background = styleTileBackground(index == selectedStyle)
                layoutParams = LinearLayout.LayoutParams(0, dp(76), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    selectedStyle = index
                    refreshFontTiles()
                    persistAndRefresh()
                }
            }
            fontTiles += tile
            fontRow.addView(tile)
        }
        root.addView(fontRow)

        val weightLabel = TextView(activity).apply {
            text = "Weight  ·  $selectedWeight"
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
        root.addView(weightLabel)

        val weightSeek = SeekBar(activity).apply {
            max = 800
            progress = selectedWeight - 100
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(70, 70, 70))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(105, 112, 123))
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
                    weightLabel.text = "Weight  ·  $selectedWeight"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    persistAndRefresh()
                }
            })
        }
        root.addView(weightSeek)

        val colorRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70)
            ).apply {
                topMargin = dp(11)
            }
        }
        val colorViews = mutableListOf<FrameLayout>()

        fun refreshColorSwatches() {
            colorViews.forEachIndexed { index, frame ->
                val selected = SWATCHES[index] == selectedColor
                val outer = frame.getChildAt(0) as View
                outer.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(if (selected) dp(4) else dp(1), if (selected) {
                        Color.rgb(0, 133, 255)
                    } else {
                        Color.argb(42, 40, 60, 80)
                    })
                }
                val inner = frame.getChildAt(1) as View
                inner.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(SWATCHES[index])
                }
            }
        }

        SWATCHES.forEachIndexed { index, color ->
            val frame = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                }
                val outer = View(activity)
                addView(outer, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
                val inner = View(activity)
                addView(inner, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                setOnClickListener {
                    selectedColor = color
                    refreshColorSwatches()
                    persistAndRefresh()
                }
            }
            colorViews += frame
            colorRow.addView(frame)
        }
        refreshColorSwatches()

        val colorScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(1), 0, dp(1), 0)
            addView(colorRow)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(70)
            )
        }
        root.addView(colorScroll)

        val modeOuter = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedPill(Color.rgb(255, 255, 255), Color.rgb(218, 229, 240))
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                dp(212),
                dp(48)
            ).apply {
                topMargin = dp(12)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val glassButton = TextView(activity).apply {
            text = "Glass"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(20, 28, 38))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val solidButton = TextView(activity).apply {
            text = "Solid"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(20, 28, 38))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        fun refreshModes() {
            glassButton.background = if (glassMode) {
                roundedPill(Color.rgb(213, 229, 247), Color.TRANSPARENT)
            } else null
            solidButton.background = if (!glassMode) {
                roundedPill(Color.rgb(213, 229, 247), Color.TRANSPARENT)
            } else null
            glassButton.setTypeface(null, if (glassMode) Typeface.BOLD else Typeface.NORMAL)
            solidButton.setTypeface(null, if (!glassMode) Typeface.BOLD else Typeface.NORMAL)
        }

        glassButton.setOnClickListener {
            glassMode = true
            refreshModes()
            persistAndRefresh()
        }
        solidButton.setOnClickListener {
            glassMode = false
            refreshModes()
            persistAndRefresh()
        }
        modeOuter.addView(glassButton)
        modeOuter.addView(solidButton)
        root.addView(modeOuter)
        refreshModes()

        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.32f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            navigationBarColor = Color.rgb(22, 31, 45)
        }
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    private fun roundedPanel(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.rgb(229, 240, 253))
            cornerRadii = floatArrayOf(
                dp(34).toFloat(), dp(34).toFloat(),
                dp(34).toFloat(), dp(34).toFloat(),
                dp(22).toFloat(), dp(22).toFloat(),
                dp(22).toFloat(), dp(22).toFloat()
            )
        }

    private fun circleBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun roundedPill(fill: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }
}
