package com.magicearth.navigation.hud

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.magicearth.navigation.R
import com.magicearth.navigation.databinding.ActivityHudBinding
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Head-Up Display (HUD) Windshield & Night Dashboard Activity
 * Uses high contrast OLED black theme with optional scaleX = -1f mirror flip
 * so the reflection on the glass windshield is crisp and readable.
 * Telemetry is driven strictly by LIVE GPS hardware (0 km/h when stationary).
 */
class HUDActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHudBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var isMirrored = true

    private var speedLimit = 60
    private var destLat = 0.0
    private var destLon = 0.0
    private var destName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding = ActivityHudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Read intent extras
        val street = intent.getStringExtra("EXTRA_STREET") ?: "Đang dò đường"
        val dist = intent.getStringExtra("EXTRA_DIST") ?: "--"
        val icon = intent.getStringExtra("EXTRA_ICON") ?: "➔"
        val initialSpeed = intent.getIntExtra("EXTRA_SPEED", 0)
        speedLimit = intent.getIntExtra("EXTRA_LIMIT", 60)
        destLat = intent.getDoubleExtra("EXTRA_DEST_LAT", 0.0)
        destLon = intent.getDoubleExtra("EXTRA_DEST_LON", 0.0)
        destName = intent.getStringExtra("EXTRA_DEST_NAME") ?: street

        // Populate initial UI
        binding.tvHudStreet.text = street
        binding.tvHudDistance.text = dist
        binding.tvHudTurnIcon.text = icon
        val displaySpeed = if (initialSpeed <= 2) 0 else initialSpeed
        binding.tvHudSpeed.text = displaySpeed.toString()
        binding.tvHudLimit.text = speedLimit.toString()

        // Mirror toggle button
        binding.btnToggleMirror.setOnClickListener {
            isMirrored = !isMirrored
            binding.hudMirroredContainer.scaleX = if (isMirrored) -1f else 1f
            binding.btnToggleMirror.text = if (isMirrored) {
                "⇄ Lật gương (Kính lái)"
            } else {
                "⇄ Màn hình trực tiếp (Giá đỡ)"
            }
        }

        // Tap container to also toggle mirror flip easily
        binding.hudMirroredContainer.setOnClickListener {
            binding.btnToggleMirror.performClick()
        }

        binding.btnExitHud.setOnClickListener {
            finish()
        }

        // Setup real-time GPS telemetry
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startGpsTracking()
    }

    private fun startGpsTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        // Fetch last known location immediately
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    updateTelemetry(loc)
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(0.5f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                updateTelemetry(loc)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback!!, mainLooper)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun updateTelemetry(loc: Location) {
        // Dynamic speed limit detection from road attributes
        val (roadLimit, roadTheme) = com.magicearth.navigation.map.TrafficCameraRepository.getRoadAttributes(destName, loc.latitude, loc.longitude)
        speedLimit = roadLimit
        binding.tvHudLimit.text = speedLimit.toString()

        // 1. Live Speed: m/s to km/h, clamped to 0 km/h when stationary
        val rawSpeed = if (loc.hasSpeed()) (loc.speed * 3.6f).roundToInt() else 0
        val currentSpeed = if (rawSpeed <= 2) 0 else rawSpeed

        binding.tvHudSpeed.text = currentSpeed.toString()
        if (currentSpeed > speedLimit) {
            binding.tvHudSpeed.setTextColor(getColor(R.color.me_accent_red))
        } else {
            binding.tvHudSpeed.setTextColor(getColor(R.color.me_accent_cyan))
        }

        // 2. Traffic Camera & Speed Trap Radar in HUD
        val nearestCam = com.magicearth.navigation.map.TrafficCameraRepository.findNearestCamera(loc.latitude, loc.longitude, 700.0)
        if (nearestCam != null) {
            val (cam, distMeters) = nearestCam
            binding.tvHudCameraAlert.visibility = android.view.View.VISIBLE
            binding.tvHudCameraAlert.text = "${cam.type.icon} CAMERA ${cam.speedLimit} KM/H (${distMeters}M)"
        } else {
            binding.tvHudCameraAlert.visibility = android.view.View.GONE
        }

        // 3. Real Physical Distance to Destination: ONLY changes if physical location changes!
        if (destLat != 0.0 && destLon != 0.0) {
            val distResults = FloatArray(1)
            Location.distanceBetween(loc.latitude, loc.longitude, destLat, destLon, distResults)
            val distMeters = distResults[0].roundToInt()

            binding.tvHudDistance.text = if (distMeters >= 1000) {
                String.format(Locale.US, "%.1f km", distMeters / 1000.0)
            } else {
                "$distMeters m"
            }

            // Direction icon & Exit Badge update
            if (roadTheme == com.magicearth.navigation.model.SignpostTheme.EXPRESSWAY_GREEN && distMeters < 1500) {
                binding.tvHudExitBadge.visibility = android.view.View.VISIBLE
                binding.tvHudExitBadge.text = "LỐI RA 08"
            } else {
                binding.tvHudExitBadge.visibility = android.view.View.GONE
            }

            binding.tvHudTurnIcon.text = when {
                distMeters < 80 -> "📍"
                distMeters < 400 -> "↱"
                else -> "↑"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }
}
