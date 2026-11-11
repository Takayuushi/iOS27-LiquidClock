package com.example.ios27clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds a soft-edged, transparent foreground cut-out from the selected wallpaper.
 *
 * ML inference runs once per wallpaper import, never inside the wallpaper frame loop.
 * The resulting PNG is cached in app-private storage and composited above the clock.
 */
internal object WallpaperDepthProcessor {

    private const val TAG = "WallpaperDepthProcessor"
    private const val MAX_INFERENCE_DIMENSION = 1800
    private const val FOREGROUND_FILE_NAME = "liquid_clock_wallpaper_foreground.png"

    private val executor = Executors.newSingleThreadExecutor()

    fun process(
        context: Context,
        sourceFile: File,
        onComplete: (Boolean) -> Unit
    ) {
        val appContext = context.applicationContext

        executor.execute {
            val foregroundFile = File(appContext.filesDir, FOREGROUND_FILE_NAME)
            val temporaryFile = File(appContext.filesDir, "$FOREGROUND_FILE_NAME.tmp")
            foregroundFile.delete()
            temporaryFile.delete()

            val inputBitmap = decodeForSegmentation(sourceFile)
            if (inputBitmap == null) {
                Log.w(TAG, "Wallpaper could not be decoded for subject segmentation.")
                completeOnMain(false, onComplete)
                return@execute
            }

            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()

            val segmenter: SubjectSegmenter = SubjectSegmentation.getClient(options)

            try {
                segmenter.process(InputImage.fromBitmap(inputBitmap, 0))
                    .addOnSuccessListener { result ->
                        var success = false

                        try {
                            val confidence = result.foregroundConfidenceMask
                            val pixelCount = inputBitmap.width * inputBitmap.height
                            val confidenceOffset = confidence?.position() ?: 0
                            val confidenceCount = confidence?.remaining() ?: 0

                            if (confidence == null || confidenceCount < pixelCount) {
                                Log.w(TAG, "ML Kit returned an invalid foreground confidence mask.")
                            } else {
                                val sourcePixels = IntArray(pixelCount)
                                inputBitmap.getPixels(
                                    sourcePixels,
                                    0,
                                    inputBitmap.width,
                                    0,
                                    0,
                                    inputBitmap.width,
                                    inputBitmap.height
                                )

                                var foregroundPixels = 0
                                val cutoutPixels = IntArray(pixelCount)

                                for (index in 0 until pixelCount) {
                                    val sourceColor = sourcePixels[index]
                                    val confidenceValue = confidence.get(confidenceOffset + index)
                                        .coerceIn(0f, 1f)

                                    // Soft threshold keeps anti-aliased tree branches and
                                    // mountain edges while removing most background pixels.
                                    val normalized = (
                                        (confidenceValue - 0.12f) / (0.82f - 0.12f)
                                    ).coerceIn(0f, 1f)
                                    val featheredAlpha = normalized * normalized *
                                        (3f - 2f * normalized)

                                    val sourceAlpha = Color.alpha(sourceColor) / 255f
                                    val alpha = (sourceAlpha * featheredAlpha * 255f)
                                        .roundToInt()
                                        .coerceIn(0, 255)

                                    if (alpha >= 12) foregroundPixels++
                                    cutoutPixels[index] =
                                        (alpha shl 24) or (sourceColor and 0x00FFFFFF)
                                }

                                val coverageRatio = foregroundPixels.toFloat() / pixelCount
                                if (coverageRatio in 0.015f..0.94f) {
                                    val cutout = Bitmap.createBitmap(
                                        cutoutPixels,
                                        inputBitmap.width,
                                        inputBitmap.height,
                                        Bitmap.Config.ARGB_8888
                                    )

                                    FileOutputStream(temporaryFile).use { output ->
                                        check(
                                            cutout.compress(
                                                Bitmap.CompressFormat.PNG,
                                                100,
                                                output
                                            )
                                        ) { "Could not encode the foreground cut-out." }
                                        output.fd.sync()
                                    }
                                    cutout.recycle()

                                    if (!temporaryFile.renameTo(foregroundFile)) {
                                        temporaryFile.copyTo(foregroundFile, overwrite = true)
                                        temporaryFile.delete()
                                    }

                                    success = foregroundFile.exists() &&
                                        foregroundFile.length() > 0L

                                    Log.i(
                                        TAG,
                                        "Foreground cut-out saved; mask coverage=" +
                                            (coverageRatio * 100f).roundToInt() + "%."
                                    )
                                } else {
                                    Log.w(
                                        TAG,
                                        "Foreground mask coverage was implausible: " +
                                            (coverageRatio * 100f) + "%."
                                    )
                                }
                            }
                        } catch (exception: Exception) {
                            Log.e(TAG, "Could not create the foreground cut-out.", exception)
                            temporaryFile.delete()
                            foregroundFile.delete()
                        } finally {
                            inputBitmap.recycle()
                            segmenter.close()
                            completeOnMain(success, onComplete)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.w(TAG, "Subject segmentation failed; depth layer will be omitted.", exception)
                        inputBitmap.recycle()
                        segmenter.close()
                        temporaryFile.delete()
                        foregroundFile.delete()
                        completeOnMain(false, onComplete)
                    }
            } catch (exception: Exception) {
                Log.e(TAG, "Could not start subject segmentation.", exception)
                inputBitmap.recycle()
                segmenter.close()
                temporaryFile.delete()
                foregroundFile.delete()
                completeOnMain(false, onComplete)
            }
        }
    }

    private fun decodeForSegmentation(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            if (bounds.outWidth < 512 || bounds.outHeight < 512) {
                Log.w(TAG, "Wallpaper is smaller than the recommended 512x512 input.")
                return null
            }

            var sampleSize = 1
            while (
                max(bounds.outWidth / (sampleSize * 2), bounds.outHeight / (sampleSize * 2)) >=
                MAX_INFERENCE_DIMENSION
            ) {
                sampleSize *= 2
            }

            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inDither = true
                }
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Wallpaper decode for segmentation failed.", exception)
            null
        }
    }

    private fun completeOnMain(
        success: Boolean,
        callback: (Boolean) -> Unit
    ) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            callback(success)
        }
    }
}
