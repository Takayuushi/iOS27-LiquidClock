package com.example.ios27clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
 * 3. Feathered foreground subject, when ML Kit produced a valid mask
 */
internal class LiquidWallpaperRenderer(
    private val context: Context
) {

    companion object {
        private const val TAG = "LiquidClockRenderer"
        private const val SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
        private const val FOREGROUND_FILE_NAME = "liquid_clock_wallpaper_foreground.png"
        private const val FONT_ASSET_PATH = "fonts/sf_pro_display_light.otf"

        private const val DEFAULT_CLOCK_X = 0.50f
        private const val DEFAULT_CLOCK_Y = 0.225f
        private const val MAX_GLYPH_HEIGHT_FRACTION = 0.32f
        private const val MAX_GLYPH_WIDTH_FRACTION = 0.94f
        private const val TRACKING_EM = -0.035f
        private const val MIN_CLOCK_SCALE = 0.72f
        private const val MAX_CLOCK_SCALE = 1.48f

        private val AGSL = """
            uniform shader uWallpaper;
            uniform shader uGlyphMask;
            uniform float2 uResolution;
            uniform float uTime;

            half4 main(float2 p) {
                float coverage = uGlyphMask.eval(p).a;

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
                float edge = clamp(gradientLength * 3.2, 0.0, 1.0);
                float rim = smoothstep(0.025, 0.62, edge);

                float waveA = sin(p.y * 0.0054 + uTime * 0.30);
                float waveB = cos(p.x * 0.0037 - uTime * 0.24);
                float2 warp = normal * (2.8 + edge * 10.5) +
                    float2(waveA * 1.9, waveB * 1.25);

                float2 samplePoint = clamp(
                    p + warp,
                    float2(0.0),
                    uResolution
                );

                float3 center = uWallpaper.eval(samplePoint).rgb;
                float red = uWallpaper.eval(
                    clamp(samplePoint + normal * 2.2, float2(0.0), uResolution)
                ).r;
                float blue = uWallpaper.eval(
                    clamp(samplePoint - normal * 2.2, float2(0.0), uResolution)
                ).b;

                float3 refracted = float3(red, center.g, blue);

                // Keep the underlying wallpaper recognisable through the glass.
                float3 body = mix(
                    refracted,
                    float3(0.88, 0.93, 1.0),
                    0.37
                );

                float2 lightDirection = normalize(float2(-0.62, -0.78));
                float2 lowerDirection = normalize(float2(0.52, 0.84));

                float topGlint = pow(
                    max(dot(normal, lightDirection), 0.0),
                    2.0
                ) * rim;

                float lowerGlint = pow(
                    max(dot(normal, lowerDirection), 0.0),
                    1.8
                ) * rim;

                float innerRim = smoothstep(0.02, 0.24, edge) *
                    (1.0 - smoothstep(0.48, 0.94, edge));

                float sweepPosition = p.x / max(uResolution.x, 1.0) -
                    0.30 - 0.04 * sin(uTime * 0.28);
                float movingHighlight = exp(
                    -pow(sweepPosition * 10.0, 2.0)
                ) * 0.075;

                float3 color = body;
                color += float3(0.22, 0.40, 0.72) * rim * 0.22;
                color += float3(0.78, 0.89, 1.0) * innerRim * 0.11;
                color += float3(1.0) * topGlint * 0.48;
                color += float3(0.43, 0.65, 0.98) * lowerGlint * 0.27;
                color += float3(1.0) * movingHighlight;

                // The final alpha is multiplied by the glyph mask again on
                // the Canvas layer, so no rectangular shader backing remains.
                float alpha = 0.96;
                return half4(clamp(color, 0.0, 1.0), alpha);
            }
        """.trimIndent()
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var backdrop: Bitmap? = null
    private var wallpaperSample: Bitmap? = null
    private var foregroundLayer: Bitmap? = null
    private var glyphMask: Bitmap? = null
    private var glassShader: RuntimeShader? = null
    private var cachedTimeText: String? = null
    private var clockCenterX = DEFAULT_CLOCK_X
    private var clockCenterY = DEFAULT_CLOCK_Y
    private var clockScale = 1f

    private val glyphBounds = RectF()
    private val shaderLayerBounds = RectF()
    private val glyphBoundsSource = Rect()
    private val typeface: Typeface = loadTypeface()

    private val bitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val shaderPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        blendMode = BlendMode.DST_IN
    }

    private val shadowPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        colorFilter = PorterDuffColorFilter(
            Color.argb(58, 3, 9, 22),
            PorterDuff.Mode.SRC_IN
        )
    }

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

        val background = backdrop ?: return
        canvas.drawBitmap(background, 0f, 0f, bitmapPaint)

        val mask = glyphMask
        val shader = glassShader
        if (canvas.isHardwareAccelerated && shader != null && mask != null &&
            !glyphBounds.isEmpty
        ) {
            try {
                shader.setFloatUniform(
                    "uResolution",
                    width.toFloat(),
                    height.toFloat()
                )
                shader.setFloatUniform("uTime", timeSeconds)
                shaderPaint.shader = shader

                shaderLayerBounds.set(glyphBounds)
                val shadowOffset = max(2f, height * 0.0015f)
                val shadowSave = canvas.save()
                canvas.translate(0f, shadowOffset)
                canvas.drawBitmap(mask, 0f, 0f, shadowPaint)
                canvas.restoreToCount(shadowSave)

                // Render the optical material to a bounded off-screen layer,
                // then retain only pixels covered by the actual anti-aliased
                // glyph alpha. This prevents the preview's rectangular patch.
                val layerSave = canvas.saveLayer(shaderLayerBounds, null)
                canvas.drawRect(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    shaderPaint
                )
                canvas.drawBitmap(mask, 0f, 0f, maskPaint)
                canvas.restoreToCount(layerSave)

                shaderPaint.shader = null
                drawForegroundLayer(canvas)
                return
            } catch (exception: RuntimeException) {
                Log.w(TAG, "AGSL rendering failed; using the masked text fallback.", exception)
                shaderPaint.shader = null
            }
        }

        if (mask != null) {
            val shadowSave = canvas.save()
            canvas.translate(0f, max(2f, height * 0.0015f))
            canvas.drawBitmap(mask, 0f, 0f, shadowPaint)
            canvas.restoreToCount(shadowSave)
            canvas.drawBitmap(mask, 0f, 0f, bitmapPaint)
        }

        drawForegroundLayer(canvas)
    }

    private fun drawForegroundLayer(canvas: Canvas) {
        foregroundLayer?.let { layer ->
            canvas.drawBitmap(layer, 0f, 0f, bitmapPaint)
        }
    }

    fun release() {
        glassShader = null
        glyphMask?.recycle()
        glyphMask = null
        backdrop?.recycle()
        if (wallpaperSample !== backdrop) wallpaperSample?.recycle()
        foregroundLayer?.recycle()
        backdrop = null
        wallpaperSample = null
        foregroundLayer = null
        cachedTimeText = null
    }

    private fun rebuildBackdrop() {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        val wallpaperFile = context.filesDir.resolve(SOURCE_FILE_NAME)
        val foregroundFile = context.filesDir.resolve(FOREGROUND_FILE_NAME)

        val decoded = if (wallpaperFile.exists() && wallpaperFile.length() > 0L) {
            decodeWallpaper(wallpaperFile.absolutePath, width, height)
        } else null

        val decodedForeground = if (foregroundFile.exists() && foregroundFile.length() > 0L) {
            decodeWallpaper(foregroundFile.absolutePath, width, height)
        } else null

        val sourceOutput = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        if (decoded != null) {
            drawCoverBitmap(Canvas(sourceOutput), decoded, width, height)
            decoded.recycle()
        } else {
            drawDefaultBackdrop(Canvas(sourceOutput), width, height)
        }

        val foregroundOutput = decodedForeground?.let { foregroundBitmap ->
            Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            ).also { output ->
                drawCoverBitmap(Canvas(output), foregroundBitmap, width, height)
                foregroundBitmap.recycle()
            }
        }

        // Keep the original image intact. The subject is drawn a second time
        // above the clock; subtracting it from the backdrop created unwanted
        // transparent regions and could expose a rectangular preview patch.
        val oldBackdrop = backdrop
        val oldForeground = foregroundLayer
        if (oldBackdrop != null && oldBackdrop !== wallpaperSample) oldBackdrop.recycle()
        oldForeground?.recycle()

        backdrop = sourceOutput
        wallpaperSample = sourceOutput
        foregroundLayer = foregroundOutput

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
            textScaleX = 0.92f
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

        glyphBounds.set(
            (startX - fontSize * 0.04f).coerceAtLeast(0f),
            (minTop - fontSize * 0.045f).coerceAtLeast(0).toFloat(),
            (startX + totalWidth + fontSize * 0.04f).coerceAtMost(width.toFloat()),
            (maxBottom + fontSize * 0.045f).coerceAtMost(height).toFloat()
        )

        cursorX = startX
        glyphs.forEach { glyph ->
            maskCanvas.drawText(glyph, cursorX, baseline, paint)
            cursorX += paint.measureText(glyph) + tracking
        }

        glyphMask?.recycle()
        glyphMask = mask
        cachedTimeText = timeText
        rebuildGlassShader()
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
