package com.magicearth.navigation.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Visual Lane Guidance View (Hỗ trợ làn đường)
 * Draws forward, left, and right lane arrows with active/inactive indicators.
 */
class LaneAssistanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#10B981") // Safety Green
        style = Paint.Style.FILL
    }

    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155") // Gray
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Default 4 lanes: Left (disabled), Straight (Active), Straight (Active), Right (Disabled)
    var laneStates = booleanArrayOf(false, true, true, false)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val laneCount = laneStates.size
        val laneWidth = (w - (laneCount - 1) * 16f) / laneCount

        for (i in 0 until laneCount) {
            val left = i * (laneWidth + 16f)
            val rect = RectF(left, 4f, left + laneWidth, h - 4f)
            val paint = if (laneStates[i]) activePaint else inactivePaint

            canvas.drawRoundRect(rect, 10f, 10f, paint)

            val arrowChar = when (i) {
                0 -> "↰"
                laneCount - 1 -> "↱"
                else -> "↑"
            }
            canvas.drawText(arrowChar, rect.centerX(), rect.centerY() + 11f, textPaint)
        }
    }
}
