package com.example.ios27clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder

class LiquidWallpaperService : WallpaperService() {

    companion object {
        const val ACTION_WALLPAPER_SOURCE_CHANGED =
            "com.example.ios27clock.action.WALLPAPER_SOURCE_CHANGED"

        private const val TAG = "LiquidWallpaperService"
        private const val FRAME_INTERVAL_MS = 33L
    }

    override fun onCreateEngine(): Engine = LiquidEngine()

    private inner class LiquidEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private val renderer = LiquidWallpaperRenderer(this@LiquidWallpaperService)
        private var frameScheduled = false

        private val wallpaperUpdatedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                renderer.reloadWallpaper()
                drawFrame()
            }
        }

        private val frameRunnable = object : Runnable {
            override fun run() {
                frameScheduled = false
                if (!isVisible) return

                drawFrame()
                scheduleNextFrame()
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(false)

            registerReceiver(
                wallpaperUpdatedReceiver,
                IntentFilter(ACTION_WALLPAPER_SOURCE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED
            )
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.setViewport(width, height)
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                drawFrame()
                scheduleNextFrame()
            } else {
                cancelScheduledFrame()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            cancelScheduledFrame()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            cancelScheduledFrame()
            try {
                unregisterReceiver(wallpaperUpdatedReceiver)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Wallpaper update receiver was already unregistered.")
            }
            renderer.release()
            super.onDestroy()
        }

        private fun scheduleNextFrame() {
            if (!frameScheduled && isVisible) {
                frameScheduled = true
                handler.postDelayed(frameRunnable, FRAME_INTERVAL_MS)
            }
        }

        private fun cancelScheduledFrame() {
            if (frameScheduled) {
                handler.removeCallbacks(frameRunnable)
                frameScheduled = false
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            if (!holder.surface.isValid) return

            var canvas: android.graphics.Canvas? = null
            try {
                canvas = try {
                    holder.lockHardwareCanvas()
                } catch (hardwareException: RuntimeException) {
                    Log.w(TAG, "Hardware canvas unavailable; using the regular surface canvas.")
                    holder.lockCanvas()
                }

                if (canvas != null) {
                    renderer.draw(
                        canvas,
                        SystemClock.uptimeMillis() / 1000f
                    )
                }
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Live wallpaper frame could not be rendered.", exception)
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (exception: RuntimeException) {
                        Log.w(TAG, "Could not post live wallpaper frame.", exception)
                    }
                }
            }
        }
    }
}
