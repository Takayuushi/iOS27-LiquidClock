package com.example.ios27clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.hypot
import kotlin.math.sin

class LiquidWallpaperService : WallpaperService() {

    companion object {
        const val ACTION_WALLPAPER_SOURCE_CHANGED =
            "com.example.ios27clock.action.WALLPAPER_SOURCE_CHANGED"

        private const val TAG = "LiquidWallpaperService"
        private const val FRAME_INTERVAL_MS = 33L
        private const val MIN_PINCH_DISTANCE_PX = 1f
    }

    override fun onCreateEngine(): Engine = LiquidEngine()

    private inner class LiquidEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private val renderer = LiquidWallpaperRenderer(this@LiquidWallpaperService)
        private val sensorManager =
            this@LiquidWallpaperService.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        private val rotationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)
        private var tiltTrackingActive = false
        private var frameScheduled = false

        private val tiltListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                renderer.setDeviceTilt(
                    sin(orientation[2]).coerceIn(-1f, 1f),
                    -sin(orientation[1]).coerceIn(-1f, 1f)
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        private var draggingClock = false
        private var activePointerId = MotionEvent.INVALID_POINTER_ID
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var pinchStartDistance = 0f
        private var pinchActive = false

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
            setTouchEventsEnabled(true)

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
            if (isVisible) startTiltTracking()
            drawFrame()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible) {
                startTiltTracking()
                drawFrame()
                scheduleNextFrame()
            } else {
                stopTiltTracking()
                cancelScheduledFrame()
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val x = event.x
                    val y = event.y
                    // Clear stale state before each new pointer sequence.
                    draggingClock = renderer.isClockHit(x, y)
                    pinchActive = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (draggingClock) {
                        activePointerId = event.getPointerId(0)
                        lastTouchX = x
                        lastTouchY = y
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (draggingClock && event.pointerCount >= 2) {
                        pinchStartDistance = pointerDistance(event)
                        pinchActive = pinchStartDistance > MIN_PINCH_DISTANCE_PX
                        if (pinchActive) renderer.beginScaleGesture()
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!draggingClock) return

                    if (event.pointerCount >= 2 && pinchActive) {
                        val currentDistance = pointerDistance(event)
                        if (currentDistance > MIN_PINCH_DISTANCE_PX &&
                            pinchStartDistance > MIN_PINCH_DISTANCE_PX
                        ) {
                            renderer.scaleClock(currentDistance / pinchStartDistance)
                        }
                    } else {
                        val index = event.findPointerIndex(activePointerId)
                        if (index >= 0) {
                            val x = event.getX(index)
                            val y = event.getY(index)
                            renderer.moveClockBy(x - lastTouchX, y - lastTouchY)
                            lastTouchX = x
                            lastTouchY = y
                        }
                    }
                    drawFrame()
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount - 1 < 2) {
                        pinchActive = false
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        if (remainingIndex < event.pointerCount) {
                            activePointerId = event.getPointerId(remainingIndex)
                            lastTouchX = event.getX(remainingIndex)
                            lastTouchY = event.getY(remainingIndex)
                        }
                    } else {
                        pinchStartDistance = pointerDistanceExcluding(event, event.actionIndex)
                        if (pinchStartDistance > MIN_PINCH_DISTANCE_PX) {
                            renderer.beginScaleGesture()
                        }
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (draggingClock) {
                        renderer.finishGesture()
                        renderer.persistComposition()
                        drawFrame()
                    }
                    draggingClock = false
                    pinchActive = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    pinchStartDistance = 0f
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopTiltTracking()
            cancelScheduledFrame()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopTiltTracking()
            cancelScheduledFrame()
            renderer.persistComposition()
            try {
                unregisterReceiver(wallpaperUpdatedReceiver)
            } catch (exception: IllegalArgumentException) {
                Log.w(TAG, "Wallpaper update receiver was already unregistered.")
            }
            renderer.release()
            super.onDestroy()
        }

        private fun startTiltTracking() {
            if (tiltTrackingActive || rotationSensor == null) return
            tiltTrackingActive = sensorManager.registerListener(
                tiltListener,
                rotationSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        private fun stopTiltTracking() {
            if (!tiltTrackingActive) return
            sensorManager.unregisterListener(tiltListener)
            tiltTrackingActive = false
        }

        private fun pointerDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val dx = event.getX(0) - event.getX(1)
            val dy = event.getY(0) - event.getY(1)
            return hypot(dx, dy)
        }

        private fun pointerDistanceExcluding(
            event: MotionEvent,
            excludedIndex: Int
        ): Float {
            if (event.pointerCount < 3) return 0f
            val indexes = (0 until event.pointerCount).filter { it != excludedIndex }
            val first = indexes[0]
            val second = indexes[1]
            return hypot(
                event.getX(first) - event.getX(second),
                event.getY(first) - event.getY(second)
            )
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
                    Log.w(TAG, "Hardware canvas unavailable; using regular surface canvas.")
                    holder.lockCanvas()
                }

                if (canvas != null) {
                    renderer.draw(canvas, SystemClock.uptimeMillis() / 1000f)
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
