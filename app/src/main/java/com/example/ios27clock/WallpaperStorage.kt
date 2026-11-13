package com.example.ios27clock

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal enum class ClockWallpaperContent(
    val sourceFileName: String,
    val manualForegroundFileName: String,
    val compositionPreferencesName: String
) {
    PREVIEW(
        WallpaperStorage.PREVIEW_SOURCE_FILE_NAME,
        WallpaperStorage.PREVIEW_MANUAL_FOREGROUND_FILE_NAME,
        WallpaperStorage.PREVIEW_COMPOSITION_PREFERENCES
    ),
    APPLIED(
        WallpaperStorage.APPLIED_SOURCE_FILE_NAME,
        WallpaperStorage.APPLIED_MANUAL_FOREGROUND_FILE_NAME,
        WallpaperStorage.APPLIED_COMPOSITION_PREFERENCES
    )
}

/**
 * Keeps the wallpaper being edited separate from the wallpaper already set
 * on the device. Preview files are disposable candidates; only the explicit
 * Apply current action copies them into the applied snapshot.
 */
internal object WallpaperStorage {
    private const val TAG = "WallpaperStorage"

    const val PREVIEW_SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
    const val PREVIEW_MANUAL_FOREGROUND_FILE_NAME =
        "liquid_clock_wallpaper_manual_foreground.png"
    const val APPLIED_SOURCE_FILE_NAME = "liquid_clock_wallpaper_applied.img"
    const val APPLIED_MANUAL_FOREGROUND_FILE_NAME =
        "liquid_clock_wallpaper_applied_manual_foreground.png"

    const val APPLIED_COMPOSITION_PREFERENCES = "liquid_clock_composition"
    const val PREVIEW_COMPOSITION_PREFERENCES = "liquid_clock_preview_composition"

    fun resetPreviewComposition(context: Context) {
        val applied = context.getSharedPreferences(
            APPLIED_COMPOSITION_PREFERENCES,
            Context.MODE_PRIVATE
        )
        val preview = context.getSharedPreferences(
            PREVIEW_COMPOSITION_PREFERENCES,
            Context.MODE_PRIVATE
        )
        copyPreferences(applied, preview)
    }

    /**
     * One-time migration for users upgrading from the old shared-source design.
     * Before the first applied snapshot exists, the source currently displayed
     * by the old wallpaper service is the best available legacy snapshot.
     */
    fun ensureAppliedSnapshot(context: Context): Boolean {
        val appliedSource = File(context.filesDir, APPLIED_SOURCE_FILE_NAME)
        if (isUsable(appliedSource)) return true

        val previewSource = File(context.filesDir, PREVIEW_SOURCE_FILE_NAME)
        if (!isUsable(previewSource)) return false

        return try {
            replaceAtomically(previewSource, appliedSource)
            copyOptional(
                File(context.filesDir, PREVIEW_MANUAL_FOREGROUND_FILE_NAME),
                File(context.filesDir, APPLIED_MANUAL_FOREGROUND_FILE_NAME)
            )
            Log.i(TAG, "Migrated the legacy wallpaper source into the applied snapshot.")
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Could not migrate the legacy wallpaper source.", exception)
            false
        }
    }

    /**
     * Copies the staged image, depth mask and composition settings into the
     * active snapshot. This is called only by the explicit Apply current action.
     */
    fun commitPreviewToApplied(context: Context): Boolean {
        val previewSource = File(context.filesDir, PREVIEW_SOURCE_FILE_NAME)
        if (!isUsable(previewSource)) {
            Log.w(TAG, "Apply requested without a usable preview wallpaper.")
            return false
        }

        return try {
            replaceAtomically(
                previewSource,
                File(context.filesDir, APPLIED_SOURCE_FILE_NAME)
            )
            copyOptional(
                File(context.filesDir, PREVIEW_MANUAL_FOREGROUND_FILE_NAME),
                File(context.filesDir, APPLIED_MANUAL_FOREGROUND_FILE_NAME)
            )

            val previewPreferences = context.getSharedPreferences(
                PREVIEW_COMPOSITION_PREFERENCES,
                Context.MODE_PRIVATE
            )
            val appliedPreferences = context.getSharedPreferences(
                APPLIED_COMPOSITION_PREFERENCES,
                Context.MODE_PRIVATE
            )
            copyPreferences(previewPreferences, appliedPreferences)

            Log.i(TAG, "Preview wallpaper and composition committed to the applied snapshot.")
            true
        } catch (exception: Exception) {
            Log.e(TAG, "Could not commit the preview wallpaper.", exception)
            false
        }
    }

    private fun copyOptional(source: File, destination: File) {
        if (isUsable(source)) {
            replaceAtomically(source, destination)
        } else {
            destination.delete()
        }
    }

    private fun isUsable(file: File): Boolean =
        file.exists() && file.isFile && file.length() > 0L

    private fun replaceAtomically(source: File, destination: File) {
        val parent = destination.parentFile
            ?: throw IOException("Destination has no parent directory.")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create the wallpaper storage directory.")
        }

        val temporary = File(parent, destination.name + ".tmp")
        temporary.delete()

        source.inputStream().use { input ->
            FileOutputStream(temporary).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }

        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            if (!isUsable(destination) || destination.length() != temporary.length()) {
                temporary.delete()
                throw IOException("Could not replace destination file.")
            }
            temporary.delete()
        }

        if (!isUsable(destination)) {
            throw IOException("The saved wallpaper file is empty.")
        }
    }

    private fun copyPreferences(
        source: SharedPreferences,
        destination: SharedPreferences
    ) {
        val editor = destination.edit().clear()
        source.all.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> {
                    val strings = value.filterIsInstance<String>().toSet()
                    editor.putStringSet(key, strings)
                }
            }
        }
        if (!editor.commit()) {
            throw IOException("Could not save the applied clock settings.")
        }
    }
}


internal fun ClockWallpaperContent.toRendererContent() = RendererWallpaperContent(
    sourceFileName = sourceFileName,
    manualForegroundFileName = manualForegroundFileName,
    compositionPreferencesName = compositionPreferencesName
)
