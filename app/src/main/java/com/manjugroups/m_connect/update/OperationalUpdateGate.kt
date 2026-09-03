package com.manjugroups.m_connect.update

import android.app.NotificationManager
import android.content.Context
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.notifications.ModernDialerCallController
import com.manjugroups.m_connect.notifications.ModernDialerWebViewBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OperationalUpdateState(
    val loggedIn: Boolean,
    val trackingRequested: Boolean,
    val trackingSessionActive: Boolean,
    val fieldActivityActive: Boolean,
    val onDutyActive: Boolean,
    val dialerCallActive: Boolean,
    val pendingLocationPoints: Int,
    val pendingTrackingEvents: Int,
    val pendingPunches: Int,
    val pendingChatMessages: Int,
    val attendanceSessionOpen: Boolean?,
)

internal object OperationalUpdatePolicy {
    fun isSafe(state: OperationalUpdateState): Boolean =
        state.loggedIn &&
            !state.trackingRequested &&
            !state.trackingSessionActive &&
            !state.fieldActivityActive &&
            !state.onDutyActive &&
            !state.dialerCallActive &&
            state.pendingLocationPoints == 0 &&
            state.pendingTrackingEvents == 0 &&
            state.pendingPunches == 0 &&
            state.pendingChatMessages == 0 &&
            state.attendanceSessionOpen == false
}

/**
 * Fail-closed safety check for installing an already downloaded app update.
 * Any unknown server state or unsynced local work postpones the update.
 */
class OperationalUpdateGate(
    context: Context,
    private val session: SessionManager,
    private val api: ApiService,
) {
    private val appContext = context.applicationContext

    suspend fun isSafeToUpdate(): Boolean {
        val localState = OperationalUpdateState(
            loggedIn = session.isLoggedIn,
            trackingRequested = session.shouldTrackNow,
            trackingSessionActive = !session.activeTrackingSessionId.isNullOrBlank(),
            fieldActivityActive = session.fieldActivity() != null,
            onDutyActive = session.isOnDuty || !session.onDutyTripId.isNullOrBlank(),
            dialerCallActive = ModernDialerWebViewBridge.hasActiveCall() ||
                hasDialerCallNotification(),
            pendingLocationPoints = 0,
            pendingTrackingEvents = 0,
            pendingPunches = 0,
            pendingChatMessages = 0,
            attendanceSessionOpen = null,
        )
        if (!isLocallyIdle(localState)) return false

        val database = GeoTrackDatabase.getInstance(appContext)
        val queuedState = withContext(Dispatchers.IO) {
            localState.copy(
                pendingLocationPoints = database.locationPointDao().getUnsentCount(),
                pendingTrackingEvents = database.pendingGeoTrackEventDao().getPendingCount(),
                pendingPunches = database.pendingPunchDao().count(),
                pendingChatMessages = database.pendingChatMessageDao().count(),
            )
        }
        if (!isLocallyIdle(queuedState)) return false

        // External fleet principals do not have staff-attendance endpoints.
        // Their persisted fleet activity above is the authoritative local gate.
        val attendanceOpen = if (session.isExternalFleetPrincipal) {
            false
        } else {
            AttendanceTrackingGate.hasOpenSessionNow(session.bearerToken, api)
        }
        return OperationalUpdatePolicy.isSafe(
            queuedState.copy(attendanceSessionOpen = attendanceOpen),
        )
    }

    private fun isLocallyIdle(state: OperationalUpdateState): Boolean =
        state.loggedIn &&
            !state.trackingRequested &&
            !state.trackingSessionActive &&
            !state.fieldActivityActive &&
            !state.onDutyActive &&
            !state.dialerCallActive &&
            state.pendingLocationPoints == 0 &&
            state.pendingTrackingEvents == 0 &&
            state.pendingPunches == 0 &&
            state.pendingChatMessages == 0

    private fun hasDialerCallNotification(): Boolean {
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
            ?: return false
        return runCatching {
            notificationManager.activeNotifications.any { notification ->
                notification.id == ModernDialerCallController.INCOMING_NOTIFICATION_ID ||
                    notification.id == ModernDialerCallController.ACTIVE_NOTIFICATION_ID
            }
        }.getOrDefault(true)
    }
}
