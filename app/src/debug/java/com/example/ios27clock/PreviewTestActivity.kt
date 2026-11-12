package com.example.ios27clock

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Debug-only entry point used by GitHub Actions to open the real system
 * live-wallpaper preview on an Android emulator and capture its rendering.
 */
class PreviewTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewIntent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this@PreviewTestActivity, LiquidWallpaperService::class.java)
            )
        }
        try {
            startActivity(previewIntent)
        } catch (exception: Exception) {
            Log.e("ClockPreviewTest", "Could not open the system wallpaper preview.", exception)
        } finally {
            finish()
        }
    }
}
