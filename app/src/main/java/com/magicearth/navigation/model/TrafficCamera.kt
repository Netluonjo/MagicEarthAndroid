package com.magicearth.navigation.model

/**
 * Type of Traffic Surveillance Camera in Vietnam
 */
enum class CameraType(val titleVi: String, val titleEn: String, val icon: String) {
    SPEED("Camera Bắn Tốc Độ", "Speed Camera", "📷"),
    RED_LIGHT("Camera Phạt Nguội Vượt Đèn Đỏ", "Red Light Camera", "🚦"),
    LANE("Camera Giám Sát Phạt Sai Làn", "Lane Enforcement Camera", "🛣️")
}

/**
 * Signpost Color Theme simulating real Vietnamese and International Road Standards (QCVN 41:2019/BGTVT)
 */
enum class SignpostTheme {
    EXPRESSWAY_GREEN, // Cao tốc (Green #007A3D)
    NATIONAL_BLUE     // Quốc lộ & Đường đô thị (Blue #0055A5)
}

/**
 * Supported Voice Guidance Languages
 */
enum class VoiceLanguage(val code: String, val displayName: String, val localeTag: String) {
    VIETNAMESE("vi", "Tiếng Việt (Chuẩn)", "vi-VN"),
    ENGLISH("en", "English (Natural US)", "en-US"),
    FRENCH("fr", "Français", "fr-FR")
}

/**
 * Data model for a verified Traffic Camera location
 */
data class TrafficCamera(
    val id: String,
    val name: String,
    val type: CameraType,
    val latitude: Double,
    val longitude: Double,
    val speedLimit: Int,
    val roadName: String,
    val description: String = ""
)

/**
 * Real-time Signpost Rendering Data Model
 */
data class SignpostData(
    val theme: SignpostTheme,
    val routeCode: String,            // e.g. "CT.01 / AH1" or "QL.1A" or "Đ. TỐ HỮU"
    val exitBadge: String?,           // e.g. "LỐI RA 08" or "EXIT 08" or "NÚT GIAO PHÁP VÂN"
    val destinationName: String,      // e.g. "SÂN BAY NỘI BÀI / BẮC NINH"
    val distanceText: String,         // e.g. "500 m" or "1.2 km"
    val distanceMeters: Int,
    val turnIcon: String = "↑"        // "↑", "↱", "↰", etc.
)
