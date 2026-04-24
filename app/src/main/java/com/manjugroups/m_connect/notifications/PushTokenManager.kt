package com.manjugroups.m_connect.notifications

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.PushRegisterRequest
import com.manjugroups.m_connect.network.PushUnregisterRequest
import kotlinx.coroutines.tasks.await

object PushTokenManager {

    const val CHANNEL_ID = "hr_workflow_notifications"
    private val api by lazy { ApiService.create() }

    fun ensureFirebaseInitialized(context: Context): Boolean {
        if (FirebaseApp.getApps(context).isNotEmpty()) return true

        val applicationId = BuildConfig.FIREBASE_APPLICATION_ID
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        val apiKey = BuildConfig.FIREBASE_API_KEY
        val senderId = BuildConfig.FIREBASE_GCM_SENDER_ID

        if (applicationId.isBlank() || projectId.isBlank() || apiKey.isBlank() || senderId.isBlank()) {
            return false
        }

        val optionsBuilder = FirebaseOptions.Builder()
            .setApplicationId(applicationId)
            .setProjectId(projectId)
            .setApiKey(apiKey)
            .setGcmSenderId(senderId)

        if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) {
            optionsBuilder.setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
        }

        FirebaseApp.initializeApp(context, optionsBuilder.build())
        return FirebaseApp.getApps(context).isNotEmpty()
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    suspend fun syncCurrentToken(context: Context, session: SessionManager): Boolean {
        if (!session.isLoggedIn) return false
        if (!hasNotificationPermission(context)) return false
        if (!ensureFirebaseInitialized(context)) return false

        val token = FirebaseMessaging.getInstance().token.await()
        api.registerPushDevice(
            session.bearerToken,
            PushRegisterRequest(
                token = token,
                platform = "android",
                provider = "fcm",
                bundleId = context.packageName,
                appId = context.packageName,
                appName = context.getString(R.string.app_name),
                deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: "unknown-device"
            )
        )
        session.pushToken = token
        return true
    }

    suspend fun registerRefreshedToken(
        context: Context,
        session: SessionManager,
        token: String
    ): Boolean {
        if (!session.isLoggedIn) return false
        if (!ensureFirebaseInitialized(context)) return false
        api.registerPushDevice(
            session.bearerToken,
            PushRegisterRequest(
                token = token,
                platform = "android",
                provider = "fcm",
                bundleId = context.packageName,
                appId = context.packageName,
                appName = context.getString(R.string.app_name),
                deviceId = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ANDROID_ID
                ) ?: "unknown-device"
            )
        )
        session.pushToken = token
        return true
    }

    suspend fun unregisterCurrentToken(context: Context, session: SessionManager) {
        val token = session.pushToken ?: return
        runCatching {
            api.unregisterPushDevice(
                session.bearerToken,
                PushUnregisterRequest(token)
            )
        }
        session.pushToken = null
    }
}
