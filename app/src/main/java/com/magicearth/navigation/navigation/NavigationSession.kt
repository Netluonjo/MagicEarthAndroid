package com.magicearth.navigation.navigation

import android.location.Location
import com.magicearth.navigation.map.CommunityIncidentRepository
import com.magicearth.navigation.map.TrafficCameraRepository
import com.magicearth.navigation.model.*
import com.magicearth.navigation.routing.RoutingEngine
import com.magicearth.navigation.traffic.LiveTrafficEngine
import com.magicearth.navigation.weather.WeatherRouteService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Manages active Turn-by-Turn in-drive navigation session.
 * Features:
 * 1. Live GPS Telemetry (clamped to 0 km/h when stationary).
 * 2. Real-time Traffic Camera & Speed Trap Distance Calculation & Voice Chime.
 * 3. Dynamic Road Speed Limits & Audible Overspeed Alarm (cooldown 15s).
 * 4. Realistic Signpost Rendering Updates (Expressway Green vs National Blue & Exit Badges).
 * 5. Natural Voice Turn-by-Turn with Street Name Pronunciation.
 */
class NavigationSession(
    private val voiceManager: VoiceGuidanceManager,
    private val listener: NavigationListener
) {
    interface NavigationListener {
        fun onManeuverUpdated(maneuver: Maneuver)
        fun onSpeedUpdated(speedKmh: Int, speedLimit: Int, isOverspeed: Boolean)
        fun onRemainingUpdated(distanceRemKm: Double, etaString: String)
        fun onCameraAlert(camera: TrafficCamera, distanceMeters: Int)
        fun onCameraCleared()
        fun onSignpostUpdated(signpost: SignpostData)
        fun onNavigationFinished()
        fun onIncidentAlert(incident: CommunityIncident, distanceMeters: Int) {}
        fun onIncidentCleared() {}
        fun onWeatherAlert(weather: RouteWeatherSegment) {}
        fun onWeatherCleared() {}
        fun onAlternativeRouteAvailable(suggestion: AlternativeRouteSuggestion) {}
        fun onVehiclePositionUpdated(lat: Double, lon: Double, bearing: Float) {}
        fun onRunnerMetricsUpdated(
            distanceRunKm: Double,
            caloriesBurned: Int,
            paceMinPerKm: Double,
            durationSeconds: Long
        ) {}
    }

    var isRunning = false
        private set

    var destination: LatLng? = null
        private set
    var destinationName: String = "Điểm đến"
        private set

    var currentSpeedLimit = 60
        private set

    private var lastDistanceMeters: Float = -1f
    private var lastSpokenThreshold = -1

    // Camera Alert state
    private var activeCameraId: String? = null
    private var lastCameraVoiceTime: Long = 0L

    // Overspeed Audio Alert cooldown state
    private var lastOverspeedVoiceTime: Long = 0L

    // Community Incident State
    private var activeIncidentId: String? = null
    private var lastIncidentVoiceTime: Long = 0L

    // Weather along route state
    private var activeWeatherCondition: WeatherConditionType? = null
    private var lastWeatherVoiceTime: Long = 0L
    private var routeWeatherSegments: List<RouteWeatherSegment> = emptyList()

    // Live Traffic & Reroute Engine
    private val liveTrafficEngine = LiveTrafficEngine(RoutingEngine())
    private var lastRerouteCheckTime: Long = 0L
    private var isEvaluatingReroute: Boolean = false

    // Route Simulation Engine for Desktop/Testing & Stationary Preview
    private var simulationJob: Job? = null
    private var isRealGpsMoving: Boolean = false

    // Route Steps and Maneuver Tracking
    private var routeSteps: List<RouteStep> = emptyList()
    private var currentStepIndex: Int = 0
    private var lastSpokenStepIndex: Int = -1
    private var lastSpokenStepThreshold: Int = -1

    var isRunnerMode: Boolean = false
        private set
    var runnerWeightKg: Double = RunningCalorieCalculator.DEFAULT_WEIGHT_KG
        private set
    var workoutStartTimeMs: Long = 0L
        private set
    val totalRunDistanceKm: Double
        get() = totalRunDistanceMeters / 1000.0
    var totalBurnedCalories: Int = 0
        private set

    private var totalRunDistanceMeters: Double = 0.0
    private var lastRecordedLocation: Location? = null
    private var lastAnnouncedSplitKm: Int = 0
    private var workoutTimerJob: Job? = null
    private var currentLiveSpeedKmh: Int = 0

    private data class StepDisplayInfo(
        val icon: String,
        val instruction: String,
        val streetName: String,
        val turnDistText: String,
        val turnDistMeters: Int
    )

    fun start(
        dest: LatLng? = null,
        destName: String? = null,
        initialLocation: Location? = null,
        routeCoordinates: List<LatLng>? = null,
        totalDistKm: Double = 0.0,
        enableSimulation: Boolean = false,
        steps: List<RouteStep> = emptyList(),
        isRunnerMode: Boolean = false,
        runnerWeightKg: Double = RunningCalorieCalculator.DEFAULT_WEIGHT_KG
    ) {
        isRunning = true
        this.isRunnerMode = isRunnerMode
        this.runnerWeightKg = runnerWeightKg
        this.workoutStartTimeMs = System.currentTimeMillis()
        this.totalBurnedCalories = 0
        this.totalRunDistanceMeters = 0.0
        this.lastRecordedLocation = initialLocation
        this.lastAnnouncedSplitKm = 0
        destination = dest
        destinationName = destName ?: if (isRunnerMode) "Cung đường chạy bộ" else "Điểm đến"
        routeSteps = steps
        currentStepIndex = 0
        lastSpokenStepIndex = -1
        lastSpokenStepThreshold = -1
        lastDistanceMeters = -1f
        lastSpokenThreshold = -1
        activeCameraId = null
        activeIncidentId = null
        activeWeatherCondition = null
        lastCameraVoiceTime = 0L
        lastOverspeedVoiceTime = 0L
        lastIncidentVoiceTime = 0L
        lastWeatherVoiceTime = 0L
        lastRerouteCheckTime = 0L
        liveTrafficEngine.clearActiveAlternative()

        if (routeCoordinates != null && totalDistKm > 0.0) {
            routeWeatherSegments = WeatherRouteService.analyzeRouteWeather(routeCoordinates, totalDistKm)
        }

        // Determine initial road speed limit & theme
        val (initialLimit, initialTheme) = if (isRunnerMode) {
            Pair(20, SignpostTheme.NATIONAL_BLUE)
        } else if (initialLocation != null) {
            TrafficCameraRepository.getRoadAttributes(destinationName, initialLocation.latitude, initialLocation.longitude)
        } else {
            Pair(60, SignpostTheme.NATIONAL_BLUE)
        }
        currentSpeedLimit = initialLimit
        NavigationStateBridge.setNavigating(true, destinationName)
        if (!isRunnerMode) {
            NavigationStateBridge.updateTelemetry(0, currentSpeedLimit, false)
        }

        if (isRunnerMode) {
            voiceManager.speak("Bắt đầu bài tập chạy bộ trên cung đường. Lộ trình dài ${String.format(Locale.US, "%.1f", totalDistKm)} ki lô mét. Chúc bạn có buổi tập tràn đầy năng lượng!")
        } else {
            voiceManager.speak("Bắt đầu dẫn đường tới $destinationName. Tốc độ giới hạn $currentSpeedLimit ki lô mét một giờ.")
            // Initial stationary state: 0 km/h (ONLY for vehicles)
            listener.onSpeedUpdated(0, currentSpeedLimit, false)
        }

        // Initial Signpost
        val routeCode = if (isRunnerMode) "🏃 CHẠY BỘ" else if (initialTheme == SignpostTheme.EXPRESSWAY_GREEN) "CT.01 / AH1" else "QL.1A"
        val exitBadge = if (isRunnerMode) null else if (initialTheme == SignpostTheme.EXPRESSWAY_GREEN) "LỐI RA 08" else "NÚT GIAO"
        val initialSignpost = SignpostData(
            theme = initialTheme,
            routeCode = routeCode,
            exitBadge = exitBadge,
            destinationName = destinationName.uppercase(Locale.getDefault()),
            distanceText = "--",
            distanceMeters = 0,
            turnIcon = "↑"
        )
        listener.onSignpostUpdated(initialSignpost)

        if (isRunnerMode) {
            workoutTimerJob?.cancel()
            // Initial state: not started moving yet -> pace is 0.0 (--:--), duration is 0
            listener.onRunnerMetricsUpdated(0.0, 0, 0.0, 0L)

            workoutTimerJob = CoroutineScope(Dispatchers.Main).launch {
                while (isActive && isRunning) {
                    delay(1000L)
                    val elapsedSec = maxOf(0L, (System.currentTimeMillis() - workoutStartTimeMs) / 1000L)
                    val currentKm = totalRunDistanceMeters / 1000.0
                    val currentPace = if (currentLiveSpeedKmh >= 2) {
                        60.0 / currentLiveSpeedKmh
                    } else if (currentKm >= 0.05 && elapsedSec > 0) {
                        (elapsedSec / 60.0) / currentKm
                    } else {
                        0.0
                    }

                    listener.onRunnerMetricsUpdated(
                        distanceRunKm = Math.round(currentKm * 100.0) / 100.0,
                        caloriesBurned = totalBurnedCalories,
                        paceMinPerKm = currentPace,
                        durationSeconds = elapsedSec
                    )
                }
            }
        }

        val firstStep = steps.firstOrNull()
        val initialManeuver = if (firstStep != null) {
            val dText = if (firstStep.distanceMeters < 1000) "${firstStep.distanceMeters.roundToInt()} m" else String.format(Locale.US, "%.1f km", firstStep.distanceMeters / 1000.0)
            Maneuver(
                icon = firstStep.icon,
                instruction = firstStep.instruction,
                street = firstStep.streetName,
                distanceText = dText,
                distanceMeters = firstStep.distanceMeters.roundToInt(),
                speedLimit = currentSpeedLimit,
                lanes = booleanArrayOf(true, true, false),
                signBadge = routeCode,
                signDestination = firstStep.streetName.uppercase(Locale.getDefault()),
                voiceText = firstStep.instruction,
                hasSpeedCamera = false
            )
        } else {
            Maneuver(
                icon = "↑",
                instruction = "Đi thẳng hướng về $destinationName",
                street = destinationName,
                distanceText = "--",
                distanceMeters = 0,
                speedLimit = currentSpeedLimit,
                lanes = booleanArrayOf(true, true, false),
                signBadge = routeCode,
                signDestination = destinationName.uppercase(Locale.getDefault()),
                voiceText = "Bắt đầu dẫn đường.",
                hasSpeedCamera = false
            )
        }
        listener.onManeuverUpdated(initialManeuver)

        if (initialLocation != null && destination != null) {
            updateLocation(initialLocation)
        }

        // Launch simulated movement along polyline ONLY when explicitly requested (e.g. desktop/demo testing)
        // In real navigation, GPS coordinates must strictly follow the user's real hardware GPS fixes
        if (enableSimulation && dest != null && routeCoordinates != null && routeCoordinates.size >= 2) {
            startSimulation(routeCoordinates, dest)
        }
    }

    /**
     * Process real GPS location updates from FusedLocationProviderClient.
     * Speed and distance are directly computed from satellite fixes.
     */
    fun updateLocation(location: Location, destOverride: LatLng? = null) {
        if (!isRunning) return

        val targetDest = destOverride ?: destination

        // Detect real vehicle movement from satellite fix (> 1.8 m/s = 6.5 km/h)
        if (location.provider != "simulated" && location.hasSpeed() && location.speed > 1.8f) {
            isRealGpsMoving = true
        }

        // 1. DYNAMIC ROAD ATTRIBUTES (Speed Limit & Signpost Theme)
        val (roadLimit, roadTheme) = TrafficCameraRepository.getRoadAttributes(destinationName, location.latitude, location.longitude)
        currentSpeedLimit = roadLimit

        // 2. REAL SPEED FROM GPS (loc.speed is in meters/sec)
        // Clamp GPS jitter (< 2 km/h) to 0 km/h so standing still displays exact 0.
        val rawKmh = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
        val currentSpeed = if (rawKmh <= 2) 0 else rawKmh
        currentLiveSpeedKmh = currentSpeed
        val isOverspeed = currentSpeed > currentSpeedLimit
        if (!isRunnerMode) {
            listener.onSpeedUpdated(currentSpeed, currentSpeedLimit, isOverspeed)
            NavigationStateBridge.updateTelemetry(currentSpeed, currentSpeedLimit, isOverspeed)
        }

        // 3. AUDIBLE OVERSPEED ALERT (Alarm chime + Voice reminder, with 15s cooldown)
        if (isOverspeed && currentSpeed >= currentSpeedLimit + 3 && !isRunnerMode) {
            val now = System.currentTimeMillis()
            if (now - lastOverspeedVoiceTime > 15000L) {
                lastOverspeedVoiceTime = now
                voiceManager.speakOverspeed(currentSpeed, currentSpeedLimit)
            }
        }

        // 3b. RUNNER WORKOUT MILESTONE TRACKING & REALTIME CALORIES
        if (isRunnerMode) {
            if (lastRecordedLocation != null) {
                val stepDist = FloatArray(1)
                Location.distanceBetween(
                    lastRecordedLocation!!.latitude, lastRecordedLocation!!.longitude,
                    location.latitude, location.longitude,
                    stepDist
                )
                // Filter out stationary GPS drift:
                // Only count distance when actually moving (speed >= 0.5 m/s or stepDist >= 2.0m with speed >= 2 km/h)
                val isMoving = (location.hasSpeed() && location.speed >= 0.5f) || (stepDist[0] >= 2.0f && currentSpeed >= 2)
                if (isMoving && stepDist[0] in 1.0f..35.0f) {
                    totalRunDistanceMeters += stepDist[0]
                    val currentKm = totalRunDistanceMeters / 1000.0
                    val currentKmSplit = currentKm.toInt()
                    totalBurnedCalories = RunningCalorieCalculator.calculateCalories(currentKm, runnerWeightKg)

                    if (currentKmSplit > lastAnnouncedSplitKm && currentKmSplit > 0) {
                        lastAnnouncedSplitKm = currentKmSplit
                        voiceManager.speak("Hoàn thành $currentKmSplit ki lô mét. Bạn đã tiêu hao khoảng $totalBurnedCalories calo. Hãy tiếp tục duy trì nhịp độ!")
                    }
                }
            }
            val currentKm = totalRunDistanceMeters / 1000.0
            val workoutDurationSec = maxOf(0L, (System.currentTimeMillis() - workoutStartTimeMs) / 1000L)
            val currentPace = if (currentSpeed >= 2) {
                60.0 / currentSpeed
            } else if (currentKm >= 0.05 && workoutDurationSec > 0) {
                (workoutDurationSec / 60.0) / currentKm
            } else {
                0.0
            }

            listener.onRunnerMetricsUpdated(
                distanceRunKm = Math.round(currentKm * 100.0) / 100.0,
                caloriesBurned = totalBurnedCalories,
                paceMinPerKm = currentPace,
                durationSeconds = workoutDurationSec
            )
        }
        lastRecordedLocation = location

        // 4. TRAFFIC CAMERA & SPEED TRAP RADAR CHECK (within 700m radius)
        checkTrafficCameras(location.latitude, location.longitude)

        // 4b. COMMUNITY INCIDENTS AHEAD CHECK (Accident, Roadwork, Hazard within 700m)
        checkCommunityIncidents(location.latitude, location.longitude)

        // 4c. ADVERSE WEATHER AHEAD CHECK
        checkAdverseWeather(location.latitude, location.longitude)

        // 5. REAL DISTANCE TO DESTINATION (in meters)
        if (targetDest != null) {
            val distResults = FloatArray(1)
            Location.distanceBetween(
                location.latitude, location.longitude,
                targetDest.latitude, targetDest.longitude,
                distResults
            )
            val distMeters = distResults[0]
            lastDistanceMeters = distMeters

            // Arrival check (< 25m)
            if (distMeters < 25f) {
                voiceManager.speakArrival(destinationName)
                listener.onNavigationFinished()
                return
            }

            val distRemKm = Math.round((distMeters / 1000.0) * 10.0) / 10.0

            // Format distance string (e.g. "450 m" or "12.4 km")
            val distText = if (distMeters < 1000f) {
                "${distMeters.roundToInt()} m"
            } else {
                String.format(Locale.US, "%.1f km", distRemKm)
            }

            // Realistic ETA: based on current speed or 35 km/h urban estimate if stopped
            val drivingSpeed = if (currentSpeed >= 15) currentSpeed.toDouble() else 35.0
            val minutesRemaining = Math.max(1, ((distMeters / 1000.0) / drivingSpeed * 60).roundToInt())
            val etaCal = Calendar.getInstance().apply { add(Calendar.MINUTE, minutesRemaining) }
            val etaString = String.format(Locale.US, "%02d:%02d", etaCal.get(Calendar.HOUR_OF_DAY), etaCal.get(Calendar.MINUTE))

            if (!isRunnerMode) {
                listener.onRemainingUpdated(distRemKm, etaString)
                NavigationStateBridge.updateRemaining(distRemKm, etaString, distMeters.roundToInt())
            }

            // 5a. STEP-BY-STEP REAL-TIME MANEUVER TRACKING
            if (routeSteps.isNotEmpty()) {
                while (currentStepIndex < routeSteps.size - 1) {
                    val nextStep = routeSteps[currentStepIndex + 1]
                    val distToNext = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, nextStep.latitude, nextStep.longitude, distToNext)

                    val currStep = routeSteps[currentStepIndex]
                    val distToCurr = FloatArray(1)
                    Location.distanceBetween(location.latitude, location.longitude, currStep.latitude, currStep.longitude, distToCurr)

                    if (distToNext[0] < 35f || (distToCurr[0] < 20f && currentStepIndex < routeSteps.size - 1)) {
                        currentStepIndex++
                    } else {
                        break
                    }
                }
            }

            val activeStep = if (routeSteps.isNotEmpty() && currentStepIndex < routeSteps.size) {
                routeSteps[currentStepIndex]
            } else null

            val (icon, instruction, streetName, turnDistText, turnDistMeters) = if (activeStep != null) {
                val distResultsToStep = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, activeStep.latitude, activeStep.longitude, distResultsToStep)
                val dMeters = distResultsToStep[0].roundToInt()
                val dText = if (dMeters < 1000) "$dMeters m" else String.format(Locale.US, "%.1f km", dMeters / 1000.0)

                val displayInstruction = when {
                    dMeters <= 30 -> "${activeStep.instruction} ngay bây giờ"
                    dMeters <= 120 -> "${activeStep.instruction} sau 100m"
                    else -> "${activeStep.instruction} sau $dText"
                }
                StepDisplayInfo(activeStep.icon, displayInstruction, activeStep.streetName, dText, dMeters)
            } else {
                val (i, ins) = when {
                    distMeters > 1000f -> Pair("↑", "Đi thẳng hướng về $destinationName")
                    distMeters > 300f -> Pair("↱", "Chuẩn bị tới $destinationName trong $distText")
                    else -> Pair("📍", "Điểm đến $destinationName ở ngay phía trước")
                }
                StepDisplayInfo(i, ins, destinationName, distText, distMeters.roundToInt())
            }

            // 6. REALISTIC SIGNPOST RENDERING
            val routeCode = if (isRunnerMode) "🏃 CHẠY BỘ" else if (roadTheme == SignpostTheme.EXPRESSWAY_GREEN) "CT.01 / AH1" else "QL.1A"
            val exitBadge = if (isRunnerMode) null else if (roadTheme == SignpostTheme.EXPRESSWAY_GREEN) {
                if (turnDistMeters < 1200) "LỐI RA 08" else "LỐI RA 05"
            } else {
                if (turnDistMeters < 1200) "NÚT GIAO" else null
            }

            val signDestinationText = if (streetName.isNotBlank() && streetName != "tuyến đường tiếp theo") {
                streetName.uppercase(Locale.getDefault())
            } else {
                destinationName.uppercase(Locale.getDefault())
            }

            val signpost = SignpostData(
                theme = roadTheme,
                routeCode = routeCode,
                exitBadge = exitBadge,
                destinationName = signDestinationText,
                distanceText = turnDistText,
                distanceMeters = turnDistMeters,
                turnIcon = icon
            )
            listener.onSignpostUpdated(signpost)

            val maneuver = Maneuver(
                icon = icon,
                instruction = instruction,
                street = streetName,
                distanceText = turnDistText,
                distanceMeters = turnDistMeters,
                speedLimit = currentSpeedLimit,
                lanes = booleanArrayOf(true, true, false),
                signBadge = routeCode,
                signDestination = signDestinationText,
                voiceText = instruction,
                hasSpeedCamera = activeCameraId != null
            )
            listener.onManeuverUpdated(maneuver)
            NavigationStateBridge.updateManeuver(maneuver)

            // Voice announcements: announce upcoming turns in advance
            if (activeStep != null) {
                checkStepVoicePrompts(currentStepIndex, turnDistMeters, activeStep.instruction, activeStep.streetName)
            } else {
                checkVoicePrompts(distMeters.roundToInt(), instruction)
            }

            // 7. LIVE TRAFFIC & DYNAMIC REROUTING EVALUATION
            checkLiveTrafficReroute(location.latitude, location.longitude, distRemKm, minutesRemaining)
        }
    }

    /**
     * Check for nearby Speed & Traffic Cameras along vehicle coordinates
     */
    private fun checkTrafficCameras(lat: Double, lon: Double) {
        val nearest = TrafficCameraRepository.findNearestCamera(lat, lon, 700.0)

        if (nearest != null) {
            val (cam, distMeters) = nearest
            listener.onCameraAlert(cam, distMeters)
            NavigationStateBridge.updateCameraAlert(cam, distMeters)

            // Announce voice alert with audible chime on first encounter or at 500m / 250m thresholds
            val now = System.currentTimeMillis()
            val isNewCamera = activeCameraId != cam.id
            val isThreshold = (distMeters in 450..550 || distMeters in 200..300) && (now - lastCameraVoiceTime > 20000L)

            if (isNewCamera || isThreshold) {
                activeCameraId = cam.id
                lastCameraVoiceTime = now
                voiceManager.speakCameraAlert(cam, distMeters)
            }
        } else {
            if (activeCameraId != null) {
                activeCameraId = null
                listener.onCameraCleared()
                NavigationStateBridge.updateCameraAlert(null, 0)
            }
        }
    }

    private fun checkCommunityIncidents(lat: Double, lon: Double) {
        val nearest = CommunityIncidentRepository.findNearestIncident(lat, lon, 700.0)
        if (nearest != null) {
            val (inc, distMeters) = nearest
            listener.onIncidentAlert(inc, distMeters)
            NavigationStateBridge.updateIncident(inc, distMeters)

            val now = System.currentTimeMillis()
            val isNewIncident = activeIncidentId != inc.id
            val isThreshold = (distMeters in 450..550 || distMeters in 200..300) && (now - lastIncidentVoiceTime > 20000L)

            if (isNewIncident || isThreshold) {
                activeIncidentId = inc.id
                lastIncidentVoiceTime = now
                voiceManager.speakIncidentAlert(inc, distMeters)
            }
        } else {
            if (activeIncidentId != null) {
                activeIncidentId = null
                listener.onIncidentCleared()
                NavigationStateBridge.updateIncident(null, 0)
            }
        }
    }

    private fun checkAdverseWeather(lat: Double, lon: Double) {
        val weatherAhead = WeatherRouteService.checkApproachingWeather(lat, lon, routeWeatherSegments)
        if (weatherAhead != null) {
            listener.onWeatherAlert(weatherAhead)
            NavigationStateBridge.updateWeather(weatherAhead)

            val now = System.currentTimeMillis()
            val isNewWeather = activeWeatherCondition != weatherAhead.condition
            if (isNewWeather && (now - lastWeatherVoiceTime > 60000L)) {
                activeWeatherCondition = weatherAhead.condition
                lastWeatherVoiceTime = now
                voiceManager.speakWeatherAlert(weatherAhead)
            }
        } else {
            if (activeWeatherCondition != null) {
                activeWeatherCondition = null
                listener.onWeatherCleared()
                NavigationStateBridge.updateWeather(null)
            }
        }
    }

    private fun checkLiveTrafficReroute(lat: Double, lon: Double, distRemKm: Double, minutesRemaining: Int) {
        if (isEvaluatingReroute || destination == null) return
        val now = System.currentTimeMillis()
        if (now - lastRerouteCheckTime < 45000L) return
        lastRerouteCheckTime = now

        isEvaluatingReroute = true
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val suggestion = liveTrafficEngine.evaluateReroute(
                    LatLng(lat, lon),
                    destination!!,
                    destinationName,
                    distRemKm,
                    minutesRemaining
                )
                if (suggestion != null) {
                    listener.onAlternativeRouteAvailable(suggestion)
                    NavigationStateBridge.updateReroute(suggestion)
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                isEvaluatingReroute = false
            }
        }
    }

    private fun checkStepVoicePrompts(stepIndex: Int, meters: Int, instruction: String, street: String) {
        val threshold = when {
            meters in 250..350 -> 300
            meters in 70..130 -> 100
            meters in 15..45 -> 30
            else -> -1
        }
        if (threshold != -1 && (stepIndex != lastSpokenStepIndex || threshold != lastSpokenStepThreshold)) {
            lastSpokenStepIndex = stepIndex
            lastSpokenStepThreshold = threshold
            voiceManager.speakManeuver(instruction, street, threshold)
        }
    }

    private fun checkVoicePrompts(meters: Int, instruction: String) {
        val threshold = when {
            meters in 900..1100 -> 1000
            meters in 450..550 -> 500
            meters in 150..250 -> 200
            meters in 30..60 -> 50
            else -> -1
        }
        if (threshold != -1 && threshold != lastSpokenThreshold) {
            lastSpokenThreshold = threshold
            voiceManager.speakManeuver(instruction, destinationName, threshold)
        }
    }

    fun stop() {
        isRunning = false
        workoutTimerJob?.cancel()
        workoutTimerJob = null
        currentLiveSpeedKmh = 0
        routeSteps = emptyList()
        currentStepIndex = 0
        lastSpokenStepIndex = -1
        lastSpokenStepThreshold = -1
        simulationJob?.cancel()
        simulationJob = null
        isRealGpsMoving = false
        destination = null
        activeCameraId = null
        activeIncidentId = null
        activeWeatherCondition = null
        voiceManager.stop()
        listener.onCameraCleared()
        listener.onIncidentCleared()
        listener.onWeatherCleared()
        NavigationStateBridge.setNavigating(false)
        NavigationStateBridge.updateCameraAlert(null, 0)
        NavigationStateBridge.updateIncident(null, 0)
        NavigationStateBridge.updateWeather(null)
        NavigationStateBridge.updateReroute(null)
    }

    private fun startSimulation(points: List<LatLng>, dest: LatLng) {
        simulationJob?.cancel()
        isRealGpsMoving = false

        simulationJob = CoroutineScope(Dispatchers.Main).launch {
            delay(1200)
            var index = 0
            while (isActive && isRunning && index < points.size) {
                if (isRealGpsMoving) {
                    delay(2000)
                    continue
                }

                val currentPt = points[index]
                val nextPt = if (index + 1 < points.size) points[index + 1] else dest
                val bearing = computeBearing(currentPt, nextPt)

                val simSpeedKmh = Math.min(currentSpeedLimit - 2, 48)
                val simLoc = Location("simulated").apply {
                    latitude = currentPt.latitude
                    longitude = currentPt.longitude
                    speed = (simSpeedKmh / 3.6f)
                    this.bearing = bearing
                    accuracy = 2.0f
                    time = System.currentTimeMillis()
                }

                updateLocation(simLoc, dest)
                listener.onVehiclePositionUpdated(currentPt.latitude, currentPt.longitude, bearing)

                val distMeters = haversineMeters(currentPt.latitude, currentPt.longitude, nextPt.latitude, nextPt.longitude)
                val step = Math.max(1, (28.0 / Math.max(8.0, distMeters)).roundToInt())
                index += step

                delay(1000)
            }
        }
    }

    private fun computeBearing(from: LatLng, to: LatLng): Float {
        val lat1 = Math.toRadians(from.latitude)
        val lon1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lon2 = Math.toRadians(to.longitude)

        val dLon = lon2 - lon1
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val radBearing = Math.atan2(y, x)
        return ((Math.toDegrees(radBearing) + 360) % 360).toFloat()
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}

