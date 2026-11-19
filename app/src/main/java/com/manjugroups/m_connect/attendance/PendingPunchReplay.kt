package com.manjugroups.m_connect.attendance

import com.manjugroups.m_connect.geotrack.data.PendingPunchDao
import com.manjugroups.m_connect.geotrack.data.PendingPunchEntity
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DaySessionsResponse
import com.manjugroups.m_connect.network.PunchRequest
import com.manjugroups.m_connect.network.PunchResponse
import com.manjugroups.m_connect.network.StorageUploader
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import retrofit2.HttpException

internal enum class PunchReplayResult { RESOLVED, RETRY, NEEDS_REVIEW, WAIT_FOR_LOGIN }

internal fun pendingPunchDay(punch: PendingPunchEntity): String? = runCatching {
    OffsetDateTime.parse(punch.clientPunchTime).atZoneSameInstant(ZoneId.of("Asia/Kolkata"))
        .toLocalDate().toString()
}.getOrNull()

/** A refusal blocks dependent punches that day, not unrelated later attendance days. */
internal suspend fun replayPendingPunchQueue(
    pending: List<PendingPunchEntity>,
    replay: suspend (PendingPunchEntity) -> PunchReplayResult,
): Boolean {
    val pausedDays = mutableSetOf<String>()
    for (punch in pending) {
        val day = pendingPunchDay(punch)
        if (day in pausedDays) continue
        when (replay(punch)) {
            PunchReplayResult.RESOLVED -> Unit
            PunchReplayResult.RETRY -> return true
            PunchReplayResult.WAIT_FOR_LOGIN -> return false
            PunchReplayResult.NEEDS_REVIEW -> if (day == null) return false else pausedDays.add(day)
        }
    }
    return false
}

/** A rejection is not an acknowledgement of this phone's saved punch. */
internal fun recordedPunchMatches(punch: PendingPunchEntity, day: DaySessionsResponse): Boolean {
    if (!day.success) return false
    val expected = runCatching { OffsetDateTime.parse(punch.clientPunchTime).toInstant() }.getOrNull()
        ?: return false
    return day.sessions.orEmpty().any { session ->
        val recorded = if (punch.isPunchIn) session.punchInTime else session.punchOutTime
        runCatching { OffsetDateTime.parse(recorded).toInstant() }.getOrNull() == expected
    }
}

internal class PendingPunchReplay(
    private val dao: PendingPunchDao,
    private val api: ApiService,
    private val token: String,
    private val upload: suspend (File) -> StorageUploader.Result,
    private val onRecorded: suspend (PendingPunchEntity, PunchResponse) -> Unit,
    private val deletePhoto: (String?) -> Unit,
) {
    suspend fun replay(punch: PendingPunchEntity): PunchReplayResult {
        if (punch.reviewReason != null) return PunchReplayResult.NEEDS_REVIEW
        currentCoroutineContext().ensureActive()
        var submittingPunch = false
        try {
            var storageId = punch.uploadedPhotoId?.takeIf { it.isNotBlank() }
            if (storageId == null && punch.photoPath != null) {
                val photo = File(punch.photoPath)
                if (!photo.exists()) return pause(punch, "Saved selfie is missing. Please contact HR.")
                val result = upload(photo)
                currentCoroutineContext().ensureActive()
                storageId = result.storageId?.takeIf { it.isNotBlank() }
                if (storageId == null) {
                    result.failureCause?.let { throw it }
                    return retry(punch, result.errorMessage ?: "Selfie upload could not be confirmed")
                }
                // Commit before submitting, so a lost punch reply never re-uploads the selfie.
                dao.saveUploadedPhoto(punch.id, storageId)
            }
            val request = PunchRequest(
                latitude = punch.latitude, longitude = punch.longitude,
                address = punch.address, photo = storageId, deviceId = punch.deviceId,
                source = punch.source, clientPunchTime = punch.clientPunchTime,
            )
            submittingPunch = true
            val response = if (punch.isPunchIn) api.punchIn(token, request) else api.punchOut(token, request)
            if (!response.success) return rejected(punch, AttendanceFailure.serverMessage(response.error))
            dao.deleteById(punch.id)
            deletePhoto(punch.photoPath)
            onRecorded(punch, response)
            return PunchReplayResult.RESOLVED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: HttpException) {
            val failure = AttendanceFailure.route(error) as AttendanceFailure.Rejected
            // The authenticated HTTP client owns session invalidation. Keep evidence for re-login.
            if (error.code() == 401) {
                return if (submittingPunch) PunchReplayResult.WAIT_FOR_LOGIN
                else pause(punch, "Selfie upload was not authorized. Please contact support.")
            }
            if (error.code() == 408 || error.code() == 429 || error.code() >= 500) {
                // Older servers wrapped duplicate refusals in 500; reconcile those too.
                if (AttendanceFailure.alreadyRecorded(failure.message) == null) return retry(punch, failure.message)
            }
            return rejected(punch, failure.message)
        } catch (error: Exception) {
            return retry(punch, AttendanceFailure.serverMessage(error.message))
        }
    }

    private suspend fun rejected(punch: PendingPunchEntity, message: String): PunchReplayResult {
        val already = AttendanceFailure.alreadyRecorded(message)
        val sameDirection = already?.clockedIn == punch.isPunchIn
        if (already != null && sameDirection) {
            val date = pendingPunchDay(punch)
            if (date != null) {
                val day = try {
                    api.getDaySessions(token, date)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (day != null && recordedPunchMatches(punch, day)) {
                    dao.deleteById(punch.id)
                    deletePhoto(punch.photoPath)
                    // Do not synthesize a punch or restart tracking for an old replay.
                    return PunchReplayResult.RESOLVED
                }
            }
        }
        return pause(punch, message)
    }

    private suspend fun pause(punch: PendingPunchEntity, message: String): PunchReplayResult {
        dao.markForReview(punch.id, message)
        return PunchReplayResult.NEEDS_REVIEW
    }

    private suspend fun retry(punch: PendingPunchEntity, message: String): PunchReplayResult {
        dao.bumpAttempt(punch.id, message)
        return PunchReplayResult.RETRY
    }
}
