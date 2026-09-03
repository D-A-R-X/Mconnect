package com.manjugroups.m_connect.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.manjugroups.m_connect.notifications.ModernDialerCallController
import com.manjugroups.m_connect.notifications.ModernDialerWebViewBridge

/** Debug-build-only entry point for exercising server push behavior with adb. */
class DebugDialerPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.extras?.keySet()?.associateWith { key ->
            intent.getStringExtra(key).orEmpty()
        }.orEmpty()

        when (intent.action) {
            ACTION_INCOMING -> ModernDialerCallController.showIncomingCall(context, data)
            ACTION_ENDED -> {
                ModernDialerCallController.clearCallNotifications(context)
                ModernDialerWebViewBridge.remoteEnded()
            }
        }
    }

    companion object {
        const val ACTION_INCOMING = "com.manjugroups.mconnect.debug.DIALER_INCOMING"
        const val ACTION_ENDED = "com.manjugroups.mconnect.debug.DIALER_ENDED"
    }
}
