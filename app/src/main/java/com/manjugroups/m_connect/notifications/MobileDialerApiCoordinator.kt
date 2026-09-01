package com.manjugroups.m_connect.notifications

import android.content.Context
import android.provider.Settings
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.MobileDialerCallActionRequest
import com.manjugroups.m_connect.network.MobileDialerCallActionResponse
import com.manjugroups.m_connect.network.MobileDialerMediaRestartRequest
import com.manjugroups.m_connect.network.MobileDialerMediaRestartResponse
import java.nio.charset.StandardCharsets
import java.util.UUID

object MobileDialerApiCoordinator {
    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: "mconnect-android"

    fun idempotencyKey(
        callId: String,
        operation: String,
        deviceId: String,
        eventId: String? = null,
    ): String {
        val source = listOf(callId, operation, deviceId, eventId.orEmpty()).joinToString(":")
        return UUID.nameUUIDFromBytes(source.toByteArray(StandardCharsets.UTF_8)).toString()
    }

    suspend fun performAction(
        api: ApiService,
        token: String,
        callId: String,
        action: String,
        deviceId: String,
        eventId: String?,
    ): MobileDialerCallActionResponse {
        return api.performMobileDialerCallAction(
            token = token,
            idempotencyKey = idempotencyKey(callId, action, deviceId, eventId),
            callId = callId,
            body = MobileDialerCallActionRequest(
                action = action,
                deviceId = deviceId,
                eventId = eventId,
            ),
        )
    }

    suspend fun restartMedia(
        api: ApiService,
        token: String,
        callId: String,
        reason: String,
        deviceId: String,
    ): MobileDialerMediaRestartResponse {
        return api.restartMobileDialerMedia(
            token = token,
            idempotencyKey = idempotencyKey(callId, "media:$reason", deviceId),
            callId = callId,
            body = MobileDialerMediaRestartRequest(
                reason = reason,
                deviceId = deviceId,
            ),
        )
    }
}
