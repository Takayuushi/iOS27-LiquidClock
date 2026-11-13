package com.example.ios27clock

/** App-owned wallpaper paths and preferences passed into the shared renderer. */
data class RendererWallpaperContent(
    val sourceFileName: String,
    val manualForegroundFileName: String,
    val compositionPreferencesName: String
) {
    companion object {
        val PREVIEW = RendererWallpaperContent(
            "liquid_clock_wallpaper_source.img",
            "liquid_clock_wallpaper_manual_foreground.png",
            "liquid_clock_preview_composition"
        )
    }
}
