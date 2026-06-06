package com.manjugroups.m_connect.ui.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.BookingDraftClearRequest
import com.manjugroups.m_connect.network.BookingDraftSaveRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Booking-form draft auto-save / restore. Two layers of persistence:
 *
 *   1. SharedPreferences (local, synchronous, survives crash + immediate
 *      relaunch). Writes happen on every push() call — no debounce, no
 *      network, basically free.
 *   2. Convex /api/bookings/draft/{save|get|clear} (cloud, async, survives logging in
 *      from a different phone). Writes are debounced 2s so a fast
 *      typist doesn't hammer the network.
 *
 * Restore prefers whichever copy has the newer `updatedAt` so a user
 * who's been offline for a while but typing recently still gets their
 * local state instead of a stale cloud snapshot.
 *
 * `sourceKey` shape:
 *   - "cp:<cpVisitId>"     when the form is converting a CP visit
 *   - "sv:<siteVisitId>"   when converting a Site Visit
 *   - "standalone:<staffId>" for the Bookings + button on home
 *
 * The mobile owns the JSON blob shape — the backend just stores it as
 * an opaque string. That lets us add/remove form fields without
 * touching the schema each time.
 */
class BookingDraftManager(
    context: Context,
    private val api: ApiService,
    private val scope: CoroutineScope,
) {
    data class Snapshot(val draftJson: String, val updatedAt: Long)

    private val prefs = context.applicationContext.getSharedPreferences(
        SP_NAME,
        Context.MODE_PRIVATE,
    )
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingFlushRunnable: Runnable? = null
    private var lastFlushedJson: String? = null

    /**
     * Push the latest form state. Writes locally immediately + schedules
     * a debounced cloud push. Idempotent — the same draftJson back-to-
     * back collapses into one cloud round-trip.
     */
    fun push(
        bearerToken: String,
        sourceKey: String,
        sourceCpVisitId: String?,
        sourceSiteVisitId: String?,
        draftJson: String,
    ) {
        val now = System.currentTimeMillis()

        // 1. Synchronous local write — no batching, always wins on the
        //    timeline of the current device.
        prefs.edit()
            .putString(spKeyJson(sourceKey), draftJson)
            .putLong(spKeyUpdatedAt(sourceKey), now)
            .apply()

        // 2. Debounced cloud write. Cancel any pending fire-then-flush
        //    so a continuous typing burst collapses into one push when
        //    the user pauses for ~2s.
        if (draftJson == lastFlushedJson) return
        pendingFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            pendingFlushRunnable = null
            lastFlushedJson = draftJson
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        api.saveBookingDraft(
                            bearerToken,
                            BookingDraftSaveRequest(
                                sourceKey = sourceKey,
                                sourceCpVisitId = sourceCpVisitId,
                                sourceSiteVisitId = sourceSiteVisitId,
                                draftJson = draftJson,
                            ),
                        )
                    }
                }
                // Swallow network errors — the local SP copy is the
                // safety net. The next push() that DOES land replays
                // the latest blob anyway.
            }
        }
        pendingFlushRunnable = runnable
        mainHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    /**
     * Fetch the most recent draft. Cloud is preferred when its
     * updatedAt beats local; otherwise the SP copy wins. Null when no
     * draft exists in either store. Called once on dialog open before
     * the AI prefill runs so the operator's last manual edits get the
     * priority slot.
     */
    suspend fun restore(bearerToken: String, sourceKey: String): Snapshot? {
        val localJson = prefs.getString(spKeyJson(sourceKey), null)
        val localUpdatedAt = prefs.getLong(spKeyUpdatedAt(sourceKey), 0L)

        val cloudSnapshot = runCatching {
            withContext(Dispatchers.IO) {
                api.getBookingDraft(bearerToken, sourceKey)
            }
        }.getOrNull()
        val cloudDraft = cloudSnapshot?.takeIf { it.success }?.draft
        val cloudJson = cloudDraft?.draftJson
        val cloudUpdatedAt = cloudDraft?.updatedAt ?: 0L

        return when {
            cloudJson != null && cloudUpdatedAt >= localUpdatedAt ->
                Snapshot(cloudJson, cloudUpdatedAt)
            localJson != null ->
                Snapshot(localJson, localUpdatedAt)
            else -> null
        }
    }

    /**
     * Drop both copies. Called after the booking row actually lands so
     * the next form open for a different source starts clean instead
     * of inheriting the previous source's leftovers.
     */
    fun clear(bearerToken: String, sourceKey: String) {
        prefs.edit()
            .remove(spKeyJson(sourceKey))
            .remove(spKeyUpdatedAt(sourceKey))
            .apply()
        pendingFlushRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingFlushRunnable = null
        lastFlushedJson = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    api.clearBookingDraft(
                        bearerToken,
                        BookingDraftClearRequest(sourceKey = sourceKey),
                    )
                }
            }
        }
    }

    /** Flush any pending debounced write immediately (e.g. on dialog dismiss). */
    fun flushNow() {
        pendingFlushRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
            runnable.run()
        }
    }

    /** Convenience JSON helpers shared by the bottom sheet. */
    fun encode(map: Map<String, String?>): String = gson.toJson(map)

    @Suppress("UNCHECKED_CAST")
    fun decode(json: String?): Map<String, String?> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            gson.fromJson(json, Map::class.java) as? Map<String, String?> ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val SP_NAME = "booking_draft_cache_v1"
        private const val DEBOUNCE_MS = 2_000L

        private fun spKeyJson(sourceKey: String) = "draft_json:$sourceKey"
        private fun spKeyUpdatedAt(sourceKey: String) = "draft_at:$sourceKey"

        /** Build the canonical sourceKey for a given (staffId, source) pair. */
        fun buildSourceKey(
            cpVisitId: String?,
            siteVisitId: String?,
            staffId: String?,
        ): String = when {
            !cpVisitId.isNullOrBlank() -> "cp:$cpVisitId"
            !siteVisitId.isNullOrBlank() -> "sv:$siteVisitId"
            else -> "standalone:${staffId ?: "anon"}"
        }
    }
}
