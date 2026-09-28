package com.magicearth.navigation.model

data class POI(
    val id: String,
    val name: String,
    val category: String,
    val categoryName: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceText: String = "500 m",
    val rating: Float = 4.8f,
    val hasWiki: Boolean = false,
    val wikiTitle: String? = null,
    val wikiSnippet: String? = null,
    val imageUrl: String? = null
)

data class RouteMetrics(
    val distanceKm: Double,
    val durationMinutes: Int,
    val etaString: String,
    val trafficCondition: String,
    val surfacePavedPct: Int = 94,
    val surfaceUnpavedPct: Int = 6,
    val hasWaypoints: Boolean = false
)

data class RouteStep(
    val instruction: String,
    val streetName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val turnType: String,
    val modifier: String,
    val icon: String,
    val latitude: Double,
    val longitude: Double
)

data class Maneuver(
    val icon: String,
    val instruction: String,
    val street: String,
    val distanceText: String,
    val distanceMeters: Int,
    val speedLimit: Int,
    val lanes: BooleanArray,
    val signBadge: String,
    val signDestination: String,
    val voiceText: String,
    val hasSpeedCamera: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Maneuver
        if (icon != other.icon) return false
        if (instruction != other.instruction) return false
        if (street != other.street) return false
        if (distanceMeters != other.distanceMeters) return false
        if (speedLimit != other.speedLimit) return false
        if (!lanes.contentEquals(other.lanes)) return false
        if (signBadge != other.signBadge) return false
        if (signDestination != other.signDestination) return false
        if (voiceText != other.voiceText) return false
        return hasSpeedCamera == other.hasSpeedCamera
    }

    override fun hashCode(): Int {
        var result = icon.hashCode()
        result = 31 * result + instruction.hashCode()
        result = 31 * result + street.hashCode()
        result = 31 * result + distanceMeters
        result = 31 * result + speedLimit
        result = 31 * result + lanes.contentHashCode()
        result = 31 * result + signBadge.hashCode()
        result = 31 * result + signDestination.hashCode()
        result = 31 * result + voiceText.hashCode()
        result = 31 * result + hasSpeedCamera.hashCode()
        return result
    }
}

data class GPXPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val timestamp: Long,
    val speedKmh: Float = 0f,
    val note: String? = null
)

enum class TransportMode {
    CAR, TRUCK, BICYCLE, PEDESTRIAN, TRANSIT
}

enum class MapStyleType {
    VECTOR_2D,
    VECTOR_3D, SATELLITE, TERRAIN, DARK
}

data class IncidentReport(
    val id: String,
    val typeName: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = System.currentTimeMillis()
)

data class TruckConfig(
    var weightTons: Double = 18.0,
    var heightMeters: Double = 3.9,
    var axles: Int = 3
)

data class WeatherAlert(
    val title: String,
    val description: String,
    val distanceKm: Double,
    val severity: String = "WARNING"
)

data class SavedPlace(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val icon: String
)

data class Waypoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val order: Int
)

