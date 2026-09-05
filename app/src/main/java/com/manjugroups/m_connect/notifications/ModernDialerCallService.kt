package com.manjugroups.m_connect.notifications

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ModernDialerCallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null
    private var selectedRoute = AudioRoute.PHONE

    private enum class AudioRoute { PHONE, SPEAKER, BLUETOOTH }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val callId = intent.getStringExtra(ModernDialerCallController.EXTRA_CALL_ID).orEmpty()
        val displayName = intent.getStringExtra(ModernDialerCallController.EXTRA_DISPLAY_NAME)
        val number = intent.getStringExtra(ModernDialerCallController.EXTRA_FROM_NUMBER)
        val eventId = intent.getStringExtra(ModernDialerCallController.EXTRA_EVENT_ID)
        intent.getStringExtra(ModernDialerCallController.EXTRA_AUDIO_ROUTE)?.let { raw ->
            selectedRoute = runCatching { AudioRoute.valueOf(raw) }.getOrDefault(selectedRoute)
        }
        if (callId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ModernDialerCallController.ACTION_KEEP_ACTIVE -> {
                ModernDialerCallController.clearIncoming(this)
                ModernDialerCallController.showActiveCall(this, callId, displayName, number)
            }
            ModernDialerCallController.ACTION_PICKUP -> {
                ModernDialerCallController.clearIncoming(this)
                ModernDialerCallController.showActiveCall(this, callId, displayName, number)
                performCallAction(callId, "pickup", eventId, startId)
            }
            ModernDialerCallController.ACTION_REJECT -> {
                ModernDialerCallController.clearIncoming(this)
                performCallAction(callId, "reject", eventId, startId)
            }
            ModernDialerCallController.ACTION_HANGUP -> {
                ModernDialerCallController.clearIncoming(this)
                performCallAction(callId, "hangup", eventId, startId)
            }
            ModernDialerCallController.ACTION_SET_AUDIO_ROUTE -> {
                selectedRoute = runCatching {
                    AudioRoute.valueOf(
                        intent.getStringExtra(ModernDialerCallController.EXTRA_AUDIO_ROUTE).orEmpty(),
                    )
                }.getOrDefault(selectedRoute)
                startCallAudio()
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ModernDialerCallController.clearActive(applicationContext)
        stopCallAudio()
        scope.cancel()
        super.onDestroy()
    }

    private fun startCallAudio() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest == null) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .build()
                audioManager.requestAudioFocus(audioFocusRequest!!)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
            applyAudioRoute(audioManager)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyAudioRoute(audioManager: AudioManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wantedTypes = when (selectedRoute) {
                AudioRoute.PHONE -> setOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.SPEAKER -> setOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.BLUETOOTH -> setOf(
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
                )
            }
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { it.type in wantedTypes } ?: return false
            return audioManager.setCommunicationDevice(device)
        }
        return when (selectedRoute) {
            AudioRoute.PHONE -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
                true
            }
            AudioRoute.SPEAKER -> {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = true
                true
            }
            AudioRoute.BLUETOOTH -> {
                if (!audioManager.isBluetoothScoAvailableOffCall) return false
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopCallAudio() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            } else {
                audioManager.abandonAudioFocus(null)
            }
            audioFocusRequest = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun performCallAction(callId: String, action: String, eventId: String?, startId: Int) {
        scope.launch {
            var succeeded = false
            try {
                val session = SessionManager(applicationContext)
                if (!session.isLoggedIn) throw IllegalStateException("Login required")
                val api = ApiService.create()
                val deviceId = MobileDialerApiCoordinator.deviceId(applicationContext)
                val response = MobileDialerApiCoordinator.performAction(
                    api = api,
                    token = session.bearerToken,
                    callId = callId,
                    action = action,
                    deviceId = deviceId,
                    eventId = eventId,
                )
                if (!response.success) {
                    throw IllegalStateException(response.error ?: "Dialer action was not accepted")
                }
                when (action) {
                    "pickup" -> {
                        val config = api.getMobileDialerConfig(session.bearerToken)
                        config.mapping?.token?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("Modern Dialer token missing")
                        ModernDialerWebViewBridge.ensureLoaded(applicationContext, config)
                        ModernDialerWebViewBridge.pickup()
                    }
                    "reject" -> ModernDialerWebViewBridge.reject()
                    "hangup" -> ModernDialerWebViewBridge.hangup()
                    else -> throw IllegalStateException("Unsupported dialer action: $action")
                }
                succeeded = true
                if (action != "pickup") {
                    ModernDialerCallController.clearActive(applicationContext)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "Dialer ${action} failed: ${e.message ?: "unknown"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (action != "pickup") {
                    ModernDialerCallController.clearActive(applicationContext)
                }
            } finally {
                if (action != "pickup" || !succeeded) {
                    ModernDialerCallController.clearActive(applicationContext)
                    stopSelf(startId)
                }
            }
        }
    }
}
