package com.magicearth.navigation.navigation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.magicearth.navigation.model.CameraType
import com.magicearth.navigation.model.CommunityIncident
import com.magicearth.navigation.model.RouteWeatherSegment
import com.magicearth.navigation.model.TrafficCamera
import com.magicearth.navigation.model.VoiceLanguage
import java.util.Locale

/**
 * Advanced Native Android Voice Guidance Manager
 * Features:
 * 1. Multi-Language Natural Speech (Vietnamese, English US, French)
 * 2. Natural Street Name Pronunciation & Turn-by-Turn Phrasing
 * 3. Urgent Audio Chimes (ToneGenerator) for Overspeed & Camera warnings
 * 4. Audio Ducking (lowers background music during navigation voiceover)
 */
class VoiceGuidanceManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false
    private val prefs = context.getSharedPreferences("voice_guidance_prefs", Context.MODE_PRIVATE)

    var isMuted: Boolean
        get() = prefs.getBoolean("KEY_VOICE_MUTED", false)
        set(value) {
            prefs.edit().putBoolean("KEY_VOICE_MUTED", value).apply()
            if (value) {
                stop()
            }
        }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var toneGenerator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
    } catch (e: Exception) {
        null
    }

    var currentLanguage: VoiceLanguage
        get() {
            val code = prefs.getString("KEY_VOICE_LANG", VoiceLanguage.VIETNAMESE.name)
            return try {
                VoiceLanguage.valueOf(code ?: VoiceLanguage.VIETNAMESE.name)
            } catch (e: Exception) {
                VoiceLanguage.VIETNAMESE
            }
        }
        private set(value) {
            prefs.edit().putString("KEY_VOICE_LANG", value.name).apply()
        }

    companion object {
        private const val TAG = "VoiceGuidanceManager"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyLanguage(currentLanguage)
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.05f) // Slightly higher pitch for punchy, clear in-car acoustics

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    requestAudioFocus()
                }

                override fun onDone(utteranceId: String?) {
                    abandonAudioFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    abandonAudioFocus()
                }
            })

            isInitialized = true
            Log.d(TAG, "TTS initialized successfully with language: ${currentLanguage.displayName}")
        } else {
            Log.e(TAG, "TTS initialization failed with status: $status")
        }
    }

    /**
     * Switch voice guidance language dynamically
     */
    fun setLanguage(language: VoiceLanguage) {
        currentLanguage = language
        if (isInitialized) {
            applyLanguage(language)
        }
    }

    private fun applyLanguage(language: VoiceLanguage) {
        val locale = when (language) {
            VoiceLanguage.VIETNAMESE -> Locale("vi", "VN")
            VoiceLanguage.ENGLISH -> Locale.US
            VoiceLanguage.FRENCH -> Locale.FRANCE
        }

        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "Locale $locale not fully supported, falling back to US English")
            tts?.language = Locale.US
        }
    }

    /**
     * Announce maneuver instruction with natural phrasing and street name
     */
    fun speakManeuver(instruction: String, street: String, distanceMeters: Int) {
        if (isMuted || !isInitialized) return

        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> {
                when {
                    distanceMeters <= 50 -> "Ngay bây giờ, $instruction"
                    distanceMeters in 51..300 -> "Sau $distanceMeters mét nữa, $instruction vào $street"
                    else -> "Trong ${formatDistVi(distanceMeters)}, $instruction theo hướng $street"
                }
            }
            VoiceLanguage.ENGLISH -> {
                when {
                    distanceMeters <= 50 -> "Now, $instruction"
                    distanceMeters in 51..300 -> "In $distanceMeters meters, $instruction onto $street"
                    else -> "In ${formatDistEn(distanceMeters)}, $instruction towards $street"
                }
            }
            VoiceLanguage.FRENCH -> {
                "Dans ${distanceMeters} mètres, $instruction sur $street"
            }
        }
        speak(text)
    }

    /**
     * Announce Traffic Camera / Speed Camera warning with urgent audible chime
     */
    fun speakCameraAlert(camera: TrafficCamera, distanceMeters: Int) {
        if (isMuted) return

        // Play warning chime tone first
        playAlertTone(ToneGenerator.TONE_PROP_BEEP2)

        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> {
                val camTypeStr = when (camera.type) {
                    CameraType.SPEED -> "bắn tốc độ, giới hạn ${camera.speedLimit} ki lô mét một giờ"
                    CameraType.RED_LIGHT -> "phạt nguội vượt đèn đỏ"
                    CameraType.LANE -> "giám sát phạt lấn làn đường"
                }
                "Chú ý! Có camera $camTypeStr, cách $distanceMeters mét trên ${camera.roadName}."
            }
            VoiceLanguage.ENGLISH -> {
                val camTypeStr = when (camera.type) {
                    CameraType.SPEED -> "speed camera ahead, limit ${camera.speedLimit} kilometers per hour"
                    CameraType.RED_LIGHT -> "red light enforcement camera ahead"
                    CameraType.LANE -> "lane surveillance camera ahead"
                }
                "Caution! $camTypeStr in $distanceMeters meters."
            }
            VoiceLanguage.FRENCH -> {
                "Attention! Radar dans $distanceMeters mètres."
            }
        }
        speak(text)
    }

    /**
     * Announce Overspeed Alert with double audible beep
     */
    fun speakOverspeed(currentSpeed: Int, speedLimit: Int) {
        if (isMuted) return

        // Play double urgent alert chime
        playAlertTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK)

        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> {
                "Cảnh báo quá tốc độ! Bạn đang chạy $currentSpeed ki lô mét một giờ, vượt quá giới hạn $speedLimit ki lô mét một giờ. Vui lòng giảm tốc độ!"
            }
            VoiceLanguage.ENGLISH -> {
                "Speed warning! Driving at $currentSpeed kilometers per hour, exceeding the speed limit of $speedLimit. Please slow down!"
            }
            VoiceLanguage.FRENCH -> {
                "Attention à votre vitesse! Vous roulez à $currentSpeed kilomètres par heure. La limite est de $speedLimit."
            }
        }
        speak(text)
    }

    /**
     * Announce highway exit badge and destination
     */
    fun speakSignpostExit(exitName: String, destination: String) {
        if (isMuted || !isInitialized) return
        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> "Chuẩn bị tới $exitName, hướng đi $destination."
            VoiceLanguage.ENGLISH -> "Approaching $exitName, toward $destination."
            VoiceLanguage.FRENCH -> "Approche de $exitName, vers $destination."
        }
        speak(text)
    }

    /**
     * Announce Community Reported Incidents (Accident, Roadwork, Hazard)
     */
    fun speakIncidentAlert(incident: CommunityIncident, distanceMeters: Int) {
        if (isMuted) return
        playAlertTone(ToneGenerator.TONE_PROP_BEEP2)

        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> {
                "Chú ý! Phía trước cách $distanceMeters mét có ${incident.type.titleVi.lowercase(Locale.getDefault())} trên ${incident.roadName}. Vui lòng chú ý quan sát!"
            }
            VoiceLanguage.ENGLISH -> {
                "Caution! ${incident.type.titleVi} reported in $distanceMeters meters on ${incident.roadName}."
            }
            VoiceLanguage.FRENCH -> {
                "Attention! Incident signalé dans $distanceMeters mètres sur ${incident.roadName}."
            }
        }
        speak(text)
    }

    /**
     * Announce Adverse Weather Zone along Route
     */
    fun speakWeatherAlert(weather: RouteWeatherSegment) {
        if (isMuted) return
        playAlertTone(ToneGenerator.TONE_PROP_BEEP)

        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> {
                "Cảnh báo thời tiết: Tuyến đường phía trước đang có ${weather.condition.titleVi.lowercase(Locale.getDefault())}. ${weather.condition.advisory}"
            }
            VoiceLanguage.ENGLISH -> {
                "Weather warning: Adverse weather ahead. ${weather.condition.advisory}"
            }
            VoiceLanguage.FRENCH -> {
                "Alerte météo: Conditions difficiles à venir. Ralentissez."
            }
        }
        speak(text)
    }

    /**
     * Announce Dynamic Traffic Reroute bypass
     */
    fun speakRerouteNotice(savedMinutes: Int) {
        if (isMuted) return
        playAlertTone(ToneGenerator.TONE_PROP_ACK)

        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> {
                "Phát hiện tuyến đường tránh kẹt xe nhanh hơn $savedMinutes phút. Đang chuyển hướng sang lộ trình tối ưu mới."
            }
            VoiceLanguage.ENGLISH -> {
                "Faster route found saving $savedMinutes minutes. Rerouting now."
            }
            VoiceLanguage.FRENCH -> {
                "Itinéraire plus rapide trouvé. Gain de $savedMinutes minutes."
            }
        }
        speak(text)
    }

    fun speakArrival(destinationName: String) {
        if (isMuted) return
        val text = when (currentLanguage) {
            VoiceLanguage.VIETNAMESE -> "Bạn đã đến điểm đến: $destinationName. Kết thúc hành trình."
            VoiceLanguage.ENGLISH -> "You have arrived at your destination: $destinationName. Navigation complete."
            VoiceLanguage.FRENCH -> "Vous êtes arrivé à destination: $destinationName."
        }
        speak(text)
    }

    /**
     * Play hardware audible tone
     */
    fun playAlertTone(toneType: Int = ToneGenerator.TONE_PROP_BEEP) {
        if (isMuted) return
        try {
            toneGenerator?.startTone(toneType, 220)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play tone: ${e.message}")
        }
    }

    fun speak(text: String) {
        if (isMuted || !isInitialized) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NavUtterance_${System.currentTimeMillis()}")
    }

    fun stop() {
        try {
            tts?.stop()
            toneGenerator?.stopTone()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio: ${e.message}")
        }
        abandonAudioFocus()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            // Ignored
        }
        abandonAudioFocus()
    }

    // Helpers for natural distance pronunciation
    private fun formatDistVi(meters: Int): String {
        return if (meters >= 1000) {
            val km = String.format(Locale.US, "%.1f", meters / 1000.0).replace(".", " phẩy ")
            "$km ki lô mét"
        } else {
            "$meters mét"
        }
    }

    private fun formatDistEn(meters: Int): String {
        return if (meters >= 1000) {
            String.format(Locale.US, "%.1f kilometers", meters / 1000.0)
        } else {
            "$meters meters"
        }
    }

    // Audio Focus request to duck background audio while speaking
    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus error: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            // Ignored
        }
    }
}

