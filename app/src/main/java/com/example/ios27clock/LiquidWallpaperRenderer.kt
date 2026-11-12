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
        private const val DEFAULT_CLOCK_Y = 0.31f
        private const val LEGACY_DEFAULT_CLOCK_Y = 0.36f
        private const val MAX_GLYPH_HEIGHT_FRACTION = 0.38f
        private const val MAX_COMMITTED_HEIGHT_FRACTION = 0.74f
        private const val MAX_GLYPH_WIDTH_FRACTION = 0.94f
        private const val CLOCK_GLYPH_WIDTH_SCALE = 1.00f
        private const val CLOCK_GLYPH_HEIGHT_SCALE = 1.04f

        // Keep the natural horizontal layout while the adaptive clock grows
        // vertically. Individual glyph profiles handle optical corrections.
        private const val MIN_GLYPH_WIDTH_SCALE = 1.00f
        private const val MAX_GLYPH_WIDTH_SCALE = 1.00f
        private const val MIN_GLYPH_HEIGHT_SCALE = 1.00f
        private const val MAX_GLYPH_HEIGHT_SCALE = 1.04f
        private const val TRACKING_EM = 0.035f
        private const val BASE_WEIGHT_MIN = 520
        private const val BASE_WEIGHT_MAX = 820
        private const val COLON_ADVANCE_EM = 0.105f
        private const val COLON_DOT_RADIUS_EM = 0.040f
        private const val GLYPH_STROKE_FRACTION = 0.0f
        private const val COLON_DOT_OFFSET_EM = 0.16f
        private const val MIN_CLOCK_SCALE = 0.72f
        private const val MAX_CLOCK_SCALE = 2.55f
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
        val savedCenterY = clockPreferences.getFloat("center_y", DEFAULT_CLOCK_Y)
        val positionMigrationDone = clockPreferences.getBoolean("reference_position_v1", false)
        // Move only the untouched legacy default. A position the user dragged
        // manually is kept exactly as saved.
        clockCenterY = if (
            !positionMigrationDone &&
            savedCenterY == LEGACY_DEFAULT_CLOCK_Y
        ) {
            DEFAULT_CLOCK_Y
        } else {
            savedCenterY.coerceIn(0.10f, 0.78f)
        }
        clockScale = clockPreferences.getFloat("clock_scale", 1f)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        if (!positionMigrationDone) {
            clockPreferences.edit()
                .putBoolean("reference_position_v1", true)
                .putFloat("center_y", clockCenterY)
                .apply()
        }
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
        val requestedScale = (pinchStartClockScale * distanceRatio)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
        // Derive the pinch ceiling from the actual rendered glyph bounds,
        // not a guessed font-size fraction. This keeps the preview and final
        // committed mask on exactly the same vertical limit.
        val maxPreviewRatio = if (!glyphBaseBounds.isEmpty && surfaceHeight > 0) {
            (surfaceHeight * MAX_COMMITTED_HEIGHT_FRACTION /
                glyphBaseBounds.height().coerceAtLeast(1f)).coerceAtLeast(1f)
        } else {
            MAX_CLOCK_SCALE / maskClockScale.coerceAtLeast(0.01f)
        }
        previewScaleX = 1f
        previewScaleY = (requestedScale / maskClockScale.coerceAtLeast(0.01f))
            .coerceIn(0.25f, maxPreviewRatio)
        clockScale = (maskClockScale * previewScaleY)
            .coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
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
        val maxWidth = width * MAX_GLYPH_WIDTH_FRACTION
        val maxBaseHeight = min(height * MAX_GLYPH_HEIGHT_FRACTION, width * 0.90f)
        val trackingEm = 0.032f
        val colonAdvanceEm = 0.13f
        val colonExtraGapEm = 0.035f
        val totalAdvanceEm = glyphs.sumOf {
            (if (it == ":") colonAdvanceEm else vectorDigitAdvanceEm(it)).toDouble()
        }.toFloat() + trackingEm * (glyphs.size - 1).coerceAtLeast(0) +
            colonExtraGapEm * glyphs.count { it == ":" }

        // One common cap height is fitted to the available line width. The
        // individual advances are part of this vector family, not estimates
        // taken from a system font or independent per-glyph stretch factors.
        val capHeight = min(maxBaseHeight, maxWidth / totalAdvanceEm.coerceAtLeast(0.1f))
        val centerX = clockCenterX * width
        val centerY = clockCenterY * height
        val tracking = capHeight * trackingEm
        val colonExtraGap = capHeight * colonExtraGapEm
        val advances = glyphs.map { glyph ->
            capHeight * if (glyph == ":") colonAdvanceEm else vectorDigitAdvanceEm(glyph)
        }
        val totalWidth = advances.sum() +
            tracking * (glyphs.size - 1).coerceAtLeast(0) +
            colonExtraGap * glyphs.count { it == ":" }
        var cursorX = centerX - totalWidth * 0.5f
        val top = centerY - capHeight * 0.5f
        val glyphStrokeWidth = capHeight * 0.092f

        data class GlyphShape(val path: Path, val filled: Boolean)
        val shapes = mutableListOf<GlyphShape>()
        val baseBounds = RectF()
        var hasBounds = false
        val colonCenters = mutableListOf<Pair<Float, Float>>()

        glyphs.forEachIndexed { index, glyph ->
            if (glyph == ":") {
                val dotOffset = capHeight * COLON_DOT_OFFSET_EM
                val dotX = cursorX + advances[index] * 0.5f
                colonCenters += dotX to (centerY - dotOffset)
                colonCenters += dotX to (centerY + dotOffset)
            } else {
                val digitBounds = RectF(
                    cursorX,
                    top,
                    cursorX + advances[index],
                    top + capHeight
                )
                val isFilled = glyph == "0" || glyph == "8"
                val digitPath = buildVectorDigitPath(glyph, digitBounds)
                shapes += GlyphShape(digitPath, isFilled)
                val pathBounds = RectF()
                digitPath.computeBounds(pathBounds, true)
                if (!isFilled && !pathBounds.isEmpty) {
                    pathBounds.inset(-glyphStrokeWidth * 0.5f, -glyphStrokeWidth * 0.5f)
                }
                if (!pathBounds.isEmpty) {
                    if (!hasBounds) {
                        baseBounds.set(pathBounds)
                        hasBounds = true
                    } else baseBounds.union(pathBounds)
                }
            }
            cursorX += advances[index] + tracking +
                if (glyph == ":") colonExtraGap else 0f
        }

        if (hasBounds) {
            // Measure the rendered outlines, including the common stroke,
            // before committing the vertical expansion. This retains the
            // established hard viewport ceiling and never scales X.
            val totalShapeHeightScale = CLOCK_GLYPH_HEIGHT_SCALE
            val naturalBounds = RectF(baseBounds)
            naturalBounds.top = centerY + (baseBounds.top - centerY) * totalShapeHeightScale
            naturalBounds.bottom = centerY + (baseBounds.bottom - centerY) * totalShapeHeightScale
            val allowedScaleY = (
                height * MAX_COMMITTED_HEIGHT_FRACTION /
                    naturalBounds.height().coerceAtLeast(1f)
                ).coerceAtLeast(1f)
            val committedScaleY = clockScale.coerceAtMost(allowedScaleY)
            if (committedScaleY != clockScale) {
                clockScale = committedScaleY.coerceIn(MIN_CLOCK_SCALE, MAX_CLOCK_SCALE)
            }
            val finalScaleY = totalShapeHeightScale * committedScaleY

            val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = glyphStrokeWidth
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeMiter = 1f
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

            maskCanvas.save()
            maskCanvas.scale(CLOCK_GLYPH_WIDTH_SCALE, finalScaleY, centerX, centerY)
            shapes.forEach { shape ->
                maskCanvas.drawPath(shape.path, if (shape.filled) fillPaint else strokePaint)
            }
            maskCanvas.restore()

            // Keep the colon dots circular while moving them with the
            // vertical expansion. The dots must not turn into tall ovals.
            val dotRadius = capHeight * COLON_DOT_RADIUS_EM * 1.08f
            colonCenters.forEach { (dotX, originalY) ->
                val finalY = centerY + (originalY - centerY) * finalScaleY
                maskCanvas.drawCircle(
                    centerX + (dotX - centerX) * CLOCK_GLYPH_WIDTH_SCALE,
                    finalY,
                    dotRadius,
                    fillPaint
                )
            }

            val finalBounds = RectF(
                centerX + (baseBounds.left - centerX) * CLOCK_GLYPH_WIDTH_SCALE,
                centerY + (baseBounds.top - centerY) * finalScaleY,
                centerX + (baseBounds.right - centerX) * CLOCK_GLYPH_WIDTH_SCALE,
                centerY + (baseBounds.bottom - centerY) * finalScaleY
            )
            colonCenters.forEach { (dotX, originalY) ->
                val finalX = centerX + (dotX - centerX) * CLOCK_GLYPH_WIDTH_SCALE
                val finalY = centerY + (originalY - centerY) * finalScaleY
                finalBounds.union(
                    RectF(finalX - dotRadius, finalY - dotRadius,
                        finalX + dotRadius, finalY + dotRadius)
                )
            }
            val inset = max(2f, capHeight * 0.018f)
            glyphBounds.set(
                (finalBounds.left - inset).coerceAtLeast(0f),
                (finalBounds.top - inset).coerceAtLeast(0f),
                (finalBounds.right + inset).coerceAtMost(width.toFloat()),
                (finalBounds.bottom + inset).coerceAtMost(height.toFloat())
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

    /** Widths are optical advances in a single normalized cap-height grid. */
    private fun vectorDigitAdvanceEm(glyph: String): Float = when (glyph) {
        "0" -> 0.43f
        "1" -> 0.38f
        "2" -> 0.39f
        "3" -> 0.39f
        "4" -> 0.42f
        "5" -> 0.39f
        "6" -> 0.41f
        "7" -> 0.39f
        "8" -> 0.43f
        "9" -> 0.41f
        else -> 0.39f
    }

    /**
     * Custom iOS-inspired display numerals. Each digit is built on the same
     * cap-height grid and uses the same stroke weight, round caps and joins.
     * 0 and 8 are filled silhouettes with real counters; other digits are
     * continuous centerline contours rendered with the shared stroke paint.
     */
    private fun buildVectorDigitPath(glyph: String, bounds: RectF): Path {
        val l = bounds.left
        val t = bounds.top
        val w = bounds.width()
        val h = bounds.height()
        val r = bounds.right
        val b = bounds.bottom
        fun x(fraction: Float) = l + w * fraction
        fun y(fraction: Float) = t + h * fraction
        val path = Path()

        when (glyph) {
            "0" -> {
                // Broad, softly squared outer bowl with a tall rounded
                // rectangular counter. Side, top and bottom walls stay near
                // the same optical thickness.
                path.fillType = Path.FillType.EVEN_ODD
                path.addRoundRect(
                    RectF(x(0.04f), y(0.012f), x(0.96f), y(0.988f)),
                    h * 0.145f, h * 0.145f, Path.Direction.CW
                )
                path.addRoundRect(
                    RectF(x(0.27f), y(0.115f), x(0.73f), y(0.885f)),
                    h * 0.075f, h * 0.075f, Path.Direction.CCW
                )
            }
            "1" -> {
                // A narrower angled flag flows into a straight stem. The
                // short foot is optically centred rather than spanning the
                // entire advance box.
                path.moveTo(x(0.18f), y(0.235f))
                path.cubicTo(x(0.29f), y(0.19f), x(0.43f), y(0.085f), x(0.55f), y(0.065f))
                path.lineTo(x(0.55f), y(0.925f))
                path.moveTo(x(0.30f), y(0.925f))
                path.lineTo(x(0.79f), y(0.925f))
            }
            "2" -> {
                path.moveTo(x(0.12f), y(0.235f))
                path.cubicTo(x(0.14f), y(0.075f), x(0.75f), y(0.025f), x(0.87f), y(0.205f))
                path.cubicTo(x(0.99f), y(0.385f), x(0.78f), y(0.565f), x(0.54f), y(0.735f))
                path.lineTo(x(0.16f), y(0.925f))
                path.lineTo(x(0.87f), y(0.925f))
            }
            "3" -> {
                // Two balanced lobes share a smooth right-hand spine. Avoid
                // reversing the tangent at the waist: that created the
                // swollen, pinched joint in the previous version.
                path.moveTo(x(0.13f), y(0.16f))
                path.cubicTo(x(0.32f), y(0.035f), x(0.73f), y(0.035f), x(0.86f), y(0.19f))
                path.cubicTo(x(0.93f), y(0.27f), x(0.89f), y(0.41f), x(0.89f), y(0.50f))
                // Matching vertical handles make the waist tangent continuous.
                path.cubicTo(x(0.89f), y(0.59f), x(0.95f), y(0.71f), x(0.85f), y(0.82f))
                path.cubicTo(x(0.71f), y(0.98f), x(0.32f), y(0.98f), x(0.12f), y(0.84f))
            }
            "4" -> {
                path.moveTo(x(0.70f), y(0.925f))
                path.lineTo(x(0.70f), y(0.075f))
                path.moveTo(x(0.10f), y(0.60f))
                path.lineTo(x(0.91f), y(0.60f))
                path.moveTo(x(0.12f), y(0.60f))
                path.lineTo(x(0.66f), y(0.075f))
            }
            "5" -> {
                path.moveTo(x(0.87f), y(0.075f))
                path.lineTo(x(0.14f), y(0.075f))
                path.lineTo(x(0.14f), y(0.455f))
                path.cubicTo(x(0.36f), y(0.385f), x(0.77f), y(0.40f), x(0.86f), y(0.595f))
                path.cubicTo(x(0.87f), y(0.81f), x(0.74f), y(0.975f), x(0.49f), y(0.935f))
                path.cubicTo(x(0.31f), y(0.925f), x(0.18f), y(0.875f), x(0.14f), y(0.80f))
            }
            "6" -> {
                path.moveTo(x(0.82f), y(0.095f))
                path.cubicTo(x(0.49f), y(0.09f), x(0.14f), y(0.34f), x(0.14f), y(0.65f))
                path.cubicTo(x(0.14f), y(0.89f), x(0.36f), y(0.965f), x(0.60f), y(0.93f))
                path.cubicTo(x(0.86f), y(0.89f), x(0.87f), y(0.66f), x(0.76f), y(0.545f))
                path.cubicTo(x(0.60f), y(0.43f), x(0.31f), y(0.49f), x(0.14f), y(0.67f))
            }
            "7" -> {
                path.moveTo(x(0.14f), y(0.075f))
                path.lineTo(x(0.86f), y(0.075f))
                path.cubicTo(x(0.70f), y(0.36f), x(0.51f), y(0.65f), x(0.31f), y(0.925f))
            }
            "8" -> {
                path.fillType = Path.FillType.EVEN_ODD
                // A broader upper bowl and slightly fuller lower bowl, with
                // tall rounded-square counters and a controlled waist.
                path.moveTo(x(0.50f), y(0.012f))
                path.cubicTo(x(0.22f), y(0.012f), x(0.055f), y(0.115f), x(0.055f), y(0.265f))
                path.cubicTo(x(0.055f), y(0.405f), x(0.235f), y(0.465f), x(0.50f), y(0.50f))
                path.cubicTo(x(0.235f), y(0.535f), x(0.055f), y(0.605f), x(0.055f), y(0.755f))
                path.cubicTo(x(0.055f), y(0.905f), x(0.22f), y(0.988f), x(0.50f), y(0.988f))
                path.cubicTo(x(0.78f), y(0.988f), x(0.945f), y(0.905f), x(0.945f), y(0.755f))
                path.cubicTo(x(0.945f), y(0.605f), x(0.765f), y(0.535f), x(0.50f), y(0.50f))
                path.cubicTo(x(0.765f), y(0.465f), x(0.945f), y(0.405f), x(0.945f), y(0.265f))
                path.cubicTo(x(0.945f), y(0.115f), x(0.78f), y(0.012f), x(0.50f), y(0.012f))
                path.close()
                path.addRoundRect(
                    RectF(x(0.265f), y(0.115f), x(0.735f), y(0.415f)),
                    h * 0.055f, h * 0.055f, Path.Direction.CW
                )
                path.addRoundRect(
                    RectF(x(0.265f), y(0.585f), x(0.735f), y(0.885f)),
                    h * 0.055f, h * 0.055f, Path.Direction.CW
                )
            }
            "9" -> {
                path.addRoundRect(
                    RectF(x(0.13f), y(0.035f), x(0.87f), y(0.59f)),
                    h * 0.17f, h * 0.17f, Path.Direction.CW
                )
                path.moveTo(x(0.86f), y(0.43f))
                path.cubicTo(x(0.86f), y(0.64f), x(0.70f), y(0.83f), x(0.42f), y(0.93f))
            }
        }
        return path
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

    private fun buildAdaptiveZeroPath(bounds: RectF): Path {
        val width = bounds.width().coerceAtLeast(1f)
        val height = bounds.height().coerceAtLeast(1f)
        val centerX = bounds.centerX()
        val outer = RectF(
            bounds.left - width * 0.075f,
            bounds.top,
            bounds.right + width * 0.075f,
            bounds.bottom
        )
        val outerWidth = outer.width()

        return Path().apply {
            fillType = Path.FillType.EVEN_ODD
            // Broad vertical sides with rounded shoulders: avoid the narrow,
            // elliptical zero produced by simply stretching a text glyph.
            addRoundRect(
                outer,
                outerWidth * 0.30f,
                height * 0.115f,
                Path.Direction.CW
            )
            val counter = RectF(
                centerX - outerWidth * 0.205f,
                bounds.top + height * 0.145f,
                centerX + outerWidth * 0.205f,
                bounds.bottom - height * 0.145f
            )
            addRoundRect(
                counter,
                outerWidth * 0.16f,
                height * 0.075f,
                Path.Direction.CCW
            )
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
    private fun glyphHeightFactor(glyph: String): Float = when (glyph) {
        "1" -> 1.015f
        "4", "7" -> 0.985f
        "3", "5" -> 1.01f
        else -> 1.0f
    }

    private fun glyphWeightOffset(glyph: String): Int = when (glyph) {
        "1", "7" -> 18
        "2", "4" -> 24
        "3", "5" -> 30
        "0", "6", "8", "9" -> 38
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
