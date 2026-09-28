package com.magicearth.navigation.model

import org.maplibre.android.geometry.LatLng

enum class IncidentType(val icon: String, val titleVi: String, val colorHex: String) {
    ACCIDENT("💥", "Tai nạn giao thông", "#EF4444"),
    ROADWORK("🚧", "Công trường thi công", "#F59E0B"),
    HAZARD("⚠️", "Chướng ngại vật / Ngập nước", "#00F0FF"),
    SPEED_TRAP("📸", "Bắn tốc độ / Kiểm tra", "#7928CA")
}

data class CommunityIncident(
    val id: String,
    val type: IncidentType,
    val title: String,
    val description: String,
    val roadName: String,
    val latitude: Double,
    val longitude: Double,
    var confirmedCount: Int = 1,
    var isCleared: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class WeatherConditionType(val icon: String, val titleVi: String, val advisory: String) {
    HEAVY_RAIN("⛈️", "Mưa dông lớn / Ngập nước", "Bật đèn cos, giữ khoảng cách 50m, tránh vũng nước sâu."),
    THICK_FOG("🌫️", "Sương mù dày đặc", "Bật đèn sương mù, giảm tốc độ dưới 40 km/h, không vượt ẩu."),
    SNOW_ICE("❄️", "Băng giá trơn trượt", "Đi số thấp, không phanh gấp, cẩn trọng đoạn dốc đèo."),
    HIGH_WIND("🌪️", "Gió lốc mạnh / Bão", "Cẩn thận gió tạt ngang trên cầu cao và đường trống trải."),
    CLEAR("☀️", "Thời tiết thuận lợi", "Đường khô ráo, tầm nhìn tốt.")
}

data class RouteWeatherSegment(
    val condition: WeatherConditionType,
    val locationName: String,
    val latitude: Double,
    val longitude: Double,
    val startKm: Double,
    val endKm: Double,
    val temperatureC: Int,
    val windKmh: Int
)

data class AlternativeRouteSuggestion(
    val id: String,
    val name: String,
    val reason: String,
    val savedMinutes: Int,
    val distanceKm: Double,
    val coordinates: List<LatLng>
)
