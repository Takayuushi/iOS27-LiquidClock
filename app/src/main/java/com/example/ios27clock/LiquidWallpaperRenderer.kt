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
import android.graphics.Path
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
import kotlin.math.roundToInt

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
        private const val VARIABLE_FONT_ASSET_PATH = "fonts/SF-Pro.ttf"

        private const val DEFAULT_CLOCK_X = 0.50f
        private const val DEFAULT_CLOCK_Y = 0.36f
        private const val MAX_GLYPH_HEIGHT_FRACTION = 0.39f
        private const val MAX_GLYPH_WIDTH_FRACTION = 0.94f

        // The whole clock is not stretched as one texture. Each numeral gets
        // its own optical width, weight and height, then is composed as a path.
        private const val GLOBAL_GLYPH_WIDTH_SCALE = 0.70f
        private const val GLOBAL_GLYPH_HEIGHT_SCALE = 1.34f
        private const val TRACKING_EM = -0.030f
        private const val BASE_WEIGHT_MIN = 340
        private const val BASE_WEIGHT_MAX = 560
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

            // Derive a stable surface normal from the actual glyph silhouette.
            float2 sampleStep = float2(2.0, 2.0);
            float leftMask = uGlyphMask.eval(
                clamp(p - float2(sampleStep.x, 0.0), float2(0.0), uResolution)
            ).a;
            float rightMask = uGlyphMask.eval(
                clamp(p + float2(sampleStep.x, 0.0), float2(0.0), uResolution)
            ).a;
            float topMask = uGlyphMask.eval(
                clamp(p - float2(0.0, sampleStep.y), float2(0.0), uResolution)
            ).a;
            float bottomMask = uGlyphMask.eval(
                clamp(p + float2(0.0, sampleStep.y), float2(0.0), uResolution)
            ).a;

            float2 gradient = float2(rightMask - leftMask, bottomMask - topMask);
            float gradientLength = length(gradient);
            float2 normal = gradient / max(gradientLength, 0.0005);
            float edge = clamp(gradientLength * 3.2, 0.0, 1.0);
            float rim = smoothstep(0.015, 0.52, edge);
            float innerBevel = smoothstep(0.01, 0.18, edge) *
                (1.0 - smoothstep(0.30, 0.82, edge));

            // The refraction is strongest at the curved glass boundary.
            // A slow, restrained wave keeps the material alive without
            // making the numerals look like a wobbly filter.
            float waveA = sin(p.y * 0.0042 + uTime * 0.18);
            float waveB = cos(p.x * 0.0030 - uTime * 0.15);
            float displacement = 1.8 + rim * 7.5 + (1.0 - edge) * 1.6;
            float2 warp = normal * displacement +
                float2(waveA * 1.05, waveB * 0.85);
            float2 samplePoint = clamp(p + warp, float2(0.0), uResolution);

            float3 center = uWallpaper.eval(samplePoint).rgb;
            float2 dispersion = normal * (1.6 + rim * 2.8);
            float red = uWallpaper.eval(
                clamp(samplePoint + dispersion, float2(0.0), uResolution)
            ).r;
            float blue = uWallpaper.eval(
                clamp(samplePoint - dispersion, float2(0.0), uResolution)
            ).b;
            float3 refracted = float3(red, center.g, blue);

            // Slight internal diffusion gives the face volume while retaining
            // recognizable wallpaper detail through the transparent body.
            float3 blurA = uWallpaper.eval(
                clamp(samplePoint + float2(3.2, 0.0), float2(0.0), uResolution)
            ).rgb;
            float3 blurB = uWallpaper.eval(
                clamp(samplePoint - float2(3.2, 0.0), float2(0.0), uResolution)
            ).rgb;
            float3 blurC = uWallpaper.eval(
                clamp(samplePoint + float2(0.0, 3.2), float2(0.0), uResolution)
            ).rgb;
            float3 blurD = uWallpaper.eval(
                clamp(samplePoint - float2(0.0, 3.2), float2(0.0), uResolution)
            ).rgb;
            float3 diffuseTransmission = (blurA + blurB + blurC + blurD) * 0.25;
            float3 transmitted = mix(refracted, diffuseTransmission, 0.16);

            float luminance = dot(transmitted, float3(0.2126, 0.7152, 0.0722));
            float maxChannel = max(max(transmitted.r, transmitted.g), transmitted.b);
            float minChannel = min(min(transmitted.r, transmitted.g), transmitted.b);
            float saturation = (maxChannel - minChannel) / max(maxChannel, 0.08);
            float brightSurface = smoothstep(0.42, 0.78, luminance);
            float darkSurface = 1.0 - smoothstep(0.12, 0.38, luminance);
            float monochrome = 1.0 - smoothstep(0.07, 0.20, saturation);

            // Keep the local wallpaper visible inside the glyphs. The pearl
            // tint is a material colour, not an opaque replacement fill.
            float3 pearlTint = mix(
                float3(0.88, 0.90, 0.98),
                uAccentColor,
                0.12
            );
            float3 smokedTint = mix(
                float3(0.10, 0.12, 0.17),
                uAccentColor,
                0.13
            );
            float3 colourGlass = mix(
                mix(transmitted, pearlTint, 0.30),
                mix(transmitted, smokedTint, 0.48),
                brightSurface
            );
            float3 monochromeGlass = mix(
                mix(transmitted, float3(0.88, 0.90, 0.96), 0.34),
                mix(transmitted, float3(0.12, 0.14, 0.18), 0.52),
                brightSurface
            );
            float3 body = mix(colourGlass, monochromeGlass, monochrome * 0.92);

            // Fresnel-like edge response and a broad studio reflection.
            float2 keyLight = normalize(float2(-0.58, -0.82));
            float2 rimLight = normalize(float2(0.66, 0.75));
            float facing = max(dot(normal, keyLight), 0.0);
            float oppositeFacing = max(dot(normal, rimLight), 0.0);
            float fresnel = pow(
                1.0 - clamp(abs(dot(normal, keyLight)), 0.0, 1.0),
                3.0
            ) * rim;
            float keySpecular = pow(facing, 8.0) * rim;
            float rimSpecular = pow(oppositeFacing, 5.0) * rim;
            float edgeShade = pow(oppositeFacing, 2.0) * rim;

            float relativeY = p.y / max(uResolution.y, 1.0) - uClockCenter.y;
            float broadReflection =
                exp(-pow((relativeY + 0.028) * 11.0, 2.0)) * 0.075;
            float sweep = p.x / max(uResolution.x, 1.0) -
                0.30 - 0.035 * sin(uTime * 0.20);
            float movingSpecular = exp(-pow(sweep * 9.0, 2.0)) * 0.045;

            float3 colour = body;
            colour += uAccentColor * fresnel * 0.12;
            colour += float3(0.82, 0.90, 1.0) * innerBevel * 0.12;
            colour += float3(1.0, 1.0, 1.0) * keySpecular * 0.42;
            colour += float3(0.62, 0.78, 1.0) * rimSpecular * 0.20;
            colour -= float3(0.08, 0.10, 0.14) * edgeShade * 0.18;
            colour += float3(1.0, 1.0, 1.0) * (broadReflection + movingSpecular);

            float bodyOpacity = mix(0.60, 0.76, brightSurface);
            bodyOpacity = mix(bodyOpacity, bodyOpacity + 0.04, monochrome);
            float alpha = coverage * (bodyOpacity + rim * 0.06);
            return half4(clamp(colour, 0.0, 1.0), alpha);
        }
    """.trimIndent()

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
    private val fallbackTypeface: Typeface = loadFallbackTypeface()
    private val typefaceCache = LinkedHashMap<Int, Typeface>()

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

        val glyphs = timeText.map { it.toString() }
        val scaleProgress = (
            (clockScale - MIN_CLOCK_SCALE) /
                (MAX_CLOCK_SCALE - MIN_CLOCK_SCALE)
            ).coerceIn(0f, 1f)
        val baseWeight = (
            BASE_WEIGHT_MIN +
                (BASE_WEIGHT_MAX - BASE_WEIGHT_MIN) * scaleProgress
            ).roundToInt()
        val maxWidth = width * MAX_GLYPH_WIDTH_FRACTION * clockScale
        val maxHeight = height * MAX_GLYPH_HEIGHT_FRACTION * clockScale

        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        var low = 1f
        var high = min(height * 0.62f, width * 0.88f)
        var fontSize = high

        // Fit the final, individually shaped glyph run rather than the raw
        // text metrics. Each numeral has its own optical width and height.
        val sizingPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            textScaleX = 1f
            typeface = typefaceFor(baseWeight, 80)
            isSubpixelText = true
        }

        repeat(28) {
            val candidate = (low + high) / 2f
            sizingPaint.textSize = candidate
            val tracking = candidate * TRACKING_EM * GLOBAL_GLYPH_WIDTH_SCALE
            var candidateWidth = 0f
            var visibleHeight = 0f

            glyphs.forEach { glyph ->
                val widthFactor = glyphWidthFactor(glyph)
                val heightFactor = glyphHeightFactor(glyph)
                candidateWidth +=
                    sizingPaint.measureText(glyph) *
                    GLOBAL_GLYPH_WIDTH_SCALE *
                    widthFactor
                val bounds = android.graphics.Rect()
                sizingPaint.getTextBounds(glyph, 0, glyph.length, bounds)
                visibleHeight = max(
                    visibleHeight,
                    bounds.height() * GLOBAL_GLYPH_HEIGHT_SCALE * heightFactor
                )
            }
            candidateWidth += tracking * (glyphs.size - 1).coerceAtLeast(0)

            if (candidateWidth <= maxWidth && visibleHeight <= maxHeight) {
                fontSize = candidate
                low = candidate
            } else {
                high = candidate
            }
        }

        sizingPaint.textSize = fontSize
        val metrics = sizingPaint.fontMetrics
        val centerY = clockCenterY * height
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        val tracking = fontSize * TRACKING_EM * GLOBAL_GLYPH_WIDTH_SCALE

        val advances = glyphs.map { glyph ->
            sizingPaint.measureText(glyph) *
                GLOBAL_GLYPH_WIDTH_SCALE *
                glyphWidthFactor(glyph)
        }
        val totalWidth = advances.sum() +
            tracking * (glyphs.size - 1).coerceAtLeast(0)
        var cursorX = clockCenterX * width - totalWidth / 2f

        val composedPath = Path()
        val composedBounds = RectF()
        var hasBounds = false

        glyphs.forEachIndexed { index, glyph ->
            val weightForGlyph = (
                baseWeight +
                    glyphWeightOffset(glyph) +
                    wallpaperWeightAdjustment(
                        cursorX + advances[index] * 0.5f,
                        centerY,
                        fontSize * 0.10f
                    )
                ).coerceIn(100, 900)

            val glyphPaint = Paint(sizingPaint).apply {
                typeface = typefaceFor(
                    weightForGlyph,
                    (fontSize / density).roundToInt().coerceIn(6, 144)
                )
                textSize = fontSize
                style = Paint.Style.FILL
            }

            val glyphPath = Path()
            glyphPaint.getTextPath(
                glyph,
                0,
                glyph.length,
                cursorX,
                baseline,
                glyphPath
            )

            val originalBounds = RectF()
            glyphPath.computeBounds(originalBounds, true)
            if (!originalBounds.isEmpty) {
                val widthScale =
                    GLOBAL_GLYPH_WIDTH_SCALE * glyphWidthFactor(glyph)
                val heightScale =
                    GLOBAL_GLYPH_HEIGHT_SCALE * glyphHeightFactor(glyph)
                val transform = Matrix().apply {
                    setScale(
                        widthScale,
                        heightScale,
                        originalBounds.centerX(),
                        originalBounds.centerY()
                    )
                }
                val opticalPath = Path()
                glyphPath.transform(transform, opticalPath)
                composedPath.addPath(opticalPath)

                val opticalBounds = RectF()
                opticalPath.computeBounds(opticalBounds, true)
                if (!hasBounds) {
                    composedBounds.set(opticalBounds)
                    hasBounds = true
                } else {
                    composedBounds.union(opticalBounds)
                }
            }

            cursorX += advances[index] + tracking
        }

        if (hasBounds) {
            val inset = max(2f, fontSize * 0.012f)
            glyphBounds.set(
                (composedBounds.left - inset).coerceAtLeast(0f),
                (composedBounds.top - inset).coerceAtLeast(0f),
                (composedBounds.right + inset).coerceAtMost(width.toFloat()),
                (composedBounds.bottom + inset).coerceAtMost(height.toFloat())
            )
            maskCanvas.drawPath(
                composedPath,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
            )
            // A small final contour expansion gives the variable font enough
            // body at large display sizes without making the whole typeface bold.
            maskCanvas.drawPath(
                composedPath,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = fontSize * 0.008f
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = Paint.Cap.ROUND
                }
            )
        } else {
            glyphBounds.setEmpty()
        }

        glyphMask?.recycle()
        glyphMask = mask
        cachedTimeText = timeText
        updateGlyphMaskInput()
    }

    private fun glyphWidthFactor(glyph: String): Float = when (glyph) {
        "1" -> 0.82f
        "0", "8" -> 1.00f
        "6", "9" -> 0.98f
        "2", "3", "5" -> 0.94f
        "4" -> 0.91f
        "7" -> 0.88f
        ":" -> 0.48f
        else -> 0.94f
    }

    private fun glyphHeightFactor(glyph: String): Float = when (glyph) {
        ":" -> 0.82f
        "1" -> 1.025f
        "4", "7" -> 1.015f
        else -> 1f
    }

    private fun glyphWeightOffset(glyph: String): Int = when (glyph) {
        "1" -> 48
        "0", "6", "9" -> 12
        "8" -> -8
        ":" -> -65
        else -> 0
    }

    private fun wallpaperWeightAdjustment(
        x: Float,
        y: Float,
        radius: Float
    ): Int {
        val image = backdrop ?: return 0
        if (image.isRecycled || image.width <= 0 || image.height <= 0) return 0

        val centerX = x.roundToInt().coerceIn(0, image.width - 1)
        val centerY = y.roundToInt().coerceIn(0, image.height - 1)
        val offset = radius.roundToInt().coerceAtLeast(1)
        var luminance = 0f
        var samples = 0

        for (dy in -1..1) {
            for (dx in -1..1) {
                val sx = (centerX + dx * offset).coerceIn(0, image.width - 1)
                val sy = (centerY + dy * offset).coerceIn(0, image.height - 1)
                val pixel = image.getPixel(sx, sy)
                luminance += (
                    Color.red(pixel) * 0.2126f +
                        Color.green(pixel) * 0.7152f +
                        Color.blue(pixel) * 0.0722f
                    ) / 255f
                samples++
            }
        }

        val average = luminance / samples.coerceAtLeast(1)
        return when {
            average > 0.68f -> 64
            average > 0.52f -> 34
            average < 0.16f -> 18
            else -> 0
        }
    }

    private fun typefaceFor(weight: Int, opticalSize: Int): Typeface {
        val safeWeight = weight.coerceIn(100, 900)
        val safeOpticalSize = opticalSize.coerceIn(6, 144)
        val key = safeWeight * 1000 + safeOpticalSize
        typefaceCache[key]?.let { return it }

        val variableFace = try {
            Typeface.Builder(context.assets, VARIABLE_FONT_ASSET_PATH)
                .setFontVariationSettings(
                    "'wght' $safeWeight, 'opsz' $safeOpticalSize"
                )
                .build()
        } catch (axisException: Exception) {
            try {
                Typeface.Builder(context.assets, VARIABLE_FONT_ASSET_PATH)
                    .setFontVariationSettings("'wght' $safeWeight")
                    .build()
            } catch (fontException: Exception) {
                Log.w(
                    TAG,
                    "Variable SF Pro unavailable; falling back to the installed light face.",
                    fontException
                )
                fallbackTypeface
            }
        }

        if (typefaceCache.size >= 48) {
            typefaceCache.clear()
        }
        typefaceCache[key] = variableFace
        return variableFace
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

    private fun loadFallbackTypeface(): Typeface {
        return try {
            Typeface.createFromAsset(context.assets, FONT_ASSET_PATH)
        } catch (exception: Exception) {
            Log.w(TAG, "SF Pro fallback font unavailable; using system light font.", exception)
            Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
    }
}
