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
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Paint-to-select foreground editor.
 *
 * The user paints the exact pixels that should be composited above the clock.
 * All strokes are stored in source-image coordinates, so the saved cut-out
 * remains aligned when the live wallpaper applies its center-crop transform.
 */
class MaskDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class BrushStroke(
        val path: Path,
        val start: PointF,
        val erase: Boolean,
        val width: Float
    )

    private var sourceBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private val strokes = mutableListOf<BrushStroke>()
    private var currentStroke: BrushStroke? = null
    private var eraseMode = false
    private var brushSizeDp = 64f
    private val imageRect = RectF()

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(
            Color.argb(176, 38, 190, 255),
            PorterDuff.Mode.SRC_IN
        )
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private val exportMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    fun loadImage(sourceFile: File, existingManualCutout: File) {
        release()

        sourceBitmap = decodeImage(sourceFile)
            ?: throw IllegalArgumentException("Could not decode the selected wallpaper.")

        val source = requireNotNull(sourceBitmap)
        maskBitmap = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )
        maskCanvas = Canvas(requireNotNull(maskBitmap)).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        }

        if (existingManualCutout.exists() && existingManualCutout.length() > 0L) {
            loadExistingCutout(existingManualCutout, source.width, source.height)
        }

        invalidate()
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
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

    private fun loadExistingCutout(file: File, targetWidth: Int, targetHeight: Int) {
        val existing = decodeImage(file) ?: return
        val mask = maskBitmap ?: run {
            existing.recycle()
            return
        }

        val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        }
        val scaled = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        maskCanvas?.drawBitmap(existing, null, scaled, copyPaint)
        existing.recycle()
    }

    fun setEraseMode(erase: Boolean) {
        eraseMode = erase
    }

    fun setBrushSizeDp(size: Float) {
        brushSizeDp = size.coerceIn(10f, 180f)
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            currentStroke = null
            redrawMask()
            invalidate()
        }
    }

    fun clearSelection() {
        strokes.clear()
        currentStroke = null
        maskBitmap?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(7, 11, 21))

        val source = sourceBitmap ?: return
        updateImageRect(source)

        canvas.drawBitmap(source, null, imageRect, imagePaint)
        maskBitmap?.let { mask ->
            canvas.drawBitmap(mask, null, imageRect, overlayPaint)
        }

        currentStroke?.let { stroke ->
            drawStrokePreview(canvas, stroke)
        }

        if (isEnabled) {
            val brushRadiusPx = brushSizeDp * resources.displayMetrics.density / 2f
            cursorPaint.color = if (eraseMode) Color.rgb(255, 178, 84) else Color.WHITE
            canvas.drawCircle(lastTouchX, lastTouchY, brushRadiusPx, cursorPaint)
        }
    }

    private var lastTouchX = -1000f
    private var lastTouchY = -1000f

    private fun updateImageRect(bitmap: Bitmap) {
        val availableWidth = width.toFloat()
        val availableHeight = height.toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return

        val scale = min(
            availableWidth / bitmap.width,
            availableHeight / bitmap.height
        )
        val renderedWidth = bitmap.width * scale
        val renderedHeight = bitmap.height * scale
        val left = (availableWidth - renderedWidth) / 2f
        val top = (availableHeight - renderedHeight) / 2f
        imageRect.set(left, top, left + renderedWidth, top + renderedHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val source = sourceBitmap ?: return false
        updateImageRect(source)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false

                val point = viewToImage(event.x, event.y, source) ?: return false
                val path = Path().apply { moveTo(point.x, point.y) }
                val scale = imageRect.width() / source.width
                val widthInImage = brushSizeDp *
                    resources.displayMetrics.density / scale

                currentStroke = BrushStroke(
                    path = path,
                    start = point,
                    erase = eraseMode,
                    width = widthInImage
                )
                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = currentStroke ?: return false
                val point = viewToImage(event.x, event.y, source) ?: return true
                stroke.path.lineTo(point.x, point.y)
                lastTouchX = event.x
                lastTouchY = event.y
                redrawMask()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                currentStroke?.let { stroke ->
                    strokes.add(stroke)
                    currentStroke = null
                    redrawMask()
                }
                lastTouchX = -1000f
                lastTouchY = -1000f
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                performClick()
                return true
            }
        }
        return true
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

    private fun drawStrokePreview(canvas: Canvas, stroke: BrushStroke) {
        val source = sourceBitmap ?: return
        updateImageRect(source)

        val save = canvas.save()
        canvas.clipRect(imageRect)
        canvas.translate(imageRect.left, imageRect.top)
        canvas.scale(
            imageRect.width() / source.width,
            imageRect.height() / source.height
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (stroke.erase) Color.rgb(255, 178, 84) else Color.rgb(38, 190, 255)
            style = Paint.Style.STROKE
            strokeWidth = stroke.width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            alpha = 190
        }
        canvas.drawPath(stroke.path, paint)
        canvas.restoreToCount(save)
    }

    private fun redrawMask() {
        val target = maskCanvas ?: return
        val source = sourceBitmap ?: return

        target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        for (stroke in strokes + listOfNotNull(currentStroke)) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = stroke.width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (stroke.erase) {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }

            target.drawCircle(
                stroke.start.x,
                stroke.start.y,
                stroke.width / 2f,
                if (stroke.erase) erasePaint else fillPaint
            )
            target.drawPath(stroke.path, paint)
            paint.xfermode = null
        }
    }

    fun writeForegroundCutout(destination: File) {
        val source = sourceBitmap ?: throw IllegalStateException("No wallpaper is loaded.")
        val mask = maskBitmap ?: throw IllegalStateException("No manual mask is available.")

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Could not allocate the foreground image.")

        try {
            Canvas(output).drawBitmap(mask, 0f, 0f, exportMaskPaint)
            FileOutputStream(destination).use { stream ->
                check(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Could not write the foreground mask image."
                }
                stream.fd.sync()
            }
        } finally {
            output.recycle()
        }
    }

    fun release() {
        sourceBitmap?.recycle()
        maskBitmap?.recycle()
        sourceBitmap = null
        maskBitmap = null
        maskCanvas = null
        strokes.clear()
        currentStroke = null
    }
}
