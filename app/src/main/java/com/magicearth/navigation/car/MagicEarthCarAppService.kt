package com.magicearth.navigation.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.R
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto Main Service Entry Point for Magic Earth.
 * Declared in AndroidManifest.xml to allow Android Auto Head Units
 * (Car screens) to bind and render navigation sessions.
 */
class MagicEarthCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Allows connection from Android Auto Head Units, DHU emulators, and certified OEM hosts
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return MagicEarthCarSession()
    }
}

/**
 * Android Auto Session managing the screen stack for the car screen.
 */
class MagicEarthCarSession : Session() {
    override fun onCreateScreen(intent: Intent): androidx.car.app.Screen {
        return CarNavigationScreen(carContext)
    }
}
