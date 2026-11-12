package com.example.ios27clock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Manual foreground selector with polygon and magnetic lasso modes.
 *
 * Add and erase are both closed-lasso operations; there is deliberately no
 * paint brush. One finger traces, while two fingers pinch and pan. All paths
 * are stored in source-image coordinates so zooming never misaligns a mask.
 */
class MaskDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class SelectionAction(
        val path: Path? = null,
        val erase: Boolean = false,
        val clear: Boolean = false
    )

    private var sourceBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var baseMaskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null

    private val actions = mutableListOf<SelectionAction>()
    private var currentPath: Path? = null
    private var lastMagneticPoint: PointF? = null
    private var currentOperationErase = false

    var isEraseMode: Boolean = false
        private set
    var isMagneticLassoEnabled: Boolean = true
        private set

    private val imageRect = RectF()
    private val imageClipPath = Path()
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var transformGestureActive = false
    private var lastCentroidX = -1f
    private var lastCentroidY = -1f

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val imageFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = Color.argb(76, 210, 228, 255)
    }

    private val imageCornerRadius = 14f * resources.displayMetrics.density

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(
            Color.argb(108, 35, 150, 235),
            PorterDuff.Mode.SRC_IN
        )
    }

    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
    }

    private val lassoPreviewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val addMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val eraseMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val source = sourceBitmap ?: return false
                val anchor = viewToImage(detector.focusX, detector.focusY, source)

                val newScale = (zoomScale * detector.scaleFactor).coerceIn(1f, 6f)
                if (newScale == zoomScale) return true

                zoomScale = newScale
                updateImageRect(source)

                // Keep the source pixel under the pinch midpoint stationary.
                if (anchor != null) {
                    val mappedX = imageRect.left +
                        (anchor.x / source.width) * imageRect.width()
                    val mappedY = imageRect.top +
                        (anchor.y / source.height) * imageRect.height()
                    panX += detector.focusX - mappedX
                    panY += detector.focusY - mappedY
                    updateImageRect(source)
                }

                invalidate()
                return true
            }
        }
    )

    fun setEraseMode(enabled: Boolean) {
        if (isEraseMode == enabled) return
        isEraseMode = enabled
        currentPath = null
        lastMagneticPoint = null
        invalidate()
    }

    fun setMagneticLassoEnabled(enabled: Boolean) {
        if (isMagneticLassoEnabled == enabled) return
        isMagneticLassoEnabled = enabled
        currentPath = null
        lastMagneticPoint = null
        invalidate()
    }

    fun loadImage(sourceFile: File, existingSelection: File) {
        release()

        sourceBitmap = decodeImage(sourceFile)
            ?: throw IllegalArgumentException("Could not decode the selected wallpaper.")

        zoomScale = 1f
        panX = 0f
        panY = 0f

        val source = requireNotNull(sourceBitmap)
        maskBitmap = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )
        maskCanvas = Canvas(requireNotNull(maskBitmap)).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        if (existingSelection.exists() && existingSelection.length() > 0L) {
            loadExistingSelection(existingSelection, source.width, source.height)
        }

        baseMaskBitmap = requireNotNull(maskBitmap).copy(Bitmap.Config.ARGB_8888, true)
        invalidate()
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val maxDimension = 2560
        while (
            bounds.outWidth / (sampleSize * 2) > maxDimension ||
            bounds.outHeight / (sampleSize * 2) > maxDimension
        ) {
            sampleSize *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = true
            }
        )
    }

    private fun loadExistingSelection(file: File, targetWidth: Int, targetHeight: Int) {
        val existing = decodeImage(file) ?: return
        try {
            val target = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
            maskCanvas?.drawBitmap(existing, null, target, imagePaint)
        } finally {
            existing.recycle()
        }
    }

    fun undo() {
        if (actions.isNotEmpty()) {
            actions.removeAt(actions.lastIndex)
            currentPath = null
            lastMagneticPoint = null
            redrawMask()
            invalidate()
        }
    }

    fun clearSelection() {
        currentPath = null
        lastMagneticPoint = null
        actions.add(SelectionAction(clear = true))
        redrawMask()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val source = sourceBitmap ?: run {
            canvas.drawColor(Color.rgb(20, 24, 32))
            return
        }

        // Use one clean, fit-to-view image over a restrained dark matte.
        // Avoid repeating enlarged slices of the wallpaper around the preview.
        canvas.drawColor(Color.rgb(8, 13, 22))
        updateImageRect(source)
        updateImageClipPath()

        val imageSave = canvas.save()
        canvas.clipPath(imageClipPath)
        canvas.drawBitmap(source, null, imageRect, imagePaint)
        maskBitmap?.let { canvas.drawBitmap(it, null, imageRect, overlayPaint) }
        canvas.restoreToCount(imageSave)

        canvas.drawPath(imageClipPath, imageFramePaint)
        currentPath?.let { drawLassoPreview(canvas, it, source, currentOperationErase) }
    }

    private fun updateImageRect(bitmap: Bitmap) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val fitScale = min(
            viewWidth / bitmap.width,
            viewHeight / bitmap.height
        )
        val scale = fitScale * zoomScale
        val renderedWidth = bitmap.width * scale
        val renderedHeight = bitmap.height * scale
        val left = (viewWidth - renderedWidth) / 2f + panX
        val top = (viewHeight - renderedHeight) / 2f + panY
        imageRect.set(left, top, left + renderedWidth, top + renderedHeight)
    }

    private fun updateImageClipPath() {
        imageClipPath.rewind()
        imageClipPath.addRoundRect(
            imageRect,
            imageCornerRadius,
            imageCornerRadius,
            Path.Direction.CW
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val source = sourceBitmap ?: return false
        scaleGestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                transformGestureActive = false
                lastCentroidX = -1f
                lastCentroidY = -1f
                updateImageRect(source)
                if (!imageRect.contains(event.x, event.y)) return false

                val rawStart = viewToImage(event.x, event.y, source) ?: return false
                currentOperationErase = isEraseMode
                val start = if (isMagneticLassoEnabled) {
                    snapToNearbyEdge(rawStart, source, null)
                } else {
                    rawStart
                }
                currentPath = Path().apply { moveTo(start.x, start.y) }
                lastMagneticPoint = start
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // A pinch/pan takes precedence over an unfinished lasso.
                currentPath = null
                lastMagneticPoint = null
                transformGestureActive = true
                val centroid = pointerCentroid(event)
                lastCentroidX = centroid.x
                lastCentroidY = centroid.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2 || transformGestureActive) {
                    if (event.pointerCount >= 2) {
                        val centroid = pointerCentroid(event)
                        if (lastCentroidX >= 0f && lastCentroidY >= 0f) {
                            panX += centroid.x - lastCentroidX
                            panY += centroid.y - lastCentroidY
                        }
                        lastCentroidX = centroid.x
                        lastCentroidY = centroid.y
                        updateImageRect(source)
                        invalidate()
                    }
                    return true
                }

                if (currentPath == null) return false

                // Android may batch several pointer samples into one MOVE.
                // Consume them all so a fast finger movement cannot leave
                // long straight chords that cut across the intended contour.
                for (historyIndex in 0 until event.historySize) {
                    appendLassoPoint(
                        event.getHistoricalX(0, historyIndex),
                        event.getHistoricalY(0, historyIndex),
                        source
                    )
                }
                appendLassoPoint(event.x, event.y, source)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                transformGestureActive = true
                lastCentroidX = -1f
                lastCentroidY = -1f
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!transformGestureActive) {
                    // Include the final release coordinate; some devices do
                    // not emit a final MOVE before ACTION_UP.
                    if (currentPath != null) {
                        appendLassoPoint(event.x, event.y, source)
                    }
                    currentPath?.let { path ->
                        path.close()
                        actions.add(
                            SelectionAction(
                                path = Path(path),
                                erase = currentOperationErase
                            )
                        )
                        redrawMask()
                    }
                }

                currentPath = null
                lastMagneticPoint = null
                transformGestureActive = false
                lastCentroidX = -1f
                lastCentroidY = -1f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                currentPath = null
                lastMagneticPoint = null
                transformGestureActive = false
                lastCentroidX = -1f
                lastCentroidY = -1f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    private fun appendLassoPoint(screenX: Float, screenY: Float, source: Bitmap) {
        val rawPoint = viewToImage(screenX, screenY, source) ?: return
        val previous = lastMagneticPoint
        val point = if (isMagneticLassoEnabled) {
            snapToNearbyEdge(rawPoint, source, previous)
        } else {
            rawPoint
        }
        if (previous == null || hypot(point.x - previous.x, point.y - previous.y) >= 1.4f) {
            currentPath?.lineTo(point.x, point.y)
            lastMagneticPoint = point
        }
    }

    /**
     * Prefer edges that run along the direction of travel. The old "strongest
     * edge in a circle" rule could jump to internal snow/rock texture instead
     * of following the silhouette that the finger was tracing.
     */
    private fun snapToNearbyEdge(
        pointer: PointF,
        bitmap: Bitmap,
        previous: PointF?
    ): PointF {
        if (imageRect.width() <= 0f) return pointer

        val radius = (
            11f * resources.displayMetrics.density *
                bitmap.width / imageRect.width()
            ).coerceIn(3f, 40f)
        val radiusInt = radius.roundToInt()
        val minX = max(1, (pointer.x - radiusInt).toInt())
        val maxX = min(bitmap.width - 2, (pointer.x + radiusInt).toInt())
        val minY = max(1, (pointer.y - radiusInt).toInt())
        val maxY = min(bitmap.height - 2, (pointer.y + radiusInt).toInt())
        if (minX > maxX || minY > maxY) return pointer

        val moveX = if (previous == null) 0f else pointer.x - previous.x
        val moveY = if (previous == null) 0f else pointer.y - previous.y
        val moveLength = hypot(moveX, moveY).coerceAtLeast(0.001f)

        fun luminance(x: Int, y: Int): Float {
            val pixel = bitmap.getPixel(x, y)
            return Color.red(pixel) * 0.2126f +
                Color.green(pixel) * 0.7152f +
                Color.blue(pixel) * 0.0722f
        }

        fun scoreAt(x: Int, y: Int): Float {
            val gradient = edgeMagnitude(bitmap, x, y)
            val dx = x - pointer.x
            val dy = y - pointer.y
            val distance = hypot(dx, dy)
            if (distance > radius) return 0f

            val proximity = 1f - (distance / radius).coerceIn(0f, 1f)
            val horizontal = luminance(x + 1, y) - luminance(x - 1, y)
            val vertical = luminance(x, y + 1) - luminance(x, y - 1)
            val gradientLength = hypot(horizontal, vertical)
            val tangentAlignment = if (previous == null || gradientLength < 0.001f) {
                1f
            } else {
                val dot = (-vertical * moveX + horizontal * moveY) /
                    (gradientLength * moveLength)
                abs(dot).coerceIn(0f, 1f)
            }

            val directionalWeight = 0.28f + 0.72f * tangentAlignment
            return gradient * (0.38f + 0.62f * proximity) * directionalWeight
        }

        val coarseStep = max(1, (radius / 9f).roundToInt())
        var bestX = pointer.x.roundToInt().coerceIn(minX, maxX)
        var bestY = pointer.y.roundToInt().coerceIn(minY, maxY)
        var bestScore = 0f

        var y = minY
        while (y <= maxY) {
            var x = minX
            while (x <= maxX) {
                val score = scoreAt(x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                x += coarseStep
            }
            y += coarseStep
        }

        val coarseBestX = bestX
        val coarseBestY = bestY
        val refineRadius = coarseStep
        for (candidateY in (coarseBestY - refineRadius)..(coarseBestY + refineRadius)) {
            if (candidateY !in minY..maxY) continue
            for (candidateX in (coarseBestX - refineRadius)..(coarseBestX + refineRadius)) {
                if (candidateX !in minX..maxX) continue
                val score = scoreAt(candidateX, candidateY)
                if (score > bestScore) {
                    bestScore = score
                    bestX = candidateX
                    bestY = candidateY
                }
            }
        }

        return if (bestScore < 24f) pointer else PointF(bestX.toFloat(), bestY.toFloat())
    }

    private fun edgeMagnitude(bitmap: Bitmap, x: Int, y: Int): Float {
        fun luminance(px: Int, py: Int): Float {
            val pixel = bitmap.getPixel(px, py)
            return Color.red(pixel) * 0.2126f +
                Color.green(pixel) * 0.7152f +
                Color.blue(pixel) * 0.0722f
        }

        val tl = luminance(x - 1, y - 1)
        val tc = luminance(x, y - 1)
        val tr = luminance(x + 1, y - 1)
        val ml = luminance(x - 1, y)
        val mr = luminance(x + 1, y)
        val bl = luminance(x - 1, y + 1)
        val bc = luminance(x, y + 1)
        val br = luminance(x + 1, y + 1)

        val gx = -tl + tr - 2f * ml + 2f * mr - bl + br
        val gy = -tl - 2f * tc - tr + bl + 2f * bc + br
        return hypot(gx, gy) * 0.25f
    }

    private fun pointerCentroid(event: MotionEvent): PointF {
        var x = 0f
        var y = 0f
        for (index in 0 until event.pointerCount) {
            x += event.getX(index)
            y += event.getY(index)
        }
        return PointF(x / event.pointerCount, y / event.pointerCount)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun viewToImage(x: Float, y: Float, source: Bitmap): PointF? {
        if (imageRect.width() <= 0f || imageRect.height() <= 0f) return null
        return PointF(
            ((x - imageRect.left) / imageRect.width() * source.width)
                .coerceIn(0f, source.width.toFloat()),
            ((y - imageRect.top) / imageRect.height() * source.height)
                .coerceIn(0f, source.height.toFloat())
        )
    }

    private fun drawLassoPreview(
        canvas: Canvas,
        path: Path,
        source: Bitmap,
        erase: Boolean
    ) {
        updateImageRect(source)
        val saveCount = canvas.save()
        updateImageClipPath()
        canvas.clipPath(imageClipPath)
        canvas.translate(imageRect.left, imageRect.top)
        canvas.scale(
            imageRect.width() / source.width,
            imageRect.height() / source.height
        )

        // Show the enclosed region while drawing, not only a thin outline.
        // On release the same closed polygon becomes the saved selection mask.
        val previewPath = Path(path).apply { close() }
        val previousWidth = lassoPaint.strokeWidth
        val previousColor = lassoPaint.color
        val previousFillColor = lassoPreviewFillPaint.color
        lassoPreviewFillPaint.color = if (erase) {
            Color.argb(78, 255, 112, 94)
        } else {
            Color.argb(88, 65, 174, 255)
        }
        canvas.drawPath(previewPath, lassoPreviewFillPaint)

        lassoPaint.strokeWidth = 2.6f * source.width / imageRect.width()
        lassoPaint.color = if (erase) Color.rgb(255, 142, 110) else Color.rgb(105, 215, 255)
        canvas.drawPath(path, lassoPaint)
        lassoPreviewFillPaint.color = previousFillColor
        lassoPaint.strokeWidth = previousWidth
        lassoPaint.color = previousColor
        canvas.restoreToCount(saveCount)
    }

    private fun redrawMask() {
        val target = maskCanvas ?: return
        target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        baseMaskBitmap?.takeUnless { it.isRecycled }?.let {
            target.drawBitmap(it, 0f, 0f, null)
        }

        for (action in actions) {
            if (action.clear) {
                target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            } else {
                action.path?.let { path ->
                    target.drawPath(path, if (action.erase) eraseMaskPaint else addMaskPaint)
                }
            }
        }
    }

    fun writeSelectionMask(destination: File) {
        val mask = maskBitmap
            ?: throw IllegalStateException("No selection mask is available.")

        FileOutputStream(destination).use { stream ->
            check(mask.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                "Could not write the selection mask."
            }
            stream.fd.sync()
        }
    }

    fun release() {
        sourceBitmap?.recycle()
        maskBitmap?.recycle()
        baseMaskBitmap?.recycle()
        sourceBitmap = null
        maskBitmap = null
        baseMaskBitmap = null
        maskCanvas = null
        actions.clear()
        currentPath = null
        lastMagneticPoint = null
        zoomScale = 1f
        panX = 0f
        panY = 0f
        transformGestureActive = false
    }
}
