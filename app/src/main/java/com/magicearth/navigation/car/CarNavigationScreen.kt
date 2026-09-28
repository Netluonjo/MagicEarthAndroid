package com.magicearth.navigation.car

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.*
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import com.magicearth.navigation.R
import com.magicearth.navigation.model.Maneuver
import com.magicearth.navigation.model.TrafficCamera
import com.magicearth.navigation.navigation.NavigationStateBridge

/**
 * Android Auto Screen optimized for in-car dashboard center displays.
 * Adapts dynamically between:
 * 1. Idle / Standby: High contrast OLED status card with quick actions.
 * 2. Active Drive: NavigationTemplate with Maneuver banners, camera traps, speed & ETA.
 */
class CarNavigationScreen(carContext: CarContext) : Screen(carContext), NavigationStateBridge.NavigationStateListener {

    private var isNavigating = NavigationStateBridge.isNavigating
    private var destinationName = NavigationStateBridge.destinationName
    private var currentManeuver = NavigationStateBridge.currentManeuver
    private var currentSpeed = NavigationStateBridge.currentSpeed
    private var currentLimit = NavigationStateBridge.currentSpeedLimit
    private var isOverspeed = NavigationStateBridge.isOverspeed
    private var remainingKm = NavigationStateBridge.remainingKm
    private var remainingMeters = NavigationStateBridge.remainingMeters
    private var etaString = NavigationStateBridge.etaString
    private var activeCamera = NavigationStateBridge.activeCamera
    private var isMuted = NavigationStateBridge.isMuted

    init {
        // Register Android Auto host callbacks
        try {
            carContext.getCarService(AppManager::class.java).setSurfaceCallback(object : SurfaceCallback {
                override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {}
                override fun onVisibleAreaChanged(visibleArea: Rect) {}
                override fun onStableAreaChanged(stableArea: Rect) {}
                override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {}
            })

            carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    NavigationStateBridge.onRequestStopNav?.invoke()
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }

        NavigationStateBridge.addListener(this)
    }

    override fun onGetTemplate(): Template {
        return if (isNavigating) {
            buildActiveDriveTemplate()
        } else {
            buildIdleStandbyTemplate()
        }
    }

    /**
     * Active Turn-by-Turn in-car screen with large turn icons, street names,
     * live speed limits, and traffic camera banners.
     */
    private fun buildActiveDriveTemplate(): Template {
        val actionStripBuilder = ActionStrip.Builder()

        // 1. Mute / Unmute Button on Car Dashboard
        val muteIconRes = if (isMuted) R.drawable.ic_voice_off else R.drawable.ic_voice_on
        val muteAction = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, muteIconRes)).build())
            .setOnClickListener {
                NavigationStateBridge.onRequestToggleMute?.invoke()
            }
            .build()
        actionStripBuilder.addAction(muteAction)

        // 2. Stop Navigation Button on Car Dashboard
        val stopAction = Action.Builder()
            .setTitle("Dừng")
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_close_clear_cancel)).build())
            .setOnClickListener {
                NavigationStateBridge.onRequestStopNav?.invoke()
            }
            .build()
        actionStripBuilder.addAction(stopAction)

        // Construct Navigation Turn Step
        val instructionText = currentManeuver?.instruction ?: "Tiếp tục di chuyển hướng về $destinationName"
        val streetText = currentManeuver?.street ?: destinationName

        val stepBuilder = Step.Builder(instructionText)
        stepBuilder.setRoad(streetText)

        // Map turn icons to Android Auto Maneuver types
        val maneuverType = when (currentManeuver?.icon) {
            "↱" -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_RIGHT
            "↰" -> androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_LEFT
            "➔" -> androidx.car.app.navigation.model.Maneuver.TYPE_STRAIGHT
            else -> androidx.car.app.navigation.model.Maneuver.TYPE_STRAIGHT
        }
        stepBuilder.setManeuver(androidx.car.app.navigation.model.Maneuver.Builder(maneuverType).build())

        val stepDistance = Distance.create(
            if (remainingMeters > 0) remainingMeters.toDouble() else 100.0,
            Distance.UNIT_METERS
        )

        // Camera / Speed Warning Banner
        val alertHeader = if (activeCamera != null) {
            "⚠️ Camera ${activeCamera?.type?.titleVi}: Tối đa ${activeCamera?.speedLimit} km/h"
        } else if (isOverspeed) {
            "⚠️ QUÁ TỐC ĐỘ: $currentSpeed / $currentLimit km/h"
        } else {
            "⚡ $currentSpeed km/h (Giới hạn: $currentLimit km/h)"
        }
        stepBuilder.setRoad("$streetText • $alertHeader")

        val routingInfo = RoutingInfo.Builder()
            .setCurrentStep(stepBuilder.build(), stepDistance)
            .build()

        val navTemplateBuilder = NavigationTemplate.Builder()
            .setNavigationInfo(routingInfo)
            .setActionStrip(actionStripBuilder.build())

        // Add Travel Estimate (Distance + ETA)
        try {
            val distRemaining = Distance.create(remainingKm, Distance.UNIT_KILOMETERS)
            val estimateBuilder = TravelEstimate.Builder(distRemaining, DateTimeWithZone.create(System.currentTimeMillis(), java.util.TimeZone.getDefault()))
            navTemplateBuilder.setDestinationTravelEstimate(estimateBuilder.build())
        } catch (e: Exception) {
            // Fallback
        }

        return navTemplateBuilder.build()
    }

    /**
     * Standby screen when vehicle is connected to Android Auto without active navigation.
     */
    private fun buildIdleStandbyTemplate(): Template {
        val paneBuilder = Pane.Builder()

        val statusRow = Row.Builder()
            .setTitle("Magic Earth Navigation")
            .addText("Hệ thống dẫn đường ngoại tuyến & cảnh báo camera an toàn.")
            .addText("Chọn điểm đến trên điện thoại để bắt đầu hành trình.")
            .build()
        paneBuilder.addRow(statusRow)

        val privacyRow = Row.Builder()
            .setTitle("Bảo mật dữ liệu 100%")
            .addText("Không theo dõi, không quảng cáo, hoạt động mượt mà với GPS độc lập.")
            .build()
        paneBuilder.addRow(privacyRow)

        val actionStrip = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Đang sẵn sàng")
                    .setOnClickListener {}
                    .build()
            )
            .build()

        return PaneTemplate.Builder(paneBuilder.build())
            .setTitle("Magic Earth Auto")
            .setActionStrip(actionStrip)
            .build()
    }

    // ========================================================
    // NavigationStateListener Callbacks (Update Auto UI in Real-time)
    // ========================================================
    override fun onNavStateChanged(isNavigating: Boolean, destinationName: String) {
        this.isNavigating = isNavigating
        this.destinationName = destinationName
        invalidate()
    }

    override fun onManeuverChanged(maneuver: Maneuver) {
        this.currentManeuver = maneuver
        invalidate()
    }

    override fun onTelemetryChanged(speedKmh: Int, speedLimit: Int, isOverspeed: Boolean) {
        this.currentSpeed = speedKmh
        this.currentLimit = speedLimit
        this.isOverspeed = isOverspeed
        invalidate()
    }

    override fun onRemainingChanged(distanceRemKm: Double, etaString: String, distanceMeters: Int) {
        this.remainingKm = distanceRemKm
        this.etaString = etaString
        this.remainingMeters = distanceMeters
        invalidate()
    }

    override fun onCameraAlertChanged(camera: TrafficCamera?, distanceMeters: Int) {
        this.activeCamera = camera
        invalidate()
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        this.isMuted = isMuted
        invalidate()
    }
}
