package com.manjugroups.m_connect.attendance

import com.manjugroups.m_connect.geotrack.data.PendingPunchDao
import com.manjugroups.m_connect.geotrack.data.PendingPunchEntity
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DaySessionsResponse
import com.manjugroups.m_connect.network.PunchResponse
import com.manjugroups.m_connect.network.SessionData
import com.manjugroups.m_connect.network.StorageUploader
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class PendingPunchReplayTest {
    private val time = "2026-11-19T09:00:00.123+05:30"
    private val initial = PendingPunchEntity(id = 1, isPunchIn = true, clientPunchTime = time,
        staffId = "staff-one", createdAt = 1)
    private val dao = FakeDao(initial)
    private var punchCalls = 0
    private var dayCalls = 0
    private var uploads = 0
    private var recorded = 0
    private var photosDeleted = 0
    private var response: suspend () -> PunchResponse = { throw http(400, "Already punched in. Punch out first.") }
    private var day = DaySessionsResponse(true, sessions = listOf(SessionData(time, null, "mobile")))
    private var dayRead: suspend () -> DaySessionsResponse = { day }
    private var uploadResponse: suspend () -> StorageUploader.Result = { StorageUploader.Result("saved-photo", null) }

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        val block: suspend () -> Any? = {
            when (method.name) {
                "punchIn", "punchOut" -> { punchCalls++; response() }
                "getDaySessions" -> { dayCalls++; assertEquals("2026-11-19", args!![1]); dayRead() }
                else -> error("Unexpected ${method.name}")
            }
        }
        block.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
    } as ApiService

    private fun replayer() = PendingPunchReplay(dao, api, "Bearer test",
        upload = { uploads++; uploadResponse() },
        onRecorded = { _, _ -> recorded++ }, deletePhoto = { photosDeleted++ })

    @Test fun confirmedDuplicateIsClearedWithoutMakingUpANewPunch() = runTest {
        assertEquals(PunchReplayResult.RESOLVED, replayer().replay(dao.row!!))
        assertNull(dao.row)
        assertEquals(1, punchCalls)
        assertEquals(1, dayCalls)
        assertEquals(0, recorded)
    }

    @Test fun anotherPunchAtADifferentTimeIsKeptForReviewAndNeverReplayedAgain() = runTest {
        day = day.copy(sessions = listOf(SessionData("2026-11-19T08:45:00+05:30", null, "biometric")))
        val replay = replayer()
        assertEquals(PunchReplayResult.NEEDS_REVIEW, replay.replay(dao.row!!))
        assertNotNull(dao.row?.reviewReason)
        repeat(5) { assertEquals(PunchReplayResult.NEEDS_REVIEW, replay.replay(dao.row!!)) }
        assertEquals(1, punchCalls)
        assertEquals(1, dayCalls)
        assertEquals(0, photosDeleted)
    }

    @Test fun rejectedEnvelopeDoesNotDeleteUnconfirmedEvidence() = runTest {
        response = { PunchResponse(false, null, "Outside the allowed attendance location.") }
        assertEquals(PunchReplayResult.NEEDS_REVIEW, replayer().replay(dao.row!!))
        assertNotNull(dao.row?.reviewReason)
        assertEquals(0, dayCalls)
        assertEquals(0, photosDeleted)
    }

    @Test fun failedReconciliationKeepsEvidenceWithoutSchedulingMoreReads() = runTest {
        dayRead = { throw IOException("offline") }
        val replay = replayer()
        assertEquals(PunchReplayResult.NEEDS_REVIEW, replay.replay(dao.row!!))
        assertEquals(PunchReplayResult.NEEDS_REVIEW, replay.replay(dao.row!!))
        assertEquals(1, dayCalls)
    }

    @Test fun failedEnvelopeCannotConfirmADuplicate() = runTest {
        assertFalse(recordedPunchMatches(initial, day.copy(success = false)))
        assertFalse(recordedPunchMatches(initial, day.copy(sessions = null)))
        assertFalse(recordedPunchMatches(initial.copy(isPunchIn = false), day))
        assertTrue(recordedPunchMatches(initial, day.copy(sessions = listOf(
            SessionData("2026-11-19T03:30:00.123Z", null, "mobile")))))
        assertFalse(recordedPunchMatches(initial.copy(clientPunchTime = "invalid"), day))
    }

    @Test fun networkRetryReusesTheUploadedSelfie() = runTest {
        val photo = File.createTempFile("punch-replay", ".jpg")
        try {
            dao.row = initial.copy(photoPath = photo.absolutePath)
            response = { throw IOException("lost reply") }
            val replay = replayer()
            assertEquals(PunchReplayResult.RETRY, replay.replay(dao.row!!))
            assertEquals("saved-photo", dao.row?.uploadedPhotoId)
            response = { PunchResponse(true, "attendance") }
            assertEquals(PunchReplayResult.RESOLVED, replay.replay(dao.row!!))
            assertEquals(1, uploads)
            assertEquals(2, punchCalls)
            assertEquals(1, recorded)
        } finally { photo.delete() }
    }

    @Test fun rateLimitAndServerOverloadRetryButOtherClientErrorsDoNot() = runTest {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            dao.row = initial
            response = { throw http(status, "Server is busy") }
            assertEquals(PunchReplayResult.RETRY, replayer().replay(dao.row!!))
            assertNull(dao.row?.reviewReason)
        }
        for (status in listOf(400, 403, 404, 409, 422)) {
            dao.row = initial
            response = { throw http(status, "Punch not allowed") }
            assertEquals(PunchReplayResult.NEEDS_REVIEW, replayer().replay(dao.row!!))
            assertNotNull(dao.row?.reviewReason)
        }
    }

    @Test fun legacy500DuplicateIsAlsoReconciled() = runTest {
        response = { throw http(500, "Uncaught Error: Already punched in. Punch out first.") }
        assertEquals(PunchReplayResult.RESOLVED, replayer().replay(dao.row!!))
        assertNull(dao.row)
    }

    @Test fun unauthorizedKeepsThePunchForTheNextLoginAndDoesNotReconcile() = runTest {
        response = { throw http(401, "Unauthorized") }
        assertEquals(PunchReplayResult.WAIT_FOR_LOGIN, replayer().replay(dao.row!!))
        assertEquals(initial, dao.row)
        assertEquals(0, dayCalls)
    }

    @Test fun secondaryUpload401IsAnOperationFailureNotAnMmsLoginFailure() = runTest {
        val photo = File.createTempFile("punch-auth", ".jpg")
        try {
            dao.row = initial.copy(photoPath = photo.absolutePath)
            uploadResponse = { StorageUploader.Result(null, "Unauthorized", failureCause = http(401, "Unauthorized")) }
            assertEquals(PunchReplayResult.NEEDS_REVIEW, replayer().replay(dao.row!!))
            assertNotNull(dao.row?.reviewReason)
            assertEquals(0, punchCalls)
            assertEquals(0, photosDeleted)
        } finally { photo.delete() }
    }

    @Test fun cancellationNeverBecomesABusinessRefusalOrRetry() = runTest {
        response = { throw CancellationException("worker stopped") }
        try { replayer().replay(dao.row!!); fail("Expected cancellation") }
        catch (_: CancellationException) { assertEquals(initial, dao.row) }
    }

    @Test fun pausedPunchStopsDependentClockOutWithoutMoreRequests() = runTest {
        dao.row = initial.copy(reviewReason = "Needs review")
        assertFalse(replayPendingPunchQueue(listOf(dao.row!!, initial.copy(id = 2, isPunchIn = false)), replayer()::replay))
        assertEquals(0, punchCalls)
        assertEquals(0, dayCalls)
        assertEquals(0, uploads)
    }

    @Test fun oldRefusalDoesNotBlockAnUnrelatedLaterAttendanceDay() = runTest {
        val attempted = mutableListOf<Long>()
        val pending = listOf(initial, initial.copy(id = 2, isPunchIn = false),
            initial.copy(id = 3, clientPunchTime = "2026-11-20T09:00:00+05:30"))
        assertFalse(replayPendingPunchQueue(pending) {
            attempted += it.id
            if (it.id == 1L) PunchReplayResult.NEEDS_REVIEW else PunchReplayResult.RESOLVED
        })
        assertEquals(listOf(1L, 3L), attempted)
    }

    @Test fun retryAndExpiredSessionStopEveryDependentRequest() = runTest {
        for (result in listOf(PunchReplayResult.RETRY, PunchReplayResult.WAIT_FOR_LOGIN)) {
            val attempted = mutableListOf<Long>()
            assertEquals(result == PunchReplayResult.RETRY,
                replayPendingPunchQueue(listOf(initial, initial.copy(id = 2))) {
                    attempted += it.id; result
                })
            assertEquals(listOf(1L), attempted)
        }
    }

    private class FakeDao(var row: PendingPunchEntity?) : PendingPunchDao {
        override suspend fun insert(punch: PendingPunchEntity): Long { row = punch; return punch.id }
        override suspend fun listAll() = listOfNotNull(row)
        override suspend fun deleteById(id: Long) { row = null }
        override suspend fun bumpAttempt(id: Long, err: String?) { row = row?.copy(attemptCount = row!!.attemptCount + 1, lastError = err) }
        override suspend fun saveUploadedPhoto(id: Long, storageId: String) { row = row?.copy(uploadedPhotoId = storageId) }
        override suspend fun markForReview(id: Long, reason: String) { row = row?.copy(reviewReason = reason, lastError = reason) }
        override suspend fun count() = if (row == null) 0 else 1
    }

    private fun http(status: Int, message: String) = HttpException(Response.error<Any>(status,
        "{\"success\":false,\"error\":\"$message\"}".toResponseBody("application/json".toMediaType())))
}
