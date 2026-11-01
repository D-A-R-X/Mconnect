package com.manjugroups.m_connect.attendance

import com.google.gson.JsonSyntaxException
import com.google.gson.stream.MalformedJsonException
import com.manjugroups.m_connect.network.StorageUploader
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class AttendanceFailureTest {
    @Test fun uploadClassificationUsesOriginalCauseInsteadOfLegacyNetworkFlag() {
        for (cause in listOf(SSLHandshakeException("Certificate failure"), EOFException(), MalformedJsonException("Bad JSON"))) {
            val result = StorageUploader.Result(null, "Network error.", true, cause)
            assertTrue(AttendanceFailure.uploadFailure(result) is AttendanceFailure.Rejected)
        }
        assertEquals(AttendanceFailure.Offline, AttendanceFailure.uploadFailure(
            StorageUploader.Result(null, "Network error.", true, UnknownHostException()),
        ))
        try {
            AttendanceFailure.uploadFailure(StorageUploader.Result(null, "Cancelled", false, CancellationException()))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    private fun http(status: Int, body: String) = HttpException(
        Response.error<Any>(status, body.toResponseBody("application/json".toMediaType())),
    )

    @Test fun connectivityFailuresAreTheOnlyOfflineRoute() {
        listOf(IOException("Disconnected"), UnknownHostException(), ConnectException(), SocketTimeoutException())
            .forEach { assertEquals(AttendanceFailure.Offline, AttendanceFailure.route(it)) }
    }

    @Test fun emptyHtmlAndUnrecognizedHttpErrorsNeverQueue() {
        for (status in listOf(400, 401, 403, 500, 503)) {
            for (body in listOf("", "<html>Internal server error</html>", "{}", "{broken")) {
                assertTrue(AttendanceFailure.route(http(status, body)) is AttendanceFailure.Rejected)
            }
        }
    }

    @Test fun decodingProtocolTlsAndLocalFileErrorsNeverQueueEvenWhenWrappedInIo() {
        listOf(
            JsonSyntaxException("Bad JSON"), MalformedJsonException("Bad JSON"), EOFException(),
            ProtocolException(), SSLHandshakeException("Untrusted certificate"), FileNotFoundException(),
            http(500, ""),
        ).forEach {
            assertTrue(AttendanceFailure.route(it) is AttendanceFailure.Rejected)
            assertTrue(AttendanceFailure.route(IOException("Wrapped", it)) is AttendanceFailure.Rejected)
        }
    }

    @Test fun cancellationPropagatesInsteadOfReportingFailureOrQueueing() {
        val cancellation = CancellationException("Leaving screen")
        for (error in listOf(cancellation, IOException("Wrapped", cancellation))) {
            try {
                AttendanceFailure.route(error)
                fail("Cancellation must propagate")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
        }
        assertTrue(AttendanceFailure.route(IOException("Canceled")) is AttendanceFailure.Rejected)
    }

    @Test fun businessValidationSurvivesNestedErrorsAndBusinessHttp500() {
        val business = "No active punch-in found for today"
        assertEquals(
            AttendanceFailure.Rejected(business),
            AttendanceFailure.route(http(500, """{"error":{"message":"$business"}}""")),
        )
        assertEquals("You have already clocked in today.",
            AttendanceFailure.serverMessage("You have already clocked in today."))
        assertEquals("Outside the allowed attendance location.",
            AttendanceFailure.serverMessage("Outside the allowed attendance location."))
    }

    @Test fun backendDiagnosticsAreNotUserFacingInHttpOrFailedEnvelopes() {
        listOf(
            "Uncaught ReferenceError: checkStaffEscalationPenalty is not defined",
            "TypeError: Cannot read properties of undefined",
            "Unexpected failure\n    at attendance.ts:42:9",
            "java.lang.IllegalStateException: broken",
            "<!doctype html><html>500</html>",
        ).forEach { diagnostic ->
            assertEquals(AttendanceFailure.UNCONFIRMED, AttendanceFailure.serverMessage(diagnostic))
        }
        assertEquals(
            AttendanceFailure.Rejected(AttendanceFailure.UNCONFIRMED),
            AttendanceFailure.route(http(500,
                """{"message":"Uncaught ReferenceError: checkStaffEscalationPenalty is not defined"}""")),
        )
        assertEquals(AttendanceFailure.Rejected(AttendanceFailure.UNCONFIRMED),
            AttendanceFailure.route(IllegalStateException("private implementation detail")))
    }

    @Test fun submissionGateSurvivesRefreshAndAllowsRetryOnlyAfterCompletion() {
        val gate = AttendanceSubmissionGate()
        assertTrue(gate.tryBegin())
        assertTrue(gate.isActive)
        repeat(3) { assertFalse(gate.tryBegin()) }
        gate.finish()
        assertFalse(gate.isActive)
        assertTrue(gate.tryBegin())
    }

    @Test fun confirmationButtonUsesCurrentSubmissionStateAndPreservesGpsAndPhotoChecks() {
        assertTrue(canSubmitAttendance(hasPhoto = true, hasLocation = true, isSubmitting = false))
        assertFalse(canSubmitAttendance(hasPhoto = true, hasLocation = true, isSubmitting = true))
        assertFalse(canSubmitAttendance(hasPhoto = false, hasLocation = true, isSubmitting = false))
        assertFalse(canSubmitAttendance(hasPhoto = true, hasLocation = false, isSubmitting = false))
    }
}
