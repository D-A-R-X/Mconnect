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
import android.util.Log


object PushTokenManager {

    const val CHANNEL_ID = "hr_workflow_notifications"
    private val api by lazy { ApiService.create() }

    fun ensureFirebaseInitialized(context: Context): Boolean {
        Log.d("PushTokenManager", "ensureFirebaseInitialized: checking status")
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            Log.d("PushTokenManager", "ensureFirebaseInitialized: already initialized")
            return true
        }

        val applicationId = BuildConfig.FIREBASE_APPLICATION_ID
        val projectId = BuildConfig.FIREBASE_PROJECT_ID
        val apiKey = BuildConfig.FIREBASE_API_KEY
        val senderId = BuildConfig.FIREBASE_GCM_SENDER_ID

        Log.d("PushTokenManager", "ensureFirebaseInitialized config: appId='$applicationId', projId='$projectId', hasApiKey=${apiKey.isNotBlank()}, senderId='$senderId'")

        if (applicationId.isBlank() || projectId.isBlank() || apiKey.isBlank() || senderId.isBlank()) {
            Log.w("PushTokenManager", "ensureFirebaseInitialized: blank fields detected; aborting programmatical initialization")
            return false
        }

        return try {
            val optionsBuilder = FirebaseOptions.Builder()
                .setApplicationId(applicationId)
                .setProjectId(projectId)
                .setApiKey(apiKey)
                .setGcmSenderId(senderId)

            if (BuildConfig.FIREBASE_STORAGE_BUCKET.isNotBlank()) {
                optionsBuilder.setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
            }

            FirebaseApp.initializeApp(context, optionsBuilder.build())
            val success = FirebaseApp.getApps(context).isNotEmpty()
            Log.d("PushTokenManager", "ensureFirebaseInitialized: programmatic initialization finished, success=$success")
            success
        } catch (e: Exception) {
            Log.e("PushTokenManager", "ensureFirebaseInitialized: failed to initialize", e)
            false
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    suspend fun syncCurrentToken(context: Context, session: SessionManager): Boolean {
        Log.d("PushTokenManager", "syncCurrentToken: starting sync")
        if (!session.isLoggedIn) {
            Log.w("PushTokenManager", "syncCurrentToken: skipped (user not logged in)")
            return false
        }
        if (!hasNotificationPermission(context)) {
            Log.w("PushTokenManager", "syncCurrentToken: skipped (no notification permission)")
            return false
        }
        if (!ensureFirebaseInitialized(context)) {
            Log.w("PushTokenManager", "syncCurrentToken: skipped (Firebase not initialized)")
            return false
        }

        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d("PushTokenManager", "syncCurrentToken: fetched FCM token: $token")
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
            Log.d("PushTokenManager", "syncCurrentToken: successfully registered token on backend")
            true
        } catch (e: Exception) {
            Log.e("PushTokenManager", "syncCurrentToken: failed to sync token", e)
            false
        }
    }

    suspend fun registerRefreshedToken(
        context: Context,
        session: SessionManager,
        token: String
    ): Boolean {
        Log.d("PushTokenManager", "registerRefreshedToken: token refreshed to: $token")
        if (!session.isLoggedIn) {
            Log.w("PushTokenManager", "registerRefreshedToken: skipped (user not logged in)")
            return false
        }
        if (!ensureFirebaseInitialized(context)) {
            Log.w("PushTokenManager", "registerRefreshedToken: skipped (Firebase not initialized)")
            return false
        }
        return try {
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
            Log.d("PushTokenManager", "registerRefreshedToken: successfully registered refreshed token on backend")
            true
        } catch (e: Exception) {
            Log.e("PushTokenManager", "registerRefreshedToken: failed to register refreshed token", e)
            false
        }
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
