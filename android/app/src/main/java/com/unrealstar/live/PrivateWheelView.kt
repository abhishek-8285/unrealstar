package com.unrealstar.live

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * RangLive-inspired 8-Sector Lucky Wheel Pan.
 * Reconstructed from decompiled com.melot.meshow.room.view.PrivateWheelView
 * Uses physics formula: angle = 1440 - (360 / 8) * sector
 */
class PrivateWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val sectorColors = intArrayOf(
        Color.parseColor("#4A154B"), // Sector 0: 0x No Luck
        Color.parseColor("#E50914"), // Sector 1: 1x Return
        Color.parseColor("#FF9900"), // Sector 2: 2x Double
        Color.parseColor("#00A86B"), // Sector 3: 3x Spark
        Color.parseColor("#0070BA"), // Sector 4: 5x Fortune
        Color.parseColor("#8E44AD"), // Sector 5: 10x Jackpot
        Color.parseColor("#D4AF37"), // Sector 6: 20x Taj Gold
        Color.parseColor("#FF007F")  // Sector 7: 50x Crown
    )

    private val sectorLabels = arrayOf(
        "0x", "1x", "2x", "3x", "5x", "10x", "20x", "50x"
    )

    private val sectorSubLabels = arrayOf(
        "Miss", "Return", "2X", "Spark", "Mega", "Jackpot", "Taj Gold", "CROWN"
    )

    private val sectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 36f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#80000000"))
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE082")
        textAlign = Paint.Align.CENTER
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        setShadowLayer(2f, 0f, 1f, Color.parseColor("#80000000"))
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700") // Gold border
        style = Paint.Style.STROKE
        strokeWidth = 14f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 0f, Color.YELLOW)
    }

    private val centerCapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.FILL
    }

    private val centerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A24")
        style = Paint.Style.FILL
    }

    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744") // Bright Red pointer
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 4f, Color.parseColor("#80000000"))
    }

    private val rectF = RectF()
    private var currentRotationDeg: Float = 0f
    private var animator: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) - 24f
        if (radius <= 0) return

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Draw rotated wheel sectors
        canvas.save()
        canvas.rotate(currentRotationDeg, cx, cy)

        val sweepAngle = 360f / sectorColors.size
        for (i in sectorColors.indices) {
            val startAngle = i * sweepAngle - 90f // Start from top
            sectorPaint.color = sectorColors[i]
            canvas.drawArc(rectF, startAngle, sweepAngle, true, sectorPaint)

            // Draw sector text
            canvas.save()
            val textAngle = startAngle + sweepAngle / 2f
            canvas.rotate(textAngle + 90f, cx, cy)

            // Main multiplier
            val textY = cy - radius * 0.72f
            canvas.drawText(sectorLabels[i], cx, textY, textPaint)

            // Subtitle
            val subTextY = cy - radius * 0.55f
            canvas.drawText(sectorSubLabels[i], cx, subTextY, subTextPaint)

            canvas.restore()
        }

        // Draw gold studs around perimeter
        val numDots = 16
        for (d in 0 until numDots) {
            val angleRad = Math.toRadians((d * (360.0 / numDots)).toDouble())
            val dx = cx + (radius + 7f) * cos(angleRad).toFloat()
            val dy = cy + (radius + 7f) * sin(angleRad).toFloat()
            canvas.drawCircle(dx, dy, 6f, dotPaint)
        }

        canvas.restore()

        // Outer gold border ring
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // Center hub
        canvas.drawCircle(cx, cy, radius * 0.22f, centerCapPaint)
        canvas.drawCircle(cx, cy, radius * 0.18f, centerInnerPaint)

        // Top pointer (Stationary at 12 o'clock pointing inward)
        val pointerPath = Path().apply {
            moveTo(cx, cy - radius + 28f)
            lineTo(cx - 20f, cy - radius - 16f)
            lineTo(cx + 20f, cy - radius - 16f)
            close()
        }
        canvas.drawPath(pointerPath, pointerPaint)
    }

    /**
     * Spins the wheel to the target angle calculated by server-authoritative physics:
     * theta = 1440 - (360 / 8) * sector
     */
    fun spinToAngle(targetAngleDeg: Float, onComplete: () -> Unit) {
        animator?.cancel()

        val startAngle = currentRotationDeg % 360f
        // Add full rotations so animation always spins forward smoothly
        val finalAngle = targetAngleDeg

        animator = ValueAnimator.ofFloat(startAngle, finalAngle).apply {
            duration = 3800L
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { va ->
                currentRotationDeg = va.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentRotationDeg = finalAngle % 360f
                    onComplete()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
