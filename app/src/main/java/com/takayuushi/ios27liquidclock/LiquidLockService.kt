package com.takayuushi.ios27liquidclock

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
import kotlin.math.min

class LiquidLockService : Service() {

    companion object {
        const val ACTION_SHOW =
            "com.takayuushi.ios27liquidclock.action.SHOW"
        const val ACTION_STOP =
            "com.takayuushi.ios27liquidclock.action.STOP"

        private const val TAG = "LiquidLockService"
        private const val CHANNEL_ID = "liquid_clock_overlay"
        private const val NOTIFICATION_ID = 2701
        private const val BLUR_RADIUS_DP = 34f
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
            attachOverlay()
            START_NOT_STICKY
        } catch (exception: Exception) {
            Log.e(TAG, "Could not start the liquid clock overlay.", exception)
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

    private fun attachOverlay() {
        val displayWidth = resources.displayMetrics.widthPixels
        val width = min(displayWidth - dp(40f), dp(520f))
        val height = dp(286f)

        val view = android.view.LayoutInflater.from(this).inflate(
            R.layout.ios27_lock_screen,
            null,
            false
        )

        val dismissListener = View.OnClickListener {
            stopSelf()
        }
        view.setOnClickListener(dismissListener)

        val baseFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.06f
            setBlurBehindRadius(dp(BLUR_RADIUS_DP))
        }

        overlayView = view
        overlayParams = params

        windowManager.addView(view, params)
        windowManager.addCrossWindowBlurEnabledListener(blurListener)
        blurListenerRegistered = true
        updateBlurState(windowManager.isCrossWindowBlurEnabled)

        Log.i(TAG, "Liquid glass clock overlay attached.")
    }

    private fun updateBlurState(enabled: Boolean) {
        val view = overlayView ?: return
        val params = overlayParams ?: return

        if (enabled) {
            params.flags =
                params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.setBlurBehindRadius(dp(BLUR_RADIUS_DP))
            params.dimAmount = 0.035f
        } else {
            params.flags =
                params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            params.setBlurBehindRadius(0)
            params.dimAmount = 0.12f
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

    private fun dp(value: Float): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        detachOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        Log.i(TAG, "Liquid clock overlay service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
