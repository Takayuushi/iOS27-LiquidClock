package com.example.ios27clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
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
 * Full-screen clock compositor used by the live wallpaper.
 *
 * The selected wallpaper is the actual renderer backdrop. AGSL samples that
 * bitmap from inside the glyph mask, creating image-based optical distortion
 * rather than drawing an opaque TextView over the wallpaper.
 */
internal class LiquidWallpaperRenderer(
    private val context: Context
) {

    companion object {
        private const val TAG = "LiquidClockRenderer"
        private const val SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
        private const val FONT_ASSET_PATH = "fonts/sf_pro_display_light.otf"
        private const val CLOCK_CENTER_Y_FRACTION = 0.235f
        private const val MAX_GLYPH_HEIGHT_FRACTION = 0.235f
        private const val MAX_GLYPH_WIDTH_FRACTION = 0.97f
        private const val TRACKING_EM = -0.045f

        private val AGSL = """
            uniform shader uWallpaper;
            uniform shader uGlyphMask;
            uniform float2 uResolution;
            uniform float uTime;

            half4 main(float2 p) {
                float coverage = uGlyphMask.eval(p).a;
                if (coverage < 0.002) {
                    return half4(0.0);
                }

                float leftMask = uGlyphMask.eval(
                    clamp(p - float2(1.4, 0.0), float2(0.0), uResolution)
                ).a;
                float rightMask = uGlyphMask.eval(
                    clamp(p + float2(1.4, 0.0), float2(0.0), uResolution)
                ).a;
                float topMask = uGlyphMask.eval(
                    clamp(p - float2(0.0, 1.4), float2(0.0), uResolution)
                ).a;
                float bottomMask = uGlyphMask.eval(
                    clamp(p + float2(0.0, 1.4), float2(0.0), uResolution)
                ).a;

                float2 gradient = float2(rightMask - leftMask, bottomMask - topMask);
                float gradientLength = length(gradient);
                float2 normal = gradient / max(gradientLength, 0.0005);
                float edge = clamp(gradientLength * 1.8, 0.0, 1.0);

                float slowWave = sin(p.y * 0.0065 + uTime * 0.55);
                float crossWave = cos(p.x * 0.0042 - uTime * 0.42);
                float2 refractionOffset =
                    normal * (1.2 + edge * 8.5) +
                    float2(slowWave * 1.35, crossWave * 0.9);

                float2 samplePoint = clamp(
                    p + refractionOffset,
                    float2(0.0),
                    uResolution
                );

                float3 wallpaper = uWallpaper.eval(samplePoint).rgb;
                float3 glassTint = mix(
                    wallpaper,
                    float3(0.975, 0.985, 1.0),
                    0.84
                );

                float rim = smoothstep(0.10, 0.72, edge);
                float upperSpecular = max(
                    0.0,
                    dot(normal, normalize(float2(-0.55, -0.84)))
                ) * rim;
                float lowerReflection = max(
                    0.0,
                    dot(normal, normalize(float2(0.50, 0.82)))
                ) * rim;

                float movingSheen = exp(
                    -pow((p.x / max(uResolution.x, 1.0) - 0.34 -
                    0.035 * sin(uTime * 0.42)) * 13.0, 2.0)
                ) * 0.08;

                glassTint += float3(0.42, 0.58, 0.82) * rim * 0.07;
                glassTint += float3(1.0) * upperSpecular * 0.19;
                glassTint += float3(0.58, 0.75, 1.0) * lowerReflection * 0.08;
                glassTint += float3(1.0) * movingSheen;

                float alpha = coverage * (0.97 + rim * 0.03);
                return half4(glassTint, alpha);
            }
        """.trimIndent()
    }

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var backdrop: Bitmap? = null
    private var glyphMask: Bitmap? = null
    private var glassShader: RuntimeShader? = null
    private var cachedTimeText: String? = null
    private var elapsedSeconds = 0f
    private val glyphBounds = RectF()
    private val glyphBoundsSource = Rect()
    private var typeface: Typeface = loadTypeface()

    private val bitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val shaderPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private val fallbackGlyphPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    ).apply {
        color = Color.WHITE
        alpha = 246
    }

    fun setViewport(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight && backdrop != null) {
            return
        }

        surfaceWidth = width
        surfaceHeight = height
        rebuildBackdrop()
    }

    fun reloadWallpaper() {
        rebuildBackdrop()
    }

    fun draw(canvas: Canvas, timeSeconds: Float) {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        elapsedSeconds = timeSeconds
        rebuildGlyphMask(force = false)

        val background = backdrop ?: return
        canvas.drawBitmap(background, 0f, 0f, bitmapPaint)

        val shader = glassShader
        if (canvas.isHardwareAccelerated && shader != null && glyphMask != null) {
            try {
                shader.setFloatUniform(
                    "uResolution",
                    width.toFloat(),
                    height.toFloat()
                )
                shader.setFloatUniform("uTime", elapsedSeconds)
                shaderPaint.shader = shader
                if (!glyphBounds.isEmpty) {
                    val saveCount = canvas.save()
                    canvas.clipRect(glyphBounds)
                    canvas.drawRect(
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        shaderPaint
                    )
                    canvas.restoreToCount(saveCount)
                }
                shaderPaint.shader = null
                return
            } catch (exception: RuntimeException) {
                Log.w(TAG, "AGSL rendering failed; using the crisp glyph fallback.", exception)
                shaderPaint.shader = null
            }
        }

        glyphMask?.let { mask ->
            if (!glyphBounds.isEmpty) {
                glyphBoundsSource.set(
                    glyphBounds.left.toInt().coerceAtLeast(0),
                    glyphBounds.top.toInt().coerceAtLeast(0),
                    glyphBounds.right.toInt().coerceAtMost(width),
                    glyphBounds.bottom.toInt().coerceAtMost(height)
                )
                canvas.drawBitmap(
                    mask,
                    glyphBoundsSource,
                    glyphBoundsSource,
                    fallbackGlyphPaint
                )
            }
        }
    }

    fun release() {
        glassShader = null
        glyphMask?.recycle()
        glyphMask = null
        backdrop?.recycle()
        backdrop = null
        cachedTimeText = null
    }

    private fun rebuildBackdrop() {
        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        val wallpaperFile = context.filesDir.resolve(SOURCE_FILE_NAME)
        val decoded = if (wallpaperFile.exists() && wallpaperFile.length() > 0L) {
            decodeWallpaper(wallpaperFile.absolutePath, width, height)
        } else {
            null
        }

        val output = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(output)
        if (decoded != null) {
            val scale = max(
                width.toFloat() / decoded.width,
                height.toFloat() / decoded.height
            )
            val dx = (width - decoded.width * scale) / 2f
            val dy = (height - decoded.height * scale) / 2f
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            canvas.drawBitmap(decoded, matrix, bitmapPaint)
            decoded.recycle()
        } else {
            drawDefaultBackdrop(canvas, width, height)
        }

        backdrop?.recycle()
        backdrop = output
        cachedTimeText = null
        rebuildGlyphMask(force = true)
        rebuildGlassShader()
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

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

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
            Log.e(TAG, "Could not decode the selected wallpaper.", exception)
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
        if (!force && timeText == cachedTimeText && glyphMask != null) {
            return
        }

        val width = surfaceWidth
        val height = surfaceHeight
        if (width <= 0 || height <= 0) return

        val mask = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(mask)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val paint = Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        ).apply {
            color = Color.WHITE
            typeface = this@LiquidWallpaperRenderer.typeface
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            textScaleX = 0.985f
            isSubpixelText = true
        }

        val glyphs = timeText.map { it.toString() }
        val maxWidth = width * MAX_GLYPH_WIDTH_FRACTION
        val maxHeight = height * MAX_GLYPH_HEIGHT_FRACTION

        var low = 1f
        var high = min(height * 0.38f, width * 0.56f)
        var fontSize = high

        repeat(26) {
            val candidate = (low + high) / 2f
            paint.textSize = candidate
            val tracking = candidate * TRACKING_EM
            val measuredWidth = glyphs.sumOf { paint.measureText(it).toDouble() }.toFloat() +
                tracking * (glyphs.size - 1).coerceAtLeast(0)
            val metrics = paint.fontMetrics
            val measuredHeight = metrics.descent - metrics.ascent

            if (measuredWidth <= maxWidth && measuredHeight <= maxHeight) {
                fontSize = candidate
                low = candidate
            } else {
                high = candidate
            }
        }

        paint.textSize = fontSize
        val tracking = fontSize * TRACKING_EM
        val totalWidth = glyphs.sumOf { paint.measureText(it).toDouble() }.toFloat() +
            tracking * (glyphs.size - 1).coerceAtLeast(0)
        val startX = (width - totalWidth) / 2f
        val metrics = paint.fontMetrics
        val centerY = height * CLOCK_CENTER_Y_FRACTION
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f

        glyphBounds.set(
            (startX - fontSize * 0.035f).coerceAtLeast(0f),
            (baseline + metrics.ascent - fontSize * 0.045f).coerceAtLeast(0f),
            (startX + totalWidth + fontSize * 0.035f).coerceAtMost(width.toFloat()),
            (baseline + metrics.descent + fontSize * 0.045f).coerceAtMost(height.toFloat())
        )

        var cursorX = startX
        glyphs.forEach { glyph ->
            canvas.drawText(glyph, cursorX, baseline, paint)
            cursorX += paint.measureText(glyph) + tracking
        }

        glyphMask?.recycle()
        glyphMask = mask
        cachedTimeText = timeText
        rebuildGlassShader()
    }

    private fun rebuildGlassShader() {
        val background = backdrop ?: return
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
            Log.w(TAG, "Unable to initialize the AGSL glass shader.", exception)
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
            Log.w(TAG, "SF Pro font asset unavailable; using system thin font.", exception)
            Typeface.create("sans-serif-thin", Typeface.NORMAL)
        }
    }
}
