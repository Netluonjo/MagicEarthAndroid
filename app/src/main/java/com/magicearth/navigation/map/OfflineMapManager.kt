package com.magicearth.navigation.map

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.magicearth.navigation.R
import com.magicearth.navigation.databinding.DialogOfflineMapsBinding
import com.magicearth.navigation.databinding.ItemOfflineRegionBinding
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.util.Locale

/**
 * Offline Map Package Manager
 * Handles downloading OpenStreetMap vector tiles, glyphs, and sprites
 * directly into MapLibre's on-device SQLite database.
 * Enables 100% offline map browsing, zooming, and turn-by-turn navigation
 * when mobile data or WiFi is disconnected.
 */
class OfflineMapManager(
    private val context: Context,
    private val mapProvider: () -> MapLibreMap? = { null }
) {
    companion object {
        private const val TAG = "OfflineMapManager"

        // Preset geographical bounding boxes in Vietnam
        val HANOI_BOUNDS: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(20.92, 105.70))
            .include(LatLng(21.14, 105.95))
            .build()

        val HCM_BOUNDS: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(10.68, 106.58))
            .include(LatLng(10.90, 106.84))
            .build()

        val DANANG_BOUNDS: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(15.86, 108.15))
            .include(LatLng(16.14, 108.35))
            .build()

        val HAIPHONG_BOUNDS: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(20.75, 106.60))
            .include(LatLng(21.00, 107.15))
            .build()

        val CANTHO_BOUNDS: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(9.95, 105.65))
            .include(LatLng(10.18, 105.85))
            .build()

        val NHATRANG_BOUNDS: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(12.18, 109.12))
            .include(LatLng(12.32, 109.25))
            .build()

        val DALAT_BOUNDS: LatLngBounds = LatLngBounds.Builder()
            .include(LatLng(11.88, 108.38))
            .include(LatLng(12.02, 108.50))
            .build()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val offlineManager = OfflineManager.getInstance(context).apply {
        // Allow downloading up to 100,000 tiles into on-device ambient SQLite database
        try {
            setOfflineMapboxTileCountLimit(100_000L)
        } catch (e: Exception) {
            Log.w(TAG, "Tile limit setting notice: ${e.message}")
        }
    }

    private var activeDownloadRegion: OfflineRegion? = null
    var isDownloading: Boolean = false
        private set

    data class DownloadedItem(
        val region: OfflineRegion,
        val name: String,
        val sizeBytes: Long,
        val completedResources: Long
    )

    fun showOfflineManagerDialog() {
        val binding = DialogOfflineMapsBinding.inflate(LayoutInflater.from(context))
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(binding.root)

        dialog.behavior.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = true
        }

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(R.drawable.bg_bottom_sheet_dialog)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        // 1. Storage Bar & Info
        updateStorageInfo(binding)

        // 2. Offline Simulation Switch (Allows user to test map rendering without 4G/Wifi)
        binding.switchOfflineMode.isChecked = !MapLibre.isConnected()
        binding.switchOfflineMode.setOnCheckedChangeListener { _, isOffline ->
            MapLibre.setConnected(!isOffline)
            val msg = if (isOffline) {
                "📴 Đã bật Chế độ Ngoại Tuyến (Ngắt mạng MapLibre). Bản đồ sẽ tải từ bộ nhớ máy."
            } else {
                "🌐 Đã bật kết nối mạng Internet cho MapLibre."
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        // 3. Refresh Downloaded Regions List
        fun refreshRegionsList() {
            loadDownloadedRegions { items ->
                mainHandler.post {
                    binding.layoutDownloadedRegions.removeAllViews()
                    if (items.isEmpty()) {
                        binding.tvNoDownloadedMaps.visibility = View.VISIBLE
                    } else {
                        binding.tvNoDownloadedMaps.visibility = View.GONE
                        val inflater = LayoutInflater.from(context)
                        for (item in items) {
                            val itemBinding = ItemOfflineRegionBinding.inflate(inflater, binding.layoutDownloadedRegions, false)
                            itemBinding.tvRegionName.text = item.name
                            val sizeMb = String.format(Locale.US, "%.1f MB", item.sizeBytes / (1024.0 * 1024.0))
                            itemBinding.tvRegionDetails.text = "✓ Sẵn sàng ngoại tuyến • $sizeMb (${item.completedResources} tài nguyên)"

                            itemBinding.btnDeleteRegion.setOnClickListener {
                                AlertDialog.Builder(context)
                                    .setTitle("Xóa bản đồ ngoại tuyến")
                                    .setMessage("Bạn có chắc muốn xóa bản đồ \"${item.name}\" để giải phóng dung lượng?")
                                    .setPositiveButton("Xóa") { _, _ ->
                                        item.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                            override fun onDelete() {
                                                mainHandler.post {
                                                    Toast.makeText(context, "Đã xóa bản đồ ${item.name}", Toast.LENGTH_SHORT).show()
                                                    refreshRegionsList()
                                                    updateStorageInfo(binding)
                                                }
                                            }

                                            override fun onError(error: String) {
                                                mainHandler.post {
                                                    Toast.makeText(context, "Lỗi khi xóa: $error", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        })
                                    }
                                    .setNegativeButton("Hủy", null)
                                    .show()
                            }
                            binding.layoutDownloadedRegions.addView(itemBinding.root)
                        }
                    }
                }
            }
        }
        refreshRegionsList()

        val allPresetButtons = listOf(
            binding.btnDownloadCurrentView,
            binding.btnDownloadHanoi,
            binding.btnDownloadHcm,
            binding.btnDownloadDanang,
            binding.btnDownloadHaiPhong,
            binding.btnDownloadCanTho,
            binding.btnDownloadNhaTrang,
            binding.btnDownloadDaLat
        )

        // 4. Download Execution Helper
        fun triggerDownload(
            name: String,
            bounds: LatLngBounds,
            targetButton: com.google.android.material.button.MaterialButton,
            minZ: Double = 10.0,
            maxZ: Double = 15.0
        ) {
            if (isDownloading) {
                Toast.makeText(context, "Đang có tiến trình tải bản đồ khác đang chạy!", Toast.LENGTH_SHORT).show()
                return
            }

            val originalButtonText = targetButton.text.toString()
            targetButton.text = "⏳ Đang tải $name..."
            allPresetButtons.forEach { it.isEnabled = false }
            binding.switchOfflineMode.isChecked = false

            binding.cardActiveDownload.visibility = View.VISIBLE
            binding.tvDownloadTitle.text = "Đang tải: $name"
            binding.pbDownloadProgress.isIndeterminate = true
            binding.tvDownloadStatus.text = "Đang kết nối OpenFreeMap tải dữ liệu vector..."

            // Auto-scroll to top so progress bar is directly visible
            binding.root.post {
                binding.root.smoothScrollTo(0, 0)
            }

            Toast.makeText(context, "🚀 Bắt đầu tải bản đồ $name...", Toast.LENGTH_SHORT).show()

            fun restoreButtons() {
                mainHandler.post {
                    targetButton.text = originalButtonText
                    allPresetButtons.forEach { it.isEnabled = true }
                }
            }

            startDownload(
                name = name,
                bounds = bounds,
                minZoom = minZ,
                maxZoom = maxZ,
                onProgress = { percent, completed, total, sizeBytes ->
                    mainHandler.post {
                        binding.cardActiveDownload.visibility = View.VISIBLE
                        binding.pbDownloadProgress.isIndeterminate = false
                        binding.pbDownloadProgress.progress = percent
                        val sizeMb = String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
                        binding.tvDownloadStatus.text = "$percent% ($completed/$total tài nguyên • $sizeMb)"
                    }
                },
                onComplete = {
                    mainHandler.post {
                        restoreButtons()
                        binding.cardActiveDownload.visibility = View.GONE
                        Toast.makeText(context, "🎉 Đã tải xong bản đồ \"$name\"! Bạn có thể sử dụng ngoại tuyến không cần 4G.", Toast.LENGTH_LONG).show()
                        refreshRegionsList()
                        updateStorageInfo(binding)
                    }
                },
                onError = { errorMsg ->
                    mainHandler.post {
                        restoreButtons()
                        binding.cardActiveDownload.visibility = View.GONE
                        Toast.makeText(context, "⚠️ Lỗi tải bản đồ: $errorMsg", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // 5. Preset Download Actions
        binding.btnDownloadCurrentView.setOnClickListener {
            val map = mapProvider()
            if (map != null) {
                try {
                    val currentBounds = map.projection.visibleRegion.latLngBounds
                    val currentZoom = map.cameraPosition.zoom
                    val minZ = maxOf(9.0, currentZoom - 1.0)
                    val maxZ = minOf(15.0, currentZoom + 2.0)
                    triggerDownload("Khu vực bản đồ đang xem", currentBounds, binding.btnDownloadCurrentView, minZ, maxZ)
                } catch (e: Exception) {
                    Toast.makeText(context, "Không thể lấy tọa độ màn hình: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(context, "Bản đồ chưa sẵn sàng, đang chuyển sang tải Hà Nội...", Toast.LENGTH_SHORT).show()
                triggerDownload("Hà Nội", HANOI_BOUNDS, binding.btnDownloadCurrentView)
            }
        }

        binding.btnDownloadHanoi.setOnClickListener {
            triggerDownload("Hà Nội", HANOI_BOUNDS, binding.btnDownloadHanoi, 10.0, 15.0)
        }

        binding.btnDownloadHcm.setOnClickListener {
            triggerDownload("TP. Hồ Chí Minh", HCM_BOUNDS, binding.btnDownloadHcm, 10.0, 15.0)
        }

        binding.btnDownloadDanang.setOnClickListener {
            triggerDownload("Đà Nẵng & Hội An", DANANG_BOUNDS, binding.btnDownloadDanang, 10.0, 15.0)
        }

        binding.btnDownloadHaiPhong.setOnClickListener {
            triggerDownload("Hải Phòng & Hạ Long", HAIPHONG_BOUNDS, binding.btnDownloadHaiPhong, 10.0, 15.0)
        }

        binding.btnDownloadCanTho.setOnClickListener {
            triggerDownload("Cần Thơ", CANTHO_BOUNDS, binding.btnDownloadCanTho, 10.0, 15.0)
        }

        binding.btnDownloadNhaTrang.setOnClickListener {
            triggerDownload("Nha Trang", NHATRANG_BOUNDS, binding.btnDownloadNhaTrang, 10.0, 15.0)
        }

        binding.btnDownloadDaLat.setOnClickListener {
            triggerDownload("Đà Lạt", DALAT_BOUNDS, binding.btnDownloadDaLat, 10.0, 15.0)
        }

        binding.btnCancelDownload.setOnClickListener {
            cancelActiveDownload()
            binding.cardActiveDownload.visibility = View.GONE
            allPresetButtons.forEach { it.isEnabled = true }
            Toast.makeText(context, "Đã hủy tải bản đồ", Toast.LENGTH_SHORT).show()
        }

        binding.btnCloseOfflineTop.setOnClickListener {
            dialog.dismiss()
        }

        binding.btnCloseOfflineDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Start downloading an offline region using MapLibre Native OfflineManager
     */
    fun startDownload(
        name: String,
        bounds: LatLngBounds,
        minZoom: Double = 10.0,
        maxZoom: Double = 15.0,
        onProgress: (percent: Int, completed: Long, total: Long, sizeBytes: Long) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        val pixelRatio = context.resources.displayMetrics.density
        val metadataJson = JSONObject().apply {
            put("name", name)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        val metadataBytes = metadataJson.toByteArray(Charsets.UTF_8)

        // Resilient download: try online Liberty style first, fallback to local asset style
        fun createRegionWithStyle(styleUrl: String, isFallback: Boolean = false) {
            try {
                MapLibre.setConnected(true)
                val definition = OfflineTilePyramidRegionDefinition(
                    styleUrl,
                    bounds,
                    minZoom,
                    maxZoom,
                    pixelRatio
                )

                offlineManager.createOfflineRegion(
                    definition,
                    metadataBytes,
                    object : OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(offlineRegion: OfflineRegion) {
                            activeDownloadRegion = offlineRegion
                            isDownloading = true
                            offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                                override fun onStatusChanged(status: OfflineRegionStatus) {
                                    val completed = status.completedResourceCount
                                    val total = status.requiredResourceCount
                                    val sizeBytes = status.completedResourceSize

                                    val percent = if (total > 0) {
                                        Math.min(100, ((100.0 * completed) / total).toInt())
                                    } else {
                                        0
                                    }

                                    onProgress(percent, completed, total, sizeBytes)

                                    if (status.isComplete) {
                                        isDownloading = false
                                        activeDownloadRegion = null
                                        onComplete()
                                    }
                                }

                                override fun onError(error: OfflineRegionError) {
                                    isDownloading = false
                                    activeDownloadRegion = null
                                    Log.e(TAG, "Offline download observer error: ${error.reason} - ${error.message}")
                                    onError("${error.reason}: ${error.message}")
                                }

                                override fun mapboxTileCountLimitExceeded(limit: Long) {
                                    Log.w(TAG, "Offline tile limit exceeded ($limit)")
                                }
                            })

                            offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        }

                        override fun onError(error: String) {
                            Log.e(TAG, "Create offline region error with $styleUrl: $error")
                            if (!isFallback) {
                                Log.i(TAG, "Falling back to local asset style...")
                                createRegionWithStyle(MapEngineManager.STYLE_DETAILED_ASSET, true)
                            } else {
                                isDownloading = false
                                activeDownloadRegion = null
                                onError(error)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Exception in createRegionWithStyle: ${e.message}", e)
                if (!isFallback) {
                    createRegionWithStyle(MapEngineManager.STYLE_DETAILED_ASSET, true)
                } else {
                    isDownloading = false
                    activeDownloadRegion = null
                    onError(e.message ?: "Lỗi tạo gói tải bản đồ")
                }
            }
        }

        createRegionWithStyle("https://tiles.openfreemap.org/styles/liberty")
    }

    fun cancelActiveDownload() {
        isDownloading = false
        activeDownloadRegion?.let { reg ->
            try {
                reg.setDownloadState(OfflineRegion.STATE_INACTIVE)
                reg.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {
                        activeDownloadRegion = null
                    }
                    override fun onError(error: String) {
                        activeDownloadRegion = null
                    }
                })
            } catch (e: Exception) {
                Log.w(TAG, "Cancel notice: ${e.message}")
            }
        }
        activeDownloadRegion = null
    }

    fun loadDownloadedRegions(callback: (List<DownloadedItem>) -> Unit) {
        offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                if (offlineRegions == null || offlineRegions.isEmpty()) {
                    callback(emptyList())
                    return
                }

                val resultList = mutableListOf<DownloadedItem>()
                var pendingCount = offlineRegions.size

                for (region in offlineRegions) {
                    val name = try {
                        val metaStr = String(region.metadata, Charsets.UTF_8)
                        val json = JSONObject(metaStr)
                        json.optString("name", "Bản đồ ngoại tuyến #${region.id}")
                    } catch (e: Exception) {
                        "Bản đồ ngoại tuyến #${region.id}"
                    }

                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            synchronized(resultList) {
                                resultList.add(
                                    DownloadedItem(
                                        region = region,
                                        name = name,
                                        sizeBytes = status?.completedResourceSize ?: 0L,
                                        completedResources = status?.completedResourceCount ?: 0L
                                    )
                                )
                                pendingCount--
                                if (pendingCount == 0) {
                                    callback(resultList)
                                }
                            }
                        }

                        override fun onError(error: String?) {
                            synchronized(resultList) {
                                resultList.add(
                                    DownloadedItem(
                                        region = region,
                                        name = name,
                                        sizeBytes = 0L,
                                        completedResources = 0L
                                    )
                                )
                                pendingCount--
                                if (pendingCount == 0) {
                                    callback(resultList)
                                }
                            }
                        }
                    })
                }
            }

            override fun onError(error: String) {
                callback(emptyList())
            }
        })
    }

    private fun updateStorageInfo(binding: DialogOfflineMapsBinding) {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableGb = availableBytes / (1024.0 * 1024.0 * 1024.0)
            val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            val usedPercent = if (totalBytes > 0) (((totalBytes - availableBytes) * 100.0) / totalBytes).toInt() else 0

            binding.pbStorage.progress = usedPercent
            binding.tvStorageUsage.text = String.format(Locale.US, "Bộ nhớ thiết bị: Còn trống %.1f GB / %.0f GB (Đã dùng %d%%)", availableGb, totalGb, usedPercent)
        } catch (e: Exception) {
            binding.tvStorageUsage.text = "Bộ nhớ thiết bị: Sẵn sàng lưu trữ"
        }
    }
}
