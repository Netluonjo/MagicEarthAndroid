package com.magicearth.navigation.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Custom View that renders the SVG-like Elevation Profile curve (Trắc dọc cao độ)
 * directly using native Android Canvas and Hardware Acceleration.
 */
class ElevationProfileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9F7AEA")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val path = Path()
    private val elevationPoints = floatArrayOf(
        14f, 16f, 19f, 25f, 32f, 42f, 38f, 30f, 22f, 18f, 15f, 21f, 28f, 35f, 24f, 16f
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val minVal = 10f
        val maxVal = 45f

        path.reset()
        path.moveTo(0f, h)

        val stepX = w / (elevationPoints.size - 1)
        for (i in elevationPoints.indices) {
            val x = i * stepX
            val normalized = (elevationPoints[i] - minVal) / (maxVal - minVal)
            val y = h - (normalized * (h - 14f) + 4f)
            if (i == 0) {
                path.lineTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw Stroke line
        canvas.drawPath(path, linePaint)

        // Close for gradient area fill
        path.lineTo(w, h)
        path.close()

        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#807928CA"),
            Color.parseColor("#087928CA"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, fillPaint)
    }
}
