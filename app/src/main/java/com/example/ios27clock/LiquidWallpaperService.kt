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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class LiquidWallpaperService : WallpaperService() {

    companion object {
        const val ACTION_APPLIED_CONTENT_CHANGED =
            "com.example.ios27clock.action.APPLIED_CONTENT_CHANGED"

        private const val TAG = "LiquidWallpaperService"
        private const val FRAME_INTERVAL_MS = 33L
        private const val MIN_PINCH_DISTANCE_PX = 1f
    }

    override fun onCreateEngine(): Engine = LiquidEngine()

    private enum class PinchAxis { HORIZONTAL, VERTICAL }

    private inner class LiquidEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private lateinit var renderer: LiquidWallpaperRenderer
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
        private var pinchAxis = PinchAxis.VERTICAL
        private var pinchStartDistance = 0f
        private var pinchActive = false

        private val wallpaperUpdatedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_APPLIED_CONTENT_CHANGED) {
                    renderer.reloadAppliedState()
                }
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

            val wallpaperContent = if (isPreview) {
                ClockWallpaperContent.PREVIEW
            } else {
                if (!WallpaperStorage.ensureAppliedSnapshot(this@LiquidWallpaperService)) {
                    ClockDiagnostics.record(
                        "No committed wallpaper snapshot was available; using the renderer fallback."
                    )
                }
                ClockWallpaperContent.APPLIED
            }
            renderer = LiquidWallpaperRenderer(this@LiquidWallpaperService, wallpaperContent)
            ClockDiagnostics.record(
                "Wallpaper engine created: preview=$isPreview source=$wallpaperContent"
            )

            setTouchEventsEnabled(true)

            val updateFilter = IntentFilter().apply {
                addAction(ACTION_APPLIED_CONTENT_CHANGED)
            }
            registerReceiver(
                wallpaperUpdatedReceiver,
                updateFilter,
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
                    pinchStartDistance = 0f
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (draggingClock) {
                        activePointerId = event.getPointerId(0)
                        lastTouchX = x
                        lastTouchY = y
                    }
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (draggingClock && event.pointerCount >= 2) {
                        beginPinchGesture(event)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!draggingClock) return

                    if (event.pointerCount >= 2 && pinchActive) {
                        val currentDistance = currentPinchDistance(event)
                        if (currentDistance > MIN_PINCH_DISTANCE_PX &&
                            pinchStartDistance > MIN_PINCH_DISTANCE_PX
                        ) {
                            val ratio = currentDistance / pinchStartDistance
                            if (pinchAxis == PinchAxis.HORIZONTAL) {
                                renderer.scaleClockWidth(ratio)
                            } else {
                                renderer.scaleClock(ratio)
                            }
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
                        // Commit the final two-finger scale as soon as the
                        // pinch ends. Do not leave it as a transient canvas
                        // transform that can be discarded by preview redraws.
                        if (pinchActive) {
                            renderer.finishGesture()
                            renderer.persistComposition()
                            drawFrame()
                        }
                        pinchActive = false
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        if (remainingIndex < event.pointerCount) {
                            activePointerId = event.getPointerId(remainingIndex)
                            lastTouchX = event.getX(remainingIndex)
                            lastTouchY = event.getY(remainingIndex)
                        }
                    } else {
                        beginPinchGesture(event, event.actionIndex)
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

        private fun beginPinchGesture(
            event: MotionEvent,
            excludedIndex: Int = -1
        ) {
            val indexes = (0 until event.pointerCount)
                .filter { it != excludedIndex }
            if (indexes.size < 2) {
                pinchActive = false
                pinchStartDistance = 0f
                return
            }

            val first = indexes[0]
            val second = indexes[1]
            val deltaX = event.getX(first) - event.getX(second)
            val deltaY = event.getY(first) - event.getY(second)
            pinchAxis = if (abs(deltaX) > abs(deltaY) * 1.15f) {
                PinchAxis.HORIZONTAL
            } else {
                PinchAxis.VERTICAL
            }
            pinchStartDistance = if (pinchAxis == PinchAxis.HORIZONTAL) {
                abs(deltaX)
            } else {
                hypot(deltaX, deltaY)
            }
            pinchActive = pinchStartDistance > MIN_PINCH_DISTANCE_PX

            if (pinchActive) {
                if (pinchAxis == PinchAxis.HORIZONTAL) {
                    renderer.beginWidthScaleGesture()
                } else {
                    renderer.beginScaleGesture()
                }
            }
        }

        private fun currentPinchDistance(event: MotionEvent): Float {
            if (event.pointerCount < 2) return 0f
            val deltaX = event.getX(0) - event.getX(1)
            val deltaY = event.getY(0) - event.getY(1)
            return if (pinchAxis == PinchAxis.HORIZONTAL) {
                abs(deltaX)
            } else {
                hypot(deltaX, deltaY)
            }
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
