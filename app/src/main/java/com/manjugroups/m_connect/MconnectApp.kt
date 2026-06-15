package com.manjugroups.m_connect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.notifications.PushTokenManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class MconnectApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Force a single visual mode for now: app always runs in light mode.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        // Drop any session that was minted against a different backend URL so
        // switching BuildConfig.BASE_URL doesn't leak old-account data through
        // EncryptedSharedPreferences.
        SessionManager(this).purgeIfBaseUrlChanged()
        PushTokenManager.ensureFirebaseInitialized(this)
        createNotificationChannels()
    }

    /**
     * Create one [NotificationChannel] per notification category so the
     * staff can mute / customise each in system settings (Settings →
     * Apps → Mconnect → Notifications) and so chat / alerts get a
     * heads-up while background pings stay quiet.
     *
     * createNotificationChannel is idempotent — re-creating an existing
     * channel updates its label/description but keeps user-controlled
     * sound/vibration preferences. Safe to call on every cold start.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        // Legacy / general — preserves behaviour for any old payloads
        // still floating around without a `type` tag.
        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_ID,
                "Mconnect Notifications",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "App updates and uncategorised alerts" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_CHAT,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Direct messages, channel messages and mentions" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_TASKS,
                "Tasks",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Tasks assigned to you and daily task updates" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_VISITS,
                "CP / Site Visits",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "CP visits and site visits assigned to you" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_APPROVALS,
                "Approvals & Requests",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Leave, permission, WFH and attendance requests" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_LOANS,
                "Loans & Advance",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Loan and salary advance approvals" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_ALERTS,
                "Real-time Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Tamper alerts, on-duty status, planned visit reminders" }
        )
    }
}
