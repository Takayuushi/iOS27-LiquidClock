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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
        private const val MAX_GLYPH_HEIGHT_FRACTION = 0.48f
        private const val MAX_GLYPH_WIDTH_FRACTION = 0.94f

        // One coherent SF Pro variable face supplies every numeral. Per-digit
        // profiles change real font axes instead of mixing hand-drawn glyphs.
        // The expanded lock-screen treatment is taller, not merely wider:
        // compress each glyph's horizontal advance so the fitted font can grow.
        private const val MIN_GLYPH_WIDTH_SCALE = 1.00f
        private const val MAX_GLYPH_WIDTH_SCALE = 0.92f
        private const val MIN_GLYPH_HEIGHT_SCALE = 1.00f
        private const val MAX_GLYPH_HEIGHT_SCALE = 1.12f
        private const val TRACKING_EM = -0.018f
        private const val BASE_WEIGHT_MIN = 460
        private const val BASE_WEIGHT_MAX = 680
        private const val COLON_ADVANCE_EM = 0.30f
        private const val COLON_DOT_RADIUS_EM = 0.042f
        private const val COLON_DOT_OFFSET_EM = 0.16f
        private const val MIN_CLOCK_SCALE = 0.72f
        private const val MAX_CLOCK_SCALE = 2.40f
        private const val SDF_RANGE_PX = 24f
        private const val SDF_PADDING_PX = 28

        private val AGSL = """
        uniform shader uWallpaper;
        uniform shader uGlyphMask;
        uniform shader uGlyphSdf;
        uniform float2 uResolution;
        uniform float2 uClockCenter;
        uniform float2 uSdfOrigin;
        uniform float2 uTilt;
        uniform float uSdfRange;
        uniform float uTime;
        uniform float3 uAccentColor;

        half4 main(float2 p) {
            float coverage = uGlyphMask.eval(p).a;
            if (coverage < 0.002) {
                return half4(0.0);
            }

            // A compact signed-distance field supplies stable normals and a
            // distance-controlled bevel around outside contours and counters.
            float2 sdfPoint = p - uSdfOrigin;
            float sdf = uGlyphSdf.eval(sdfPoint).a;
            float signedDistance = (sdf - 0.5) * 2.0 * uSdfRange;
            float2 sampleStep = float2(1.5, 1.5);
            float sdfLeft = uGlyphSdf.eval(sdfPoint - float2(sampleStep.x, 0.0)).a;
            float sdfRight = uGlyphSdf.eval(sdfPoint + float2(sampleStep.x, 0.0)).a;
            float sdfTop = uGlyphSdf.eval(sdfPoint - float2(0.0, sampleStep.y)).a;
            float sdfBottom = uGlyphSdf.eval(sdfPoint + float2(0.0, sampleStep.y)).a;
            float2 gradient = float2(sdfRight - sdfLeft, sdfBottom - sdfTop);
            // Positive SDF values point inward, so invert the gradient to
            // obtain the outward-facing contour normal used by glass refraction.
            float2 normal = -gradient / max(length(gradient), 0.0005);

            float edgeBand = exp(-abs(signedDistance) / 3.4);
            float innerBevel = 1.0 - smoothstep(1.0, 12.0, max(signedDistance, 0.0));
            float outerLip = 1.0 - smoothstep(0.5, 5.5, max(-signedDistance, 0.0));
            float face = smoothstep(-1.5, 5.0, signedDistance);
            float interiorVolume = smoothstep(1.0, 22.0, signedDistance);

            float2 tilt = clamp(uTilt, float2(-1.0), float2(1.0));
            float waveA = sin(p.y * 0.0038 + uTime * 0.13);
            float waveB = cos(p.x * 0.0028 - uTime * 0.11);
            float displacement = 1.8 + edgeBand * 8.6 + innerBevel * 2.4;
            float2 warp = normal * displacement +
                tilt * 1.25 +
                float2(waveA * 0.45, waveB * 0.35);
            float2 samplePoint = clamp(p + warp, float2(0.0), uResolution);

            float3 center = uWallpaper.eval(samplePoint).rgb;
            float2 dispersion = normal * (0.7 + edgeBand * 3.0);
            float red = uWallpaper.eval(
                clamp(samplePoint + dispersion, float2(0.0), uResolution)
            ).r;
            float blue = uWallpaper.eval(
                clamp(samplePoint - dispersion, float2(0.0), uResolution)
            ).b;
            float3 refracted = float3(red, center.g, blue);

            float3 blurA = uWallpaper.eval(
                clamp(samplePoint + float2(2.6, 0.0), float2(0.0), uResolution)
            ).rgb;
            float3 blurB = uWallpaper.eval(
                clamp(samplePoint - float2(2.6, 0.0), float2(0.0), uResolution)
            ).rgb;
            float3 blurC = uWallpaper.eval(
                clamp(samplePoint + float2(0.0, 2.6), float2(0.0), uResolution)
            ).rgb;
            float3 blurD = uWallpaper.eval(
                clamp(samplePoint - float2(0.0, 2.6), float2(0.0), uResolution)
            ).rgb;
            float3 diffuseTransmission = (blurA + blurB + blurC + blurD) * 0.25;
            float3 transmitted = mix(refracted, diffuseTransmission, 0.10);

            float luminance = dot(transmitted, float3(0.2126, 0.7152, 0.0722));
            float maxChannel = max(max(transmitted.r, transmitted.g), transmitted.b);
            float minChannel = min(min(transmitted.r, transmitted.g), transmitted.b);
            float saturation = (maxChannel - minChannel) / max(maxChannel, 0.08);
            float brightSurface = smoothstep(0.40, 0.80, luminance);
            float monochrome = 1.0 - smoothstep(0.07, 0.20, saturation);

            float3 pearlTint = mix(
                float3(0.91, 0.92, 0.99),
                uAccentColor,
                0.10
            );
            float3 smokeTint = mix(
                float3(0.10, 0.12, 0.18),
                uAccentColor,
                0.10
            );
            float3 lightFace = mix(transmitted, pearlTint, 0.16);
            float3 darkFace = mix(transmitted, smokeTint, 0.22);
            float3 body = mix(lightFace, darkFace, brightSurface * 0.72);
            body = mix(
                body,
                mix(transmitted, float3(0.90, 0.92, 0.98), 0.26),
                monochrome * 0.28
            );
            body *= mix(0.86, 1.025, interiorVolume) * mix(0.97, 1.02, face);

            // Highlights react to device orientation instead of sliding across
            // the clock at a fixed, unrelated angle.
            float2 keyLight = normalize(float2(-0.58, -0.78) + tilt * 0.72);
            float2 fillLight = normalize(float2(0.70, 0.64) - tilt * 0.28);
            float facing = max(dot(normal, keyLight), 0.0);
            float oppositeFacing = max(dot(normal, fillLight), 0.0);
            float fresnel = pow(
                1.0 - clamp(abs(dot(normal, keyLight)), 0.0, 1.0),
                2.4
            ) * edgeBand;
            float keySpecular = pow(facing, 12.0) * edgeBand;
            float fillSpecular = pow(oppositeFacing, 8.0) * edgeBand;
            float bevelShade = innerBevel * (1.0 - facing) * 0.21;

            float clockRelativeY = p.y / max(uResolution.y, 1.0) - uClockCenter.y;
            float reflectionTrack = clockRelativeY + tilt.y * 0.035;
            float broadReflection =
                exp(-pow((reflectionTrack + 0.025) * 13.0, 2.0)) * 0.055;
            float sweepPosition = p.x / max(uResolution.x, 1.0) -
                0.30 - tilt.x * 0.10 - 0.018 * sin(uTime * 0.16);
            float movingSpecular = exp(-pow(sweepPosition * 10.0, 2.0)) * 0.035;

            float3 colour = body;
            colour += uAccentColor * fresnel * 0.19;
            colour += float3(0.90, 0.94, 1.0) * innerBevel * 0.14;
            colour += float3(1.0, 1.0, 1.0) * keySpecular * 0.62;
            colour += float3(0.68, 0.82, 1.0) * fillSpecular * 0.20;
            colour -= float3(0.09, 0.11, 0.16) * bevelShade;
            colour += float3(1.0, 1.0, 1.0) * (broadReflection + movingSpecular);
            colour += float3(0.95, 0.78, 0.92) * outerLip * 0.045;

            float bodyOpacity = mix(0.46, 0.59, brightSurface);
            bodyOpacity = mix(bodyOpacity, bodyOpacity + 0.035, monochrome);
            float alpha = coverage * (bodyOpacity + edgeBand * 0.22 + outerLip * 0.06);
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
    private var glyphSdf: Bitmap? = null
    private var glyphSdfOriginX = 0f
    private var glyphSdfOriginY = 0f
    private var deviceTiltX = 0f
    private var deviceTiltY = 0f
    private var adaptiveAccentColor: Int = Color.rgb(222, 130, 177)
    private var glassShader: RuntimeShader? = null
    private var cachedTimeText: String? = null
    private var clockCenterX = DEFAULT_CLOCK_X
    private var clockCenterY = DEFAULT_CLOCK_Y
    private var clockScale = 1f
    private var maskClockScale = 1f
    private var pinchStartClockScale = 1f
    private var gestureOffsetX = 0f
    private var gestureOffsetY = 0f
    private var previewScaleX = 1f
    private var previewScaleY = 1f

    private val glyphBaseBounds = RectF()
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

        val previousX = clockCenterX
        val previousY = clockCenterY
        clockCenterX = (clockCenterX + deltaX / surfaceWidth)
            .coerceIn(0.10f, 0.90f)
        clockCenterY = (clockCenterY + deltaY / surfaceHeight)
            .coerceIn(0.10f, 0.78f)

        // Keep the cached glyph/SDF textures. Moving a canvas transform is
        // effectively free compared with rebuilding two full distance fields.
        gestureOffsetX += (clockCenterX - previousX) * surfaceWidth
        gestureOffsetY += (clockCenterY - previousY) * surfaceHeight
        updatePreviewBounds()
    }

    fun beginScaleGesture() {
        pinchStartClockScale = clockScale
    }

    fun scaleClock(distanceRatio: Float) {
        if (!distanceRatio.isFinite() || distanceRatio <= 0f) return
        // Use the original scale at pointer-down, not accumulated MOVE deltas.
        // That prevents event-rate jitter and lets every new pinch start cleanly.
        clockScale = (pinchStartClockScale * distanceRatio)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)

        val relativeScale = (clockScale / maskClockScale).coerceIn(0.40f, 2.8f)
        // Adaptive expansion is vertical only. Keep the cached glyph width
        // fixed during the gesture; the new height is committed on release.
        previewScaleX = 1f
        previewScaleY = relativeScale.pow(1.18f)
        updatePreviewBounds()
    }

    fun finishGesture() {
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        rebuildGlyphMask(force = true)
    }

    private fun updatePreviewBounds() {
        if (glyphBaseBounds.isEmpty) {
            glyphBounds.setEmpty()
            return
        }
        glyphBounds.set(glyphBaseBounds)
        val transform = Matrix().apply {
            setScale(
                previewScaleX,
                previewScaleY,
                glyphBaseBounds.centerX(),
                glyphBaseBounds.centerY()
            )
        }
        transform.mapRect(glyphBounds)
        glyphBounds.offset(gestureOffsetX, gestureOffsetY)
    }

    fun setDeviceTilt(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        deviceTiltX += (x.coerceIn(-1f, 1f) - deviceTiltX) * 0.22f
        deviceTiltY += (y.coerceIn(-1f, 1f) - deviceTiltY) * 0.22f
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
            val transformSave = canvas.save()
            canvas.translate(gestureOffsetX, gestureOffsetY)
            canvas.scale(
                previewScaleX,
                previewScaleY,
                glyphBaseBounds.centerX(),
                glyphBaseBounds.centerY()
            )

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
                    shader.setFloatUniform("uTilt", deviceTiltX, deviceTiltY)
                    shader.setFloatUniform(
                        "uClockCenter",
                        glyphBaseBounds.centerX() / width.toFloat(),
                        glyphBaseBounds.centerY() / height.toFloat()
                    )
                    shaderPaint.shader = shader
                    canvas.drawRect(glyphBaseBounds, shaderPaint)
                    shaderPaint.shader = null
                    shaderDrawn = true
                } catch (exception: RuntimeException) {
                    shaderPaint.shader = null
                    Log.w(TAG, "AGSL rendering failed; showing a visible clock fallback.", exception)
                }
            }

            // During a pinch/drag, transform the cached mask and shader rather
            // than regenerating their pixel data on the UI thread.
            if (!shaderDrawn) {
                canvas.drawBitmap(mask, 0f, 0f, fallbackGlyphPaint)
            }
            canvas.restoreToCount(transformSave)

            // Keep the selected foreground object fixed in wallpaper space;
            // only the clock layer is transformed beneath its depth mask.
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
        glyphSdf?.recycle()
        glyphSdf = null
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

        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(mask)
        maskCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val glyphs = timeText.map { it.toString() }
        val expansion = (
            (clockScale - MIN_CLOCK_SCALE) /
                (MAX_CLOCK_SCALE - MIN_CLOCK_SCALE)
            ).coerceIn(0f, 1f)
        val baseWeight = (
            BASE_WEIGHT_MIN +
                (BASE_WEIGHT_MAX - BASE_WEIGHT_MIN) * expansion
            ).roundToInt()
        val globalWidthScale =
            MIN_GLYPH_WIDTH_SCALE +
                (MAX_GLYPH_WIDTH_SCALE - MIN_GLYPH_WIDTH_SCALE) * expansion
        val globalHeightScale =
            MIN_GLYPH_HEIGHT_SCALE +
                (MAX_GLYPH_HEIGHT_SCALE - MIN_GLYPH_HEIGHT_SCALE) * expansion

        val maxWidth = width * MAX_GLYPH_WIDTH_FRACTION
        val maxHeight = height * (
            MAX_GLYPH_HEIGHT_FRACTION * clockScale
            ).coerceAtMost(0.56f)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)

        var low = 1f
        var high = min(height * 0.68f, width * 0.96f)
        var fontSize = high
        val sizingPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            typeface = typefaceFor(baseWeight, 80, glyphWidthProfile("0", expansion))
            isSubpixelText = true
        }

        repeat(28) {
            val candidate = (low + high) / 2f
            sizingPaint.textSize = candidate
            val tracking = candidate * TRACKING_EM * globalWidthScale
            var candidateWidth = 0f
            var visibleHeight = 0f

            glyphs.forEach { glyph ->
                if (glyph == ":") {
                    candidateWidth += candidate * COLON_ADVANCE_EM
                } else {
                    sizingPaint.typeface = typefaceFor(
                        (baseWeight + glyphWeightOffset(glyph)).coerceIn(100, 900),
                        (candidate / density).roundToInt().coerceIn(17, 96),
                        glyphWidthProfile(glyph, expansion)
                    )
                    candidateWidth +=
                        sizingPaint.measureText(glyph) *
                        globalWidthScale *
                        glyphWidthProfile(glyph, expansion)

                    val bounds = android.graphics.Rect()
                    sizingPaint.getTextBounds(glyph, 0, glyph.length, bounds)
                    visibleHeight = max(
                        visibleHeight,
                        bounds.height() * globalHeightScale * glyphHeightFactor(glyph)
                    )
                }
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
        sizingPaint.typeface = typefaceFor(
            baseWeight,
            (fontSize / density).roundToInt().coerceIn(17, 96),
            glyphWidthProfile("0", expansion)
        )
        val metrics = sizingPaint.fontMetrics
        val centerX = clockCenterX * width
        val centerY = clockCenterY * height
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        val tracking = fontSize * TRACKING_EM * globalWidthScale

        val advances = glyphs.map { glyph ->
            if (glyph == ":") {
                fontSize * COLON_ADVANCE_EM
            } else {
                sizingPaint.typeface = typefaceFor(
                    (baseWeight + glyphWeightOffset(glyph)).coerceIn(100, 900),
                    (fontSize / density).roundToInt().coerceIn(17, 96),
                    glyphWidthProfile(glyph, expansion)
                )
                sizingPaint.textSize = fontSize
                sizingPaint.measureText(glyph) * globalWidthScale * glyphWidthProfile(glyph, expansion)
            }
        }
        val totalWidth = advances.sum() + tracking * (glyphs.size - 1).coerceAtLeast(0)
        var cursorX = centerX - totalWidth / 2f

        val composedPath = Path().apply { fillType = Path.FillType.EVEN_ODD }
        val composedBounds = RectF()
        var hasBounds = false

        glyphs.forEachIndexed { index, glyph ->
            if (glyph == ":") {
                val dotRadius = fontSize * COLON_DOT_RADIUS_EM
                val dotOffset = fontSize * COLON_DOT_OFFSET_EM
                val dotCenterX = cursorX + advances[index] * 0.5f
                val colonPath = Path().apply {
                    addCircle(dotCenterX, centerY - dotOffset, dotRadius, Path.Direction.CW)
                    addCircle(dotCenterX, centerY + dotOffset, dotRadius, Path.Direction.CW)
                }
                composedPath.addPath(colonPath)
                val colonBounds = RectF(
                    dotCenterX - dotRadius,
                    centerY - dotOffset - dotRadius,
                    dotCenterX + dotRadius,
                    centerY + dotOffset + dotRadius
                )
                if (!hasBounds) {
                    composedBounds.set(colonBounds)
                    hasBounds = true
                } else {
                    composedBounds.union(colonBounds)
                }
            } else {
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
                        (fontSize / density).roundToInt().coerceIn(17, 96),
                        glyphWidthProfile(glyph, expansion)
                    )
                    textSize = fontSize
                    style = Paint.Style.FILL
                    strokeWidth = 0f
                }

                // Every numeral comes from the same SF Pro variable family.
                val glyphPath = Path()
                glyphPaint.getTextPath(glyph, 0, glyph.length, cursorX, baseline, glyphPath)
                val originalBounds = RectF()
                glyphPath.computeBounds(originalBounds, true)

                if (!originalBounds.isEmpty) {
                    // The iOS-style 8 uses taller, softly squared counters and
                    // a gently pinched waist instead of the stock font's oval loops.
                    val sourcePath = if (glyph == "8") {
                        buildAdaptiveEightPath(originalBounds)
                    } else {
                        glyphPath
                    }
                    val glyphTransform = Matrix().apply {
                        setScale(
                            globalWidthScale * glyphWidthProfile(glyph, expansion),
                            globalHeightScale * glyphHeightFactor(glyph),
                            originalBounds.centerX(),
                            originalBounds.centerY()
                        )
                    }
                    val opticalPath = Path()
                    sourcePath.transform(glyphTransform, opticalPath)
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
            }
            cursorX += advances[index] + tracking
        }

        if (hasBounds) {
            val fitX = ((maxWidth - 8f) / composedBounds.width().coerceAtLeast(1f))
                .coerceIn(0.01f, 1f)
            val fitY = ((maxHeight - 8f) / composedBounds.height().coerceAtLeast(1f))
                .coerceIn(0.01f, 1f)
            val fittedPath = Path()
            if (fitX < 0.999f || fitY < 0.999f) {
                val fitMatrix = Matrix().apply { setScale(fitX, fitY, centerX, centerY) }
                composedPath.transform(fitMatrix, fittedPath)
            } else {
                fittedPath.set(composedPath)
            }
            fittedPath.computeBounds(composedBounds, true)

            val inset = max(2f, fontSize * 0.010f)
            glyphBounds.set(
                (composedBounds.left - inset).coerceAtLeast(0f),
                (composedBounds.top - inset).coerceAtLeast(0f),
                (composedBounds.right + inset).coerceAtMost(width.toFloat()),
                (composedBounds.bottom + inset).coerceAtMost(height.toFloat())
            )
            maskCanvas.drawPath(
                fittedPath,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
            )
        } else {
            glyphBounds.setEmpty()
            glyphBaseBounds.setEmpty()
        }

        glyphMask?.recycle()
        glyphMask = mask
        glyphSdf?.recycle()
        glyphSdfOriginX = 0f
        glyphSdfOriginY = 0f
        glyphSdf = if (hasBounds) createGlyphSdf(mask, glyphBounds) else null
        glyphBaseBounds.set(glyphBounds)
        maskClockScale = clockScale
        gestureOffsetX = 0f
        gestureOffsetY = 0f
        previewScaleX = 1f
        previewScaleY = 1f
        cachedTimeText = timeText
        updateGlyphMaskInput()
    }

    /**
     * Builds a compact signed-distance field around the current glyph bounds.
     * A two-pass 3/4 chamfer transform gives stable normals for the shallow
     * glass bevel without doing distance work in the per-frame shader.
     */
    private fun createGlyphSdf(source: Bitmap, bounds: RectF): Bitmap? {
        if (source.isRecycled || bounds.isEmpty) return null
        glyphSdfOriginX = 0f
        glyphSdfOriginY = 0f

        val padding = max(
            SDF_PADDING_PX,
            (context.resources.displayMetrics.density * 30f).roundToInt()
        )
        val left = (bounds.left.roundToInt() - padding).coerceIn(0, source.width - 1)
        val top = (bounds.top.roundToInt() - padding).coerceIn(0, source.height - 1)
        val right = (bounds.right.roundToInt() + padding).coerceIn(left + 1, source.width)
        val bottom = (bounds.bottom.roundToInt() + padding).coerceIn(top + 1, source.height)
        val sdfWidth = right - left
        val sdfHeight = bottom - top
        val pixelCountLong = sdfWidth.toLong() * sdfHeight.toLong()
        if (pixelCountLong <= 0L || pixelCountLong > 2_500_000L) return null

        val pixelCount = pixelCountLong.toInt()
        val sourcePixels = IntArray(pixelCount)
        source.getPixels(sourcePixels, 0, sdfWidth, left, top, sdfWidth, sdfHeight)

        val maxUnits = (SDF_RANGE_PX * 3f).roundToInt()
        val infinity = (maxUnits + 1).toShort()
        val distanceToOutside = ShortArray(pixelCount)
        val distanceToInside = ShortArray(pixelCount)
        for (index in 0 until pixelCount) {
            val inside = Color.alpha(sourcePixels[index]) >= 128
            distanceToOutside[index] = if (inside) infinity else 0
            distanceToInside[index] = if (inside) 0 else infinity
        }

        chamferDistance(distanceToOutside, sdfWidth, sdfHeight, maxUnits)
        chamferDistance(distanceToInside, sdfWidth, sdfHeight, maxUnits)

        val sdfPixels = IntArray(pixelCount)
        for (index in 0 until pixelCount) {
            val inside = Color.alpha(sourcePixels[index]) >= 128
            val signedUnits = if (inside) {
                distanceToOutside[index].toInt()
            } else {
                -distanceToInside[index].toInt()
            }
            val signedPixels = signedUnits / 3f
            val encoded = (
                0.5f + signedPixels / (2f * SDF_RANGE_PX)
                ).coerceIn(0f, 1f)
            sdfPixels[index] = Color.argb(
                (encoded * 255f).roundToInt(),
                255,
                255,
                255
            )
        }

        return Bitmap.createBitmap(
            sdfWidth,
            sdfHeight,
            Bitmap.Config.ARGB_8888
        ).also { sdf ->
            sdf.setPixels(sdfPixels, 0, sdfWidth, 0, 0, sdfWidth, sdfHeight)
            glyphSdfOriginX = left.toFloat()
            glyphSdfOriginY = top.toFloat()
        }
    }

    private fun chamferDistance(
        distances: ShortArray,
        width: Int,
        height: Int,
        maxUnits: Int
    ) {
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                var best = distances[index].toInt()
                if (x > 0) best = min(best, distances[index - 1].toInt() + 3)
                if (y > 0) {
                    best = min(best, distances[index - width].toInt() + 3)
                    if (x > 0) best = min(best, distances[index - width - 1].toInt() + 4)
                    if (x + 1 < width) best = min(best, distances[index - width + 1].toInt() + 4)
                }
                distances[index] = min(best, maxUnits).toShort()
            }
        }
        for (y in height - 1 downTo 0) {
            val row = y * width
            for (x in width - 1 downTo 0) {
                val index = row + x
                var best = distances[index].toInt()
                if (x + 1 < width) best = min(best, distances[index + 1].toInt() + 3)
                if (y + 1 < height) {
                    best = min(best, distances[index + width].toInt() + 3)
                    if (x > 0) best = min(best, distances[index + width - 1].toInt() + 4)
                    if (x + 1 < width) best = min(best, distances[index + width + 1].toInt() + 4)
                }
                distances[index] = min(best, maxUnits).toShort()
            }
        }
    }

    private fun buildAdaptiveEightPath(bounds: RectF): Path {
        val left = bounds.left
        val right = bounds.right
        val top = bounds.top
        val bottom = bounds.bottom
        val width = bounds.width().coerceAtLeast(1f)
        val height = bounds.height().coerceAtLeast(1f)
        val centerX = bounds.centerX()
        val waistY = bounds.centerY()

        return Path().apply {
            fillType = Path.FillType.EVEN_ODD

            // A continuous outer silhouette: broad upper/lower bowls with a
            // soft inward waist, keeping the characteristic SF-like rhythm.
            moveTo(centerX, top)
            cubicTo(left + width * 0.22f, top, left, top + height * 0.10f,
                left, top + height * 0.27f)
            cubicTo(left, top + height * 0.40f, left + width * 0.13f,
                top + height * 0.46f, centerX, waistY)
            cubicTo(left + width * 0.13f, top + height * 0.54f, left,
                top + height * 0.61f, left, top + height * 0.75f)
            cubicTo(left, bottom - height * 0.10f, left + width * 0.22f,
                bottom, centerX, bottom)
            cubicTo(right - width * 0.22f, bottom, right,
                bottom - height * 0.10f, right, top + height * 0.75f)
            cubicTo(right, top + height * 0.61f, right - width * 0.13f,
                top + height * 0.54f, centerX, waistY)
            cubicTo(right - width * 0.13f, top + height * 0.46f, right,
                top + height * 0.40f, right, top + height * 0.27f)
            cubicTo(right, top + height * 0.10f, right - width * 0.22f,
                top, centerX, top)
            close()

            // Tall rounded-square counters are the defining detail of the
            // expanded lock-screen 8; EVEN_ODD cuts them from the silhouette.
            val counterLeft = left + width * 0.245f
            val counterRight = right - width * 0.245f
            val counterRadiusX = width * 0.095f
            val counterRadiusY = height * 0.045f
            addRoundRect(
                RectF(
                    counterLeft,
                    top + height * 0.125f,
                    counterRight,
                    top + height * 0.425f
                ),
                counterRadiusX,
                counterRadiusY,
                Path.Direction.CW
            )
            addRoundRect(
                RectF(
                    counterLeft - width * 0.008f,
                    top + height * 0.575f,
                    counterRight + width * 0.008f,
                    bottom - height * 0.115f
                ),
                counterRadiusX,
                counterRadiusY,
                Path.Direction.CW
            )
        }
    }

    /**
     * The bundled SF Pro asset has no variable wdth axis. Apply width adaptation
     * to each extracted vector glyph instead, with a separate curve per numeral.
     */
    private fun glyphWidthProfile(glyph: String, expansion: Float): Float {
        val progress = expansion.coerceIn(0f, 1f)
        val compact = when (glyph) {
            "1" -> 0.96f
            "2" -> 0.99f
            "3" -> 0.98f
            "4" -> 0.95f
            "5" -> 0.98f
            "6" -> 0.99f
            "7" -> 0.95f
            "8" -> 1.00f
            "9" -> 0.99f
            "0" -> 1.00f
            else -> 1.00f
        }
        val expanded = when (glyph) {
            "1" -> 0.86f
            "2" -> 0.91f
            "3" -> 0.90f
            "4" -> 0.88f
            "5" -> 0.90f
            "6" -> 0.92f
            "7" -> 0.86f
            "8" -> 0.93f
            "9" -> 0.92f
            "0" -> 0.93f
            else -> 0.93f
        }
        return compact + (expanded - compact) * progress
    }

    private fun glyphHeightFactor(glyph: String): Float = when (glyph) {
        "1" -> 1.015f
        "4", "7" -> 0.985f
        "3", "5" -> 1.01f
        else -> 1.0f
    }

    private fun glyphWeightOffset(glyph: String): Int = when (glyph) {
        "1", "7" -> 12
        "2", "4" -> 4
        "3", "5" -> 12
        "0", "6", "8", "9" -> 18
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
            average > 0.70f -> 28
            average > 0.54f -> 14
            average < 0.14f -> 8
            else -> 0
        }
    }

    private fun typefaceFor(
        weight: Int,
        opticalSize: Int,
        widthProfile: Float
    ): Typeface {
        val safeWeight = weight.coerceIn(100, 900)
        typefaceCache[safeWeight]?.let { return it }

        val variableFace = try {
            // The verified SF Pro asset exposes wght only. Width variation is
            // performed on extracted glyph paths; opsz is fixed in this asset.
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

        if (typefaceCache.size >= 64) {
            typefaceCache.clear()
        }
        typefaceCache[safeWeight] = variableFace
        return variableFace
    }

    private fun updateGlyphMaskInput() {
        val shader = glassShader ?: return
        val mask = glyphMask ?: return
        try {
            shader.setInputShader(
                "uGlyphMask",
                BitmapShader(mask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            val sdf = glyphSdf ?: mask
            shader.setInputShader(
                "uGlyphSdf",
                BitmapShader(sdf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("uSdfOrigin", glyphSdfOriginX, glyphSdfOriginY)
            shader.setFloatUniform("uSdfRange", SDF_RANGE_PX)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Could not refresh glyph mask/SDF inputs.", exception)
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
            val sdf = glyphSdf ?: mask
            shader.setInputShader(
                "uGlyphSdf",
                BitmapShader(sdf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
            shader.setFloatUniform("uSdfOrigin", glyphSdfOriginX, glyphSdfOriginY)
            shader.setFloatUniform("uSdfRange", SDF_RANGE_PX)
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
