package com.manjugroups.m_connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.manjugroups.m_connect.notifications.PushTokenManager

class MconnectApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        PushTokenManager.ensureFirebaseInitialized(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            PushTokenManager.CHANNEL_ID,
            "Mconnect Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "HR approvals, chat messages, and app updates"
        }
        manager.createNotificationChannel(channel)
    }
}
