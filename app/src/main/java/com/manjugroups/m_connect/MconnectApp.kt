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
