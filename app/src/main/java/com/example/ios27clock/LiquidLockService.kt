package com.example.ios27clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.function.Consumer

class LiquidLockService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.ios27clock.action.SHOW"
        const val ACTION_STOP = "com.example.ios27clock.action.STOP"

        private const val TAG = "LiquidLockService"
        private const val CHANNEL_ID = "liquid_clock_overlay"
        private const val NOTIFICATION_ID = 2701

        // WindowManager.LayoutParams.setBlurBehindRadius() takes pixels.
        private const val BLUR_BEHIND_RADIUS_PX = 45
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var blurListenerRegistered = false

    private val blurListener = Consumer<Boolean> { enabled ->
        updateBlurState(enabled)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission is not granted.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (overlayView != null) {
            return START_NOT_STICKY
        }

        return try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            attachWallpaperOverlay()
            START_NOT_STICKY
        } catch (exception: Exception) {
            Log.e(TAG, "Could not start the wallpaper clock overlay.", exception)
            detachOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LiquidLockService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_clock)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.stop_label),
                stopIntent
            )
            .build()
    }

    private fun attachWallpaperOverlay() {
        val view = android.view.LayoutInflater.from(this).inflate(
            R.layout.ios27_lock_screen,
            null,
            false
        )

        view.setOnClickListener {
            stopSelf()
        }

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            dimAmount = 0f
            setBlurBehindRadius(BLUR_BEHIND_RADIUS_PX)
        }

        overlayView = view
        overlayParams = params

        windowManager.addView(view, params)
        windowManager.addCrossWindowBlurEnabledListener(blurListener)
        blurListenerRegistered = true
        updateBlurState(windowManager.isCrossWindowBlurEnabled)

        Log.i(
            TAG,
            "Full-screen wallpaper overlay attached; cross-window blur " +
                "enabled=" + windowManager.isCrossWindowBlurEnabled + "."
        )
    }

    private fun updateBlurState(enabled: Boolean) {
        val view = overlayView ?: return
        val params = overlayParams ?: return

        val wallpaperFlag = WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER

        if (enabled) {
            params.flags =
                params.flags or
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND or
                    wallpaperFlag
            params.setBlurBehindRadius(BLUR_BEHIND_RADIUS_PX)
        } else {
            params.flags =
                (params.flags and
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()) or
                    wallpaperFlag
            params.setBlurBehindRadius(0)
        }

        try {
            windowManager.updateViewLayout(view, params)
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Overlay was detached before blur state update.", exception)
        }
    }

    private fun detachOverlay() {
        if (blurListenerRegistered) {
            windowManager.removeCrossWindowBlurEnabledListener(blurListener)
            blurListenerRegistered = false
        }

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Overlay was already removed.", exception)
            }
        }

        overlayView = null
        overlayParams = null
    }

    override fun onDestroy() {
        detachOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        Log.i(TAG, "Wallpaper clock overlay service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
