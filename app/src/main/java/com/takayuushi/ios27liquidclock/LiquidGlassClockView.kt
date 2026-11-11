package com.takayuushi.ios27liquidclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * GPU-rendered liquid-glass surface.
 *
 * AGSL distorts a procedural color field and adds animated specular lighting,
 * an inner rim and a crisp optical edge. The actual content behind an overlay
 * is blurred by WindowManager cross-window blur; Android does not expose
 * another app's framebuffer as an AGSL input.
 */
class LiquidGlassClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Choreographer.FrameCallback {

    private val runtimeShader = RuntimeShader(AGSL_SOURCE)
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = runtimeShader
    }

    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()

    private var startFrameNanos = 0L
    private var elapsedSeconds = 0f
    private var pointerX = -1000f
    private var pointerY = -1000f
    private var framePosted = false
    private var inputShaderWidth = -1
    private var inputShaderHeight = -1

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startFrameNanos = 0L
        postFrame()
    }

    override fun onDetachedFromWindow() {
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(this)
            framePosted = false
        }
        super.onDetachedFromWindow()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!isAttachedToWindow || !isShown) return

        if (startFrameNanos == 0L) {
            startFrameNanos = frameTimeNanos
        }

        elapsedSeconds = (frameTimeNanos - startFrameNanos) / 1_000_000_000f
        postInvalidateOnAnimation()
        postFrame()
    }

    private fun postFrame() {
        if (!framePosted && isAttachedToWindow && isShown) {
            Choreographer.getInstance().postFrameCallback(this)
            framePosted = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        if (!canvas.isHardwareAccelerated) {
            drawCanvasFallback(canvas, viewWidth, viewHeight)
            return
        }

        updateBackdropShader(width, height)
        runtimeShader.setFloatUniform("uResolution", viewWidth, viewHeight)
        runtimeShader.setFloatUniform("uTime", elapsedSeconds)
        runtimeShader.setFloatUniform("uPointer", pointerX, pointerY)

        canvas.drawRect(0f, 0f, viewWidth, viewHeight, shaderPaint)
    }

    private fun updateBackdropShader(viewWidth: Int, viewHeight: Int) {
        if (inputShaderWidth == viewWidth && inputShaderHeight == viewHeight) return

        val backdrop = LinearGradient(
            0f,
            0f,
            viewWidth.toFloat(),
            viewHeight.toFloat(),
            intArrayOf(
                Color.rgb(46, 105, 155),
                Color.rgb(104, 84, 168),
                Color.rgb(54, 145, 160),
                Color.rgb(26, 57, 99)
            ),
            floatArrayOf(0f, 0.38f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )

        runtimeShader.setInputShader("uBackdrop", backdrop)
        inputShaderWidth = viewWidth
        inputShaderHeight = viewHeight
    }

    private fun drawCanvasFallback(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float
    ) {
        bounds.set(1f, 1f, viewWidth - 1f, viewHeight - 1f)
        val radius = min(viewWidth, viewHeight) * 0.105f

        fallbackPaint.shader = LinearGradient(
            0f, 0f, viewWidth, viewHeight,
            intArrayOf(
                Color.argb(92, 75, 139, 190),
                Color.argb(76, 113, 91, 175),
                Color.argb(88, 56, 135, 151)
            ),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bounds, radius, radius, fallbackPaint)

        fallbackPaint.shader = null
        fallbackPaint.style = Paint.Style.STROKE
        fallbackPaint.strokeWidth = resources.displayMetrics.density
        fallbackPaint.color = Color.argb(205, 239, 249, 255)
        canvas.drawRoundRect(bounds, radius, radius, fallbackPaint)
        fallbackPaint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                pointerX = event.x
                pointerY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                pointerX = -1000f
                pointerY = -1000f
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

    private companion object {
        val AGSL_SOURCE = """
            uniform float2 uResolution;
            uniform float uTime;
            uniform float2 uPointer;
            uniform shader uBackdrop;

            float roundedBox(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + float2(radius, radius);
                return length(max(q, float2(0.0))) +
                    min(max(q.x, q.y), 0.0) - radius;
            }

            half4 main(float2 fragCoord) {
                float2 center = uResolution * 0.5;
                float2 p = fragCoord - center;
                float2 halfSize = max(center - float2(1.0), float2(1.0));
                float radius = min(uResolution.x, uResolution.y) * 0.105;

                float distance = roundedBox(p, halfSize, radius);
                float shapeMask = 1.0 - smoothstep(-0.8, 0.8, distance);
                float2 normalized = p / halfSize;

                float2 normal = normalize(
                    float2(
                        p.x / max(halfSize.x * halfSize.x, 1.0),
                        p.y / max(halfSize.y * halfSize.y, 1.0)
                    ) + float2(0.00001, 0.00001)
                );

                float waveA = sin(normalized.y * 8.4 + uTime * 0.72);
                float waveB = cos(normalized.x * 6.8 - uTime * 0.51);
                float pointerGlow = exp(-length(fragCoord - uPointer) / 74.0);
                float2 pointerDirection = normalize(
                    fragCoord - uPointer + float2(0.001, 0.001)
                );

                float2 opticalOffset =
                    normal * (waveA * 2.8 + waveB * 1.35) +
                    pointerDirection * pointerGlow * 4.5;

                float2 samplePoint = clamp(
                    fragCoord + opticalOffset,
                    float2(0.0, 0.0),
                    uResolution
                );

                float4 centerSample = uBackdrop.eval(samplePoint);
                float4 redSample = uBackdrop.eval(
                    clamp(samplePoint + float2(1.35, 0.0),
                        float2(0.0, 0.0), uResolution)
                );
                float4 blueSample = uBackdrop.eval(
                    clamp(samplePoint - float2(1.35, 0.0),
                        float2(0.0, 0.0), uResolution)
                );

                float3 refractedColor = float3(
                    redSample.r,
                    centerSample.g,
                    blueSample.b
                );

                float edgeRim = exp(-abs(distance) / 1.55);
                float innerRim = exp(-abs(distance + 10.0) / 6.0);

                float2 lightPosition = float2(
                    0.28 + 0.23 * sin(uTime * 0.43),
                    -0.58 + 0.12 * cos(uTime * 0.31)
                );
                float2 lightDelta =
                    (normalized - lightPosition) * float2(1.65, 3.8);
                float softHighlight = exp(-dot(lightDelta, lightDelta) * 3.4);

                float streakCoordinate =
                    normalized.x * 0.72 +
                    normalized.y * 0.34 +
                    sin(uTime * 0.35) * 0.35;
                float lightStreak = exp(
                    -pow(streakCoordinate * 6.2, 2.0)
                );

                float3 color = mix(
                    float3(0.035, 0.052, 0.083),
                    refractedColor,
                    0.23
                );
                color += float3(0.39, 0.61, 0.86) *
                    (edgeRim * 0.54 + innerRim * 0.13);
                color += float3(0.72, 0.84, 1.0) *
                    (softHighlight * 0.27 + lightStreak * 0.12);
                color += float3(0.16, 0.48, 0.70) *
                    pointerGlow * 0.10;

                float alpha = shapeMask * (
                    0.27 +
                    edgeRim * 0.25 +
                    innerRim * 0.08 +
                    softHighlight * 0.06
                );

                return half4(color, alpha);
            }
        """.trimIndent()
    }
}
