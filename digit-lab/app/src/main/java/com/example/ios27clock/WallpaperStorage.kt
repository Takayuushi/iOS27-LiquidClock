package com.example.ios27clock

/** Isolated Digit Lab preview storage. */
internal enum class ClockWallpaperContent(
    val sourceFileName: String,
    val manualForegroundFileName: String,
    val compositionPreferencesName: String
) {
    PREVIEW(
        WallpaperStorage.PREVIEW_SOURCE_FILE_NAME,
        WallpaperStorage.PREVIEW_MANUAL_FOREGROUND_FILE_NAME,
        WallpaperStorage.PREVIEW_COMPOSITION_PREFERENCES
    )
}
internal object WallpaperStorage {
    const val PREVIEW_SOURCE_FILE_NAME = "liquid_clock_wallpaper_source.img"
    const val PREVIEW_MANUAL_FOREGROUND_FILE_NAME = "liquid_clock_wallpaper_manual_foreground.png"
    const val PREVIEW_COMPOSITION_PREFERENCES = "digit_lab_preview_composition"
}


internal fun ClockWallpaperContent.toRendererContent() = RendererWallpaperContent(
    sourceFileName = sourceFileName,
    manualForegroundFileName = manualForegroundFileName,
    compositionPreferencesName = compositionPreferencesName
)
