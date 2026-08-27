package com.manjugroups.m_connect.attendance

import android.content.Context
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.data.GeoTrackDatabase
import com.manjugroups.m_connect.geotrack.data.PendingPunchEntity
import com.manjugroups.m_connect.util.ImageCompressor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared offline-punch queue used by BOTH clock-in/out paths — HomeViewModel and
 * AttendanceFlowViewModel (the Selfie Clock-In screen) — so a punch is never
 * lost when the network is down, and the two paths can't drift.
 *
 * A queued punch keeps the staff's REAL tap time ([nowIso]) and its selfie
 * (copied to a stable location). [PunchSyncWorker] replays it — original time
 * and photo intact — once connectivity returns, so attendance records the time
 * the staff actually punched rather than the (later) sync time.
 */
object OfflinePunchQueue {

    /** ISO-8601 device time with UTC offset (e.g. 2026-08-11T09:00:00.123+05:30);
     *  the backend parses this and records it as the punch time. */
    fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    /**
     * Persist a punch that couldn't reach the server to the offline queue, with
     * its photo copied to a stable location so it survives until sync. Returns
     * true when the punch was queued.
     */
    suspend fun enqueue(
        context: Context,
        isPunchIn: Boolean,
        punchIso: String,
        latitude: Double?,
        longitude: Double?,
        photoFile: File?,
        address: String? = null,
    ): Boolean = try {
        val persistedPhoto = photoFile
            ?.takeIf { it.exists() }
            ?.let { src ->
                runCatching {
                    // COMPRESS before storing, not just before uploading.
                    // A queued punch may sit on the device for hours and is
                    // then replayed over whatever connection came back - often
                    // a weak one. Keeping a multi-MB camera original here fills
                    // the device and makes that replay far more likely to fail.
                    // ImageCompressor is best-effort and returns the source
                    // untouched on any error, so this can never lose a selfie.
                    val compressed = ImageCompressor.compress(src)
                    val dir = File(context.filesDir, "punch_queue").apply { mkdirs() }
                    val dst = File(dir, "punch_${System.currentTimeMillis()}_${src.name}")
                    compressed.copyTo(dst, overwrite = true)
                    // The compressor writes a NEW temp file when it actually
                    // shrank something; drop it now that we hold our own copy.
                    if (compressed !== src) runCatching { compressed.delete() }
                    dst.absolutePath
                }.getOrNull()
            }
        GeoTrackDatabase.getInstance(context).pendingPunchDao().insert(
            PendingPunchEntity(
                isPunchIn = isPunchIn,
                clientPunchTime = punchIso,
                latitude = latitude,
                longitude = longitude,
                address = address,
                photoPath = persistedPhoto,
                // Bind the punch to whoever took it - see PendingPunchEntity.
                staffId = SessionManager(context).staffId,
                deviceId = SessionManager(context).trackingDeviceId,
                source = "mobile",
                createdAt = System.currentTimeMillis(),
            )
        )
        true
    } catch (_: Exception) {
        false
    }
}
