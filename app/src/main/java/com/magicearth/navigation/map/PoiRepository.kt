package com.magicearth.navigation.map

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.magicearth.navigation.model.POI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

/**
 * High-Precision Multi-Source POI & Geocoding Repository
 * Combines:
 * 1. Extensive On-Device Vietnamese Offline Database (Accent-Insensitive Fuzzy Search)
 * 2. Real-Time Distance Calculation (Haversine Formula from User GPS)
 * 3. Live OpenStreetMap Photon Global Geocoding API (Any address/street in Vietnam)
 * 4. 100% Privacy Preserving with Zero Tracking
 */
object PoiRepository {

    private const val TAG = "PoiRepository"

    /**
     * Vietnamese accent normalization helper (e.g. "Phố Lê Giản" -> "pho le gian")
     */
    fun normalizeVietnamese(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        val pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
        return pattern.matcher(nfd)
            .replaceAll("")
            .lowercase(Locale.ROOT)
            .replace("đ", "d")
            .replace("Đ", "d")
            .trim()
    }

    /**
     * Haversine formula to compute distance in meters and formatted string
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, String> {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val dist = earthRadius * c
        val text = if (dist < 1000) {
            "${dist.toInt()} m"
        } else {
            String.format(Locale.US, "%.1f km", dist / 1000.0)
        }
        return Pair(dist, text)
    }

    /**
     * Extensive On-Device Local Database for Vietnam & Hanoi
     */
    private val localDatabase = listOf(
        // User neighborhood (Hà Đông / Nam Từ Liêm / Thanh Xuân)
        POI(
            id = "legian_street",
            name = "Phố Lê Giản",
            category = "street",
            categoryName = "🛣️ Tuyến Phố Đô Thị",
            address = "Phố Lê Giản, Phường Mộ Lao / Vạn Phúc, Hà Đông, Hà Nội",
            latitude = 20.9893,
            longitude = 105.7643,
            distanceText = "Gần bạn nhất"
        ),
        POI(
            id = "tohuu_street",
            name = "Đường Tố Hữu (Lê Văn Lương Kéo Dài)",
            category = "street",
            categoryName = "🛣️ Trục Đường Huyết Mạch",
            address = "Trục Tố Hữu - Lê Văn Lương, Nam Từ Liêm / Hà Đông, Hà Nội",
            latitude = 20.9855,
            longitude = 105.7720,
            distanceText = "800 m"
        ),
        POI(
            id = "aeon_hadong",
            name = "Trung Tâm Thương Mại AEON MALL Hà Đông",
            category = "mall",
            categoryName = "🛍️ Trung Tâm Thương Mại",
            address = "Khu đô thị Dương Nội, Hà Đông, Hà Nội",
            latitude = 20.9830,
            longitude = 105.7538,
            distanceText = "2.1 km"
        ),
        POI(
            id = "vincom_smartcity",
            name = "Vincom Mega Mall Smart City",
            category = "mall",
            categoryName = "🛍️ Đại Siêu Thị & Trạm Sạc",
            address = "Khu đô thị Vinhomes Smart City, Tây Mỗ, Nam Từ Liêm, Hà Nội",
            latitude = 21.0025,
            longitude = 105.7485,
            distanceText = "3.2 km"
        ),
        POI(
            id = "ev_smartcity",
            name = "Trạm Sạc Siêu Nhanh VinFast Smart City",
            category = "ev",
            categoryName = "⚡ Trạm Sạc Xe Điện EV",
            address = "Bãi đỗ xe TTTM Vincom Mega Mall Smart City, Nam Từ Liêm",
            latitude = 21.0028,
            longitude = 105.7490,
            distanceText = "3.2 km"
        ),
        POI(
            id = "petro_hadong",
            name = "Cửa Hàng Xăng Dầu Petrolimex Số 45",
            category = "fuel",
            categoryName = "⛽ Trạm Xăng Dầu",
            address = "Đường Tố Hữu, Vạn Phúc, Hà Đông, Hà Nội",
            latitude = 20.9868,
            longitude = 105.7682,
            distanceText = "650 m"
        ),
        POI(
            id = "keangnam72",
            name = "Tòa Nhà Keangnam Landmark 72",
            category = "sight",
            categoryName = "🏢 Tòa Cao Ốc Chọc Trời",
            address = "Phạm Hùng, Mễ Trì, Nam Từ Liêm, Hà Nội",
            latitude = 21.0170,
            longitude = 105.7838,
            distanceText = "4.5 km",
            hasWiki = true,
            wikiTitle = "Keangnam Hanoi Landmark Tower",
            wikiSnippet = "Tổ hợp văn phòng, trung tâm thương mại và căn hộ cao cấp 72 tầng, từng là tòa nhà cao nhất Việt Nam từ năm 2011 đến 2018."
        ),
        POI(
            id = "ncc_vietnam",
            name = "Trung Tâm Hội Nghị Quốc Gia",
            category = "sight",
            categoryName = "🏛️ Công Trình Trọng Điểm",
            address = "Đại lộ Thăng Long, Mễ Trì, Nam Từ Liêm, Hà Nội",
            latitude = 21.0060,
            longitude = 105.7878,
            distanceText = "4.0 km"
        ),
        POI(
            id = "mydinh_stadium",
            name = "Sân Vận Động Quốc Gia Mỹ Đình",
            category = "sight",
            categoryName = "⚽ Đấu Trường Thể Thao Quốc Gia",
            address = "Đường Lê Đức Thọ, Mỹ Đình 1, Nam Từ Liêm, Hà Nội",
            latitude = 21.0205,
            longitude = 105.7638,
            distanceText = "3.8 km"
        ),
        POI(
            id = "benxe_mydinh",
            name = "Bến Xe Khách Mỹ Đình",
            category = "transit",
            categoryName = "🚌 Đầu Mối Giao Thông Vận Tải",
            address = "Số 20 Phạm Hùng, Mỹ Đình 2, Nam Từ Liêm, Hà Nội",
            latitude = 21.0282,
            longitude = 105.7782,
            distanceText = "4.9 km"
        ),
        POI(
            id = "benxe_yennghia",
            name = "Bến Xe Khách Yên Nghĩa",
            category = "transit",
            categoryName = "🚌 Ga Đầu Tuyến Metro Cát Linh",
            address = "Quốc Lộ 6, Phường Yên Nghĩa, Hà Đông, Hà Nội",
            latitude = 20.9575,
            longitude = 105.7482,
            distanceText = "4.2 km"
        ),
        POI(
            id = "hospital_103",
            name = "Bệnh Viện Quân Y 103",
            category = "hospital",
            categoryName = "🏥 Bệnh Viện Đa Khoa Trung Ương",
            address = "261 Phùng Hưng, Phúc La, Hà Đông, Hà Nội",
            latitude = 20.9695,
            longitude = 105.7890,
            distanceText = "3.5 km"
        ),
        POI(
            id = "hospital_k_tantrieu",
            name = "Bệnh Viện K - Cơ Sở Tân Triều",
            category = "hospital",
            categoryName = "🏥 Viện Ung Bướu Trung Ương",
            address = "30 Cầu Bươu, Tân Triều, Thanh Trì, Hà Nội",
            latitude = 20.9632,
            longitude = 105.7995,
            distanceText = "4.8 km"
        ),
        POI(
            id = "hospital_bachmai",
            name = "Bệnh Viện Bạch Mai",
            category = "hospital",
            categoryName = "🏥 Bệnh Viện Tuyến Đầu Quốc Gia",
            address = "78 Giải Phóng, Phương Mai, Đống Đa, Hà Nội",
            latitude = 21.0035,
            longitude = 105.8415,
            distanceText = "7.8 km",
            hasWiki = true,
            wikiTitle = "Bệnh viện Bạch Mai",
            wikiSnippet = "Một trong những bệnh viện lớn nhất và lâu đời nhất Việt Nam, thành lập năm 1911, giữ vai trò đầu ngành trong chẩn đoán và điều trị."
        ),
        POI(
            id = "hospital_vietduc",
            name = "Bệnh Viện Hữu Nghị Việt Đức",
            category = "hospital",
            categoryName = "🏥 Trung Tâm Ngoại Khoa Hàng Đầu",
            address = "40 Tràng Thi, Hàng Bông, Hoàn Kiếm, Hà Nội",
            latitude = 21.0275,
            longitude = 105.8480,
            distanceText = "9.5 km"
        ),
        POI(
            id = "noibai_airport",
            name = "Sân Bay Quốc Tế Nội Bài (HAN)",
            category = "airport",
            categoryName = "✈️ Cảng Hàng Không Quốc Tế",
            address = "Phú Minh, Sóc Sơn, Hà Nội",
            latitude = 21.2187,
            longitude = 105.8042,
            distanceText = "28 km",
            hasWiki = true,
            wikiTitle = "Sân bay quốc tế Nội Bài",
            wikiSnippet = "Cảng hàng không quốc tế lớn nhất miền Bắc Việt Nam, kết nối thủ đô Hà Nội với hàng trăm điểm đến trên toàn thế giới."
        ),
        POI(
            id = "hoankiem",
            name = "Hồ Hoàn Kiếm (Hồ Gươm)",
            category = "sight",
            categoryName = "🏛️ Di Tích Lịch Sử • Wikipedia",
            address = "Phố Đinh Tiên Hoàng, Hoàn Kiếm, Hà Nội",
            latitude = 21.0287,
            longitude = 105.8524,
            distanceText = "10 km",
            hasWiki = true,
            wikiTitle = "Hồ Hoàn Kiếm",
            wikiSnippet = "Hồ Hoàn Kiếm là trái tim lịch sử của thủ đô Hà Nội, gắn liền với truyền tích vua Lê Lợi trả gươm thần Thuận Thiên cho Rùa Vàng sau thắng lợi chống giặc Minh."
        ),
        POI(
            id = "vanmieu",
            name = "Văn Miếu - Quốc Tử Giám",
            category = "sight",
            categoryName = "🏛️ Trường Đại Học Đầu Tiên • Wikipedia",
            address = "58 Phố Quốc Tử Giám, Đống Đa, Hà Nội",
            latitude = 21.0294,
            longitude = 105.8360,
            distanceText = "8.5 km",
            hasWiki = true,
            wikiTitle = "Văn Miếu – Quốc Tử Giám",
            wikiSnippet = "Quần thể di tích trường đại học đầu tiên của Việt Nam, xây dựng từ năm 1070 dưới thời vua Lý Thánh Tông để tôn vinh Nho học và các bậc hiền tài bác học."
        ),
        POI(
            id = "hoangthanh",
            name = "Hoàng Thành Thăng Long",
            category = "sight",
            categoryName = "🏛️ Di Sản Văn Hóa Thế Giới • Wikipedia",
            address = "19C Hoàng Diệu, Ba Đình, Hà Nội",
            latitude = 21.0375,
            longitude = 105.8406,
            distanceText = "9.2 km",
            hasWiki = true,
            wikiTitle = "Hoàng thành Thăng Long",
            wikiSnippet = "Di sản văn hóa thế giới được UNESCO công nhận năm 2010, trung tâm quyền lực chính trị liên tục qua 13 thế kỷ từ triều Lý, Trần, Lê tới Nguyễn."
        ),
        POI(
            id = "westlake",
            name = "Hồ Tây & Chùa Trấn Quốc",
            category = "sight",
            categoryName = "🏛️ Danh Lam Cổ Tự Hà Nội",
            address = "Đường Thanh Niên, Yên Phụ, Tây Hồ, Hà Nội",
            latitude = 21.0478,
            longitude = 105.8368,
            distanceText = "11 km"
        ),
        POI(
            id = "petro_01",
            name = "Cửa Hàng Xăng Dầu Petrolimex Số 01",
            category = "fuel",
            categoryName = "⛽ Trạm Xăng Dầu",
            address = "Số 1 Trần Quang Khải, Hoàn Kiếm, Hà Nội",
            latitude = 21.0261,
            longitude = 105.8588,
            distanceText = "10.5 km"
        ),
        POI(
            id = "pvoil_02",
            name = "Cây Xăng PVOIL Hai Bà Trưng",
            category = "fuel",
            categoryName = "⛽ Trạm Xăng Dầu",
            address = "12 Hai Bà Trưng, Tràng Tiền, Hoàn Kiếm",
            latitude = 21.0245,
            longitude = 105.8550,
            distanceText = "10.2 km"
        ),
        POI(
            id = "ev_trangtien",
            name = "Trạm Sạc Siêu Nhanh VinFast Tràng Tiền Plaza",
            category = "ev",
            categoryName = "⚡ Trạm Sạc Xe Điện EV",
            address = "TTTM Tràng Tiền Plaza, Hoàn Kiếm",
            latitude = 21.0254,
            longitude = 105.8532,
            distanceText = "10.1 km"
        ),
        POI(
            id = "parking_dinhle",
            name = "Bãi Đỗ Xe Thông Minh Đinh Lễ",
            category = "parking",
            categoryName = "🅿️ Bãi Đỗ Xe Công Cộng",
            address = "Phố Đinh Lễ, Hoàn Kiếm, Hà Nội",
            latitude = 21.0268,
            longitude = 105.8535,
            distanceText = "10.2 km"
        ),
        POI(
            id = "saved_home",
            name = "Nhà Riêng (Home)",
            category = "saved",
            categoryName = "⭐ Địa Điểm Đã Lưu",
            address = "Phố Lê Giản, Hà Đông, Hà Nội",
            latitude = 20.9893,
            longitude = 105.7643,
            distanceText = "Vị trí của bạn"
        ),
        POI(
            id = "saved_work",
            name = "Cơ Quan (Work)",
            category = "saved",
            categoryName = "⭐ Địa Điểm Đã Lưu",
            address = "Tòa Nhà Keangnam Landmark 72, Nam Từ Liêm, Hà Nội",
            latitude = 21.0170,
            longitude = 105.7838,
            distanceText = "4.5 km"
        ),
        // ==========================================
        // TP. HỒ CHÍ MINH (SÀI GÒN) OFFLINE DATABASE
        // ==========================================
        POI(
            id = "hcm_benthanh",
            name = "Chợ Bến Thành",
            category = "sight",
            categoryName = "🏛️ Biểu Tượng Sài Gòn • Wikipedia",
            address = "Đường Lê Lợi, Phường Bến Thành, Quận 1, TP. Hồ Chí Minh",
            latitude = 10.7725,
            longitude = 106.6980,
            distanceText = "Trung tâm Q.1",
            hasWiki = true,
            wikiTitle = "Chợ Bến Thành",
            wikiSnippet = "Ngôi chợ biểu tượng lịch sử của Sài Gòn hình thành từ thế kỷ 19, nổi bật với tháp đồng hồ 4 mặt nhìn ra quảng trường Quách Thị Trang."
        ),
        POI(
            id = "hcm_nhathoducba",
            name = "Nhà Thờ Đức Bà Sài Gòn",
            category = "sight",
            categoryName = "🏛️ Kiến Trúc Cổ Điển • Wikipedia",
            address = "Số 1 Công Xã Paris, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh",
            latitude = 10.7797,
            longitude = 106.6990,
            distanceText = "Quận 1",
            hasWiki = true,
            wikiTitle = "Nhà thờ chính tòa Đức Bà Sài Gòn",
            wikiSnippet = "Kiệt tác kiến trúc Gothic Roman tuyệt mỹ hoàn thành năm 1880 với toàn bộ vật liệu gạch ngói xây dựng được chuyển trực tiếp từ Marseille (Pháp)."
        ),
        POI(
            id = "hcm_nguyenhue",
            name = "Phố Đi Bộ Nguyễn Huệ",
            category = "street",
            categoryName = "🛣️ Quảng Trường & Phố Đi Bộ",
            address = "Đường Nguyễn Huệ, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh",
            latitude = 10.7745,
            longitude = 106.7035,
            distanceText = "Quận 1"
        ),
        POI(
            id = "hcm_landmark81",
            name = "Tòa Nhà Landmark 81",
            category = "sight",
            categoryName = "🏢 Tòa Nhà Cao Nhất Việt Nam • Wikipedia",
            address = "720A Điện Biên Phủ, Phường 22, Bình Thạnh, TP. Hồ Chí Minh",
            latitude = 10.7950,
            longitude = 106.7220,
            distanceText = "Bình Thạnh",
            hasWiki = true,
            wikiTitle = "Landmark 81",
            wikiSnippet = "Tòa nhà chọc trời 81 tầng cao 461,2m, lấy cảm hứng từ bó tre truyền thống Việt Nam, hiện là tòa tháp cao nhất Việt Nam và top 20 thế giới."
        ),
        POI(
            id = "hcm_dinhdoclap",
            name = "Dinh Độc Lập (Hội Trường Thống Nhất)",
            category = "sight",
            categoryName = "🏛️ Di Tích Lịch Sử Quốc Gia • Wikipedia",
            address = "135 Nam Kỳ Khởi Nghĩa, Phường Bến Thành, Quận 1, TP. Hồ Chí Minh",
            latitude = 10.7770,
            longitude = 106.6953,
            distanceText = "Quận 1",
            hasWiki = true,
            wikiTitle = "Dinh Độc Lập",
            wikiSnippet = "Công trình lịch sử kiến trúc đỉnh cao do KTS Ngô Viết Thụ thiết kế, chứng nhân khoảnh khắc trưa ngày 30/4/1975 non sông liền một dải."
        ),
        POI(
            id = "hcm_sanbay_tsn",
            name = "Sân Bay Quốc Tế Tân Sơn Nhất",
            category = "transit",
            categoryName = "✈️ Cảng Hàng Không Quốc Tế",
            address = "Đường Trường Sơn, Phường 2, Tân Bình, TP. Hồ Chí Minh",
            latitude = 10.8185,
            longitude = 106.6588,
            distanceText = "Tân Bình"
        ),
        POI(
            id = "hcm_benbachdang",
            name = "Ga Tàu Thủy Bến Bạch Đằng & Cầu Ba Son",
            category = "transit",
            categoryName = "⚓ Cảng Du Thuyền & Cầu Thủ Thiêm 2",
            address = "Đường Tôn Đức Thắng, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh",
            latitude = 10.7735,
            longitude = 106.7065,
            distanceText = "Bờ sông Sài Gòn"
        ),
        POI(
            id = "hcm_chobinhtay",
            name = "Chợ Lớn (Chợ Bình Tây)",
            category = "mall",
            categoryName = "🛍️ Trung Tâm Giao Thương Chợ Lớn",
            address = "Đường Tháp Mười, Phường 2, Quận 6, TP. Hồ Chí Minh",
            latitude = 10.7495,
            longitude = 106.6510,
            distanceText = "Quận 6"
        ),
        POI(
            id = "hcm_phumyhung",
            name = "Khu Đô Thị Phú Mỹ Hưng (Crescent Mall & Cầu Ánh Sao)",
            category = "mall",
            categoryName = "🛍️ Đô Thị Sinh Thái Nam Sài Gòn",
            address = "101 Tôn Dật Tiên, Tân Phú, Quận 7, TP. Hồ Chí Minh",
            latitude = 10.7280,
            longitude = 106.7210,
            distanceText = "Quận 7"
        ),
        POI(
            id = "hcm_bv_choray",
            name = "Bệnh Viện Chợ Rẫy",
            category = "hospital",
            categoryName = "🏥 Bệnh Viện Đa Khoa Hạng Đặc Biệt",
            address = "201B Nguyễn Chí Thanh, Phường 12, Quận 5, TP. Hồ Chí Minh",
            latitude = 10.7578,
            longitude = 106.6595,
            distanceText = "Quận 5"
        )
    )

    /**
     * Local Fuzzy Search with Vietnamese Accent Normalization & Distance Sorting
     */
    fun searchLocal(query: String, userLat: Double? = null, userLon: Double? = null): List<POI> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val normQuery = normalizeVietnamese(trimmed)
        val queryTokens = normQuery.split(" ").filter { it.isNotBlank() }

        val matches = localDatabase.filter { poi ->
            val normName = normalizeVietnamese(poi.name)
            val normAddress = normalizeVietnamese(poi.address)
            val normCat = normalizeVietnamese(poi.categoryName)

            // Match all tokens for high precision
            queryTokens.all { token ->
                normName.contains(token) || normAddress.contains(token) || normCat.contains(token)
            }
        }

        // Calculate dynamic distances from user GPS
        return if (userLat != null && userLon != null) {
            matches.map { poi ->
                val (_, distText) = calculateDistance(userLat, userLon, poi.latitude, poi.longitude)
                poi.copy(distanceText = distText)
            }.sortedBy { poi ->
                calculateDistance(userLat, userLon, poi.latitude, poi.longitude).first
            }
        } else {
            matches
        }
    }

    /**
     * Live OpenStreetMap Photon Global Geocoding API
     * Returns exact coordinates, house numbers, streets, and wards anywhere in Vietnam.
     */
    suspend fun searchOnlinePhoton(
        query: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<POI> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()

        try {
            val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
            val urlString = buildString {
                append("https://photon.komoot.io/api/?q=").append(encodedQuery)
                append("&limit=7")
                if (userLat != null && userLon != null) {
                    append("&lat=").append(userLat)
                    append("&lon=").append(userLon)
                }
            }

            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", "MagicEarthNavigation/1.0 (Android)")

            if (conn.responseCode != 200) {
                Log.w(TAG, "Photon API returned status: ${conn.responseCode}")
                return@withContext emptyList()
            }

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JsonParser.parseString(jsonText).asJsonObject
            val features = root.getAsJsonArray("features") ?: return@withContext emptyList()

            val onlineResults = mutableListOf<POI>()

            for (i in 0 until features.size()) {
                val feat = features.get(i).asJsonObject
                val geom = feat.getAsJsonObject("geometry")
                val coords = geom.getAsJsonArray("coordinates")
                val lon = coords.get(0).asDouble
                val lat = coords.get(1).asDouble

                val props = feat.getAsJsonObject("properties")
                val name = props.get("name")?.asString
                    ?: props.get("street")?.asString
                    ?: trimmed

                val street = props.get("street")?.asString
                val district = props.get("district")?.asString ?: props.get("county")?.asString
                val city = props.get("city")?.asString ?: props.get("state")?.asString ?: "Việt Nam"
                val country = props.get("country")?.asString ?: ""

                val addressParts = listOfNotNull(street, district, city, country)
                    .filter { it.isNotBlank() }
                    .distinct()
                val fullAddress = if (addressParts.isNotEmpty()) addressParts.joinToString(", ") else "Việt Nam"

                val osmType = props.get("osm_value")?.asString ?: "location"
                val categoryIcon = when (osmType) {
                    "fuel" -> "⛽ Cây Xăng Dầu"
                    "restaurant", "cafe", "fast_food" -> "☕ Ẩm Thực & Cà Phê"
                    "hospital", "pharmacy", "clinic" -> "🏥 Y Tế & Hiệu Thuốc"
                    "bank", "atm" -> "🏦 Ngân Hàng & ATM"
                    "hotel", "motel" -> "🏨 Khách Sạn & Nghỉ Dưỡng"
                    "school", "university" -> "🎓 Giáo Dục & Trường Học"
                    "supermarket", "convenience" -> "🛒 Siêu Thị & Tiện Lợi"
                    "place_of_worship" -> "🏛️ Đền Chùa & Tâm Linh"
                    else -> "📍 Vị Trí Bản Đồ OpenStreetMap"
                }

                val distText = if (userLat != null && userLon != null) {
                    calculateDistance(userLat, userLon, lat, lon).second
                } else {
                    "Địa chỉ OSM"
                }

                onlineResults.add(
                    POI(
                        id = "osm_${lat}_${lon}",
                        name = name,
                        category = osmType,
                        categoryName = categoryIcon,
                        address = fullAddress,
                        latitude = lat,
                        longitude = lon,
                        distanceText = distText
                    )
                )
            }

            onlineResults
        } catch (e: Exception) {
            Log.w(TAG, "Online search error: ${e.message}")
            emptyList()
        }
    }

    /**
     * Unified Instant Search (Local Offline + Live OpenStreetMap Geocoding)
     */
    fun getByCategory(category: String, userLat: Double? = null, userLon: Double? = null): List<POI> {
        val list = localDatabase.filter { it.category == category }
        return if (userLat != null && userLon != null) {
            list.map { poi ->
                val (_, distText) = calculateDistance(userLat, userLon, poi.latitude, poi.longitude)
                poi.copy(distanceText = distText)
            }.sortedBy { poi ->
                calculateDistance(userLat, userLon, poi.latitude, poi.longitude).first
            }
        } else {
            list
        }
    }

    private const val PREFS_NAME = "magic_earth_favorites_prefs"
    private const val KEY_FAVORITES_JSON = "key_user_saved_pois"

    fun getAllSaved(context: Context, userLat: Double? = null, userLon: Double? = null): List<POI> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FAVORITES_JSON, null)
        val list: MutableList<POI> = if (!json.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<POI>>() {}.type
                Gson().fromJson<List<POI>>(json, type)?.toMutableList() ?: mutableListOf()
            } catch (e: Exception) {
                mutableListOf()
            }
        } else {
            // Initial seeded favorites (Home, Work, Hoan Kiem)
            mutableListOf(
                POI(
                    id = "fav_home",
                    name = "Nhà Riêng (Home)",
                    category = "saved",
                    categoryName = "⭐ Địa Điểm Đã Lưu",
                    address = "Phố Lê Giản, Phường Mộ Lao, Hà Đông, Hà Nội",
                    latitude = 20.9893,
                    longitude = 105.7643,
                    distanceText = "Gần bạn nhất"
                ),
                POI(
                    id = "fav_work",
                    name = "Nơi Làm Việc (Office)",
                    category = "saved",
                    categoryName = "⭐ Địa Điểm Đã Lưu",
                    address = "Đại Lộ Thăng Long, Nam Từ Liêm, Hà Nội",
                    latitude = 21.0068,
                    longitude = 105.7832,
                    distanceText = "3.2 km"
                ),
                POI(
                    id = "fav_hoankiem",
                    name = "Hồ Hoàn Kiếm (Trung Tâm)",
                    category = "saved",
                    categoryName = "⭐ Địa Điểm Đã Lưu",
                    address = "Đinh Tiên Hoàng, Hoàn Kiếm, Hà Nội",
                    latitude = 21.0285,
                    longitude = 105.8544,
                    distanceText = "9.5 km"
                )
            )
        }

        return if (userLat != null && userLon != null) {
            list.map { poi ->
                val (_, distText) = calculateDistance(userLat, userLon, poi.latitude, poi.longitude)
                poi.copy(distanceText = distText)
            }.sortedBy { poi ->
                calculateDistance(userLat, userLon, poi.latitude, poi.longitude).first
            }
        } else {
            list
        }
    }

    fun isFavorite(context: Context, poi: POI): Boolean {
        val list = getAllSaved(context)
        return list.any { 
            it.id == poi.id || 
            (it.name.equals(poi.name, ignoreCase = true) && Math.abs(it.latitude - poi.latitude) < 0.0005 && Math.abs(it.longitude - poi.longitude) < 0.0005)
        }
    }

    fun toggleFavorite(context: Context, poi: POI): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getAllSaved(context).toMutableList()
        val existingIndex = list.indexOfFirst {
            it.id == poi.id || 
            (it.name.equals(poi.name, ignoreCase = true) && Math.abs(it.latitude - poi.latitude) < 0.0005 && Math.abs(it.longitude - poi.longitude) < 0.0005)
        }

        val isNowSaved: Boolean
        if (existingIndex >= 0) {
            list.removeAt(existingIndex)
            isNowSaved = false
        } else {
            val savedPoi = poi.copy(
                category = "saved",
                categoryName = "⭐ Địa Điểm Đã Lưu"
            )
            list.add(0, savedPoi)
            isNowSaved = true
        }

        val json = Gson().toJson(list)
        prefs.edit().putString(KEY_FAVORITES_JSON, json).apply()
        return isNowSaved
    }

    /**
     * Reverse geocodes coordinates to a human-readable street/place name.
     * Checks local database first, then Photon/OSM reverse API, with fallback.
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String = withContext(Dispatchers.IO) {
        // 1. Check local POI database within 250m
        val nearestLocal = localDatabase.minByOrNull {
            calculateDistance(lat, lon, it.latitude, it.longitude).first
        }
        if (nearestLocal != null) {
            val (distMeters, _) = calculateDistance(lat, lon, nearestLocal.latitude, nearestLocal.longitude)
            if (distMeters <= 250.0) {
                return@withContext nearestLocal.name
            }
        }

        // 2. Query Photon reverse geocoding API
        try {
            val urlString = "https://photon.komoot.io/reverse?lat=$lat&lon=$lon"
            val url = URL(urlString)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", "MagicEarthAndroid/1.0")
            }

            if (connection.responseCode == 200) {
                val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(jsonStr).asJsonObject
                val features = root.getAsJsonArray("features")
                if (features != null && features.size() > 0) {
                    val props = features[0].asJsonObject.getAsJsonObject("properties")
                    val name = props.get("name")?.asString
                    val street = props.get("street")?.asString
                    val district = props.get("district")?.asString
                    val city = props.get("city")?.asString

                    val mainPart = name ?: street
                    if (!mainPart.isNullOrBlank()) {
                        val subPart = listOfNotNull(district, city).filter { it.isNotBlank() }.joinToString(", ")
                        return@withContext if (subPart.isNotEmpty()) "$mainPart, $subPart" else mainPart
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocoding online notice: ${e.message}")
        }

        // 3. Fallback to closest local landmark or formatted coordinates
        if (nearestLocal != null) {
            return@withContext "Khu vực ${nearestLocal.name}"
        }
        return@withContext "Điểm ghim (${String.format(Locale.US, "%.4f, %.4f", lat, lon)})"
    }
}
