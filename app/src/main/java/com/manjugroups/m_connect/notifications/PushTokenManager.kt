package com.manjugroups.m_connect.notifications

import android.Manifest
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.manjugroups.m_connect.auth.LoginDeviceInfo
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

    // Legacy single channel — kept for backwards compatibility on older
    // build payloads that don't carry a `type` field, AND as the default
    // fallback in MconnectFirebaseMessagingService when the FCM data
    // payload has no recognised type tag.
    const val CHANNEL_ID = "hr_workflow_notifications"

    // ── Per-type channels — created at app boot in MconnectApp. ──
    // Splitting these out lets the user toggle each category in system
    // Settings → Apps → Notifications, and gives chats / alerts the
    // heads-up behaviour they need while keeping background informational
    // pings quiet. The FCM data payload's "type" field routes here.
    const val CHANNEL_CHAT      = "mconnect_chat"
    const val CHANNEL_TASKS     = "mconnect_tasks"
    const val CHANNEL_VISITS    = "mconnect_visits"
    const val CHANNEL_APPROVALS = "mconnect_approvals"
    const val CHANNEL_LOANS     = "mconnect_loans"
    const val CHANNEL_ALERTS    = "mconnect_alerts"
    const val CHANNEL_CALLS     = "mconnect_calls"

    /**
     * Map a server-side notification `type` to a channel id. Unknown
     * types fall back to the legacy general channel so a new server-side
     * notification type never silently disappears on the device — the
     * user still sees it, just on the general channel.
     */
    fun channelForType(type: String?): String = when (type) {
        // Chat — server emits `chat-dm` for direct-message pushes and
        // `chat-channel` for channel fan-outs; `chat-mention` is the
        // @-mention path. Earlier the canonical keys were only
        // `chat-message` / `channel-message`, but the convex chat layer
        // now uses the more descriptive ones — keep both so older
        // payloads still route to the chat channel instead of falling
        // through to the legacy general channel (which doesn't bubble
        // a heads-up display, the visible symptom the user reported).
        "chat-message", "chat-mention", "channel-message",
        "chat-dm", "chat-channel" -> CHANNEL_CHAT
        // Tasks / daily work
        "task-assigned", "daily-task", "task-due" -> CHANNEL_TASKS
        // Visits (CP / SV) + fleet trip allocation. The fleet-assigner alert
        // fires when a new trip lands in the dispatcher's queue awaiting a
        // vehicle — it rides the visits channel so it bubbles a heads-up
        // instead of the quiet general fallback.
        "cp-visit-assigned", "site-visit-assigned", "field-visit",
        "client-place-visit",
        "land-inspection-assigned", "fleet-trip-assigned",
        "marketing-fleet-new-trip-assigner",
        "marketing-fleet-unassigned-reporting-officer",
        // Staff-facing decision on their out-of-geofence CP completion.
        "cp-approval-decision" -> CHANNEL_VISITS
        // Approvals / requests (leave, permission, WFH, attendance) +
        // the GM's out-of-geofence CP completion approval ping.
        "leave-request", "leave-decision",
        "permission-request", "permission-decision",
        "wfh-request", "wfh-decision",
        "cp-approval-needed",
        "attendance-review", "attendance-correction" -> CHANNEL_APPROVALS
        // Loans / advance
        "loan-needs-review", "loan-approval-needed",
        "loan-decision", "loan-disbursed" -> CHANNEL_LOANS
        // Real-time alerts (tamper, on-duty, geotrack, biometric punch).
        // Biometric punches are server-synced attendance events the staff
        // can't see any other way, so they ride the high-importance alerts
        // channel for a heads-up instead of the quiet general fallback.
        "dialer-call-incoming" -> CHANNEL_CALLS
        "geotrack-tamper-alert", "on-duty-started",
        "on-duty-completed", "near-planned-visit",
        "biometric-punch-in", "biometric-punch-out" -> CHANNEL_ALERTS
        else -> CHANNEL_ID
    }

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
            Log.d("PushTokenManager", "syncCurrentToken: fetched FCM token (${token.length} chars)")
            val deviceInfo = LoginDeviceInfo.capture(context)
            api.registerPushDevice(
                session.bearerToken,
                PushRegisterRequest(
                    token = token,
                    platform = "android",
                    provider = "fcm",
                    bundleId = context.packageName,
                    appId = context.packageName,
                    appName = context.getString(R.string.app_name),
                    // Same identity the login path binds with. Falling back
                    // to a literal "unknown-device" here bound the account to
                    // a string login never sends, so the phone blocked its own
                    // owner and a device reset didn't help — the next launch
                    // just re-bound it. Empty means "don't bind".
                    deviceId = deviceInfo?.deviceId.orEmpty(),
                    deviceModel = deviceInfo?.model
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

    /**
     * Register the FCM token for an external-agency driver via the travel-desk
     * route. They have no staff record, so the MMS registerPushDevice path
     * would 401 (and trip the logout) — this is their own channel, so trip
     * allocations can push them.
     */
    suspend fun syncAgencyDriverToken(context: Context, session: SessionManager): Boolean {
        if (!session.isLoggedIn) return false
        if (!hasNotificationPermission(context)) return false
        if (!ensureFirebaseInitialized(context)) return false
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            com.manjugroups.m_connect.network.TravelDeskApi.create().registerPushToken(
                session.bearerToken,
                com.manjugroups.m_connect.network.TravelDeskPushRegisterRequest(
                    pushToken = token,
                    platform = "android",
                ),
            )
            session.pushToken = token
            true
        } catch (e: Exception) {
            Log.e("PushTokenManager", "syncAgencyDriverToken failed", e)
            false
        }
    }

    suspend fun registerRefreshedToken(
        context: Context,
        session: SessionManager,
        token: String
    ): Boolean {
        Log.d("PushTokenManager", "registerRefreshedToken: received refreshed FCM token (${token.length} chars)")
        if (!session.isLoggedIn) {
            Log.w("PushTokenManager", "registerRefreshedToken: skipped (user not logged in)")
            return false
        }
        if (!ensureFirebaseInitialized(context)) {
            Log.w("PushTokenManager", "registerRefreshedToken: skipped (Firebase not initialized)")
            return false
        }
        return try {
            val deviceInfo = LoginDeviceInfo.capture(context)
            api.registerPushDevice(
                session.bearerToken,
                PushRegisterRequest(
                    token = token,
                    platform = "android",
                    provider = "fcm",
                    bundleId = context.packageName,
                    appId = context.packageName,
                    appName = context.getString(R.string.app_name),
                    // Same identity the login path binds with. Falling back
                    // to a literal "unknown-device" here bound the account to
                    // a string login never sends, so the phone blocked its own
                    // owner and a device reset didn't help — the next launch
                    // just re-bound it. Empty means "don't bind".
                    deviceId = deviceInfo?.deviceId.orEmpty(),
                    deviceModel = deviceInfo?.model
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
