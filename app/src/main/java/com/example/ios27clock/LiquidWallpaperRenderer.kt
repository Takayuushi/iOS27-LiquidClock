package com.example.ios27clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen wallpaper clock compositor.
 *
 * Composition order:
 * 1. Original selected wallpaper
 * 2. Liquid-glass clock, clipped by the real glyph alpha mask
 * 3. The selected-object mask erases only clock pixels beneath the object
 */
internal class LiquidWallpaperRenderer(
    private val context: Context
) {

    companion object {
        private const val TAG = "LiquidClockRenderer"
        private const val SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
        private const val FOREGROUND_FILE_NAME = "liquid_clock_wallpaper_foreground.png"
        private const val MANUAL_FOREGROUND_FILE_NAME = "liquid_clock_wallpaper_manual_foreground.png"
        private const val FONT_ASSET_PATH = "fonts/sf_pro_display_light.otf"

        private const val DEFAULT_CLOCK_X = 0.50f
        private const val DEFAULT_CLOCK_Y = 0.225f
        private const val MAX_GLYPH_HEIGHT_FRACTION = 0.39f
        private const val MAX_GLYPH_WIDTH_FRACTION = 0.94f
        private const val HORIZONTAL_GLYPH_STRETCH = 1.055f
        private const val VERTICAL_GLYPH_STRETCH = 1.16f
        private const val TRACKING_EM = -0.040f
        private const val MIN_CLOCK_SCALE = 0.72f
        private const val MAX_CLOCK_SCALE = 1.48f

        private val AGSL = """
            uniform shader uWallpaper;
            uniform shader uGlyphMask;
            uniform float2 uResolution;
            uniform float2 uClockCenter;
            uniform float uTime;
            uniform float3 uAccentColor;

            half4 main(float2 p) {
                float coverage = uGlyphMask.eval(p).a;
                if (coverage < 0.002) {
                    return half4(0.0);
                }

                float2 px = float2(1.0, 1.0);
                float leftMask = uGlyphMask.eval(
                    clamp(p - float2(1.8, 0.0), float2(0.0), uResolution)
                ).a;
                float rightMask = uGlyphMask.eval(
                    clamp(p + float2(1.8, 0.0), float2(0.0), uResolution)
                ).a;
                float topMask = uGlyphMask.eval(
                    clamp(p - float2(0.0, 1.8), float2(0.0), uResolution)
                ).a;
                float bottomMask = uGlyphMask.eval(
                    clamp(p + float2(0.0, 1.8), float2(0.0), uResolution)
                ).a;

                float2 gradient = float2(
                    rightMask - leftMask,
                    bottomMask - topMask
                );
                float gradientLength = length(gradient);
                float2 normal = gradient / max(gradientLength, 0.0005);
                float edge = clamp(gradientLength * 3.8, 0.0, 1.0);
                float rim = smoothstep(0.02, 0.58, edge);
                float innerBevel = smoothstep(0.015, 0.20, edge) *
                    (1.0 - smoothstep(0.28, 0.78, edge));

                float waveA = sin(p.y * 0.0050 + uTime * 0.22);
                float waveB = cos(p.x * 0.0033 - uTime * 0.18);
                float2 warp = normal * (4.2 + edge * 10.0) +
                    float2(waveA * 2.4, waveB * 1.8);

                float2 samplePoint = clamp(
                    p + warp,
                    float2(0.0),
                    uResolution
                );

                float3 center = uWallpaper.eval(samplePoint).rgb;
                float3 redShift = uWallpaper.eval(
                    clamp(
                        samplePoint + normal * 3.2 + float2(0.9, 0.0),
                        float2(0.0),
                        uResolution
                    )
                ).rgb;
                float3 blueShift = uWallpaper.eval(
                    clamp(
                        samplePoint - normal * 3.2 - float2(0.9, 0.0),
                        float2(0.0),
                        uResolution
                    )
                ).rgb;

                float3 refracted = float3(
                    redShift.r,
                    center.g,
                    blueShift.b
                );

                // A small optical blur gives the face thickness instead of
                // letting the glyph read as a transparent neon outline.
                float3 blurA = uWallpaper.eval(
                    clamp(samplePoint + float2(4.5, 0.0), float2(0.0), uResolution)
                ).rgb;
                float3 blurB = uWallpaper.eval(
                    clamp(samplePoint - float2(4.5, 0.0), float2(0.0), uResolution)
                ).rgb;
                float3 blurC = uWallpaper.eval(
                    clamp(samplePoint + float2(0.0, 4.5), float2(0.0), uResolution)
                ).rgb;
                float3 blurD = uWallpaper.eval(
                    clamp(samplePoint - float2(0.0, 4.5), float2(0.0), uResolution)
                ).rgb;
                float3 softTransmission = (blurA + blurB + blurC + blurD) * 0.25;
                float3 transmitted = mix(refracted, softTransmission, 0.24);

                float luminance = dot(
                    transmitted,
                    float3(0.2126, 0.7152, 0.0722)
                );
                float maxChannel = max(max(transmitted.r, transmitted.g), transmitted.b);
                float minChannel = min(min(transmitted.r, transmitted.g), transmitted.b);
                float saturation = (maxChannel - minChannel) / max(maxChannel, 0.08);

                float brightSurface = smoothstep(0.43, 0.78, luminance);
                float darkSurface = 1.0 - smoothstep(0.14, 0.40, luminance);
                float monochrome = 1.0 - smoothstep(0.08, 0.22, saturation);

                // Coloured wallpapers retain their hue, but switch material
                // value with the local background so the face stays legible.
                float3 accentPearl = mix(
                    float3(0.86, 0.84, 0.91),
                    uAccentColor,
                    0.22
                );
                float3 lightColourGlass = mix(transmitted, accentPearl, 0.46);
                lightColourGlass = mix(
                    lightColourGlass,
                    float3(0.93, 0.95, 1.0),
                    darkSurface * 0.30
                );

                float3 accentSmoke = mix(
                    float3(0.20, 0.17, 0.23),
                    uAccentColor,
                    0.24
                );
                float3 darkColourGlass = mix(
                    accentSmoke,
                    transmitted,
                    0.16
                );
                float3 colourGlass = mix(
                    lightColourGlass,
                    darkColourGlass,
                    brightSurface
                );

                // Monochrome images get a deliberate graphite/silver glass
                // rather than a nearly invisible transparent outline. On a
                // bright grey sky the body is smoked; over dark branches it
                // turns into a cool pearl so the numerals remain readable.
                float3 monoPearl = float3(0.76, 0.79, 0.85);
                float3 monoGraphite = mix(
                    float3(0.15, 0.16, 0.19),
                    uAccentColor,
                    0.10
                );
                float3 monoGlass = mix(
                    monoPearl,
                    monoGraphite,
                    brightSurface
                );

                float3 glassBody = mix(
                    colourGlass,
                    monoGlass,
                    monochrome * 0.96
                );

                float2 upperLight = normalize(float2(-0.62, -0.78));
                float2 lowerLight = normalize(float2(0.52, 0.84));
                float topGlint = pow(
                    max(dot(normal, upperLight), 0.0),
                    2.0
                ) * rim;
                float lowerGlint = pow(
                    max(dot(normal, lowerLight), 0.0),
                    1.8
                ) * rim;

                // A broad, slow reflection moves across the face, while
                // opposed rim lights create a visible bevel and glass depth.
                float relativeY = p.y / max(uResolution.y, 1.0) - uClockCenter.y;
                float broadReflection = exp(-pow((relativeY + 0.035) * 10.0, 2.0)) *
                    (0.045 + 0.035 * (0.5 + 0.5 * sin(p.x * 0.003 + uTime * 0.20)));
                float sweepPosition = p.x / max(uResolution.x, 1.0) -
                    0.30 - 0.045 * sin(uTime * 0.24);
                float movingHighlight = exp(-pow(sweepPosition * 10.0, 2.0)) * 0.09;
                float darkRim = pow(
                    max(dot(normal, lowerLight), 0.0),
                    2.0
                ) * rim;

                float3 colour = glassBody;
                colour += uAccentColor * rim * 0.10;
                colour += float3(0.78, 0.88, 1.0) * innerBevel * 0.18;
                colour -= float3(0.10, 0.12, 0.17) * darkRim * 0.22;
                colour += float3(1.0, 1.0, 1.0) * topGlint * 0.50;
                colour += float3(0.48, 0.68, 1.0) * lowerGlint * 0.24;
                colour += float3(1.0, 1.0, 1.0) * broadReflection;
                colour += float3(1.0, 1.0, 1.0) * movingHighlight;

                float bodyOpacity = mix(0.82, 0.90, monochrome);
                float alpha = coverage * (bodyOpacity + rim * 0.08);
                return half4(clamp(colour, 0.0, 1.0), alpha);
            }
        """.trimIndent()
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var backdrop: Bitmap? = null
    private var wallpaperSample: Bitmap? = null
    private var foregroundOcclusionMask: Bitmap? = null
    private var glyphMask: Bitmap? = null
    private var adaptiveAccentColor: Int = Color.rgb(222, 130, 177)
    private var glassShader: RuntimeShader? = null
    private var cachedTimeText: String? = null
    private var clockCenterX = DEFAULT_CLOCK_X
    private var clockCenterY = DEFAULT_CLOCK_Y
    private var clockScale = 1f

    private val glyphBounds = RectF()
    private val glyphBoundsSource = Rect()
    private val typeface: Typeface = loadTypeface()

    private val bitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val shaderPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val shadowPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        colorFilter = PorterDuffColorFilter(
            Color.argb(58, 3, 9, 22),
            PorterDuff.Mode.SRC_IN
        )
    }

    private val clockOcclusionPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    private val fallbackGlyphPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        color = Color.WHITE
        alpha = 218
    }

    private val clockLayerBounds = RectF()

    private val clockPreferences = context.getSharedPreferences(
        "liquid_clock_composition",
        Context.MODE_PRIVATE
    )

    init {
        clockCenterX = clockPreferences.getFloat("center_x", DEFAULT_CLOCK_X)
            .coerceIn(0.10f, 0.90f)
        clockCenterY = clockPreferences.getFloat("center_y", DEFAULT_CLOCK_Y)
            .coerceIn(0.10f, 0.78f)
        clockScale = clockPreferences.getFloat("clock_scale", 1f)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight && backdrop != null) return

        surfaceWidth = width
        surfaceHeight = height
        rebuildBackdrop()
    }

    fun reloadWallpaper() {
        rebuildBackdrop()
    }

    fun isClockHit(x: Float, y: Float): Boolean {
        if (glyphBounds.isEmpty) return false
        val hit = RectF(glyphBounds)
        val margin = (context.resources.displayMetrics.density * 44f)
        hit.inset(-margin, -margin)
        return hit.contains(x, y)
    }

    fun moveClockBy(deltaX: Float, deltaY: Float) {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return

        clockCenterX = (clockCenterX + deltaX / surfaceWidth)
            .coerceIn(0.10f, 0.90f)
        clockCenterY = (clockCenterY + deltaY / surfaceHeight)
            .coerceIn(0.10f, 0.78f)
        rebuildGlyphMask(force = true)
    }

    fun scaleClock(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        clockScale = (clockScale * factor)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        rebuildGlyphMask(force = true)
    }

    fun persistComposition() {
        clockPreferences.edit()
            .putFloat("center_x", clockCenterX)
            .putFloat("center_y", clockCenterY)
            .putFloat("clock_scale", clockScale)
            .apply()
    }

    fun draw(canvas: Canvas, timeSeconds: Float) {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        rebuildGlyphMask(force = false)

        // Keep the original selected wallpaper as the only background image.
        // The user selection is an alpha mask applied to the clock layer only.
        val background = backdrop ?: return
        canvas.drawBitmap(background, 0f, 0f, bitmapPaint)

        val mask = glyphMask ?: return
        if (glyphBounds.isEmpty) return

        val shadowOffset = max(2f, height * 0.0015f)
        clockLayerBounds.set(glyphBounds)
        clockLayerBounds.inset(-shadowOffset * 2f, -shadowOffset * 2f)
        clockLayerBounds.intersect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat()
        )

        // Draw the clock in isolation. DST_OUT then removes only the clock
        // pixels beneath the user-selected object. The untouched wallpaper
        // remains visible below, so the selected object never replaces it.
        val layerSave = canvas.saveLayer(clockLayerBounds, null)
        try {
            val shadowSave = canvas.save()
            canvas.translate(0f, shadowOffset)
            canvas.drawBitmap(mask, 0f, 0f, shadowPaint)
            canvas.restoreToCount(shadowSave)

            var shaderDrawn = false
            val shader = glassShader
            if (canvas.isHardwareAccelerated && shader != null) {
                try {
                    shader.setFloatUniform(
                        "uResolution",
                        width.toFloat(),
                        height.toFloat()
                    )
                    shader.setFloatUniform("uTime", timeSeconds)
                    shader.setFloatUniform(
                        "uClockCenter",
                        clockCenterX,
                        clockCenterY
                    )
                    shaderPaint.shader = shader
                    canvas.drawRect(glyphBounds, shaderPaint)
                    shaderPaint.shader = null
                    shaderDrawn = true
                } catch (exception: RuntimeException) {
                    shaderPaint.shader = null
                    Log.w(TAG, "AGSL rendering failed; showing a visible clock fallback.", exception)
                }
            }

            // If hardware shader rendering is unavailable, draw readable
            // numerals instead of silently showing wallpaper without a clock.
            if (!shaderDrawn) {
                canvas.drawBitmap(mask, 0f, 0f, fallbackGlyphPaint)
            }

            foregroundOcclusionMask?.let { occlusionMask ->
                canvas.drawBitmap(occlusionMask, 0f, 0f, clockOcclusionPaint)
            }
        } finally {
            canvas.restoreToCount(layerSave)
            shaderPaint.shader = null
        }
    }

    fun release() {
        glassShader = null
        glyphMask?.recycle()
        glyphMask = null
        backdrop?.recycle()
        if (wallpaperSample !== backdrop) wallpaperSample?.recycle()
        foregroundOcclusionMask?.recycle()
        backdrop = null
        wallpaperSample = null
        foregroundOcclusionMask = null
        cachedTimeText = null
    }

    private fun rebuildBackdrop() {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        val wallpaperFile = context.filesDir.resolve(SOURCE_FILE_NAME)
        val manualMaskFile = context.filesDir.resolve(MANUAL_FOREGROUND_FILE_NAME)

        val decodedWallpaper = if (
            wallpaperFile.exists() && wallpaperFile.length() > 0L
        ) {
            decodeWallpaper(wallpaperFile.absolutePath, width, height)
        } else {
            null
        }

        val decodedMask = manualMaskFile
            .takeIf { it.exists() && it.length() > 0L }
            ?.let { decodeWallpaper(it.absolutePath, width, height) }

        val wallpaperOutput = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        if (decodedWallpaper != null) {
            drawCoverBitmap(Canvas(wallpaperOutput), decodedWallpaper, width, height)
            decodedWallpaper.recycle()
        } else {
            Log.e(TAG, "Selected wallpaper could not be decoded; using the fallback background.")
            drawDefaultBackdrop(Canvas(wallpaperOutput), width, height)
        }

        // This is only a per-pixel alpha mask. Never draw it as an image.
        // Its alpha erases clock pixels where the selected object overlaps.
        val occlusionOutput = decodedMask?.let { sourceMask ->
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            ).also { output ->
                drawCoverBitmap(Canvas(output), sourceMask, width, height)
                sourceMask.recycle()
            }
        }

        backdrop?.recycle()
        if (wallpaperSample !== backdrop) wallpaperSample?.recycle()
        foregroundOcclusionMask?.recycle()

        adaptiveAccentColor = extractWallpaperAccent(wallpaperOutput)
        backdrop = wallpaperOutput
        wallpaperSample = wallpaperOutput
        foregroundOcclusionMask = occlusionOutput

        cachedTimeText = null
        rebuildGlyphMask(force = true)
        rebuildGlassShader()
    }

    private fun drawCoverBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        width: Int,
        height: Int
    ) {
        val scale = max(
            width.toFloat() / bitmap.width,
            height.toFloat() / bitmap.height
        )
        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        canvas.drawBitmap(bitmap, matrix, bitmapPaint)
    }

    private fun decodeWallpaper(
        path: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= targetWidth * 2 ||
                bounds.outHeight / (sampleSize * 2) >= targetHeight * 2
            ) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inDither = true
                }
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Could not decode wallpaper image.", exception)
            null
        }
    }

    private fun drawDefaultBackdrop(
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.rgb(3, 8, 20),
                    Color.rgb(14, 18, 48),
                    Color.rgb(47, 8, 67),
                    Color.rgb(6, 5, 18)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)

        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                width * 0.78f,
                height * 0.43f,
                width * 0.78f,
                intArrayOf(
                    Color.argb(105, 72, 102, 181),
                    Color.argb(0, 72, 102, 181)
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glow)
    }

    private fun rebuildGlyphMask(force: Boolean) {
        val timeText = currentTimeText()
        if (!force && timeText == cachedTimeText && glyphMask != null) return

        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        val mask = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
        val maskCanvas = Canvas(mask)
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        ).apply {
            color = Color.WHITE
            typeface = this@LiquidWallpaperRenderer.typeface
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            // Slightly broaden and embolden the light face so the numerals
            // read as substantial liquid-glass forms rather than hairlines.
            textScaleX = 0.98f
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.FILL_AND_STROKE
            isSubpixelText = true
        }

        val glyphs = timeText.map { it.toString() }
        val maxWidth = width * MAX_GLYPH_WIDTH_FRACTION * clockScale
        val maxHeight = height * MAX_GLYPH_HEIGHT_FRACTION * clockScale

        var low = 1f
        var high = min(height * 0.52f, width * 0.72f)
        var fontSize = high

        repeat(28) {
            val candidate = (low + high) / 2f
            paint.textSize = candidate
            val tracking = candidate * TRACKING_EM
            val measuredWidth = glyphs.sumOf {
                paint.measureText(it).toDouble()
            }.toFloat() + tracking * (glyphs.size - 1).coerceAtLeast(0)

            val bounds = android.graphics.Rect()
            var visibleHeight = 0
            glyphs.forEach { glyph ->
                paint.getTextBounds(glyph, 0, glyph.length, bounds)
                visibleHeight = max(visibleHeight, bounds.height())
            }

            if (measuredWidth <= maxWidth && visibleHeight <= maxHeight) {
                fontSize = candidate
                low = candidate
            } else {
                high = candidate
            }
        }

        paint.textSize = fontSize
        paint.strokeWidth = fontSize * 0.014f
        val tracking = fontSize * TRACKING_EM
        val totalWidth = glyphs.sumOf {
            paint.measureText(it).toDouble()
        }.toFloat() + tracking * (glyphs.size - 1).coerceAtLeast(0)

        val startX = clockCenterX * width - totalWidth / 2f
        val metrics = paint.fontMetrics
        val centerY = clockCenterY * height
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f

        var cursorX = startX
        val textBounds = android.graphics.Rect()
        var minTop = height
        var maxBottom = 0
        glyphs.forEach { glyph ->
            paint.getTextBounds(glyph, 0, glyph.length, textBounds)
            minTop = min(minTop, (baseline + textBounds.top).toInt())
            maxBottom = max(maxBottom, (baseline + textBounds.bottom).toInt())
            cursorX += paint.measureText(glyph) + tracking
        }

        val stretchedTop = centerY +
            (minTop.toFloat() - centerY) * VERTICAL_GLYPH_STRETCH
        val stretchedBottom = centerY +
            (maxBottom.toFloat() - centerY) * VERTICAL_GLYPH_STRETCH

        val stretchCenterX = clockCenterX * width
        val stretchedLeft = stretchCenterX +
            (startX - stretchCenterX) * HORIZONTAL_GLYPH_STRETCH
        val stretchedRight = stretchCenterX +
            (startX + totalWidth - stretchCenterX) * HORIZONTAL_GLYPH_STRETCH

        glyphBounds.set(
            (stretchedLeft - fontSize * 0.045f).coerceAtLeast(0f),
            (stretchedTop - fontSize * 0.045f).coerceAtLeast(0f),
            (stretchedRight + fontSize * 0.045f).coerceAtMost(width.toFloat()),
            (stretchedBottom + fontSize * 0.045f).coerceAtMost(height.toFloat())
        )

        val maskSave = maskCanvas.save()
        // A mild two-axis deformation makes the numerals fuller and closer
        // to rounded-square proportions as the clock expands.
        maskCanvas.scale(
            HORIZONTAL_GLYPH_STRETCH,
            VERTICAL_GLYPH_STRETCH,
            clockCenterX * width,
            centerY
        )

        cursorX = startX
        glyphs.forEach { glyph ->
            maskCanvas.drawText(glyph, cursorX, baseline, paint)
            cursorX += paint.measureText(glyph) + tracking
        }
        maskCanvas.restoreToCount(maskSave)

        glyphMask?.recycle()
        glyphMask = mask
        cachedTimeText = timeText
        updateGlyphMaskInput()
    }

    private fun updateGlyphMaskInput() {
        val shader = glassShader ?: return
        val mask = glyphMask ?: return
        try {
            shader.setInputShader(
                "uGlyphMask",
                BitmapShader(
                    mask,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP
                )
            )
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not refresh the glyph mask input.", exception)
            glassShader = null
        }
    }

    private fun extractWallpaperAccent(bitmap: Bitmap): Int {
        // Pick the strongest recurring saturated hue as the wallpaper's
        // secondary accent. Near-white and near-black samples are ignored.
        val bins = 24
        val weights = DoubleArray(bins)
        val red = DoubleArray(bins)
        val green = DoubleArray(bins)
        val blue = DoubleArray(bins)
        val hsv = FloatArray(3)
        val stepX = max(1, bitmap.width / 72)
        val stepY = max(1, bitmap.height / 144)

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                if (saturation >= 0.28f && value >= 0.22f && value <= 0.98f) {
                    val bin = ((hsv[0] / 360f) * bins)
                        .toInt()
                        .coerceIn(0, bins - 1)
                    val weight = saturation.toDouble() *
                        (0.45 + value.toDouble() * 0.55)
                    weights[bin] += weight
                    red[bin] += Color.red(pixel) * weight
                    green[bin] += Color.green(pixel) * weight
                    blue[bin] += Color.blue(pixel) * weight
                }
                x += stepX
            }
            y += stepY
        }

        var best = 0
        for (index in 1 until bins) {
            if (weights[index] > weights[best]) best = index
        }
        if (weights[best] <= 0.0) return Color.rgb(145, 150, 160)

        return Color.rgb(
            (red[best] / weights[best]).toInt().coerceIn(0, 255),
            (green[best] / weights[best]).toInt().coerceIn(0, 255),
            (blue[best] / weights[best]).toInt().coerceIn(0, 255)
        )
    }

    private fun rebuildGlassShader() {
        val background = wallpaperSample ?: return
        val mask = glyphMask ?: return

        try {
            val shader = RuntimeShader(AGSL)
            shader.setInputShader(
                "uWallpaper",
                BitmapShader(
                    background,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP
                )
            )
            shader.setInputShader(
                "uGlyphMask",
                BitmapShader(
                    mask,
                    Shader.TileMode.CLAMP,
                    Shader.TileMode.CLAMP
                )
            )
            shader.setFloatUniform(
                "uResolution",
                surfaceWidth.toFloat(),
                surfaceHeight.toFloat()
            )
            shader.setFloatUniform(
                "uAccentColor",
                Color.red(adaptiveAccentColor) / 255f,
                Color.green(adaptiveAccentColor) / 255f,
                Color.blue(adaptiveAccentColor) / 255f
            )
            shader.setFloatUniform(
                "uClockCenter",
                clockCenterX,
                clockCenterY
            )
            glassShader = shader
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to initialize AGSL shader.", exception)
            glassShader = null
        }
    }

    private fun currentTimeText(): String {
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
            .format(java.util.Date())
    }

    private fun loadTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, FONT_ASSET_PATH)
        } catch (exception: Exception) {
            Log.w(TAG, "SF Pro font unavailable; using system light font.", exception)
            Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
    }
}
