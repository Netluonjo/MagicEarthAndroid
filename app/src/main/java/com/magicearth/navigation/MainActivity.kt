package com.magicearth.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.magicearth.navigation.databinding.ActivityMainBinding
import com.magicearth.navigation.databinding.BottomSheetWikiBinding
import com.magicearth.navigation.databinding.DialogIncidentReportBinding
import com.magicearth.navigation.databinding.DialogMapLayersBinding
import com.magicearth.navigation.databinding.DialogRunnerWeightBinding
import com.magicearth.navigation.databinding.DialogRunningSummaryBinding
import com.magicearth.navigation.databinding.DialogTruckConfigBinding
import com.magicearth.navigation.hud.HUDActivity
import com.magicearth.navigation.map.CommunityIncidentRepository
import com.magicearth.navigation.map.MapEngineManager
import com.magicearth.navigation.map.OfflineMapManager
import com.magicearth.navigation.map.PoiRepository
import com.magicearth.navigation.model.*
import com.magicearth.navigation.navigation.NavigationSession
import com.magicearth.navigation.navigation.NavigationStateBridge
import com.magicearth.navigation.navigation.VoiceGuidanceManager
import com.magicearth.navigation.routing.RoutingEngine
import com.magicearth.navigation.service.GPXRecorderService
import com.magicearth.navigation.service.SmartwatchNavigationService
import com.magicearth.navigation.ui.PoiAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Main Activity of Magic Earth Navigation & Maps
 * Integrates:
 * 1. 2D/3D & Multi-layer Vector/Satellite/Terrain/Dark MapLibre Native Map
 * 2. On-device Offline POI Search & Wikipedia Landmark Integration
 * 3. Multi-modal Routing with Full Polyline Visual Drawing (Car, Truck, Bike, Walk, Transit)
 * 4. Turn-by-Turn In-Drive Experience (TTS Voice, Lane Assistance, Highway Signposts, Speed Alert, HUD)
 * 5. Real-time Services (Live Traffic, Weather Along Route, Community Incident Reporting)
 * 6. GPX Track Foreground Recording & Export
 * 7. Clean Architecture & 100% Privacy Preservation
 */
class MainActivity : AppCompatActivity(), NavigationSession.NavigationListener {

    private lateinit var binding: ActivityMainBinding
    private var mapEngine: MapEngineManager? = null
    private lateinit var voiceManager: VoiceGuidanceManager
    private lateinit var routingEngine: RoutingEngine
    private lateinit var offlineManager: OfflineMapManager
    private var navSession: NavigationSession? = null

    private lateinit var poiAdapter: PoiAdapter
    private var currentTransportMode = TransportMode.CAR
    private var isNavigating = false
    private var isTrafficEnabled = false

    private var selectedPoi: POI? = null
    private var routeJob: Job? = null

    private var lastManeuver: Maneuver? = null
    private var lastSpeed = 0
    private var lastLimit = 80

    // High-Accuracy GPS Telemetry
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var hasInitialLocationFix = false
    private var searchJob: Job? = null

    // Live Traffic, Route Coordinates & Realtime Incidents
    private var currentRouteCoordinates: List<LatLng>? = null
    private var currentRouteSteps: List<RouteStep> = emptyList()
    private var currentRouteDistKm: Double = 0.0
    private var pendingAlternativeRoute: AlternativeRouteSuggestion? = null
    private var currentAlertIncident: CommunityIncident? = null

    // Running Route Studio
    private var isDrawingRunningRoute = false
    private var isSnapToRoadsEnabled = true
    private var currentCustomDrawnPoints = listOf<LatLng>()
    private var currentRunningDistKm = 0.0

    companion object {
        private const val TAG = "MainActivity"
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            setupLocationTracking()
        } else {
            Toast.makeText(this, "Vui lòng cho phép quyền vị trí để định vị chính xác bạn đang ở đâu", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MapLibre Native SDK
        MapLibre.getInstance(this)

        // Keep navigation screen awake and turn on screen
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)

        // Initialize Subsystems
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        voiceManager = VoiceGuidanceManager(this)
        routingEngine = RoutingEngine()
        offlineManager = OfflineMapManager(this) { mapEngine?.getMap() }
        navSession = NavigationSession(voiceManager, this)

        setupPoiRecyclerView()
        setupSearchAndChips()
        setupToolbarActions()
        setupRoutePlanning()
        setupRunningRouteStudio()
        setupRealtimeAlerts()
        updateMuteButtonUI()

        // Android Auto & Smartwatch Action Handlers
        NavigationStateBridge.onRequestStopNav = {
            runOnUiThread { stopNavigation() }
        }
        NavigationStateBridge.onRequestToggleMute = {
            runOnUiThread { binding.btnVoiceMute.performClick() }
        }

        // Handle system back gesture
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isDrawingRunningRoute) {
                    toggleRunningRouteStudio(false)
                } else if (isNavigating) {
                    stopNavigation()
                } else if (binding.routePlannerCard.visibility == View.VISIBLE) {
                    binding.routePlannerCard.visibility = View.GONE
                    binding.searchCard.visibility = View.VISIBLE
                    mapEngine?.clearRoute()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Map Callback
        binding.mapView.getMapAsync { map ->
            onMapReady(map)
        }
    }

    private fun onMapReady(map: MapLibreMap) {
        mapEngine = MapEngineManager(this, map)
        binding.routeDrawingOverlay.map = map
        update2D3DButtonText(false)
        mapEngine?.renderCommunityIncidents(CommunityIncidentRepository.getAllActiveIncidents())
        
        // Tap or Long-press anywhere directly on the map to pin destination & view route planner preview
        mapEngine?.onMapClickListener = { latLng ->
            if (!isDrawingRunningRoute) {
                handleSelectDestination(latLng)
            }
        }
        mapEngine?.onMapLongClickListener = { latLng ->
            if (!isDrawingRunningRoute) {
                handleSelectDestination(latLng)
            }
        }

        checkAndRequestLocationPermission()
    }

    // ==========================================
    // 1. MAP CONTROLS & LAYERS
    // ==========================================
    private fun setupToolbarActions() {
        // 2D / 3D Perspective Toggle Badge Button
        binding.btnToggle2D3D.setOnClickListener {
            val is3D = mapEngine?.toggle3D() ?: false
            update2D3DButtonText(is3D)
            val modeMsg = if (is3D) "🏙️ Chế độ 3D bật (Góc nghiêng 60° • Khối nhà OSM)" else "🗺️ Chế độ 2D mặt phẳng tiêu chuẩn (North-Up)"
            Toast.makeText(this, modeMsg, Toast.LENGTH_SHORT).show()
        }

        // Map Layers & Styles Dialog (Vector 2D, Vector 3D, Satellite, Terrain, Dark)
        binding.fabLayers.setOnClickListener {
            showMapLayersDialog()
        }

        // Traffic Overlay Toggle
        binding.fabTraffic.setOnClickListener {
            isTrafficEnabled = !isTrafficEnabled
            mapEngine?.toggleTrafficLayer(isTrafficEnabled)
            if (isTrafficEnabled) {
                binding.fabTraffic.imageTintList = getColorStateList(R.color.me_accent_green)
                Toast.makeText(this, "🚦 Đã bật lớp Giao thông trực tiếp (Live Traffic Flow)", Toast.LENGTH_SHORT).show()
            } else {
                binding.fabTraffic.imageTintList = getColorStateList(R.color.me_text_secondary)
                Toast.makeText(this, "Đã tắt lớp Giao thông", Toast.LENGTH_SHORT).show()
            }
        }

        // Community Incident Reporting Dialog
        binding.fabReportIncident.setOnClickListener {
            showIncidentReportDialog()
        }

        // GPX Trip Recording Toggle
        binding.fabRecordGPX.setOnClickListener {
            toggleGpxRecording()
        }

        // Recenter to user's exact current location
        binding.fabRecenter.setOnClickListener {
            mapEngine?.recenterToUserLocation()
            if (mapEngine?.userLocation != null) {
                Toast.makeText(this, "📍 Đã di chuyển về vị trí chính xác của bạn", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Đang kết nối vệ tinh GPS...", Toast.LENGTH_SHORT).show()
                setupLocationTracking()
            }
        }

        // Offline Maps Manager Dialog
        binding.btnOfflineMaps.setOnClickListener {
            offlineManager.showOfflineManagerDialog()
        }

        // HUD Trigger Button
        binding.btnEnterHUD.setOnClickListener {
            val intent = Intent(this, HUDActivity::class.java).apply {
                putExtra("EXTRA_STREET", lastManeuver?.street ?: (selectedPoi?.name ?: "Đang dò đường"))
                putExtra("EXTRA_DIST", lastManeuver?.distanceText ?: (if (selectedPoi != null) "Đang dẫn đường" else "--"))
                putExtra("EXTRA_ICON", lastManeuver?.icon ?: "➔")
                putExtra("EXTRA_SPEED", lastSpeed)
                putExtra("EXTRA_LIMIT", lastLimit)
                if (selectedPoi != null) {
                    putExtra("EXTRA_DEST_LAT", selectedPoi!!.latitude)
                    putExtra("EXTRA_DEST_LON", selectedPoi!!.longitude)
                    putExtra("EXTRA_DEST_NAME", selectedPoi!!.name)
                }
            }
            startActivity(intent)
        }

        // Stop Navigation Buttons (Bottom Bar and Top Signpost)
        binding.btnStopNav.setOnClickListener {
            stopNavigation()
        }
        binding.btnStopNavTop.setOnClickListener {
            stopNavigation()
        }

        // Voice Guidance Language Switcher
        binding.btnVoiceLanguage.setOnClickListener {
            showVoiceLanguageDialog()
        }

        // Voice Guidance Mute / Unmute Toggle
        binding.btnVoiceMute.setOnClickListener {
            voiceManager.isMuted = !voiceManager.isMuted
            updateMuteButtonUI()
            NavigationStateBridge.updateMute(voiceManager.isMuted)
            if (voiceManager.isMuted) {
                Toast.makeText(this, "🔇 Đã tắt tiếng chỉ dẫn giọng nói", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "🔊 Đã bật chỉ dẫn bằng giọng nói", Toast.LENGTH_SHORT).show()
            }
        }
        updateMuteButtonUI()

        // Dismiss Camera Alert Card
        binding.btnDismissCamera.setOnClickListener {
            binding.cardCameraAlert.visibility = View.GONE
        }
    }

    private fun update2D3DButtonText(is3D: Boolean = mapEngine?.is3DMode ?: false) {
        if (is3D) {
            binding.tv2D3D.text = "2D"
            binding.tv2D3D.setTextColor(getColor(R.color.me_sign_gold))
        } else {
            binding.tv2D3D.text = "3D"
            binding.tv2D3D.setTextColor(getColor(R.color.me_accent_cyan))
        }
    }

    private fun showMapLayersDialog() {
        val dialogBinding = DialogMapLayersBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Đóng", null)
            .create()

        when (mapEngine?.currentStyle) {
            MapStyleType.VECTOR_2D -> dialogBinding.rbVector2D.isChecked = true
            MapStyleType.VECTOR_3D -> dialogBinding.rbVector3D.isChecked = true
            MapStyleType.SATELLITE -> dialogBinding.rbSatellite.isChecked = true
            MapStyleType.TERRAIN -> dialogBinding.rbTerrain.isChecked = true
            MapStyleType.DARK -> dialogBinding.rbDark.isChecked = true
            else -> dialogBinding.rbVector2D.isChecked = true
        }

        dialogBinding.rgMapLayers.setOnCheckedChangeListener { _, checkedId ->
            val styleType = when (checkedId) {
                R.id.rbVector2D -> MapStyleType.VECTOR_2D
                R.id.rbVector3D -> MapStyleType.VECTOR_3D
                R.id.rbSatellite -> MapStyleType.SATELLITE
                R.id.rbTerrain -> MapStyleType.TERRAIN
                R.id.rbDark -> MapStyleType.DARK
                else -> MapStyleType.VECTOR_2D
            }
            mapEngine?.switchStyle(styleType)
            update2D3DButtonText()
            Toast.makeText(this, "Đã chuyển sang: ${dialogBinding.root.findViewById<RadioButton>(checkedId).text}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showIncidentReportDialog() {
        val dialogBinding = DialogIncidentReportBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        val sendReport = { type: IncidentType, typeStr: String ->
            dialog.dismiss()
            val userLoc = mapEngine?.userLocation
                ?: mapEngine?.getMap()?.locationComponent?.lastKnownLocation?.let { LatLng(it.latitude, it.longitude) }
                ?: LatLng(21.0028, 105.8185)

            val currentRoad = lastManeuver?.street ?: (selectedPoi?.name ?: "Tuyến đường hiện tại")
            CommunityIncidentRepository.reportIncident(
                type = type,
                title = "Báo cáo: $typeStr",
                roadName = currentRoad,
                lat = userLoc.latitude,
                lon = userLoc.longitude
            )
            mapEngine?.renderCommunityIncidents(CommunityIncidentRepository.getAllActiveIncidents())
            Toast.makeText(this, "✅ Đã ghi nhận [${type.icon} $typeStr] tại $currentRoad! Điểm sự cố đã được cập nhật cho cộng đồng.", Toast.LENGTH_LONG).show()
        }

        dialogBinding.btnIncidentAccident.setOnClickListener { sendReport(IncidentType.ACCIDENT, "Tai nạn giao thông") }
        dialogBinding.btnIncidentCamera.setOnClickListener { sendReport(IncidentType.SPEED_TRAP, "Camera / Bắn tốc độ") }
        dialogBinding.btnIncidentRoadwork.setOnClickListener { sendReport(IncidentType.ROADWORK, "Công trường thi công") }
        dialogBinding.btnIncidentHazard.setOnClickListener { sendReport(IncidentType.HAZARD, "Chướng ngại vật / Ngập nước") }

        dialog.show()
    }

    private fun toggleGpxRecording() {
        if (!GPXRecorderService.isRecording) {
            startService(Intent(this, GPXRecorderService::class.java).apply {
                action = GPXRecorderService.ACTION_START
            })
            binding.fabRecordGPX.imageTintList = getColorStateList(R.color.me_accent_green)
            Toast.makeText(this, "Đã bắt đầu ghi log GPS hành trình (100% On-Device)!", Toast.LENGTH_SHORT).show()
        } else {
            startService(Intent(this, GPXRecorderService::class.java).apply {
                action = GPXRecorderService.ACTION_STOP
            })
            binding.fabRecordGPX.imageTintList = getColorStateList(R.color.me_accent_red)
            Toast.makeText(this, "Đã kết thúc hành trình và xuất file GPX chuẩn!", Toast.LENGTH_LONG).show()
        }
    }

    // ==========================================
    // 2. SEARCH, CATEGORIES & WIKIPEDIA
    // ==========================================
    private fun setupPoiRecyclerView() {
        poiAdapter = PoiAdapter(
            emptyList(),
            onFavoriteToggled = { poi, isSaved ->
                val msg = if (isSaved) "⭐ Đã lưu \"${poi.name}\" vào địa điểm yêu thích!" else "Đã bỏ lưu \"${poi.name}\""
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                if (selectedPoi?.id == poi.id) {
                    updateFavoriteButtonState(isSaved)
                }
            },
            onPoiClicked = { poi ->
                onPoiSelected(poi)
            }
        )
        binding.rvPoiResults.layoutManager = LinearLayoutManager(this)
        binding.rvPoiResults.adapter = poiAdapter

        binding.btnClosePoiResults.setOnClickListener {
            binding.poiResultsCard.visibility = View.GONE
        }
    }

    private fun setupSearchAndChips() {
        // Text Search
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                searchJob?.cancel()

                if (query.isNotEmpty()) {
                    val userLat = mapEngine?.userLocation?.latitude
                    val userLon = mapEngine?.userLocation?.longitude

                    // 1. Instant local search with Vietnamese accent normalization & distance calculation (0ms)
                    val localResults = PoiRepository.searchLocal(query, userLat, userLon)
                    if (localResults.isNotEmpty()) {
                        displayPoiResults("Kết quả (${localResults.size} địa điểm)", localResults)
                    }

                    // 2. Debounced live OpenStreetMap Photon search (300ms)
                    searchJob = lifecycleScope.launch {
                        delay(300)
                        val onlineResults = PoiRepository.searchOnlinePhoton(query, userLat, userLon)

                        // Merge without duplicates
                        val combined = mutableListOf<POI>()
                        combined.addAll(localResults)
                        for (online in onlineResults) {
                            val alreadyExists = combined.any {
                                it.name.equals(online.name, ignoreCase = true) ||
                                (Math.abs(it.latitude - online.latitude) < 0.0008 && Math.abs(it.longitude - online.longitude) < 0.0008)
                            }
                            if (!alreadyExists) {
                                combined.add(online)
                            }
                        }

                        if (combined.isNotEmpty()) {
                            displayPoiResults("Kết quả tìm kiếm (${combined.size} địa điểm)", combined)
                        } else if (localResults.isEmpty()) {
                            binding.poiResultsCard.visibility = View.GONE
                        }
                    }
                } else {
                    binding.poiResultsCard.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Chips with dynamic distance from user location
        binding.chipFuel.setOnClickListener {
            val userLat = mapEngine?.userLocation?.latitude
            val userLon = mapEngine?.userLocation?.longitude
            displayPoiResults("⛽ Cây xăng & Trạm dầu gần bạn nhất", PoiRepository.getByCategory("fuel", userLat, userLon))
        }
        binding.chipEV.setOnClickListener {
            val userLat = mapEngine?.userLocation?.latitude
            val userLon = mapEngine?.userLocation?.longitude
            displayPoiResults("⚡ Trạm sạc xe điện EV VinFast gần bạn", PoiRepository.getByCategory("ev", userLat, userLon))
        }
        binding.chipParking.setOnClickListener {
            val userLat = mapEngine?.userLocation?.latitude
            val userLon = mapEngine?.userLocation?.longitude
            displayPoiResults("🅿️ Bãi đỗ xe thông minh & Tầng ngầm", PoiRepository.getByCategory("parking", userLat, userLon))
        }
        binding.chipSights.setOnClickListener {
            val userLat = mapEngine?.userLocation?.latitude
            val userLon = mapEngine?.userLocation?.longitude
            displayPoiResults("🏛️ Danh lam di tích & Wikipedia", PoiRepository.getByCategory("sight", userLat, userLon))
        }
        binding.chipSaved.setOnClickListener {
            val userLat = mapEngine?.userLocation?.latitude
            val userLon = mapEngine?.userLocation?.longitude
            val savedList = PoiRepository.getAllSaved(this, userLat, userLon)
            displayPoiResults("⭐ Địa điểm yêu thích (${savedList.size} địa điểm)", savedList)
        }
    }

    private fun displayPoiResults(title: String, list: List<POI>) {
        if (list.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy địa điểm ngoại tuyến phù hợp", Toast.LENGTH_SHORT).show()
            binding.poiResultsCard.visibility = View.GONE
            return
        }
        binding.tvPoiResultsTitle.text = title
        poiAdapter.updateList(list)
        binding.poiResultsCard.visibility = View.VISIBLE
    }

    private fun onPoiSelected(poi: POI) {
        selectedPoi = poi
        binding.poiResultsCard.visibility = View.GONE
        mapEngine?.moveTo(poi.latitude, poi.longitude)

        // Update destination title & address on route card
        binding.tvDestinationTitle.text = poi.name
        binding.tvDestinationAddress.text = poi.address
        binding.tvDestinationAddress.visibility = if (poi.address.isNotEmpty()) View.VISIBLE else View.GONE

        // Update favorite button state
        val isFav = PoiRepository.isFavorite(this, poi)
        updateFavoriteButtonState(isFav)

        binding.btnSaveFavorite.setOnClickListener {
            val nowSaved = PoiRepository.toggleFavorite(this, poi)
            updateFavoriteButtonState(nowSaved)
            val msg = if (nowSaved) "⭐ Đã lưu \"${poi.name}\" vào địa điểm yêu thích!" else "Đã bỏ lưu \"${poi.name}\""
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        if (poi.hasWiki) {
            showWikipediaBottomSheet(poi)
        } else {
            handleSelectDestination(LatLng(poi.latitude, poi.longitude), poi)
        }
    }

    private fun updateFavoriteButtonState(isSaved: Boolean) {
        if (isSaved) {
            binding.btnSaveFavorite.text = "★ Đã lưu"
            binding.btnSaveFavorite.setTextColor(getColor(R.color.me_sign_gold))
        } else {
            binding.btnSaveFavorite.text = "☆ Lưu"
            binding.btnSaveFavorite.setTextColor(getColor(R.color.me_text_secondary))
        }
    }

    private fun showWikipediaBottomSheet(poi: POI) {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = BottomSheetWikiBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)

        sheetBinding.tvWikiCategory.text = poi.categoryName
        sheetBinding.tvWikiTitle.text = poi.name
        sheetBinding.tvWikiSnippet.text = poi.wikiSnippet ?: ""

        sheetBinding.btnWikiRouteHere.setOnClickListener {
            sheet.dismiss()
            handleSelectDestination(LatLng(poi.latitude, poi.longitude), poi)
        }

        sheetBinding.btnWikiSaveFav.setOnClickListener {
            val nowSaved = PoiRepository.toggleFavorite(this, poi)
            updateFavoriteButtonState(nowSaved)
            val msg = if (nowSaved) "⭐ Đã ghim \"${poi.name}\" vào danh sách yêu thích!" else "Đã bỏ lưu \"${poi.name}\""
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            sheet.dismiss()
        }

        sheet.show()
    }

    // ==========================================
    // 3. MULTI-MODAL ROUTE PLANNING & ROUTE LINE RENDERING
    // ==========================================
    private fun setupRoutePlanning() {
        binding.btnOpenRoutePlanner.setOnClickListener {
            binding.routePlannerCard.visibility = View.VISIBLE
            if (selectedPoi != null) {
                calculateAndDisplayRoute(selectedPoi!!)
            } else {
                updateRouteMetrics()
            }
        }

        binding.btnCloseRoutePlanner.setOnClickListener {
            binding.routePlannerCard.visibility = View.GONE
            binding.searchCard.visibility = View.VISIBLE
            mapEngine?.clearRoute()
            selectedPoi = null
        }

        // Mode Switchers
        binding.tabCar.setOnClickListener { setTransportMode(TransportMode.CAR) }
        binding.tabTruck.setOnClickListener { setTransportMode(TransportMode.TRUCK) }
        binding.tabBike.setOnClickListener { setTransportMode(TransportMode.BICYCLE) }
        binding.tabWalk.setOnClickListener { setTransportMode(TransportMode.PEDESTRIAN) }
        binding.tabTransit.setOnClickListener { setTransportMode(TransportMode.TRANSIT) }

        // Truck Config Dialog
        binding.btnTruckConfig.setOnClickListener {
            showTruckConfigDialog()
        }

        // Add Waypoint
        binding.btnAddWaypoint.setOnClickListener {
            routingEngine.addWaypoint(Waypoint("Cầu Nhật Tân (Điểm C)", 21.0820, 105.8190, 1))
            binding.tvWaypointsSummary.text = "Hành trình: Điểm A ➔ Điểm C (Dừng) ➔ Điểm B"
            binding.btnAddWaypoint.isEnabled = false
            if (selectedPoi != null) {
                calculateAndDisplayRoute(selectedPoi!!)
            } else {
                updateRouteMetrics()
            }
            Toast.makeText(this, "Đã thêm điểm dừng C (Cầu Nhật Tân) & tối ưu hóa chặng!", Toast.LENGTH_SHORT).show()
        }

        // Start Navigation (Real GPS by default, long press for demo simulation)
        binding.btnStartNavigation.setOnClickListener {
            startNavigation(enableSimulation = false)
        }
        binding.btnStartNavigation.setOnLongClickListener {
            Toast.makeText(this, "🚗 Bật chế độ chạy mô phỏng lộ trình (Demo)", Toast.LENGTH_SHORT).show()
            startNavigation(enableSimulation = true)
            true
        }
    }

    private fun setTransportMode(mode: TransportMode) {
        currentTransportMode = mode

        // Reset tab highlights
        val normalColor = getColor(R.color.me_text_secondary)
        val activeColor = getColor(R.color.me_accent_cyan)

        binding.tabCar.setTextColor(if (mode == TransportMode.CAR) activeColor else normalColor)
        binding.tabTruck.setTextColor(if (mode == TransportMode.TRUCK) activeColor else normalColor)
        binding.tabBike.setTextColor(if (mode == TransportMode.BICYCLE) activeColor else normalColor)
        binding.tabWalk.setTextColor(if (mode == TransportMode.PEDESTRIAN) activeColor else normalColor)
        binding.tabTransit.setTextColor(if (mode == TransportMode.TRANSIT) activeColor else normalColor)

        binding.btnTruckConfig.visibility = if (mode == TransportMode.TRUCK) View.VISIBLE else View.GONE

        if (selectedPoi != null) {
            calculateAndDisplayRoute(selectedPoi!!)
        } else {
            updateRouteMetrics()
        }
    }

    private fun showTruckConfigDialog() {
        val dialogBinding = DialogTruckConfigBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.etTruckWeight.setText(routingEngine.truckConfig.weightTons.toString())
        dialogBinding.etTruckHeight.setText(routingEngine.truckConfig.heightMeters.toString())
        dialogBinding.etTruckAxles.setText(routingEngine.truckConfig.axles.toString())

        dialogBinding.btnSaveTruckConfig.setOnClickListener {
            val weight = dialogBinding.etTruckWeight.text.toString().toDoubleOrNull() ?: 18.0
            val height = dialogBinding.etTruckHeight.text.toString().toDoubleOrNull() ?: 3.9
            val axles = dialogBinding.etTruckAxles.text.toString().toIntOrNull() ?: 3

            routingEngine.truckConfig = TruckConfig(weight, height, axles)
            dialog.dismiss()
            if (selectedPoi != null) {
                calculateAndDisplayRoute(selectedPoi!!)
            } else {
                updateRouteMetrics()
            }
            Toast.makeText(this, "Đã lưu thông số xe tải: $weight tấn, cao $height m, $axles trục!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    /**
     * Calculate route from user location to destination and render glowing route polyline
     */
    private fun getSmartRouteOrigin(destination: LatLng): LatLng {
        val userLoc = mapEngine?.userLocation
            ?: mapEngine?.getMap()?.locationComponent?.lastKnownLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: run {
                try {
                    val last = fusedLocationClient.lastLocation.result
                    if (last != null) LatLng(last.latitude, last.longitude) else null
                } catch (e: Exception) { null }
            }

        // If user has real GPS within reasonable range (< 120 km) of destination, use it
        if (userLoc != null) {
            val distResults = FloatArray(1)
            android.location.Location.distanceBetween(
                userLoc.latitude, userLoc.longitude,
                destination.latitude, destination.longitude,
                distResults
            )
            if (distResults[0] < 120_000f) {
                return userLoc
            }
        }

        // Check if map camera center is in the same city as destination
        val cameraTarget = mapEngine?.getMap()?.cameraPosition?.target
        if (cameraTarget != null) {
            val distToCam = FloatArray(1)
            android.location.Location.distanceBetween(
                cameraTarget.latitude, cameraTarget.longitude,
                destination.latitude, destination.longitude,
                distToCam
            )
            if (distToCam[0] in 250f..35_000f) {
                return cameraTarget
            }
        }

        // Regional intelligent starting node in the target city
        return when {
            // TP. Hồ Chí Minh
            destination.latitude in 10.5..11.2 && destination.longitude in 106.3..107.2 -> {
                val distToBenthanh = FloatArray(1)
                android.location.Location.distanceBetween(destination.latitude, destination.longitude, 10.7725, 106.6980, distToBenthanh)
                if (distToBenthanh[0] < 600f) {
                    LatLng(10.7797, 106.6990) // Nhà thờ Đức Bà
                } else {
                    LatLng(10.7725, 106.6980) // Chợ Bến Thành
                }
            }
            // Đà Nẵng
            destination.latitude in 15.8..16.3 && destination.longitude in 108.0..108.5 -> {
                LatLng(16.0610, 108.2272) // Cầu Rồng
            }
            // Hải Phòng
            destination.latitude in 20.7..21.0 && destination.longitude in 106.5..107.2 -> {
                LatLng(20.8600, 106.6830) // Nhà hát lớn Hải Phòng
            }
            // Cần Thơ
            destination.latitude in 9.9..10.3 && destination.longitude in 105.5..106.0 -> {
                LatLng(10.0335, 105.7875) // Bến Ninh Kiều
            }
            // Nha Trang
            destination.latitude in 12.1..12.4 && destination.longitude in 109.1..109.3 -> {
                LatLng(12.2388, 109.1965) // Tháp Trầm Hương
            }
            // Đà Lạt
            destination.latitude in 11.8..12.1 && destination.longitude in 108.3..108.6 -> {
                LatLng(11.9404, 108.4380) // Hồ Xuân Hương
            }
            // Hà Nội fallback
            else -> {
                userLoc ?: LatLng(21.0285, 105.8544)
            }
        }
    }

    /**
     * Calculate route from user location to destination and render glowing route polyline
     */
    private fun calculateAndDisplayRoute(poi: POI) {
        routeJob?.cancel()

        val destination = LatLng(poi.latitude, poi.longitude)
        val origin = getSmartRouteOrigin(destination)

        binding.tvRouteSummaryStats.text = "Đang tìm đường tối ưu tới ${poi.name}..."
        binding.tvSurfaceQuality.text = "Tối ưu hóa dữ liệu giao thông OpenStreetMap..."

        routeJob = lifecycleScope.launch {
            try {
                val result = routingEngine.calculateRouteWithCoordinates(origin, destination, currentTransportMode)

                val calExtra = if (currentTransportMode == TransportMode.PEDESTRIAN) {
                    val cal = RunningCalorieCalculator.calculateCalories(result.metrics.distanceKm)
                    " • 🔥 ~$cal kcal"
                } else ""
                binding.tvRouteSummaryStats.text = "${result.metrics.distanceKm} km • ${result.metrics.durationMinutes} phút (Đến lúc ${result.metrics.etaString})$calExtra • ${result.metrics.trafficCondition}"
                binding.tvSurfaceQuality.text = "Bề mặt: ${result.metrics.surfacePavedPct}% Nhựa, ${result.metrics.surfaceUnpavedPct}% Bê tông"
                binding.elevationProfileView.invalidate()

                currentRouteCoordinates = result.coordinates
                currentRouteSteps = result.steps
                currentRouteDistKm = result.metrics.distanceKm

                // Draw line on map and frame camera
                mapEngine?.drawRoute(result.coordinates, destination)
            } catch (e: Exception) {
                Log.e(TAG, "Error calculating route: ${e.message}", e)
                Toast.makeText(this@MainActivity, "Không thể tải đường đi: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateRouteMetrics() {
        if (selectedPoi != null) {
            calculateAndDisplayRoute(selectedPoi!!)
            return
        }

        val origin = mapEngine?.userLocation ?: LatLng(20.9893, 105.7643)
        val destination = LatLng(21.0285, 105.8544)
        val metrics = routingEngine.calculateRoute(origin, destination, currentTransportMode)

        val calExtra = if (currentTransportMode == TransportMode.PEDESTRIAN) {
            val cal = RunningCalorieCalculator.calculateCalories(metrics.distanceKm)
            " • 🔥 ~$cal kcal"
        } else ""
        binding.tvRouteSummaryStats.text = "${metrics.distanceKm} km • ${metrics.durationMinutes} phút (Đến lúc ${metrics.etaString})$calExtra • ${metrics.trafficCondition}"
        binding.tvSurfaceQuality.text = "Bề mặt: ${metrics.surfacePavedPct}% Nhựa, ${metrics.surfaceUnpavedPct}% Bê tông"
        binding.elevationProfileView.invalidate()
    }

    // ==========================================
    // 3b. CUSTOM RUNNING ROUTE STUDIO
    // ==========================================
    private fun setupRunningRouteStudio() {
        // FAB and Chip triggers
        binding.fabDrawRunningRoute.setOnClickListener {
            toggleRunningRouteStudio(true)
        }
        binding.chipDrawRunningRoute.setOnClickListener {
            toggleRunningRouteStudio(true)
        }

        // Close Studio Button
        binding.btnCloseRunningStudio.setOnClickListener {
            toggleRunningRouteStudio(false)
        }

        // Tap Calories in Running Studio to configure body weight
        binding.layoutRunCalories.setOnClickListener {
            showRunnerWeightDialog()
        }

        // Mode Selector: Draw Mode vs Inspect/Pan Mode
        binding.btnModeDraw.setOnClickListener {
            setStudioInteractionMode(isDrawing = true)
        }

        binding.btnModeInspect.setOnClickListener {
            setStudioInteractionMode(isDrawing = false)
        }

        // Synchronize UI when overlay changes mode automatically
        binding.routeDrawingOverlay.onModeChanged = { isDrawing ->
            updateStudioModeUI(isDrawing)
        }

        // Auto-snap to roads immediately when finger lifts up!
        binding.routeDrawingOverlay.onDrawingStrokeFinished = { points ->
            autoSnapRunningRouteToRoads(points)
        }

        // Live drawing metrics update listener
        binding.routeDrawingOverlay.onRouteUpdated = { points, distKm ->
            updateRunningStudioMetrics(points, distKm)
        }

        // Fit Route Bounds button
        binding.btnFitRoute.setOnClickListener {
            if (currentCustomDrawnPoints.isNotEmpty()) {
                mapEngine?.fitRouteBounds(currentCustomDrawnPoints, paddingBottom = 420)
                Toast.makeText(this, "🎯 Đã căn giữa toàn bộ tuyến chạy", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Chưa có cung đường nào được vẽ", Toast.LENGTH_SHORT).show()
            }
        }

        // Explicit Snap to Road button
        binding.btnSnapRoad.setOnClickListener {
            val pts = binding.routeDrawingOverlay.getAllPoints()
            if (pts.size >= 2) {
                Toast.makeText(this, "🧲 Đang nắn lại cung đường theo tim đường bản đồ...", Toast.LENGTH_SHORT).show()
                autoSnapRunningRouteToRoads(pts)
            } else {
                Toast.makeText(this, "Chưa có cung đường nào được vẽ để nắn đường", Toast.LENGTH_SHORT).show()
            }
        }

        // Close Loop Circuit Button
        binding.btnCloseLoop.setOnClickListener {
            binding.routeDrawingOverlay.closeLoop()
            val points = binding.routeDrawingOverlay.getAllPoints()
            if (points.size >= 3) {
                Toast.makeText(this, "🔄 Đã khép kín vòng chạy! Đang tự động nắn đường...", Toast.LENGTH_SHORT).show()
                autoSnapRunningRouteToRoads(points)
            }
        }

        // Undo Last Stroke
        binding.btnUndoDraw.setOnClickListener {
            binding.routeDrawingOverlay.undoLastStroke()
            val pts = binding.routeDrawingOverlay.getAllPoints()
            if (pts.size >= 2) {
                autoSnapRunningRouteToRoads(pts)
            } else {
                mapEngine?.clearRoute()
                updateRunningStudioMetrics(emptyList(), 0.0)
                setStudioInteractionMode(isDrawing = true)
            }
        }

        // Clear All Strokes
        binding.btnClearDraw.setOnClickListener {
            binding.routeDrawingOverlay.clear()
            mapEngine?.clearRoute()
            updateRunningStudioMetrics(emptyList(), 0.0)
            setStudioInteractionMode(isDrawing = true)
            Toast.makeText(this, "Đã xóa toàn bộ nét vẽ. Bạn có thể bắt đầu vẽ lại!", Toast.LENGTH_SHORT).show()
        }

        // Start Running Workout
        binding.btnStartRunningWorkout.setOnClickListener {
            startRunningWorkout()
        }
    }

    private fun setStudioInteractionMode(isDrawing: Boolean) {
        binding.routeDrawingOverlay.isTouchDrawingActive = isDrawing
        updateStudioModeUI(isDrawing)
        if (isDrawing) {
            Toast.makeText(this, "✏️ Dùng 1 ngón tay vuốt trên bản đồ để vẽ tiếp cung đường!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "👁️ Đã bật chế độ vuốt xem! Dùng 1 ngón tay vuốt bản đồ để xem chi tiết.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStudioModeUI(isDrawing: Boolean) {
        if (isDrawing) {
            binding.btnModeDraw.setBackgroundColor(Color.parseColor("#76FF03"))
            binding.btnModeDraw.setTextColor(Color.parseColor("#0B0E14"))
            binding.btnModeDraw.text = "✏️ Chế độ vẽ"

            binding.btnModeInspect.setBackgroundColor(Color.parseColor("#2A3546"))
            binding.btnModeInspect.setTextColor(getColor(R.color.me_text_primary))
            binding.btnModeInspect.text = "👁️ Vuốt xem bản đồ"

            binding.tvRunningStudioStatus.text = "✏️ Dùng 1 ngón tay vuốt vẽ cung đường mong muốn"
        } else {
            binding.btnModeDraw.setBackgroundColor(Color.parseColor("#2A3546"))
            binding.btnModeDraw.setTextColor(getColor(R.color.me_text_primary))
            binding.btnModeDraw.text = "✏️ Vẽ tiếp"

            binding.btnModeInspect.setBackgroundColor(Color.parseColor("#00E5FF"))
            binding.btnModeInspect.setTextColor(Color.parseColor("#0B0E14"))
            binding.btnModeInspect.text = "👁️ Đang vuốt xem"

            binding.tvRunningStudioStatus.text = "👁️ Dùng 1 ngón tay vuốt bản đồ để xem chi tiết"
        }
    }

    private fun autoSnapRunningRouteToRoads(points: List<LatLng>) {
        if (points.size < 2) return
        binding.tvRunningStudioStatus.text = "🧲 Đang nắn chuẩn theo tim đường trên bản đồ..."
        lifecycleScope.launch {
            val result = routingEngine.buildCustomRunningRoute(points, snapToOsm = true)
            if (result.coordinates.size >= 2) {
                currentCustomDrawnPoints = result.coordinates
                binding.routeDrawingOverlay.setPoints(result.coordinates)
                updateRunningStudioMetrics(result.coordinates, result.metrics.distanceKm)
                mapEngine?.drawRoute(result.coordinates, result.coordinates.last())
                mapEngine?.fitRouteBounds(result.coordinates, paddingBottom = 420)
                if (result.isSnappedToRoads) {
                    binding.tvRunningStudioStatus.text = "✅ Đã nắn chuẩn theo tim đường • Vuốt 1 ngón để xem"
                    Toast.makeText(this@MainActivity, "🧲 Đã nắn cung đường chạy khớp theo tim đường trên bản đồ!", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvRunningStudioStatus.text = "✅ Tuyến chạy tự do (ngoài đường lớn) • Vuốt 1 ngón để xem"
                }
            } else {
                binding.tvRunningStudioStatus.text = "✅ Tuyến chạy tự do • Vuốt 1 ngón để xem chi tiết"
                mapEngine?.fitRouteBounds(points, paddingBottom = 420)
            }
        }
    }

    private fun toggleRunningRouteStudio(enable: Boolean) {
        isDrawingRunningRoute = enable
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        binding.etSearch.clearFocus()

        if (enable) {
            if (isNavigating) stopNavigation()
            binding.routePlannerCard.visibility = View.GONE
            binding.poiResultsCard.visibility = View.GONE
            binding.searchCard.visibility = View.GONE
            binding.cardRunningStudio.visibility = View.VISIBLE
            binding.routeDrawingOverlay.isDrawingEnabled = true

            // Pass map instance to overlay for camera sync
            mapEngine?.getMap()?.let {
                binding.routeDrawingOverlay.map = it
            }

            // Start in Drawing mode
            setStudioInteractionMode(isDrawing = true)

            // Set transport mode to PEDESTRIAN
            currentTransportMode = TransportMode.PEDESTRIAN

            Toast.makeText(
                this,
                "🏃 Chế độ vẽ đường chạy: Dùng 1 ngón tay vuốt để vẽ. Vẽ xong sẽ tự động nắn đường và cho phép vuốt xem bản đồ!",
                Toast.LENGTH_LONG
            ).show()
        } else {
            binding.cardRunningStudio.visibility = View.GONE
            binding.routeDrawingOverlay.isDrawingEnabled = false
            if (!isNavigating) {
                binding.searchCard.visibility = View.VISIBLE
                mapEngine?.clearRoute()
            }
        }
    }

    private fun updateRunningStudioMetrics(points: List<LatLng>, distKm: Double) {
        currentCustomDrawnPoints = points
        currentRunningDistKm = distKm

        binding.tvRunDistance.text = String.format(Locale.US, "%.2f", distKm)

        // Running pace standard: 6.0 min/km (~10 km/h)
        val minutes = Math.max(1, (distKm * 6.0).roundToInt())
        binding.tvRunDuration.text = if (distKm > 0.05) "$minutes" else "0"

        // Running calories strictly calculated based on distance and runner body weight (ACSM formula)
        val weight = RunningCalorieCalculator.getUserWeight(this)
        val calories = RunningCalorieCalculator.calculateCalories(distKm, weight)
        binding.tvRunCalories.text = if (distKm > 0.05) "$calories" else "0"
        binding.tvRunCaloriesWeightLabel.text = "kcal (${weight.toInt()}kg ⚙️)"

        // Also render on mapEngine polyline if finished stroke
        if (points.size >= 2) {
            mapEngine?.drawRoute(points, points.last())
        }
    }

    private fun startRunningWorkout() {
        if (currentCustomDrawnPoints.size < 2 || currentRunningDistKm < 0.05) {
            Toast.makeText(this, "Vui lòng dùng ngón tay vẽ cung đường chạy bộ trước trên bản đồ!", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = routingEngine.buildCustomRunningRoute(
                drawnPoints = currentCustomDrawnPoints,
                snapToOsm = true,
                targetPaceMinPerKm = 6.0
            )

            currentRouteCoordinates = result.coordinates
            currentRouteSteps = result.steps
            currentRouteDistKm = result.metrics.distanceKm

            // Exit studio mode and start running navigation
            binding.cardRunningStudio.visibility = View.GONE
            binding.routeDrawingOverlay.isDrawingEnabled = false
            isDrawingRunningRoute = false

            // CRITICAL: Hide all conflicting cards to prevent overlapping text/controls
            binding.searchCard.visibility = View.GONE
            binding.topBrandBar.visibility = View.GONE
            binding.poiResultsCard.visibility = View.GONE
            binding.routePlannerCard.visibility = View.GONE

            isNavigating = true
            binding.signpostCard.visibility = View.VISIBLE
            binding.laneAssistView.visibility = View.GONE
            binding.driveBottomBar.visibility = View.VISIBLE
            binding.btnEnterHUD.visibility = View.GONE
            binding.cardCameraAlert.visibility = View.GONE
            updateVoiceLanguageButton()
            updateMuteButtonUI()

            // Setup running workout telemetry on drive bottom bar
            binding.tvSpeedLimit.text = "🏃"
            binding.tvSpeedLimit.setBackgroundResource(R.drawable.bg_glass_card)
            binding.tvLiveSpeed.text = "--:--"
            binding.tvLiveSpeedUnit.text = "min/km"
            binding.tvLiveSpeed.setTextColor(getColor(R.color.me_text_primary))
            binding.layoutRunnerLiveCalories.visibility = View.VISIBLE
            binding.tvLiveCalories.text = "0"
            binding.tvSignBadge.text = "🏃 CHẠY BỘ"
            binding.tvSignExitBadge.visibility = View.GONE
            binding.tvDriveEta.text = "Thời gian 00:00"
            binding.tvDriveRemDist.text = String.format(Locale.US, "Còn %.1f km", result.metrics.distanceKm)

            val userLoc = mapEngine?.getMap()?.locationComponent?.lastKnownLocation
                ?: mapEngine?.userLocation?.let {
                    Location("fused").apply {
                        latitude = it.latitude
                        longitude = it.longitude
                    }
                }

            val startDest = result.coordinates.last()
            val runnerWeight = RunningCalorieCalculator.getUserWeight(this@MainActivity)
            navSession?.start(
                dest = startDest,
                destName = "Cung đường chạy bộ (${result.metrics.distanceKm} km)",
                initialLocation = userLoc,
                routeCoordinates = result.coordinates,
                totalDistKm = result.metrics.distanceKm,
                enableSimulation = false,
                steps = result.steps,
                isRunnerMode = true,
                runnerWeightKg = runnerWeight
            )

            mapEngine?.drawRoute(result.coordinates, startDest)
            mapEngine?.recenterToUserLocation()
        }
    }

    // ==========================================
    // 4. TURN-BY-TURN & REALTIME NAVIGATION
    // ==========================================
    /**
     * Select any point on the map or POI, calculate optimal route, and show Route Planner preview.
     * Real GPS tracking is strictly preserved: GPS does NOT move until the user physically moves.
     */
    private fun handleSelectDestination(latLng: LatLng, poiOverride: POI? = null) {
        if (isNavigating) {
            stopNavigation()
        }

        val basePoi = poiOverride ?: POI(
            id = "tap_${System.currentTimeMillis()}",
            name = "Điểm đã chọn",
            category = "custom",
            categoryName = "Điểm ghim bản đồ",
            address = String.format(Locale.US, "%.5f, %.5f", latLng.latitude, latLng.longitude),
            latitude = latLng.latitude,
            longitude = latLng.longitude,
            hasWiki = false
        )
        selectedPoi = basePoi

        // Close search & results cards, show route planner card
        binding.poiResultsCard.visibility = View.GONE
        binding.searchCard.visibility = View.GONE
        binding.routePlannerCard.visibility = View.VISIBLE

        // Update destination title & address
        binding.tvDestinationTitle.text = basePoi.name
        binding.tvDestinationAddress.text = basePoi.address
        binding.tvDestinationAddress.visibility = View.VISIBLE

        val isFav = PoiRepository.isFavorite(this, basePoi)
        updateFavoriteButtonState(isFav)
        binding.btnSaveFavorite.setOnClickListener {
            val nowSaved = PoiRepository.toggleFavorite(this, basePoi)
            updateFavoriteButtonState(nowSaved)
            val msg = if (nowSaved) "⭐ Đã lưu \"${basePoi.name}\" vào địa điểm yêu thích!" else "Đã bỏ lưu \"${basePoi.name}\""
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Reverse geocode to get actual street name in background if custom tap
        if (poiOverride == null) {
            lifecycleScope.launch {
                val resolvedName = PoiRepository.reverseGeocode(latLng.latitude, latLng.longitude)
                if (resolvedName.isNotBlank() && !resolvedName.startsWith("Điểm ghim (")) {
                    selectedPoi = selectedPoi?.copy(name = resolvedName, address = resolvedName)
                    binding.tvDestinationTitle.text = resolvedName
                    binding.tvDestinationAddress.text = resolvedName
                }
            }
        }

        calculateAndDisplayRoute(basePoi)
    }

    private fun startNavigation(enableSimulation: Boolean = false) {
        isNavigating = true
        val isRunnerMode = currentTransportMode == TransportMode.PEDESTRIAN
        binding.routePlannerCard.visibility = View.GONE
        binding.searchCard.visibility = View.GONE
        binding.poiResultsCard.visibility = View.GONE
        binding.topBrandBar.visibility = View.GONE

        binding.signpostCard.visibility = View.VISIBLE
        binding.laneAssistView.visibility = if (isRunnerMode) View.GONE else View.VISIBLE
        binding.driveBottomBar.visibility = View.VISIBLE
        binding.btnEnterHUD.visibility = if (isRunnerMode) View.GONE else View.VISIBLE

        if (isRunnerMode) {
            binding.layoutRunnerLiveCalories.visibility = View.VISIBLE
            binding.tvLiveCalories.text = "0"
            binding.tvSpeedLimit.text = "🏃"
            binding.tvSpeedLimit.setBackgroundResource(R.drawable.bg_glass_card)
            binding.tvLiveSpeed.text = "--:--"
            binding.tvLiveSpeedUnit.text = "min/km"
            binding.tvLiveSpeed.setTextColor(getColor(R.color.me_text_primary))
            binding.tvSignBadge.text = "🏃 ĐI BỘ / CHẠY BỘ"
            binding.tvSignExitBadge.visibility = View.GONE
            binding.tvDriveEta.text = "Thời gian 00:00"
            binding.tvDriveRemDist.text = String.format(Locale.US, "Còn %.1f km", currentRouteDistKm)
        } else {
            binding.layoutRunnerLiveCalories.visibility = View.GONE
            binding.tvSpeedLimit.setBackgroundResource(R.drawable.bg_speed_limit)
            binding.tvLiveSpeedUnit.text = getString(R.string.speed_unit)
            binding.tvLiveSpeed.text = "0"
        }

        binding.cardCameraAlert.visibility = View.GONE
        updateVoiceLanguageButton()
        updateMuteButtonUI()

        val destLatLng = if (selectedPoi != null) LatLng(selectedPoi!!.latitude, selectedPoi!!.longitude) else null
        val destName = selectedPoi?.name ?: if (isRunnerMode) "Cung đường chạy bộ" else "Điểm đến"
        val userLoc = mapEngine?.getMap()?.locationComponent?.lastKnownLocation
            ?: mapEngine?.userLocation?.let {
                Location("fused").apply {
                    latitude = it.latitude
                    longitude = it.longitude
                }
            }
            ?: currentRouteCoordinates?.firstOrNull()?.let {
                Location("route_start").apply {
                    latitude = it.latitude
                    longitude = it.longitude
                }
            }

        val runnerWeight = RunningCalorieCalculator.getUserWeight(this@MainActivity)
        navSession?.start(
            dest = destLatLng,
            destName = destName,
            initialLocation = userLoc,
            routeCoordinates = currentRouteCoordinates,
            totalDistKm = currentRouteDistKm,
            enableSimulation = enableSimulation,
            steps = currentRouteSteps,
            isRunnerMode = isRunnerMode,
            runnerWeightKg = runnerWeight
        )
        try {
            SmartwatchNavigationService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "Smartwatch service start notice: ${e.message}")
        }

        mapEngine?.recenterToUserLocation()
    }

    private fun stopNavigation() {
        if (!isNavigating) return
        val wasRunnerMode = navSession?.isRunnerMode == true
        val distRun = navSession?.totalRunDistanceKm ?: 0.0
        val burnedCal = navSession?.totalBurnedCalories ?: 0
        val workoutStart = navSession?.workoutStartTimeMs ?: 0L

        isNavigating = false
        navSession?.stop()
        voiceManager.stop()
        try {
            SmartwatchNavigationService.stop(this)
        } catch (e: Exception) {}

        binding.signpostCard.visibility = View.GONE
        binding.laneAssistView.visibility = View.GONE
        binding.driveBottomBar.visibility = View.GONE
        binding.btnEnterHUD.visibility = View.VISIBLE
        binding.layoutRunnerLiveCalories.visibility = View.GONE
        binding.tvSpeedLimit.setBackgroundResource(R.drawable.bg_speed_limit)
        binding.tvLiveSpeedUnit.text = getString(R.string.speed_unit)
        binding.cardCameraAlert.visibility = View.GONE
        binding.cardWeatherAlert.visibility = View.GONE
        binding.cardIncidentAlert.visibility = View.GONE
        binding.cardRerouteSuggestion.visibility = View.GONE
        pendingAlternativeRoute = null
        currentAlertIncident = null

        // Restore normal browsing cards
        binding.searchCard.visibility = View.VISIBLE
        binding.topBrandBar.visibility = View.VISIBLE

        mapEngine?.clearRoute()
        selectedPoi = null
        Toast.makeText(this, "Đã dừng chế độ dẫn đường", Toast.LENGTH_SHORT).show()

        if (wasRunnerMode && distRun >= 0.05) {
            val durationSec = maxOf(1L, (System.currentTimeMillis() - workoutStart) / 1000L)
            val avgPace = if (distRun > 0.01) (durationSec / 60.0) / distRun else 6.0
            showRunningWorkoutSummaryDialog(distRun, burnedCal, durationSec, avgPace)
        }
    }

    private fun showVoiceLanguageDialog() {
        val options = arrayOf(
            "🇻🇳 Tiếng Việt (Giọng đọc tự nhiên kèm tên đường)",
            "🇺🇸 English (Natural US Voice with streets)",
            "🇫🇷 Français"
        )
        val currentIdx = when (voiceManager.currentLanguage) {
            VoiceLanguage.VIETNAMESE -> 0
            VoiceLanguage.ENGLISH -> 1
            VoiceLanguage.FRENCH -> 2
        }
        AlertDialog.Builder(this)
            .setTitle("Chọn giọng nói chỉ dẫn")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val selectedLang = when (which) {
                    0 -> VoiceLanguage.VIETNAMESE
                    1 -> VoiceLanguage.ENGLISH
                    else -> VoiceLanguage.FRENCH
                }
                voiceManager.setLanguage(selectedLang)
                updateVoiceLanguageButton()
                val msg = when (selectedLang) {
                    VoiceLanguage.VIETNAMESE -> "🇻🇳 Đã chuyển sang Tiếng Việt"
                    VoiceLanguage.ENGLISH -> "🇺🇸 Switched to US English"
                    VoiceLanguage.FRENCH -> "🇫🇷 Passé au français"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun updateVoiceLanguageButton() {
        binding.btnVoiceLanguage.text = when (voiceManager.currentLanguage) {
            VoiceLanguage.VIETNAMESE -> "🇻🇳 VI"
            VoiceLanguage.ENGLISH -> "🇺🇸 EN"
            VoiceLanguage.FRENCH -> "🇫🇷 FR"
        }
    }

    private fun updateMuteButtonUI() {
        if (voiceManager.isMuted) {
            binding.btnVoiceMute.setImageResource(R.drawable.ic_voice_off)
            binding.btnVoiceMute.setColorFilter(getColor(R.color.me_accent_red))
            binding.btnVoiceMute.contentDescription = "Bật tiếng chỉ dẫn"
        } else {
            binding.btnVoiceMute.setImageResource(R.drawable.ic_voice_on)
            binding.btnVoiceMute.setColorFilter(getColor(R.color.me_text_primary))
            binding.btnVoiceMute.contentDescription = "Tắt tiếng chỉ dẫn"
        }
    }

    private fun setupRealtimeAlerts() {
        // Weather Alert Dismiss
        binding.btnDismissWeather.setOnClickListener {
            binding.cardWeatherAlert.visibility = View.GONE
        }

        // Incident Alert Card Actions
        binding.btnDismissIncident.setOnClickListener {
            binding.cardIncidentAlert.visibility = View.GONE
        }
        binding.btnIncidentStillThere.setOnClickListener {
            currentAlertIncident?.let {
                CommunityIncidentRepository.confirmIncident(it.id)
                Toast.makeText(this, "Cảm ơn bạn đã xác nhận sự cố vẫn còn!", Toast.LENGTH_SHORT).show()
            }
            binding.cardIncidentAlert.visibility = View.GONE
        }
        binding.btnIncidentCleared.setOnClickListener {
            currentAlertIncident?.let {
                CommunityIncidentRepository.clearIncident(it.id)
                mapEngine?.renderCommunityIncidents(CommunityIncidentRepository.getAllActiveIncidents())
                Toast.makeText(this, "Cảm ơn bạn! Sự cố đã được đánh dấu là đã giải tỏa.", Toast.LENGTH_SHORT).show()
            }
            binding.cardIncidentAlert.visibility = View.GONE
        }

        // Reroute Suggestion Card Actions
        binding.btnDismissReroute.setOnClickListener {
            binding.cardRerouteSuggestion.visibility = View.GONE
        }
        binding.btnIgnoreReroute.setOnClickListener {
            binding.cardRerouteSuggestion.visibility = View.GONE
        }
        binding.btnAcceptReroute.setOnClickListener {
            pendingAlternativeRoute?.let { alt ->
                binding.cardRerouteSuggestion.visibility = View.GONE
                val targetDest = navSession?.destination ?: LatLng(21.0285, 105.8544)
                mapEngine?.drawRoute(alt.coordinates, targetDest)
                currentRouteCoordinates = alt.coordinates
                voiceManager.speakRerouteNotice(alt.savedMinutes)
                Toast.makeText(this, "⚡ Đã chuyển sang: ${alt.name} (Nhanh hơn ${alt.savedMinutes} phút)", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Navigation Session Callbacks
    override fun onManeuverUpdated(maneuver: Maneuver) {
        lastManeuver = maneuver
        binding.laneAssistView.laneStates = maneuver.lanes
    }

    override fun onSignpostUpdated(signpost: SignpostData) {
        // 1. Dynamic Authentic Color Simulation (Expressway Green vs National Blue)
        val bgRes = if (signpost.theme == SignpostTheme.EXPRESSWAY_GREEN) {
            R.drawable.bg_signpost_highway
        } else {
            R.drawable.bg_signpost_blue
        }
        binding.signpostCard.setBackgroundResource(bgRes)

        // 2. Route Shield Badge (e.g. CT.01 / AH1 or QL.1A or 🏃 CHẠY BỘ)
        if (navSession?.isRunnerMode == true) {
            binding.tvSignBadge.text = "🏃 CHẠY BỘ"
        } else {
            binding.tvSignBadge.text = signpost.routeCode
        }

        // 3. Exit Badge (e.g. LỐI RA 08 / EXIT 08) - hidden in runner mode
        if (signpost.exitBadge != null && navSession?.isRunnerMode != true) {
            binding.tvSignExitBadge.visibility = View.VISIBLE
            binding.tvSignExitBadge.text = signpost.exitBadge
        } else {
            binding.tvSignExitBadge.visibility = View.GONE
        }

        // 4. Destination & Turn Distance
        binding.tvSignTurnIcon.text = signpost.turnIcon
        binding.tvSignDest.text = signpost.destinationName
        binding.tvSignDist.text = signpost.distanceText
    }

    override fun onCameraAlert(camera: TrafficCamera, distanceMeters: Int) {
        binding.cardCameraAlert.visibility = View.VISIBLE
        binding.tvCameraIcon.text = camera.type.icon
        binding.tvCameraTitle.text = camera.type.titleVi
        binding.tvCameraRoadName.text = "${camera.name} (${camera.roadName})"
        binding.tvCameraDistance.text = "Cách $distanceMeters m • Tốc độ tối đa ${camera.speedLimit} km/h"
        binding.tvCameraSpeedLimit.text = camera.speedLimit.toString()
    }

    override fun onCameraCleared() {
        binding.cardCameraAlert.visibility = View.GONE
    }

    override fun onSpeedUpdated(speedKmh: Int, speedLimit: Int, isOverspeed: Boolean) {
        if (navSession?.isRunnerMode == true || currentTransportMode == TransportMode.PEDESTRIAN) return // DO NOT OVERWRITE RUNNER PACE & TELEMETRY
        lastSpeed = speedKmh
        lastLimit = speedLimit
        binding.tvLiveSpeed.text = speedKmh.toString()
        binding.tvLiveSpeedUnit.text = getString(R.string.speed_unit)
        binding.tvSpeedLimit.text = speedLimit.toString()

        if (isOverspeed) {
            binding.tvLiveSpeed.setTextColor(getColor(R.color.me_accent_red))
            binding.tvSpeedLimit.setBackgroundResource(R.drawable.bg_speed_limit)
        } else {
            binding.tvLiveSpeed.setTextColor(getColor(R.color.me_text_primary))
            binding.tvSpeedLimit.setBackgroundResource(R.drawable.bg_speed_limit)
        }
    }

    override fun onRemainingUpdated(distanceRemKm: Double, etaString: String) {
        if (navSession?.isRunnerMode == true || currentTransportMode == TransportMode.PEDESTRIAN) return // DO NOT OVERWRITE RUNNER METRICS
        binding.tvDriveRemDist.text = String.format(Locale.US, "%.1f km còn lại", distanceRemKm)
        binding.tvDriveEta.text = "Đến lúc $etaString"
    }

    override fun onIncidentAlert(incident: CommunityIncident, distanceMeters: Int) {
        currentAlertIncident = incident
        binding.cardIncidentAlert.visibility = View.VISIBLE
        binding.tvIncidentIcon.text = incident.type.icon
        binding.tvIncidentTitle.text = incident.type.titleVi
        binding.tvIncidentRoad.text = "Cách $distanceMeters m trên ${incident.roadName}"
        binding.tvIncidentDesc.text = incident.description
    }

    override fun onIncidentCleared() {
        currentAlertIncident = null
        binding.cardIncidentAlert.visibility = View.GONE
    }

    override fun onWeatherAlert(weather: RouteWeatherSegment) {
        binding.cardWeatherAlert.visibility = View.VISIBLE
        binding.tvWeatherIcon.text = weather.condition.icon
        binding.tvWeatherTitle.text = "Cảnh báo: ${weather.condition.titleVi}"
        binding.tvWeatherDesc.text = "${weather.condition.advisory} (${weather.locationName} • ${weather.temperatureC}°C)"
    }

    override fun onWeatherCleared() {
        binding.cardWeatherAlert.visibility = View.GONE
    }

    override fun onAlternativeRouteAvailable(suggestion: AlternativeRouteSuggestion) {
        pendingAlternativeRoute = suggestion
        binding.cardRerouteSuggestion.visibility = View.VISIBLE
        binding.tvRerouteTitle.text = "⚡ Tuyến tránh nhanh hơn ${suggestion.savedMinutes} phút!"
        binding.tvRerouteDesc.text = "${suggestion.name}: ${suggestion.reason}"
    }

    override fun onNavigationFinished() {
        if (!isNavigating) return
        val wasRunnerMode = navSession?.isRunnerMode == true
        val distRun = navSession?.totalRunDistanceKm ?: 0.0
        val burnedCal = navSession?.totalBurnedCalories ?: 0
        val workoutStart = navSession?.workoutStartTimeMs ?: 0L

        stopNavigation()
        if (wasRunnerMode && distRun >= 0.05) {
            val durationSec = maxOf(1L, (System.currentTimeMillis() - workoutStart) / 1000L)
            val avgPace = if (distRun > 0.01) (durationSec / 60.0) / distRun else 6.0
            showRunningWorkoutSummaryDialog(distRun, burnedCal, durationSec, avgPace)
        } else {
            Toast.makeText(this, "🎉 Bạn đã tới điểm đến! Kết thúc hành trình dẫn đường.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRunnerMetricsUpdated(
        distanceRunKm: Double,
        caloriesBurned: Int,
        paceMinPerKm: Double,
        durationSeconds: Long
    ) {
        binding.layoutRunnerLiveCalories.visibility = View.VISIBLE
        binding.tvLiveCalories.text = caloriesBurned.toString()
        val paceText = if (paceMinPerKm <= 0.0 || paceMinPerKm.isInfinite() || paceMinPerKm.isNaN() || paceMinPerKm > 30.0) {
            "--:--"
        } else {
            RunningCalorieCalculator.formatPace(paceMinPerKm).replace(" /km", "")
        }
        binding.tvLiveSpeed.text = paceText
        binding.tvLiveSpeedUnit.text = "min/km"
        binding.tvLiveSpeed.setTextColor(getColor(R.color.me_text_primary))
        binding.tvSpeedLimit.text = "🏃"
        binding.tvSpeedLimit.setBackgroundResource(R.drawable.bg_glass_card)

        val remDist = maxOf(0.0, currentRouteDistKm - distanceRunKm)
        binding.tvDriveRemDist.text = String.format(Locale.US, "Còn %.1f km", remDist)
        val formattedDuration = RunningCalorieCalculator.formatDuration(durationSeconds)
        binding.tvDriveEta.text = "Thời gian $formattedDuration"
    }

    private fun showRunnerWeightDialog() {
        val dialogBinding = DialogRunnerWeightBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        val currentWeight = RunningCalorieCalculator.getUserWeight(this)
        dialogBinding.etRunnerWeight.setText(String.format(Locale.US, "%.1f", currentWeight))

        fun updatePreview(w: Double) {
            val calPerKm = RunningCalorieCalculator.calculateCalories(1.0, w)
            dialogBinding.tvCalorieFormulaPreview.text = "🔥 Mỗi 1.0 km chạy bạn sẽ tiêu hao ~$calPerKm kcal năng lượng"
        }
        updatePreview(currentWeight)

        fun selectChip(chipId: Int, weight: Double) {
            dialogBinding.chipGroupWeight.check(chipId)
            dialogBinding.etRunnerWeight.setText(String.format(Locale.US, "%.1f", weight))
            updatePreview(weight)
        }

        when (currentWeight.toInt()) {
            50 -> dialogBinding.chip50kg.isChecked = true
            55 -> dialogBinding.chip55kg.isChecked = true
            60 -> dialogBinding.chip60kg.isChecked = true
            65 -> dialogBinding.chip65kg.isChecked = true
            70 -> dialogBinding.chip70kg.isChecked = true
            75 -> dialogBinding.chip75kg.isChecked = true
            80 -> dialogBinding.chip80kg.isChecked = true
            else -> dialogBinding.chipGroupWeight.clearCheck()
        }

        dialogBinding.chip50kg.setOnClickListener { selectChip(R.id.chip50kg, 50.0) }
        dialogBinding.chip55kg.setOnClickListener { selectChip(R.id.chip55kg, 55.0) }
        dialogBinding.chip60kg.setOnClickListener { selectChip(R.id.chip60kg, 60.0) }
        dialogBinding.chip65kg.setOnClickListener { selectChip(R.id.chip65kg, 65.0) }
        dialogBinding.chip70kg.setOnClickListener { selectChip(R.id.chip70kg, 70.0) }
        dialogBinding.chip75kg.setOnClickListener { selectChip(R.id.chip75kg, 75.0) }
        dialogBinding.chip80kg.setOnClickListener { selectChip(R.id.chip80kg, 80.0) }

        dialogBinding.etRunnerWeight.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val w = s?.toString()?.toDoubleOrNull() ?: 65.0
                updatePreview(w)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialogBinding.btnCancelWeight.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSaveWeight.setOnClickListener {
            val weight = dialogBinding.etRunnerWeight.text.toString().toDoubleOrNull() ?: 65.0
            RunningCalorieCalculator.setUserWeight(this, weight)
            updateRunningStudioMetrics(currentCustomDrawnPoints, currentRunningDistKm)
            Toast.makeText(this, "⚖️ Đã lưu cân nặng ${weight.toInt()} kg. Calo được tính toán lại chính xác!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showRunningWorkoutSummaryDialog(
        distanceKm: Double,
        calories: Int,
        durationSeconds: Long,
        averagePaceMinPerKm: Double
    ) {
        val dialogBinding = DialogRunningSummaryBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        val runnerWeight = RunningCalorieCalculator.getUserWeight(this)
        val formattedTime = RunningCalorieCalculator.formatDuration(durationSeconds)
        val formattedPace = RunningCalorieCalculator.formatPace(averagePaceMinPerKm)
        val foodEquiv = RunningCalorieCalculator.getFoodEquivalentVi(calories)
        val hydrationMl = RunningCalorieCalculator.recommendHydrationMl(distanceKm)

        dialogBinding.tvSummaryDistance.text = String.format(Locale.US, "%.2f", distanceKm)
        dialogBinding.tvSummaryCalories.text = calories.toString()
        dialogBinding.tvSummaryDuration.text = formattedTime
        dialogBinding.tvSummaryPace.text = formattedPace
        dialogBinding.tvSummaryFoodEquivalent.text = "🍲 Năng lượng đốt cháy: $foodEquiv"
        dialogBinding.tvSummaryHydration.text = "💧 Bù nước khuyến nghị: ~$hydrationMl ml nước điện giải"
        dialogBinding.tvSummaryWeightNote.text = "Cân nặng: ${runnerWeight.toInt()} kg (Công thức thể thao ACSM: ${String.format(Locale.US, "%.2f", distanceKm)}km × ${runnerWeight.toInt()}kg × 1.036)"

        val dateStr = java.text.SimpleDateFormat("HH:mm - dd/MM/yyyy", Locale.getDefault()).format(java.util.Date())
        dialogBinding.tvWorkoutDateTime.text = "Bài tập chạy bộ • $dateStr"

        dialogBinding.btnDismissSummary.setOnClickListener {
            dialog.dismiss()
        }

        voiceManager.speak("Chúc mừng bạn đã hoàn thành bài chạy! Bạn đã chạy được ${String.format(Locale.US, "%.1f", distanceKm)} ki lô mét và tiêu hao $calories calo.")
        dialog.show()
    }

    override fun onVehiclePositionUpdated(lat: Double, lon: Double, bearing: Float) {
        mapEngine?.updateUserLocation(lat, lon, animateCamera = true, bearing = bearing)
    }

    // ==========================================
    // 5. LIFECYCLE MANAGEMENT
    // ==========================================
    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
        voiceManager.stop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    // ==========================================
    // GPS LOCATION MANAGEMENT
    // ==========================================
    private fun checkAndRequestLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            setupLocationTracking()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun setupLocationTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // 1. Activate MapLibre pulsing dot and compass cone
        mapEngine?.enableLocationComponent()

        // 2. Fetch last known location immediately
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && !hasInitialLocationFix) {
                    hasInitialLocationFix = true
                    mapEngine?.updateUserLocation(loc.latitude, loc.longitude, animateCamera = true)
                    Toast.makeText(
                        this,
                        "📍 Đã định vị chính xác vị trí của bạn (±${loc.accuracy.toInt()}m)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        // 3. High-accuracy real-time location stream (GPS + Wi-Fi + Sensors)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500)
            .setMinUpdateIntervalMillis(800)
            .setMinUpdateDistanceMeters(1.0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val isFirst = !hasInitialLocationFix
                hasInitialLocationFix = true

                mapEngine?.updateUserLocation(
                    lat = loc.latitude,
                    lon = loc.longitude,
                    animateCamera = isFirst || isNavigating,
                    bearing = if (loc.hasBearing()) loc.bearing else null
                )

                // Real GPS speed calculation (clamp jitter < 2 km/h to 0)
                val rawSpeed = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
                lastSpeed = if (rawSpeed <= 2) 0 else rawSpeed

                if (isNavigating) {
                    val destLatLng = if (selectedPoi != null) LatLng(selectedPoi!!.latitude, selectedPoi!!.longitude) else null
                    navSession?.updateLocation(loc, destLatLng)
                }

                if (isFirst && selectedPoi != null && binding.routePlannerCard.visibility == View.VISIBLE) {
                    calculateAndDisplayRoute(selectedPoi!!)
                }

                if (isFirst) {
                    Toast.makeText(
                        this@MainActivity,
                        "📍 Vị trí hiện tại: ${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)} (±${loc.accuracy.toInt()}m)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        binding.mapView.onDestroy()
        voiceManager.stop()
        voiceManager.shutdown()
        navSession?.stop()
        SmartwatchNavigationService.stop(this)
        NavigationStateBridge.onRequestStopNav = null
        NavigationStateBridge.onRequestToggleMute = null
    }
}
