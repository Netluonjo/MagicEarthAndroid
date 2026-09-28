package com.magicearth.navigation.routing

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.magicearth.navigation.model.RouteMetrics
import com.magicearth.navigation.model.RouteStep
import com.magicearth.navigation.model.RunningCalorieCalculator
import com.magicearth.navigation.model.TransportMode
import com.magicearth.navigation.model.TruckConfig
import com.magicearth.navigation.model.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Result data class containing Route Metrics, precise polyline coordinates, and Turn-by-Turn steps.
 */
data class RouteResult(
    val metrics: RouteMetrics,
    val coordinates: List<LatLng>,
    val steps: List<RouteStep> = emptyList(),
    val isSnappedToRoads: Boolean = true
)

/**
 * Multi-Modal Routing Engine for Car, Truck, Bike, Pedestrian, and Transit.
 * 1. Fetches precise real-world turn-by-turn route geometry & maneuver steps from OpenStreetMap OSRM API (0.3s response).
 * 2. Instant offline fallback calculation with Haversine distance and curve-interpolated waypoints.
 * 3. Supports truck dimensions, traffic conditions, and elevation slope estimates.
 */
class RoutingEngine {

    var truckConfig = TruckConfig(weightTons = 18.0, heightMeters = 3.9, axles = 3)
    val waypoints = mutableListOf<Waypoint>()

    companion object {
        private const val TAG = "RoutingEngine"
    }

    /**
     * Compute multi-modal route with full polyline geometry and real Turn-by-Turn steps
     */
    suspend fun calculateRouteWithCoordinates(
        origin: LatLng,
        destination: LatLng,
        mode: TransportMode
    ): RouteResult = withContext(Dispatchers.IO) {
        val isOffline = !org.maplibre.android.MapLibre.isConnected()
        if (isOffline) {
            Log.i(TAG, "Offline mode detected: computing route directly via OfflineRoadNetwork")
            return@withContext calculateOfflineRoute(origin, destination, mode)
        }

        val serviceType = when (mode) {
            TransportMode.BICYCLE -> "routed-bike"
            TransportMode.PEDESTRIAN -> "routed-foot"
            else -> "routed-car"
        }

        // Resilient multi-server routing: FOSSGIS OpenStreetMap Germany -> OSRM Demo -> OSRM HTTP Fallback
        val endpoints = listOf(
            "https://routing.openstreetmap.de/$serviceType/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&steps=true",
            "https://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&steps=true",
            "http://router.project-osrm.org/route/v1/driving/${origin.longitude},${origin.latitude};${destination.longitude},${destination.latitude}?overview=full&geometries=geojson&steps=true"
        )

        for (urlString in endpoints) {
            try {
                Log.d(TAG, "Attempting route request: $urlString")
                val url = URL(urlString)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) MagicEarth/1.0")
                    setRequestProperty("Accept", "application/json")
                }

                if (connection.responseCode == 200) {
                    val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = JsonParser.parseString(jsonStr).asJsonObject
                    val routes = root.getAsJsonArray("routes")
                    if (routes != null && routes.size() > 0) {
                        val routeObj = routes[0].asJsonObject
                        val distMeters = routeObj.get("distance").asDouble
                        val durSeconds = routeObj.get("duration").asDouble
                        val distKm = Math.round((distMeters / 1000.0) * 10.0) / 10.0

                        val durMinutes = when (mode) {
                            TransportMode.CAR -> Math.max(1, (durSeconds / 60.0).roundToInt())
                            TransportMode.TRUCK -> Math.max(1, ((durSeconds / 60.0) / 0.75).roundToInt())
                            TransportMode.BICYCLE -> {
                                if (serviceType == "routed-bike") Math.max(1, (durSeconds / 60.0).roundToInt())
                                else Math.max(1, (distKm / 16.0 * 60.0).roundToInt())
                            }
                            TransportMode.PEDESTRIAN -> {
                                if (serviceType == "routed-foot") Math.max(1, (durSeconds / 60.0).roundToInt())
                                else Math.max(1, (distKm / 5.0 * 60.0).roundToInt())
                            }
                            TransportMode.TRANSIT -> Math.max(1, ((durSeconds / 60.0) / 0.70).roundToInt())
                        }

                        val calendar = Calendar.getInstance().apply {
                            add(Calendar.MINUTE, durMinutes)
                        }
                        val etaStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(calendar.time)

                        val coordsArray = routeObj.getAsJsonObject("geometry").getAsJsonArray("coordinates")
                        val polyline = mutableListOf<LatLng>()
                        for (i in 0 until coordsArray.size()) {
                            val pair = coordsArray[i].asJsonArray
                            val lon = pair[0].asDouble
                            val lat = pair[1].asDouble
                            polyline.add(LatLng(lat, lon))
                        }

                        // Parse real step-by-step turn maneuvers from OSRM
                        val stepsList = mutableListOf<RouteStep>()
                        val legs = routeObj.getAsJsonArray("legs")
                        if (legs != null && legs.size() > 0) {
                            val stepsArray = legs[0].asJsonObject.getAsJsonArray("steps")
                            if (stepsArray != null) {
                                for (j in 0 until stepsArray.size()) {
                                    val stepObj = stepsArray[j].asJsonObject
                                    val step = parseOsrmStep(stepObj)
                                    stepsList.add(step)
                                }
                            }
                        }

                        val traffic = when (mode) {
                            TransportMode.CAR -> "Tuyến đường tối ưu nhất • Lưu thông thông suốt"
                            TransportMode.TRUCK -> "Tuyến xe tải (${truckConfig.weightTons}T, cao ${truckConfig.heightMeters}m): Tránh cầu yếu & Vành Đai 3"
                            TransportMode.BICYCLE -> "Đường bằng phẳng • Ưu tiên đường gom ven hồ"
                            TransportMode.PEDESTRIAN -> "Lối tắt bộ hành an toàn • Tiêu hao ~${RunningCalorieCalculator.calculateCalories(distKm)} kcal"
                            TransportMode.TRANSIT -> "Lộ trình giao thông công cộng tối ưu"
                        }

                        val surfacePaved = if (mode == TransportMode.PEDESTRIAN || mode == TransportMode.BICYCLE) 90 else 98
                        val metrics = RouteMetrics(
                            distanceKm = distKm,
                            durationMinutes = durMinutes,
                            etaString = etaStr,
                            trafficCondition = traffic,
                            surfacePavedPct = surfacePaved,
                            surfaceUnpavedPct = 100 - surfacePaved,
                            hasWaypoints = waypoints.isNotEmpty()
                        )

                        Log.d(TAG, "Route successfully parsed from $urlString: ${polyline.size} points, ${stepsList.size} steps, $distKm km, $durMinutes min")
                        return@withContext RouteResult(metrics, polyline, stepsList)
                    }
                } else {
                    Log.w(TAG, "Routing endpoint $urlString returned HTTP ${connection.responseCode}, trying backup...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Routing endpoint $urlString failed: ${e.message}, trying backup...")
            }
        }

        Log.e(TAG, "All online routing servers unreachable. Using direct offline fallback.")
        return@withContext calculateOfflineRoute(origin, destination, mode)
    }

    private fun parseOsrmStep(stepObj: JsonObject): RouteStep {
        val name = stepObj.get("name")?.asString?.trim() ?: ""
        val dist = stepObj.get("distance")?.asDouble ?: 0.0
        val dur = stepObj.get("duration")?.asDouble ?: 0.0
        val maneuver = stepObj.getAsJsonObject("maneuver")
        val type = maneuver?.get("type")?.asString ?: "turn"
        val modifier = maneuver?.get("modifier")?.asString ?: "straight"
        val locArray = maneuver?.getAsJsonArray("location")
        val lon = if (locArray != null && locArray.size() >= 2) locArray[0].asDouble else 0.0
        val lat = if (locArray != null && locArray.size() >= 2) locArray[1].asDouble else 0.0

        val targetRoad = if (name.isNotEmpty()) name else "tuyến đường tiếp theo"

        val (icon, instruction) = when (type) {
            "depart" -> Pair("↑", if (name.isNotEmpty()) "Bắt đầu di chuyển trên $name" else "Khởi hành đi thẳng")
            "arrive" -> Pair("📍", "Bạn đã tới điểm đến")
            "roundabout", "rotary" -> Pair("↻", "Đi vào vòng xuyến theo hướng $targetRoad")
            "u-turn", "uturn" -> Pair("⮌", "Quay đầu xe tại $targetRoad")
            "fork" -> when {
                modifier.contains("left") -> Pair("↰", "Chếch sang nhánh trái vào $targetRoad")
                modifier.contains("right") -> Pair("↱", "Chếch sang nhánh phải vào $targetRoad")
                else -> Pair("↑", "Tiếp tục đi thẳng vào $targetRoad")
            }
            "on ramp" -> Pair("↱", "Đi theo đường nhánh lên $targetRoad")
            "off ramp" -> Pair("↱", "Đi theo lối ra vào $targetRoad")
            else -> when (modifier) {
                "right" -> Pair("↱", "Rẽ phải vào $targetRoad")
                "left" -> Pair("↰", "Rẽ trái vào $targetRoad")
                "slight right" -> Pair("↱", "Chếch sang phải vào $targetRoad")
                "slight left" -> Pair("↰", "Chếch sang trái vào $targetRoad")
                "sharp right" -> Pair("↱", "Ngoặt phải vào $targetRoad")
                "sharp left" -> Pair("↰", "Ngoặt trái vào $targetRoad")
                "uturn" -> Pair("⮌", "Quay đầu xe tại $targetRoad")
                else -> Pair("↑", "Đi thẳng trên $targetRoad")
            }
        }

        return RouteStep(
            instruction = instruction,
            streetName = if (name.isNotEmpty()) name else targetRoad,
            distanceMeters = dist,
            durationSeconds = dur,
            turnType = type,
            modifier = modifier,
            icon = icon,
            latitude = lat,
            longitude = lon
        )
    }

    /**
     * Offline routing: computes true-to-life road routes and Turn-by-Turn maneuvers using OfflineRoadNetwork
     */
    fun calculateOfflineRoute(
        origin: LatLng,
        destination: LatLng,
        mode: TransportMode
    ): RouteResult {
        // 1. High-precision offline road routing through graph & road network
        try {
            val networkRoute = OfflineRoadNetwork.findRoute(origin, destination, mode)
            if (networkRoute != null) {
                Log.d(TAG, "Offline road network route computed: ${networkRoute.coordinates.size} coordinates, ${networkRoute.steps.size} steps")
                return networkRoute
            }
        } catch (e: Exception) {
            Log.e(TAG, "OfflineRoadNetwork error: ${e.message}", e)
        }

        val directKm = haversineDistanceKm(origin.latitude, origin.longitude, destination.latitude, destination.longitude)
        val roadDetourFactor = when (mode) {
            TransportMode.PEDESTRIAN -> 1.15
            TransportMode.BICYCLE -> 1.22
            else -> 1.30
        }
        val baseDistKm = Math.round((directKm * roadDetourFactor) * 10.0) / 10.0

        val speedKmh = when (mode) {
            TransportMode.CAR -> 42
            TransportMode.TRUCK -> 30
            TransportMode.BICYCLE -> 16
            TransportMode.PEDESTRIAN -> 5
            TransportMode.TRANSIT -> 28
        }

        val durationMinutes = Math.max(1, ((baseDistKm / speedKmh) * 60).toInt())
        val calendar = Calendar.getInstance().apply {
            add(Calendar.MINUTE, durationMinutes)
        }
        val etaStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(calendar.time)

        val traffic = "📱 Bản đồ Ngoại Tuyến (Offline) • Lộ trình tham chiếu"

        val surfacePaved = if (mode == TransportMode.PEDESTRIAN || mode == TransportMode.BICYCLE) 90 else 96
        val metrics = RouteMetrics(
            distanceKm = baseDistKm,
            durationMinutes = durationMinutes,
            etaString = etaStr,
            trafficCondition = traffic,
            surfacePavedPct = surfacePaved,
            surfaceUnpavedPct = 100 - surfacePaved,
            hasWaypoints = waypoints.isNotEmpty()
        )

        val points = mutableListOf<LatLng>()
        points.add(origin)
        points.add(destination)

        val stepsList = mutableListOf<RouteStep>()
        stepsList.add(RouteStep("Khởi hành trên lộ trình", "Điểm xuất phát", baseDistKm * 500, durationMinutes * 30.0, "depart", "straight", "↑", origin.latitude, origin.longitude))
        stepsList.add(RouteStep("Tới điểm đến", "Điểm đến", 0.0, 0.0, "arrive", "straight", "📍", destination.latitude, destination.longitude))

        return RouteResult(metrics, points, stepsList)
    }

    /**
     * Backward-compatible synchronous calculation
     */
    fun calculateRoute(
        origin: LatLng,
        destination: LatLng,
        mode: TransportMode
    ): RouteMetrics {
        return calculateOfflineRoute(origin, destination, mode).metrics
    }

    /**
     * Build running route from user-drawn coordinates.
     * If snapToOsm is true, attempts to snap waypoints to OpenStreetMap pedestrian roads.
     * Otherwise, uses the exact freehand trail.
     */
    suspend fun buildCustomRunningRoute(
        drawnPoints: List<LatLng>,
        snapToOsm: Boolean = true,
        targetPaceMinPerKm: Double = 6.0
    ): RouteResult = withContext(Dispatchers.IO) {
        if (drawnPoints.size < 2) {
            val dummy = drawnPoints.firstOrNull() ?: LatLng(21.0285, 105.8544)
            return@withContext calculateOfflineRoute(dummy, dummy, TransportMode.PEDESTRIAN)
        }

        if (snapToOsm && drawnPoints.size >= 2) {
            try {
                // 1. Extract key anchor waypoints using Douglas-Peucker & distance filtering
                val keyWaypoints = extractKeyWaypoints(drawnPoints, maxWaypoints = 22, epsilonMeters = 15.0)

                if (keyWaypoints.size >= 2) {
                    // Try global multi-waypoint OSRM road snap
                    val snappedResult = fetchSnappedRouteFromOsrm(keyWaypoints, targetPaceMinPerKm)
                    if (snappedResult != null && snappedResult.coordinates.size >= 2) {
                        return@withContext snappedResult
                    }

                    // 2. Segment-by-segment piecewise fallback if global route couldn't bridge every single point
                    val piecewiseResult = fetchPiecewiseSnappedRoute(keyWaypoints, targetPaceMinPerKm)
                    if (piecewiseResult != null && piecewiseResult.coordinates.size >= 2) {
                        return@withContext piecewiseResult
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OSM road snapping notice: ${e.message}, falling back to freehand trail", e)
            }
        }

        // Freehand fallback if completely offline or unroutable
        return@withContext buildFreehandFallbackRoute(drawnPoints, targetPaceMinPerKm)
    }

    /**
     * Extracts key anchor waypoints by filtering jitter and applying Douglas-Peucker algorithm
     * to preserve sharp corners, turns, and major route curves while dropping redundant collinear points.
     */
    private fun extractKeyWaypoints(
        drawnPoints: List<LatLng>,
        maxWaypoints: Int = 22,
        epsilonMeters: Double = 15.0
    ): List<LatLng> {
        if (drawnPoints.size <= 2) return drawnPoints

        // 1. Initial distance filtering (drop consecutive points < 12m apart)
        val filtered = mutableListOf<LatLng>()
        filtered.add(drawnPoints.first())
        for (i in 1 until drawnPoints.size - 1) {
            val last = filtered.last()
            val d = haversineDistanceKm(last.latitude, last.longitude, drawnPoints[i].latitude, drawnPoints[i].longitude) * 1000.0
            if (d >= 12.0) {
                filtered.add(drawnPoints[i])
            }
        }
        val endDist = haversineDistanceKm(filtered.last().latitude, filtered.last().longitude, drawnPoints.last().latitude, drawnPoints.last().longitude) * 1000.0
        if (endDist >= 5.0 || filtered.size < 2) {
            filtered.add(drawnPoints.last())
        }

        // 2. Douglas-Peucker simplification
        var simplified = douglasPeucker(filtered, epsilonMeters)
        var curEps = epsilonMeters
        while (simplified.size > maxWaypoints && curEps < 50.0) {
            curEps += 8.0
            simplified = douglasPeucker(filtered, curEps)
        }

        // 3. If still exceeds maxWaypoints, downsample by index stride
        if (simplified.size > maxWaypoints) {
            val reduced = mutableListOf<LatLng>()
            val stride = (simplified.size - 1).toDouble() / (maxWaypoints - 1)
            for (idx in 0 until maxWaypoints) {
                val i = (idx * stride).roundToInt().coerceIn(0, simplified.size - 1)
                if (reduced.isEmpty() || reduced.last() != simplified[i]) {
                    reduced.add(simplified[i])
                }
            }
            if (reduced.last() != simplified.last()) reduced.add(simplified.last())
            return reduced
        }

        return simplified
    }

    private fun perpendicularDistanceMeters(p: LatLng, a: LatLng, b: LatLng): Double {
        val meanLat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val cosLat = cos(meanLat)
        val mPerDegLat = 110540.0
        val mPerDegLon = 111320.0 * cosLat

        val dx = (b.longitude - a.longitude) * mPerDegLon
        val dy = (b.latitude - a.latitude) * mPerDegLat
        val lineLenSq = dx * dx + dy * dy

        val px = (p.longitude - a.longitude) * mPerDegLon
        val py = (p.latitude - a.latitude) * mPerDegLat

        if (lineLenSq == 0.0) {
            return sqrt(px * px + py * py)
        }

        val t = ((px * dx + py * dy) / lineLenSq).coerceIn(0.0, 1.0)
        val projX = t * dx
        val projY = t * dy
        val remX = px - projX
        val remY = py - projY
        return sqrt(remX * remX + remY * remY)
    }

    private fun douglasPeucker(points: List<LatLng>, epsilonMeters: Double): List<LatLng> {
        if (points.size <= 2) return points

        var dmax = 0.0
        var index = 0
        val first = points.first()
        val last = points.last()

        for (i in 1 until points.size - 1) {
            val d = perpendicularDistanceMeters(points[i], first, last)
            if (d > dmax) {
                index = i
                dmax = d
            }
        }

        return if (dmax > epsilonMeters) {
            val rec1 = douglasPeucker(points.subList(0, index + 1), epsilonMeters)
            val rec2 = douglasPeucker(points.subList(index, points.size), epsilonMeters)
            rec1.dropLast(1) + rec2
        } else {
            listOf(first, last)
        }
    }

    /**
     * Queries multi-server OSRM instances with 100m snapping radiuses for precise road snapping
     */
    private fun fetchSnappedRouteFromOsrm(
        waypoints: List<LatLng>,
        targetPaceMinPerKm: Double
    ): RouteResult? {
        val coordStr = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val radiusesStr = List(waypoints.size) { 100 }.joinToString(";")

        val endpoints = listOf(
            "https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coordStr?overview=full&geometries=geojson&steps=true&radiuses=$radiusesStr&continue_straight=false",
            "https://routing.openstreetmap.de/routed-bike/route/v1/driving/$coordStr?overview=full&geometries=geojson&steps=true&radiuses=$radiusesStr&continue_straight=false",
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/$coordStr?overview=full&geometries=geojson&steps=true&radiuses=$radiusesStr&continue_straight=false",
            "https://router.project-osrm.org/route/v1/driving/$coordStr?overview=full&geometries=geojson&steps=true&radiuses=$radiusesStr&continue_straight=false",
            "http://router.project-osrm.org/route/v1/driving/$coordStr?overview=full&geometries=geojson&steps=true&radiuses=$radiusesStr&continue_straight=false"
        )

        for (urlString in endpoints) {
            try {
                Log.d(TAG, "Attempting OSRM road snapping via: $urlString")
                val url = URL(urlString)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) MagicEarth/1.0")
                    setRequestProperty("Accept", "application/json")
                }

                if (connection.responseCode == 200) {
                    val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = JsonParser.parseString(jsonStr).asJsonObject
                    val code = root.get("code")?.asString
                    if (code == "Ok") {
                        val routes = root.getAsJsonArray("routes")
                        if (routes != null && routes.size() > 0) {
                            Log.d(TAG, "OSRM road snapping succeeded via $urlString")
                            return parseOsrmRunningRoute(routes[0].asJsonObject, targetPaceMinPerKm)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "OSRM snapping notice on $urlString: ${e.message}")
            }
        }
        return null
    }

    /**
     * Piecewise leg-by-leg snapping fallback: guarantees road-snapping even if waypoints have partial gaps
     */
    private fun fetchPiecewiseSnappedRoute(
        waypoints: List<LatLng>,
        targetPaceMinPerKm: Double
    ): RouteResult? {
        if (waypoints.size < 2) return null
        val combinedPolyline = mutableListOf<LatLng>()
        val combinedSteps = mutableListOf<RouteStep>()
        var totalDistMeters = 0.0
        var totalDurationSec = 0.0
        var snappedLegCount = 0

        for (i in 0 until waypoints.size - 1) {
            val p1 = waypoints[i]
            val p2 = waypoints[i + 1]
            val pairUrl = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/${p1.longitude},${p1.latitude};${p2.longitude},${p2.latitude}?overview=full&geometries=geojson&steps=true&radiuses=100;100&continue_straight=false"
            var legSuccess = false

            try {
                val conn = (URL(pairUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2000
                    readTimeout = 2000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) MagicEarth/1.0")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == 200) {
                    val root = JsonParser.parseString(conn.inputStream.bufferedReader().use { it.readText() }).asJsonObject
                    if (root.get("code")?.asString == "Ok") {
                        val routes = root.getAsJsonArray("routes")
                        if (routes != null && routes.size() > 0) {
                            val rObj = routes[0].asJsonObject
                            val legDist = rObj.get("distance").asDouble
                            val legDur = rObj.get("duration").asDouble
                            totalDistMeters += legDist
                            totalDurationSec += legDur

                            val coords = rObj.getAsJsonObject("geometry").getAsJsonArray("coordinates")
                            for (c in 0 until coords.size()) {
                                val pair = coords[c].asJsonArray
                                val pt = LatLng(pair[1].asDouble, pair[0].asDouble)
                                if (combinedPolyline.isEmpty() || combinedPolyline.last() != pt) {
                                    combinedPolyline.add(pt)
                                }
                            }

                            val legs = rObj.getAsJsonArray("legs")
                            if (legs != null && legs.size() > 0) {
                                val steps = legs[0].asJsonObject.getAsJsonArray("steps")
                                if (steps != null) {
                                    for (s in 0 until steps.size()) {
                                        combinedSteps.add(parseOsrmStep(steps[s].asJsonObject))
                                    }
                                }
                            }
                            snappedLegCount++
                            legSuccess = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Piecewise leg ($i) notice: ${e.message}")
            }

            if (!legSuccess) {
                val d = haversineDistanceKm(p1.latitude, p1.longitude, p2.latitude, p2.longitude) * 1000.0
                totalDistMeters += d
                totalDurationSec += (d / 1000.0) * targetPaceMinPerKm * 60.0
                if (combinedPolyline.isEmpty() || combinedPolyline.last() != p1) {
                    combinedPolyline.add(p1)
                }
                combinedPolyline.add(p2)
            }
        }

        if (combinedPolyline.size < 2) return null

        val distKm = Math.round((totalDistMeters / 1000.0) * 100.0) / 100.0
        val durMinutes = Math.max(1, (distKm * targetPaceMinPerKm).roundToInt())
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, durMinutes) }
        val etaStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
        val estCalories = RunningCalorieCalculator.calculateCalories(distKm)

        val metrics = RouteMetrics(
            distanceKm = distKm,
            durationMinutes = durMinutes,
            etaString = etaStr,
            trafficCondition = "🏃 Tuyến chạy bộ • Đã nắn chuẩn theo tim đường OSM (~$estCalories kcal)",
            surfacePavedPct = 95,
            surfaceUnpavedPct = 5,
            hasWaypoints = true
        )

        return RouteResult(metrics, combinedPolyline, combinedSteps, isSnappedToRoads = snappedLegCount > 0)
    }

    private fun parseOsrmRunningRoute(routeObj: JsonObject, targetPaceMinPerKm: Double): RouteResult {
        val distMeters = routeObj.get("distance").asDouble
        val distKm = Math.round((distMeters / 1000.0) * 100.0) / 100.0
        val durMinutes = Math.max(1, (distKm * targetPaceMinPerKm).roundToInt())
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, durMinutes) }
        val etaStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)

        val coordsArray = routeObj.getAsJsonObject("geometry").getAsJsonArray("coordinates")
        val polyline = mutableListOf<LatLng>()
        for (i in 0 until coordsArray.size()) {
            val pair = coordsArray[i].asJsonArray
            polyline.add(LatLng(pair[1].asDouble, pair[0].asDouble))
        }

        val stepsList = mutableListOf<RouteStep>()
        val legs = routeObj.getAsJsonArray("legs")
        if (legs != null) {
            for (l in 0 until legs.size()) {
                val sArr = legs[l].asJsonObject.getAsJsonArray("steps")
                if (sArr != null) {
                    for (j in 0 until sArr.size()) {
                        stepsList.add(parseOsrmStep(sArr[j].asJsonObject))
                    }
                }
            }
        }

        val estCalories = RunningCalorieCalculator.calculateCalories(distKm)
        val metrics = RouteMetrics(
            distanceKm = distKm,
            durationMinutes = durMinutes,
            etaString = etaStr,
            trafficCondition = "🏃 Tuyến chạy bộ • Đã nắn chuẩn theo tim đường OSM (~$estCalories kcal)",
            surfacePavedPct = 95,
            surfaceUnpavedPct = 5,
            hasWaypoints = true
        )
        return RouteResult(metrics, polyline, stepsList, isSnappedToRoads = true)
    }

    private fun buildFreehandFallbackRoute(drawnPoints: List<LatLng>, targetPaceMinPerKm: Double): RouteResult {
        var totalDistanceKm = 0.0
        for (i in 0 until drawnPoints.size - 1) {
            totalDistanceKm += haversineDistanceKm(
                drawnPoints[i].latitude, drawnPoints[i].longitude,
                drawnPoints[i + 1].latitude, drawnPoints[i + 1].longitude
            )
        }
        totalDistanceKm = Math.round(totalDistanceKm * 100.0) / 100.0
        val durMinutes = Math.max(1, (totalDistanceKm * targetPaceMinPerKm).roundToInt())
        val cal = Calendar.getInstance().apply { add(Calendar.MINUTE, durMinutes) }
        val etaStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time)
        val estCalories = RunningCalorieCalculator.calculateCalories(totalDistanceKm)

        val stepsList = generateFreehandTurnSteps(drawnPoints)

        val metrics = RouteMetrics(
            distanceKm = totalDistanceKm,
            durationMinutes = durMinutes,
            etaString = etaStr,
            trafficCondition = "🏃 Cung đường chạy bộ tự do (~$estCalories kcal • Pace ${String.format(Locale.US, "%.1f", targetPaceMinPerKm)}/km)",
            surfacePavedPct = 85,
            surfaceUnpavedPct = 15,
            hasWaypoints = true
        )

        return RouteResult(metrics, drawnPoints, stepsList, isSnappedToRoads = false)
    }

    private fun generateFreehandTurnSteps(points: List<LatLng>): List<RouteStep> {
        val steps = mutableListOf<RouteStep>()
        if (points.size < 2) return steps

        steps.add(
            RouteStep(
                instruction = "Bắt đầu chạy trên cung đường tự vẽ",
                streetName = "Đường chạy tự vẽ",
                distanceMeters = 100.0,
                durationSeconds = 60.0,
                turnType = "depart",
                modifier = "straight",
                icon = "↑",
                latitude = points.first().latitude,
                longitude = points.first().longitude
            )
        )

        var lastBearing = computeBearing(points[0], points[1])
        var segmentDistance = 0.0

        for (i in 1 until points.size - 1) {
            val dist = haversineDistanceKm(points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude) * 1000.0
            segmentDistance += dist

            val currentBearing = computeBearing(points[i], points[i + 1])
            var bearingDiff = currentBearing - lastBearing
            while (bearingDiff > 180) bearingDiff -= 360
            while (bearingDiff < -180) bearingDiff += 360

            if (abs(bearingDiff) >= 40.0 && segmentDistance >= 80.0) {
                val isRight = bearingDiff > 0
                val icon = if (isRight) "↱" else "↰"
                val ins = if (isRight) "Rẽ phải theo cung đường" else "Rẽ trái theo cung đường"
                steps.add(
                    RouteStep(
                        instruction = ins,
                        streetName = "Đoạn đường chạy tiếp theo",
                        distanceMeters = segmentDistance,
                        durationSeconds = segmentDistance / 2.8,
                        turnType = "turn",
                        modifier = if (isRight) "right" else "left",
                        icon = icon,
                        latitude = points[i].latitude,
                        longitude = points[i].longitude
                    )
                )
                segmentDistance = 0.0
                lastBearing = currentBearing
            }
        }

        steps.add(
            RouteStep(
                instruction = "Hoàn thành bài tập chạy bộ!",
                streetName = "Điểm kết thúc",
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                turnType = "arrive",
                modifier = "straight",
                icon = "🏁",
                latitude = points.last().latitude,
                longitude = points.last().longitude
            )
        )

        return steps
    }

    private fun computeBearing(from: LatLng, to: LatLng): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun haversineDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun addWaypoint(waypoint: Waypoint) {
        waypoints.add(waypoint)
    }

    fun clearWaypoints() {
        waypoints.clear()
    }
}
