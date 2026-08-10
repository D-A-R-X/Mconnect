package com.manjugroups.m_connect.notifications

import android.app.Service
import android.content.Intent
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val callId = intent.getStringExtra(ModernDialerCallController.EXTRA_CALL_ID).orEmpty()
        val displayName = intent.getStringExtra(ModernDialerCallController.EXTRA_DISPLAY_NAME)
        val number = intent.getStringExtra(ModernDialerCallController.EXTRA_FROM_NUMBER)
        if (callId.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent.action) {
            ModernDialerCallController.ACTION_PICKUP -> {
                ModernDialerCallController.clearIncoming(this)
                startForeground(
                    ModernDialerCallController.ACTIVE_NOTIFICATION_ID,
                    ModernDialerCallController.activeCallNotification(this, callId, displayName, number),
                )
                performCallAction(callId, "pickup", startId)
            }
            ModernDialerCallController.ACTION_REJECT -> {
                ModernDialerCallController.clearIncoming(this)
                performCallAction(callId, "reject", startId)
            }
            ModernDialerCallController.ACTION_HANGUP -> {
                ModernDialerCallController.clearIncoming(this)
                performCallAction(callId, "hangup", startId)
            }
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun performCallAction(callId: String, action: String, startId: Int) {
        scope.launch {
            var succeeded = false
            try {
                val session = SessionManager(applicationContext)
                if (!session.isLoggedIn) throw IllegalStateException("Login required")
                val config = ApiService.create().getMobileDialerConfig(session.bearerToken)
                config.mapping?.token?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Modern Dialer token missing")
                ModernDialerWebViewBridge.ensureLoaded(applicationContext, config)
                when (action) {
                    "pickup" -> ModernDialerWebViewBridge.pickup()
                    "reject" -> ModernDialerWebViewBridge.reject()
                    "hangup" -> ModernDialerWebViewBridge.hangup()
                    else -> throw IllegalStateException("Unsupported dialer action: $action")
                }
                succeeded = true
                if (action != "pickup") {
                    stopForeground(STOP_FOREGROUND_REMOVE)
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            } finally {
                if (action != "pickup" || !succeeded) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
    }
}
