package com.manjugroups.m_connect.attendance

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.GeoTrackBootstrapSync
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.geotrack.data.PendingPunchDao
import com.manjugroups.m_connect.geotrack.data.PendingPunchEntity
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.PunchRequest
import com.manjugroups.m_connect.network.PunchResponse
import com.manjugroups.m_connect.network.StorageUploader
import com.manjugroups.m_connect.notifications.PushTokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Sends queued attendance punches to the server. The ONE place that does it:
 * the clock-in screens call it straight after saving a punch (so an online
 * staff member still gets the server's real answer), and [PunchSyncWorker]
 * calls it when the network comes back.
 *
 * Punches go oldest-first and the flush stops at the first one that can't be
 * sent yet, so a clock-out is never sent ahead of the clock-in it closes.
 * A lock makes the screens and the worker take turns, so the same row is never
 * sent twice at once.
 */
object PunchReplayer {

    sealed interface Outcome {
        /** The server recorded the punch. */
        data class Recorded(val response: PunchResponse) : Outcome
        /** The server refused it (blocked location, already punched…). Row removed. */
        data class Rejected(val error: String) : Outcome
        /** Not sent yet (no network, outage, selfie still uploading). Row kept. */
        data object Deferred : Outcome
    }

    /** Retries before a punch is recorded without its selfie. */
    private const val MAX_SELFIE_UPLOAD_ATTEMPTS = 20

    private val lock = Mutex()

    /** Outlives the screen, so a send the screen stopped waiting for still finishes. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Punches a screen is waiting on, with their outcome once known. While a
     * row is here the screen reports its result and runs its own follow-up, so
     * the flush neither notifies nor starts GeoTrack for it.
     */
    private val claimed = HashMap<Long, Outcome?>()

    /**
     * Send the punch the screen just saved, waiting at most [timeoutMs]. Returns
     * its outcome, or null when there was no answer in time; then the send keeps
     * going in the background and the flush does the follow-up itself, so a
     * punch that lands late still starts or stops tracking.
     */
    suspend fun sendNow(context: Context, id: Long, timeoutMs: Long): Outcome? {
        synchronized(claimed) { claimed[id] = null }
        val appContext = context.applicationContext
        val send = scope.async { flush(appContext) }
        val answered = withTimeoutOrNull(timeoutMs) { send.await() }?.get(id)
        // Whatever the flush recorded for it before we let go.
        val recorded = synchronized(claimed) { claimed.remove(id) }
        return answered ?: recorded
    }

    /** True while a screen is waiting on [id]; stores [outcome] for it. */
    private fun keepForScreen(id: Long, outcome: Outcome): Boolean = synchronized(claimed) {
        if (!claimed.containsKey(id)) return@synchronized false
        claimed[id] = outcome
        true
    }

    /**
     * Flush the signed-in staff's queued punches. Returns each attempted row's
     * outcome. A rejection nobody is waiting on is shown as a notification,
     * since no screen is open for it.
     */
    suspend fun flush(context: Context): Map<Long, Outcome> = lock.withLock {
        val appContext = context.applicationContext
        val session = SessionManager(appContext)
        val token = session.bearerToken
        // Logged out: leave the rows; a later flush after login sends them.
        if (token.isBlank()) return@withLock emptyMap()
        val currentStaffId = session.staffId
        val dao = GeoTrackDatabase.getInstance(appContext).pendingPunchDao()
        // Only the signed-in staff's punches. A queued punch outlives a logout,
        // and must not be recorded against whoever signs in next. Rows queued
        // before staffId existed carry null and are still sent.
        val pending = dao.listAll().filter { p ->
            p.staffId.isNullOrBlank() || p.staffId == currentStaffId
        }
        val api = ApiService.create()
        val outcomes = LinkedHashMap<Long, Outcome>()
        for (p in pending) {
            val outcome = replayOne(api, token, dao, p)
            outcomes[p.id] = outcome
            when (outcome) {
                Outcome.Deferred -> break
                is Outcome.Rejected -> {
                    dao.deleteById(p.id)
                    deletePhoto(p.photoPath)
                    if (!keepForScreen(p.id, outcome)) notifyRejected(appContext, p, outcome.error)
                }
                is Outcome.Recorded -> {
                    dao.deleteById(p.id)
                    deletePhoto(p.photoPath)
                    if (!keepForScreen(p.id, outcome)) {
                        GeoTrackBootstrapSync.onPunchRecorded(
                            context = appContext,
                            punchedIn = p.isPunchIn,
                            contextId = outcome.response.attendanceId,
                            occurredAt = punchMillis(p.clientPunchTime),
                            lat = p.latitude,
                            lng = p.longitude,
                            api = GeoTrackApi.create(),
                        )
                    }
                }
            }
        }
        outcomes
    }

    private suspend fun replayOne(
        api: ApiService,
        token: String,
        dao: PendingPunchDao,
        p: PendingPunchEntity,
    ): Outcome {
        return try {
            val photo = p.photoPath?.let(::File)?.takeIf { it.exists() }
            val storageId = photo?.let { f ->
                runCatching {
                    StorageUploader.upload(
                        api,
                        token,
                        f,
                        purpose = com.manjugroups.m_connect.network.MobileStoragePurpose.ATTENDANCE_PHOTO,
                    ).storageId
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
            // The selfie is part of the punch: keep the row and retry rather
            // than recording it without the photo. After many failed attempts,
            // record it without the photo so the attendance itself is not lost.
            if (photo != null && storageId == null && p.attemptCount < MAX_SELFIE_UPLOAD_ATTEMPTS) {
                dao.bumpAttempt(p.id, "Selfie upload failed")
                return Outcome.Deferred
            }
            val request = PunchRequest(
                latitude = p.latitude,
                longitude = p.longitude,
                address = p.address,
                remarks = p.remarks,
                photo = storageId,
                deviceId = p.deviceId,
                source = p.source,
                clientPunchTime = p.clientPunchTime,
            )
            val resp = if (p.isPunchIn) api.punchIn(token, request) else api.punchOut(token, request)
            if (resp.success) Outcome.Recorded(resp)
            else Outcome.Rejected(resp.error?.takeIf { it.isNotBlank() } ?: "Punch was not accepted")
        } catch (e: retrofit2.HttpException) {
            // A server answer with a message is a business rejection; retrying
            // it would loop forever. A gateway/outage error has no message and
            // is retried.
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            httpRejection(e.code(), body) ?: run {
                dao.bumpAttempt(p.id, e.message())
                Outcome.Deferred
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No server response (offline / timeout): keep it for later.
            dao.bumpAttempt(p.id, e.message)
            Outcome.Deferred
        }
    }

    private val OUTAGE_CODES = setOf(408, 429, 502, 503, 504)

    /**
     * A refusal when the server answered with a reason (Convex sends business
     * errors as an HTTP error with a JSON `error`/`message`), or null when it is
     * an outage or an answer without a reason, which is retried.
     */
    internal fun httpRejection(code: Int, body: String?): Outcome.Rejected? {
        if (code in OUTAGE_CODES) return null
        val raw = body?.takeIf { it.isNotBlank() } ?: return null
        val message = runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.takeIf { it.isJsonPrimitive }?.asString
                ?: obj.get("message")?.takeIf { it.isJsonPrimitive }?.asString)
                ?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: return null
        return Outcome.Rejected(message)
    }

    /**
     * Tell the staff a saved punch was refused, since the screen already said
     * "saved". A refusal that says the punch is already there means an earlier
     * attempt landed, so that one stays quiet.
     */
    private fun notifyRejected(context: Context, p: PendingPunchEntity, error: String) {
        if (error.contains("already", ignoreCase = true)) return
        val what = if (p.isPunchIn) "Clock-in" else "Clock-out"
        val time = runCatching {
            SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(punchMillis(p.clientPunchTime)))
        }.getOrDefault("")
        val title = "$what${if (time.isNotBlank()) " at $time" else ""} was not recorded"
        val notification = NotificationCompat.Builder(context, PushTokenManager.CHANNEL_ALERTS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + p.id.toInt(), notification)
        }
    }

    private const val NOTIFICATION_BASE = 0x5A_0000

    private fun punchMillis(iso: String): Long = runCatching {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    }.getOrDefault(System.currentTimeMillis())

    private fun deletePhoto(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }
}
