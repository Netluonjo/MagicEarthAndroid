package com.magicearth.navigation.map

import android.location.Location
import com.magicearth.navigation.model.CommunityIncident
import com.magicearth.navigation.model.IncidentType
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Community Crowd-sourced Incident Repository.
 * Collects, verifies, and stores active road incidents:
 * Accidents, Roadworks, Hazards/Floods, and Mobile Speed Traps.
 */
object CommunityIncidentRepository {

    private val incidents = CopyOnWriteArrayList<CommunityIncident>()

    init {
        // Pre-populate with realistic community reports in Vietnam traffic hotspots
        incidents.addAll(
            listOf(
                CommunityIncident(
                    id = "inc_01",
                    type = IncidentType.ROADWORK,
                    title = "Sửa chữa mặt đường & rào chắn làn phải",
                    description = "Đang thi công trải thảm nhựa làn ngoài cùng, phương tiện đi chậm.",
                    roadName = "Đường Vành Đai 3 Trên Cao (Gần nút giao Khuất Duy Tiến)",
                    latitude = 20.9982,
                    longitude = 105.7938,
                    confirmedCount = 14
                ),
                CommunityIncident(
                    id = "inc_02",
                    type = IncidentType.ACCIDENT,
                    title = "Va chạm 2 ô tô con",
                    description = "Va chạm nhẹ chiếm 1 làn giữa, đang chờ cảnh sát giao thông xử lý.",
                    roadName = "Đường Nguyễn Trãi (Gần cầu vượt Ngã Tư Sở)",
                    latitude = 21.0028,
                    longitude = 105.8185,
                    confirmedCount = 28
                ),
                CommunityIncident(
                    id = "inc_03",
                    type = IncidentType.HAZARD,
                    title = "Điểm ngập nước sau mưa lớn",
                    description = "Nước ngập sâu 30-40cm mép đường gom, xe gầm thấp chú ý đi làn giữa.",
                    roadName = "Đại Lộ Thăng Long (Hầm chui số 3)",
                    latitude = 21.0065,
                    longitude = 105.7650,
                    confirmedCount = 35
                ),
                CommunityIncident(
                    id = "inc_04",
                    type = IncidentType.SPEED_TRAP,
                    title = "Tổ CSGT kiểm tra tốc độ",
                    description = "Đoạn bắt đầu vào khu đông dân cư, giới hạn 50 km/h.",
                    roadName = "Quốc Lộ 1A (Thường Tín - Hà Nội)",
                    latitude = 20.8710,
                    longitude = 105.8645,
                    confirmedCount = 19
                ),
                CommunityIncident(
                    id = "inc_05",
                    type = IncidentType.ROADWORK,
                    title = "Bảo trì khe co giãn cầu",
                    description = "Thu hẹp còn 1 làn lưu thông, ùn ứ vào giờ cao điểm.",
                    roadName = "Cầu Thanh Trì",
                    latitude = 20.9780,
                    longitude = 105.9080,
                    confirmedCount = 42
                )
            )
        )
    }

    /**
     * Add a newly reported incident from the community
     */
    fun reportIncident(
        type: IncidentType,
        title: String,
        roadName: String,
        lat: Double,
        lon: Double,
        desc: String = "Báo cáo bởi người lái xe qua Magic Earth"
    ): CommunityIncident {
        val newIncident = CommunityIncident(
            id = "inc_user_${System.currentTimeMillis()}",
            type = type,
            title = title,
            description = desc,
            roadName = roadName,
            latitude = lat,
            longitude = lon,
            confirmedCount = 1
        )
        incidents.add(0, newIncident)
        return newIncident
    }

    /**
     * Find nearest active incident ahead within radius (e.g. 700m)
     */
    fun findNearestIncident(lat: Double, lon: Double, maxDistMeters: Double = 700.0): Pair<CommunityIncident, Int>? {
        var nearest: CommunityIncident? = null
        var minDistance = Float.MAX_VALUE
        val results = FloatArray(1)

        for (inc in incidents) {
            if (inc.isCleared) continue
            Location.distanceBetween(lat, lon, inc.latitude, inc.longitude, results)
            val dist = results[0]
            if (dist <= maxDistMeters && dist < minDistance) {
                minDistance = dist
                nearest = inc
            }
        }

        return nearest?.let { Pair(it, minDistance.toInt()) }
    }

    /**
     * Confirm incident is still present (Vote up)
     */
    fun confirmIncident(id: String) {
        incidents.find { it.id == id }?.let {
            it.confirmedCount += 1
        }
    }

    /**
     * Mark incident as cleared
     */
    fun clearIncident(id: String) {
        incidents.find { it.id == id }?.let {
            it.isCleared = true
        }
    }

    fun getAllActiveIncidents(): List<CommunityIncident> {
        return incidents.filter { !it.isCleared }
    }
}
