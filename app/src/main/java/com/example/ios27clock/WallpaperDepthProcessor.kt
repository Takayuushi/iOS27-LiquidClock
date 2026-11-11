package com.example.ios27clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Creates an optional transparent foreground layer from a selected wallpaper.
 *
 * Segmentation runs once when a wallpaper is imported. The live wallpaper
 * engine only composites the cached result; it never runs ML inference per frame.
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
                onCompleteOnMain(false, onComplete)
                return@execute
            }

            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
            val segmenter: SubjectSegmenter = SubjectSegmentation.getClient(options)

            try {
                segmenter.process(InputImage.fromBitmap(inputBitmap, 0))
                    .addOnSuccessListener { result ->
                        var success = false
                        try {
                            val foreground = result.foregroundBitmap
                            if (foreground != null) {
                                val normalized = if (
                                    foreground.width == inputBitmap.width &&
                                    foreground.height == inputBitmap.height
                                ) {
                                    foreground.copy(Bitmap.Config.ARGB_8888, false)
                                } else {
                                    Bitmap.createScaledBitmap(
                                        foreground,
                                        inputBitmap.width,
                                        inputBitmap.height,
                                        true
                                    )
                                }

                                if (hasTransparentBackground(normalized)) {
                                    FileOutputStream(temporaryFile).use { output ->
                                        check(
                                            normalized.compress(
                                                Bitmap.CompressFormat.PNG,
                                                100,
                                                output
                                            )
                                        ) { "Could not encode the foreground PNG." }
                                        output.fd.sync()
                                    }

                                    if (!temporaryFile.renameTo(foregroundFile)) {
                                        temporaryFile.copyTo(foregroundFile, overwrite = true)
                                        temporaryFile.delete()
                                    }
                                    success = foregroundFile.exists() &&
                                        foregroundFile.length() > 0L
                                } else {
                                    Log.w(
                                        TAG,
                                        "Segmentation result had no transparent background; " +
                                            "using the normal wallpaper composition."
                                    )
                                    normalized.recycle()
                                }
                            } else {
                                Log.w(TAG, "ML Kit returned no foreground bitmap.")
                            }
                        } catch (exception: Exception) {
                            Log.e(TAG, "Could not save the foreground layer.", exception)
                            temporaryFile.delete()
                            foregroundFile.delete()
                        } finally {
                            inputBitmap.recycle()
                            segmenter.close()
                            onCompleteOnMain(success, onComplete)
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.w(TAG, "Subject segmentation failed; continuing without depth.", exception)
                        inputBitmap.recycle()
                        segmenter.close()
                        temporaryFile.delete()
                        foregroundFile.delete()
                        onCompleteOnMain(false, onComplete)
                    }
            } catch (exception: Exception) {
                Log.e(TAG, "Could not start subject segmentation.", exception)
                inputBitmap.recycle()
                segmenter.close()
                temporaryFile.delete()
                foregroundFile.delete()
                onCompleteOnMain(false, onComplete)
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

    private fun hasTransparentBackground(bitmap: Bitmap): Boolean {
        val sampleStepX = max(1, bitmap.width / 32)
        val sampleStepY = max(1, bitmap.height / 32)
        var samples = 0
        var transparentSamples = 0

        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val alpha = (bitmap.getPixel(x, y) ushr 24) and 0xFF
                samples++
                if (alpha < 245) transparentSamples++
                x += sampleStepX
            }
            y += sampleStepY
        }

        return samples > 0 && transparentSamples >= 8 &&
            transparentSamples.toFloat() / samples.toFloat() >= 0.015f
    }

    private fun onCompleteOnMain(
        success: Boolean,
        callback: (Boolean) -> Unit
    ) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            callback(success)
        }
    }
}
