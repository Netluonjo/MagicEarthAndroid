package com.magicearth.navigation.map

import com.magicearth.navigation.model.CameraType
import com.magicearth.navigation.model.SignpostTheme
import com.magicearth.navigation.model.TrafficCamera
import kotlin.math.*

/**
 * On-Device Traffic Camera & Speed Limit Repository
 * Contains verified cameras across major expressways, highways, and urban intersections in Vietnam.
 */
object TrafficCameraRepository {

    private val cameras = listOf(
        // 1. Tuyến Trục Tố Hữu - Lê Văn Lương - Khuất Duy Tiến (Khu vực lân cận)
        TrafficCamera(
            id = "cam_tohuu_01",
            name = "Camera Phạt Nguội Tố Hữu - Mộ Lao",
            type = CameraType.RED_LIGHT,
            latitude = 20.9855,
            longitude = 105.7720,
            speedLimit = 60,
            roadName = "Đường Tố Hữu",
            description = "Giám sát vượt đèn đỏ và lấn làn BRT"
        ),
        TrafficCamera(
            id = "cam_khuatduytien_01",
            name = "Camera Bắn Tốc Độ Nút Giao Khuất Duy Tiến - Nguyễn Trãi",
            type = CameraType.SPEED,
            latitude = 20.9930,
            longitude = 105.7980,
            speedLimit = 60,
            roadName = "Đường Khuất Duy Tiến",
            description = "Bắn tốc độ tự động 2 chiều"
        ),
        TrafficCamera(
            id = "cam_nguyentrai_01",
            name = "Camera Phạt Nguội Trục Nguyễn Trãi - Ngã Tư Sở",
            type = CameraType.LANE,
            latitude = 21.0020,
            longitude = 105.8150,
            speedLimit = 60,
            roadName = "Đường Nguyễn Trãi",
            description = "Phạt nguội đi sai làn đường và dừng đỗ sai quy định"
        ),

        // 2. Vành Đai 3 Trên Cao (Cao tốc đô thị - Giới hạn 80 km/h)
        TrafficCamera(
            id = "cam_vd3_01",
            name = "Camera Đo Tốc Độ Tự Động Vành Đai 3 (Đoạn Keangnam - Trung Hòa)",
            type = CameraType.SPEED,
            latitude = 21.0175,
            longitude = 105.7835,
            speedLimit = 80,
            roadName = "Vành Đai 3 Trên Cao",
            description = "Giám sát tốc độ tối đa 80 km/h và dừng đỗ làn khẩn cấp"
        ),
        TrafficCamera(
            id = "cam_vd3_02",
            name = "Camera Giám Sát Làn Khẩn Cấp Vành Đai 3 (Đoạn Hồ Linh Đàm)",
            type = CameraType.LANE,
            latitude = 20.9680,
            longitude = 105.8280,
            speedLimit = 80,
            roadName = "Vành Đai 3 Trên Cao",
            description = "Phạt nguội chạy xe vào làn dừng khẩn cấp"
        ),

        // 3. Đại Lộ Thăng Long (Cao tốc Láng - Hòa Lạc)
        TrafficCamera(
            id = "cam_dltl_01",
            name = "Camera Bắn Tốc Độ Đại Lộ Thăng Long (Km 6)",
            type = CameraType.SPEED,
            latitude = 21.0015,
            longitude = 105.7480,
            speedLimit = 100,
            roadName = "Đại Lộ Thăng Long (CT.08)",
            description = "Bắn tốc độ tự động làn cao tốc (100 km/h)"
        ),

        // 4. Tuyến Cầu Nhật Tân - Đường Võ Chí Công - Võ Nguyên Giáp (Đi Sân Bay Nội Bài)
        TrafficCamera(
            id = "cam_vochicong_01",
            name = "Camera Giám Sát Tốc Độ Đường Võ Chí Công",
            type = CameraType.SPEED,
            latitude = 21.0620,
            longitude = 105.8050,
            speedLimit = 80,
            roadName = "Đường Võ Chí Công",
            description = "Giám sát tốc độ 80 km/h hướng đi Cầu Nhật Tân"
        ),
        TrafficCamera(
            id = "cam_nhattan_01",
            name = "Camera Giám Sát Tốc Độ Cầu Nhật Tân",
            type = CameraType.SPEED,
            latitude = 21.0880,
            longitude = 105.8210,
            speedLimit = 80,
            roadName = "Cầu Nhật Tân",
            description = "Kiểm soát tốc độ tối đa 80 km/h trên cầu"
        ),
        TrafficCamera(
            id = "cam_vonguyengiap_01",
            name = "Camera Bắn Tốc Độ Đường Võ Nguyên Giáp (Km 8)",
            type = CameraType.SPEED,
            latitude = 21.1680,
            longitude = 105.8150,
            speedLimit = 90,
            roadName = "Đường Võ Nguyên Giáp",
            description = "Bắn tốc độ tự động trục nối Cầu Nhật Tân - Sân Bay Nội Bài"
        ),

        // 5. Cao Tốc Hà Nội - Hải Phòng (CT.04)
        TrafficCamera(
            id = "cam_hnhp_01",
            name = "Camera Giám Sát Tốc Độ Cao Tốc Hà Nội - Hải Phòng (Km 12)",
            type = CameraType.SPEED,
            latitude = 20.9450,
            longitude = 105.9750,
            speedLimit = 120,
            roadName = "Cao Tốc Hà Nội - Hải Phòng (CT.04)",
            description = "Bắn tốc độ tự động tốc độ tối đa 120 km/h, tối thiểu 60 km/h"
        ),

        // 6. Cao Tốc Pháp Vân - Cầu Giẽ (CT.01 / AH1)
        TrafficCamera(
            id = "cam_pvcg_01",
            name = "Camera Giám Sát Cao Tốc Pháp Vân - Cầu Giẽ (Trạm Thu Phí)",
            type = CameraType.SPEED,
            latitude = 20.9520,
            longitude = 105.8450,
            speedLimit = 100,
            roadName = "Cao Tốc Pháp Vân - Cầu Giẽ (CT.01)",
            description = "Giám sát tốc độ 100 km/h và giữ khoảng cách an toàn"
        )
    )

    /**
     * Find the nearest camera within warning distance (meters) of current location
     */
    fun findNearestCamera(lat: Double, lon: Double, maxRadiusMeters: Double = 800.0): Pair<TrafficCamera, Int>? {
        var closestCam: TrafficCamera? = null
        var minDistance = Double.MAX_VALUE

        for (cam in cameras) {
            val dist = computeDistanceMeters(lat, lon, cam.latitude, cam.longitude)
            if (dist <= maxRadiusMeters && dist < minDistance) {
                minDistance = dist
                closestCam = cam
            }
        }

        return if (closestCam != null) {
            Pair(closestCam, minDistance.roundToInt())
        } else {
            null
        }
    }

    /**
     * Determine dynamic road speed limit & signpost theme based on road name and coordinates
     */
    fun getRoadAttributes(roadName: String?, lat: Double, lon: Double): Pair<Int, SignpostTheme> {
        val name = roadName?.uppercase() ?: ""

        // Check if current position is within proximity of an expressway camera
        val nearbyCam = findNearestCamera(lat, lon, 1500.0)
        if (nearbyCam != null && (nearbyCam.first.speedLimit >= 100 || nearbyCam.first.roadName.contains("Cao Tốc", ignoreCase = true))) {
            return Pair(nearbyCam.first.speedLimit, SignpostTheme.EXPRESSWAY_GREEN)
        }

        return when {
            name.contains("CAO TỐC") || name.contains("CT.") || name.contains("AH1") || name.contains("HẢI PHÒNG") || name.contains("PHÁP VÂN") -> {
                Pair(100, SignpostTheme.EXPRESSWAY_GREEN)
            }
            name.contains("VÀNH ĐAI 3") || name.contains("VÕ NGUYÊN GIÁP") || name.contains("VÕ CHÍ CÔNG") || name.contains("NHẬT TÂN") || name.contains("THĂNG LONG") -> {
                Pair(80, SignpostTheme.EXPRESSWAY_GREEN)
            }
            name.contains("QUỐC LỘ") || name.contains("QL.") -> {
                Pair(80, SignpostTheme.NATIONAL_BLUE)
            }
            else -> {
                // Default Urban road (Đường đô thị)
                Pair(60, SignpostTheme.NATIONAL_BLUE)
            }
        }
    }

    /**
     * Haversine formula distance in meters
     */
    private fun computeDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
