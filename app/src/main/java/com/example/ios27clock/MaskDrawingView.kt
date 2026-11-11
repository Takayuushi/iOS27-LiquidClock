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
import android.graphics.RenderEffect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Foreground mask editor with brush and polygon-lasso selection.
 *
 * The whole wallpaper always remains visible in the editor. The image uses
 * the same center-crop transform as the live wallpaper renderer, so the mask
 * lines up exactly when exported and composited above the clock.
 */
class MaskDrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class SelectionAction(
        val path: Path? = null,
        val start: PointF? = null,
        val erase: Boolean = false,
        val width: Float = 0f,
        val lasso: Boolean = false,
        val clear: Boolean = false
    )

    private var sourceBitmap: Bitmap? = null
    private var maskBitmap: Bitmap? = null
    private var baseMaskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null

    private val actions = mutableListOf<SelectionAction>()
    private var currentAction: SelectionAction? = null
    private var eraseMode = false
    private var lassoMode = false
    private var brushSizeDp = 64f
    private val imageRect = RectF()
    private val coverRect = RectF()

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ambientPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 150
        renderEffect = RenderEffect.createBlurEffect(
            30f,
            30f,
            android.graphics.Shader.TileMode.CLAMP
        )
    }
    private val ambientShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(58, 8, 14, 24)
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(
            Color.argb(186, 30, 170, 255),
            PorterDuff.Mode.SRC_IN
        )
    }

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(96, 218, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        setShadowLayer(3f, 0f, 1f, Color.BLACK)
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

        baseMaskBitmap = requireNotNull(maskBitmap).copy(Bitmap.Config.ARGB_8888, true)
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
        try {
            val copyPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            }
            val target = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
            maskCanvas?.drawBitmap(existing, null, target, copyPaint)
        } finally {
            existing.recycle()
        }
    }

    fun setEraseMode(erase: Boolean) {
        eraseMode = erase
        invalidate()
    }

    fun setLassoMode(enabled: Boolean) {
        lassoMode = enabled
        invalidate()
    }

    fun isLassoMode(): Boolean = lassoMode

    fun setBrushSizeDp(size: Float) {
        brushSizeDp = size.coerceIn(4f, 180f)
        invalidate()
    }

    fun undo() {
        if (actions.isNotEmpty()) {
            actions.removeAt(actions.lastIndex)
            currentAction = null
            redrawMask()
            invalidate()
        }
    }

    fun clearSelection() {
        currentAction = null
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

        // Fill unused editor space with a blurred, dimmed extension of the same
        // wallpaper, then show the entire original image sharply in fit-center.
        // This avoids black bars while keeping the complete building visible for
        // precise lasso tracing. Selection coordinates remain in source pixels.
        updateImageRect(source)
        updateCoverRect(source)
        canvas.drawBitmap(source, null, coverRect, ambientPaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ambientShadePaint)
        canvas.drawBitmap(source, null, imageRect, imagePaint)

        maskBitmap?.let { mask ->
            canvas.drawBitmap(mask, null, imageRect, overlayPaint)
        }

        currentAction?.takeIf { it.lasso }?.let { drawLassoPreview(canvas, it) }

        if (!lassoMode && lastTouchX >= 0f && lastTouchY >= 0f) {
            val brushRadiusPx = brushSizeDp * resources.displayMetrics.density / 2f
            cursorPaint.color = if (eraseMode) {
                Color.rgb(255, 178, 84)
            } else {
                Color.WHITE
            }
            canvas.drawCircle(lastTouchX, lastTouchY, brushRadiusPx, cursorPaint)
        }
    }

    private var lastTouchX = -1f
    private var lastTouchY = -1f

    private fun updateImageRect(bitmap: Bitmap) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        // Fit-center the complete source image. All editing operations use
        // these source-image coordinates, not the wallpaper's later crop.
        val scale = min(
            viewWidth / bitmap.width,
            viewHeight / bitmap.height
        )
        val renderedWidth = bitmap.width * scale
        val renderedHeight = bitmap.height * scale
        val left = (viewWidth - renderedWidth) / 2f
        val top = (viewHeight - renderedHeight) / 2f
        imageRect.set(left, top, left + renderedWidth, top + renderedHeight)
    }

    private fun updateCoverRect(bitmap: Bitmap) {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val scale = max(
            viewWidth / bitmap.width,
            viewHeight / bitmap.height
        )
        val renderedWidth = bitmap.width * scale
        val renderedHeight = bitmap.height * scale
        val left = (viewWidth - renderedWidth) / 2f
        val top = (viewHeight - renderedHeight) / 2f
        coverRect.set(left, top, left + renderedWidth, top + renderedHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val source = sourceBitmap ?: return false
        updateImageRect(source)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false

                val point = viewToImage(event.x, event.y, source) ?: return false
                val path = Path().apply { moveTo(point.x, point.y) }

                if (lassoMode) {
                    currentAction = SelectionAction(
                        path = path,
                        lasso = true
                    )
                } else {
                    val scale = imageRect.width() / source.width
                    val widthInImage = brushSizeDp *
                        resources.displayMetrics.density / scale
                    currentAction = SelectionAction(
                        path = path,
                        start = point,
                        erase = eraseMode,
                        width = widthInImage
                    )
                }

                lastTouchX = event.x
                lastTouchY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val action = currentAction ?: return false
                val point = viewToImage(event.x, event.y, source) ?: return true
                action.path?.lineTo(point.x, point.y)
                lastTouchX = event.x
                lastTouchY = event.y
                if (!action.lasso) redrawMask()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                currentAction?.let { action ->
                    if (action.lasso) {
                        action.path?.close()
                    }
                    actions.add(
                        action.copy(
                            path = action.path?.let(::Path),
                            start = action.start?.let { PointF(it.x, it.y) }
                        )
                    )
                    currentAction = null
                    redrawMask()
                }

                lastTouchX = -1f
                lastTouchY = -1f
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

    private fun drawLassoPreview(canvas: Canvas, action: SelectionAction) {
        val path = action.path ?: return
        val source = sourceBitmap ?: return
        updateImageRect(source)

        val save = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(imageRect.left, imageRect.top)
        canvas.scale(
            imageRect.width() / source.width,
            imageRect.height() / source.height
        )
        canvas.drawPath(path, lassoPaint)

        canvas.restoreToCount(save)
    }

    private fun redrawMask() {
        val target = maskCanvas ?: return
        val base = baseMaskBitmap

        target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (base != null && !base.isRecycled) {
            target.drawBitmap(base, 0f, 0f, null)
        }

        for (action in actions + listOfNotNull(currentAction?.takeUnless { it.lasso })) {
            if (action.clear) {
                target.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                continue
            }

            val path = action.path ?: continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = if (action.lasso) Paint.Style.FILL else Paint.Style.STROKE
                strokeWidth = action.width
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                if (action.erase) {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }

            if (!action.lasso) {
                action.start?.let { start ->
                    target.drawCircle(
                        start.x,
                        start.y,
                        action.width / 2f,
                        paint
                    )
                }
            }
            target.drawPath(path, paint)
            paint.xfermode = null
        }
    }

    fun writeForegroundCutout(destination: File) {
        val source = sourceBitmap
            ?: throw IllegalStateException("No wallpaper is loaded.")
        val mask = maskBitmap
            ?: throw IllegalStateException("No manual mask is available.")

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("Could not allocate the foreground image.")

        try {
            // DST_IN multiplies the wallpaper alpha by the selection alpha.
            // Pixels outside the lasso/brush selection remain transparent; the
            // live wallpaper's original background therefore stays visible.
            Canvas(output).drawBitmap(mask, 0f, 0f, exportMaskPaint)
            FileOutputStream(destination).use { stream ->
                check(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Could not write the foreground image."
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
        baseMaskBitmap?.recycle()
        sourceBitmap = null
        maskBitmap = null
        baseMaskBitmap = null
        maskCanvas = null
        actions.clear()
        currentAction = null
    }
}
