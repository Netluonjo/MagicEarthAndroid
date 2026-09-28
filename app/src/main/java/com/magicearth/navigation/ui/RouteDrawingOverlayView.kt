package com.magicearth.navigation.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.*

/**
 * Interactive touch overlay view that allows users to freely draw
 * custom running / jogging trails directly on top of the MapLibre map.
 * Converts screen strokes to real geographic LatLng coordinates.
 */
class RouteDrawingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cameraMoveListener: MapLibreMap.OnCameraMoveListener? = null

    var map: MapLibreMap? = null
        set(value) {
            field = value
            cameraMoveListener = MapLibreMap.OnCameraMoveListener {
                invalidate()
            }
            cameraMoveListener?.let { value?.addOnCameraMoveListener(it) }
        }

    var isDrawingEnabled: Boolean = false
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            if (!value) {
                clear()
            }
        }

    /**
     * Controls whether 1-finger touches are captured for drawing
     * or passed through to the map for smooth panning / swiping / inspecting.
     */
    var isTouchDrawingActive: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var onRouteUpdated: ((points: List<LatLng>, distanceKm: Double) -> Unit)? = null
    var onDrawingStrokeFinished: ((points: List<LatLng>) -> Unit)? = null
    var onModeChanged: ((isDrawing: Boolean) -> Unit)? = null

    // Multi-stroke storage for undo support
    private val strokes = mutableListOf<MutableList<LatLng>>()
    private val allLatLngPoints = mutableListOf<LatLng>()

    // Drawing paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4076FF03") // Neon Lime translucent glow
        style = Paint.Style.STROKE
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#76FF03") // Vibrant Neon Lime
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val startMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Cyan start dot
        style = Paint.Style.FILL
    }

    private val endMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3D00") // Orange-Red end dot
        style = Paint.Style.FILL
    }

    private val markerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Current active finger stroke path
    private val currentPath = Path()
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val currentStrokeLatLng = mutableListOf<LatLng>()

    init {
        visibility = GONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDrawingEnabled || map == null) return false

        // In inspection / pan mode: pass ALL touches directly to the map!
        if (!isTouchDrawingActive) {
            return false
        }

        // Allow multi-finger gestures (pinch zoom, two-finger pan/rotate) to pass to map
        if (event.pointerCount >= 2) {
            return false
        }

        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                currentPath.reset()
                currentPath.moveTo(x, y)
                lastTouchX = x
                lastTouchY = y
                currentStrokeLatLng.clear()

                val latLng = map?.projection?.fromScreenLocation(PointF(x, y))
                if (latLng != null) {
                    currentStrokeLatLng.add(latLng)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(x - lastTouchX)
                val dy = abs(y - lastTouchY)

                // Minimum threshold (8px) for smooth Bezier curve and performance
                if (dx >= 8f || dy >= 8f) {
                    currentPath.quadTo(lastTouchX, lastTouchY, (x + lastTouchX) / 2, (y + lastTouchY) / 2)
                    lastTouchX = x
                    lastTouchY = y

                    val latLng = map?.projection?.fromScreenLocation(PointF(x, y))
                    if (latLng != null) {
                        currentStrokeLatLng.add(latLng)
                    }
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                currentPath.lineTo(x, y)
                val latLng = map?.projection?.fromScreenLocation(PointF(x, y))
                if (latLng != null) {
                    currentStrokeLatLng.add(latLng)
                }

                if (currentStrokeLatLng.isNotEmpty()) {
                    strokes.add(ArrayList(currentStrokeLatLng))
                    rebuildAllPoints()
                }

                currentPath.reset()
                currentStrokeLatLng.clear()
                invalidate()

                if (allLatLngPoints.size >= 2) {
                    // Automatically switch to Inspection / Pan mode so user can swipe with 1 finger to view!
                    isTouchDrawingActive = false
                    onModeChanged?.invoke(false)
                    onDrawingStrokeFinished?.invoke(ArrayList(allLatLngPoints))
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                currentPath.reset()
                currentStrokeLatLng.clear()
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = map ?: return

        // 1. Draw all committed strokes by re-projecting LatLng to screen coordinates
        if (allLatLngPoints.size >= 2) {
            val committedPath = Path()
            var isFirst = true

            for (pt in allLatLngPoints) {
                val screenPt = m.projection.toScreenLocation(pt)
                if (isFirst) {
                    committedPath.moveTo(screenPt.x, screenPt.y)
                    isFirst = false
                } else {
                    committedPath.lineTo(screenPt.x, screenPt.y)
                }
            }

            canvas.drawPath(committedPath, glowPaint)
            canvas.drawPath(committedPath, linePaint)

            // Start marker
            val startScreen = m.projection.toScreenLocation(allLatLngPoints.first())
            canvas.drawCircle(startScreen.x, startScreen.y, 16f, startMarkerPaint)
            canvas.drawCircle(startScreen.x, startScreen.y, 16f, markerBorderPaint)

            // End marker
            val endScreen = m.projection.toScreenLocation(allLatLngPoints.last())
            canvas.drawCircle(endScreen.x, endScreen.y, 16f, endMarkerPaint)
            canvas.drawCircle(endScreen.x, endScreen.y, 16f, markerBorderPaint)
        }

        // 2. Draw active finger stroke
        if (!currentPath.isEmpty) {
            canvas.drawPath(currentPath, glowPaint)
            canvas.drawPath(currentPath, linePaint)
        }
    }

    /**
     * Undo last drawn stroke
     */
    fun undoLastStroke() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            rebuildAllPoints()
            invalidate()
        }
    }

    /**
     * Clear all drawn points
     */
    fun clear() {
        strokes.clear()
        allLatLngPoints.clear()
        currentPath.reset()
        currentStrokeLatLng.clear()
        isTouchDrawingActive = true
        onModeChanged?.invoke(true)
        invalidate()
        notifyRouteUpdated()
    }

    /**
     * Close the running loop: connects current end point back to start point
     */
    fun closeLoop() {
        if (allLatLngPoints.size >= 3) {
            val first = allLatLngPoints.first()
            val last = allLatLngPoints.last()
            val dist = calculateDistanceKm(last.latitude, last.longitude, first.latitude, first.longitude)
            if (dist > 0.01) { // more than 10 meters
                val loopStroke = mutableListOf(last, first)
                strokes.add(loopStroke)
                rebuildAllPoints()
                invalidate()
            }
        }
    }

    /**
     * Replace points (e.g. after snapping to OSM roads)
     */
    fun setPoints(newPoints: List<LatLng>) {
        strokes.clear()
        if (newPoints.isNotEmpty()) {
            strokes.add(ArrayList(newPoints))
        }
        allLatLngPoints.clear()
        allLatLngPoints.addAll(newPoints)
        invalidate()
    }

    fun getAllPoints(): List<LatLng> = ArrayList(allLatLngPoints)

    private fun rebuildAllPoints() {
        allLatLngPoints.clear()
        for (stroke in strokes) {
            allLatLngPoints.addAll(stroke)
        }
        notifyRouteUpdated()
    }

    private fun notifyRouteUpdated() {
        val totalKm = calculateTotalDistanceKm(allLatLngPoints)
        onRouteUpdated?.invoke(ArrayList(allLatLngPoints), totalKm)
    }

    companion object {
        fun calculateTotalDistanceKm(points: List<LatLng>): Double {
            if (points.size < 2) return 0.0
            var total = 0.0
            for (i in 0 until points.size - 1) {
                total += calculateDistanceKm(
                    points[i].latitude, points[i].longitude,
                    points[i + 1].latitude, points[i + 1].longitude
                )
            }
            return Math.round(total * 100.0) / 100.0
        }

        fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return r * c
        }
    }
}
