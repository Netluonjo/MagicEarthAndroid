package com.magicearth.navigation.navigation

import com.magicearth.navigation.model.Maneuver
import com.magicearth.navigation.model.TrafficCamera

/**
 * Real-time State Bridge connecting NavigationSession with:
 * 1. Android Auto Car Central Display (CarNavigationScreen)
 * 2. Smartwatch Companion Service (SmartwatchNavigationService)
 * 3. Mobile In-App UI (MainActivity / HUDActivity)
 */
object NavigationStateBridge {

    interface NavigationStateListener {
        fun onNavStateChanged(isNavigating: Boolean, destinationName: String)
        fun onManeuverChanged(maneuver: Maneuver)
        fun onTelemetryChanged(speedKmh: Int, speedLimit: Int, isOverspeed: Boolean)
        fun onRemainingChanged(distanceRemKm: Double, etaString: String, distanceMeters: Int)
        fun onCameraAlertChanged(camera: TrafficCamera?, distanceMeters: Int)
        fun onMuteStateChanged(isMuted: Boolean)
        fun onIncidentChanged(incident: com.magicearth.navigation.model.CommunityIncident?, distanceMeters: Int) {}
        fun onWeatherChanged(weather: com.magicearth.navigation.model.RouteWeatherSegment?) {}
        fun onRerouteAvailable(suggestion: com.magicearth.navigation.model.AlternativeRouteSuggestion?) {}
    }

    private val listeners = mutableSetOf<NavigationStateListener>()

    var isNavigating: Boolean = false
        private set

    var destinationName: String = ""
        private set

    var currentManeuver: Maneuver? = null
        private set

    var currentSpeed: Int = 0
        private set

    var currentSpeedLimit: Int = 60
        private set

    var isOverspeed: Boolean = false
        private set

    var remainingKm: Double = 0.0
        private set

    var remainingMeters: Int = 0
        private set

    var etaString: String = "--:--"
        private set

    var activeCamera: TrafficCamera? = null
        private set

    var activeCameraDistance: Int = 0
        private set

    var isMuted: Boolean = false
        private set

    var activeIncident: com.magicearth.navigation.model.CommunityIncident? = null
        private set

    var activeIncidentDistance: Int = 0
        private set

    var activeWeather: com.magicearth.navigation.model.RouteWeatherSegment? = null
        private set

    var activeReroute: com.magicearth.navigation.model.AlternativeRouteSuggestion? = null
        private set

    // Callbacks to request actions from Android Auto or Smartwatch
    var onRequestStopNav: (() -> Unit)? = null
    var onRequestToggleMute: (() -> Unit)? = null

    fun addListener(listener: NavigationStateListener) {
        synchronized(listeners) {
            listeners.add(listener)
            // Immediately dispatch current state to newly connected screen
            listener.onNavStateChanged(isNavigating, destinationName)
            currentManeuver?.let { listener.onManeuverChanged(it) }
            listener.onTelemetryChanged(currentSpeed, currentSpeedLimit, isOverspeed)
            listener.onRemainingChanged(remainingKm, etaString, remainingMeters)
            listener.onCameraAlertChanged(activeCamera, activeCameraDistance)
            listener.onMuteStateChanged(isMuted)
        }
    }

    fun removeListener(listener: NavigationStateListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun setNavigating(navigating: Boolean, dest: String = "") {
        isNavigating = navigating
        destinationName = dest
        if (!navigating) {
            currentManeuver = null
            activeCamera = null
            remainingKm = 0.0
            remainingMeters = 0
            etaString = "--:--"
        }
        notifyAll { it.onNavStateChanged(navigating, dest) }
    }

    fun updateManeuver(maneuver: Maneuver) {
        currentManeuver = maneuver
        notifyAll { it.onManeuverChanged(maneuver) }
    }

    fun updateTelemetry(speedKmh: Int, speedLimit: Int, overspeed: Boolean) {
        currentSpeed = speedKmh
        currentSpeedLimit = speedLimit
        isOverspeed = overspeed
        notifyAll { it.onTelemetryChanged(speedKmh, speedLimit, overspeed) }
    }

    fun updateRemaining(distKm: Double, eta: String, meters: Int) {
        remainingKm = distKm
        etaString = eta
        remainingMeters = meters
        notifyAll { it.onRemainingChanged(distKm, eta, meters) }
    }

    fun updateCameraAlert(camera: TrafficCamera?, distMeters: Int) {
        activeCamera = camera
        activeCameraDistance = distMeters
        notifyAll { it.onCameraAlertChanged(camera, distMeters) }
    }

    fun updateMute(muted: Boolean) {
        isMuted = muted
        notifyAll { it.onMuteStateChanged(muted) }
    }

    fun updateIncident(incident: com.magicearth.navigation.model.CommunityIncident?, distMeters: Int) {
        activeIncident = incident
        activeIncidentDistance = distMeters
        notifyAll { it.onIncidentChanged(incident, distMeters) }
    }

    fun updateWeather(weather: com.magicearth.navigation.model.RouteWeatherSegment?) {
        activeWeather = weather
        notifyAll { it.onWeatherChanged(weather) }
    }

    fun updateReroute(suggestion: com.magicearth.navigation.model.AlternativeRouteSuggestion?) {
        activeReroute = suggestion
        notifyAll { it.onRerouteAvailable(suggestion) }
    }

    private fun notifyAll(action: (NavigationStateListener) -> Unit) {
        synchronized(listeners) {
            listeners.toList().forEach { action(it) }
        }
    }
}
