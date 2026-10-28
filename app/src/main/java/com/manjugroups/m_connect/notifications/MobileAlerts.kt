package com.manjugroups.m_connect.notifications

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.gson.Gson
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.MobileAlert
import com.manjugroups.m_connect.network.MobileAlertViewedRequest
import com.manjugroups.m_connect.ui.alerts.MobileAlertActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Mandatory alerts sent from web Settings → Mobile → Alert.
 *
 * Each alert the staff member has not viewed is pinned in the notification
 * shade and shown full screen ([MobileAlertActivity]); it can be closed only
 * after its minimum view time, and closing is what records it as viewed. An
 * alert closed offline is remembered on the phone (never shown again) and its
 * receipt retried on the next refresh.
 */
object MobileAlerts {

    const val CHANNEL_ID = "mconnect_mandatory_alerts"
    private const val PREFS = "mobile_alerts"
    private const val KEY_VIEWED = "viewed"
    private const val KEY_UNACKED = "unacked"
    private const val DEFAULT_MIN_VIEW_SECONDS = 5

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Mutex()

    private val _pushArrived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A mobile_alert push arrived; a screen in the foreground shows it now. */
    val pushArrived: SharedFlow<Unit> = _pushArrived

    /** The alert screen is up (it shows the queue one by one itself). */
    @Volatile
    var screenShowing = false

    fun toJson(alert: MobileAlert): String = gson.toJson(alert)

    fun fromJson(json: String?): MobileAlert? =
        json?.let { runCatching { gson.fromJson(it, MobileAlert::class.java) }.getOrNull() }

    fun minViewSeconds(alert: MobileAlert): Int =
        (alert.minViewSeconds ?: DEFAULT_MIN_VIEW_SECONDS).coerceIn(0, 3600)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun idSet(context: Context, key: String): Set<String> =
        prefs(context).getStringSet(key, emptySet()).orEmpty().toSet()

    private fun saveIdSet(context: Context, key: String, ids: Set<String>) {
        // Keep the newest few hundred; ids are never reused.
        prefs(context).edit().putStringSet(key, ids.toList().takeLast(400).toSet()).apply()
    }

    /**
     * The alerts this staff member still has to view, oldest first, minus any
     * closed on this phone. Also retries receipts not yet accepted. Null when
     * the server could not be reached.
     */
    suspend fun fetchPending(context: Context, api: ApiService = ApiService.create()): List<MobileAlert>? =
        withContext(Dispatchers.IO) {
            val session = SessionManager(context.applicationContext)
            if (!session.isLoggedIn) return@withContext emptyList()
            retryReceipts(context, api, session)
            val response = runCatching { api.getPendingMobileAlerts(session.bearerToken) }.getOrNull()
                ?.takeIf { it.success } ?: return@withContext null
            val viewed = idSet(context, KEY_VIEWED)
            response.alerts.orEmpty()
                .filter { !it.deliveryId.isNullOrBlank() && it.deliveryId !in viewed }
                .sortedBy { it.sentAt ?: 0L }
        }

    /** Fetches pending alerts, pins their notifications and opens the screen. */
    fun refreshAndShow(activity: Activity) {
        val appContext = activity.applicationContext
        scope.launch {
            refreshLock.withLock {
                val pending = fetchPending(appContext) ?: return@withLock
                pending.forEach { postNotification(appContext, it) }
                val first = pending.firstOrNull() ?: return@withLock
                withContext(Dispatchers.Main) {
                    if (!screenShowing && !activity.isFinishing && !activity.isDestroyed) {
                        screenShowing = true
                        activity.startActivity(MobileAlertActivity.intent(activity, first))
                    }
                }
            }
        }
    }

    /** Records the alert as viewed: never shown again here, notification gone,
     *  receipt sent now or on the next refresh. */
    fun markViewed(context: Context, alert: MobileAlert) {
        val deliveryId = alert.deliveryId?.takeIf { it.isNotBlank() } ?: return
        val appContext = context.applicationContext
        saveIdSet(appContext, KEY_VIEWED, idSet(appContext, KEY_VIEWED) + deliveryId)
        saveIdSet(appContext, KEY_UNACKED, idSet(appContext, KEY_UNACKED) + deliveryId)
        cancelNotification(appContext, deliveryId)
        scope.launch {
            val session = SessionManager(appContext)
            if (session.isLoggedIn) retryReceipts(appContext, ApiService.create(), session)
        }
    }

    private suspend fun retryReceipts(context: Context, api: ApiService, session: SessionManager) {
        val unacked = idSet(context, KEY_UNACKED)
        if (unacked.isEmpty()) return
        val done = mutableSetOf<String>()
        for (deliveryId in unacked) {
            val delivered = try {
                api.markMobileAlertViewed(session.bearerToken, MobileAlertViewedRequest(deliveryId)).success
            } catch (e: retrofit2.HttpException) {
                // Gone or not this staff member's: retrying can never succeed.
                e.code() in 400..499 && e.code() !in listOf(401, 408, 429)
            } catch (_: Exception) {
                false
            }
            if (delivered) done += deliveryId
        }
        if (done.isNotEmpty()) saveIdSet(context, KEY_UNACKED, idSet(context, KEY_UNACKED) - done)
    }

    /** Push arrived: pin it now (it opens the alert) and tell the foreground. */
    fun onPush(context: Context, data: Map<String, String>) {
        val deliveryId = data["alertDeliveryId"]?.takeIf { it.isNotBlank() } ?: return
        if (deliveryId in idSet(context, KEY_VIEWED)) return
        postNotification(
            context.applicationContext,
            MobileAlert(
                deliveryId = deliveryId,
                sendId = data["alertSendId"],
                title = data["title"],
                bodyHtml = null,
            ),
            previewText = data["body"],
        )
        _pushArrived.tryEmit(Unit)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mandatory alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Company alerts that stay until you open them"
                setShowBadge(true)
            },
        )
    }

    private fun notificationId(deliveryId: String) = 0x4D41_0000 or (deliveryId.hashCode() and 0xFFFF)

    /** Pinned until viewed: ongoing, not auto-cancelled; tapping opens the alert. */
    private fun postNotification(context: Context, alert: MobileAlert, previewText: String? = null) {
        val deliveryId = alert.deliveryId ?: return
        if (!PushTokenManager.hasNotificationPermission(context)) return
        ensureChannel(context)
        val intent = MobileAlertActivity.intent(context, alert)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(deliveryId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = previewText?.takeIf { it.isNotBlank() }
            ?: alert.bodyHtml?.let { html ->
                androidx.core.text.HtmlCompat.fromHtml(html, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
                    .toString().replace(Regex("\\s+"), " ").trim()
            }
            ?: "Tap to view this alert"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(alert.title?.takeIf { it.isNotBlank() } ?: "New alert")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(deliveryId), notification) }
    }

    private fun cancelNotification(context: Context, deliveryId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(deliveryId))
    }
}
