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
import coil.decode.VideoFrameDecoder

class MconnectApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                // Render a frame from video attachments (daily-log media, etc.)
                // so they get a real thumbnail instead of a blank box.
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.20) // Limit memory cache to 20% of available heap
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512 * 1024 * 1024) // 512 MB disk cache
                    .build()
            }
            .allowRgb565(true) // Save 50% memory per bitmap on low-end devices
            .crossfade(true)   // Smooth image transition animations
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Publish the app context before anything builds an HTTP client — the
        // shared offline response cache needs it to open its cache directory.
        com.manjugroups.m_connect.network.MconnectAppContext.set(this)
        // Force a single visual mode for now: app always runs in light mode.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        
        // Preheat EncryptedSharedPreferences and purge session if BASE_URL changed on a background
        // thread to prevent heavy Keystore operations from blocking the main thread during startup.
        java.lang.Thread {
            try {
                SessionManager(this@MconnectApp).purgeIfBaseUrlChanged()
            } catch (e: Exception) {
                android.util.Log.e("MconnectApp", "Failed to preheat/purge SessionManager", e)
            }
        }.start()

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
        // still floating around without a `type` tag. Lights + vibration
        // are enabled here so the catch-all channel feels alive on
        // pre-API-26 devices where per-channel settings don't apply.
        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_ID,
                "Mconnect Notifications",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "App updates and uncategorised alerts"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(100, 200, 300, 400, 500, 400, 300, 200, 400)
            }
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

        manager.createNotificationChannel(
            NotificationChannel(
                PushTokenManager.CHANNEL_CALLS,
                "Dialer Calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Incoming Modern Dialer calls"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
