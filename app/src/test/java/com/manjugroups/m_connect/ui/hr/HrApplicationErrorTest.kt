package com.manjugroups.m_connect.ui.hr

import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

class HrApplicationErrorTest {
    private fun http(status: Int, body: String) = HttpException(Response.error<String>(status, body.toResponseBody()))

    @Test fun preservesPolicyReasonEvenOnAnOlderServer500() {
        assertEquals("Permission must start at 09:30", HrApplicationError.message(
            http(500, """{"error":"Permission must start at 09:30"}"""), "Permission"))
    }

    @Test fun readsStructuredAndMessageErrors() {
        assertEquals("Insufficient balance", HrApplicationError.message(
            http(400, """{"error":{"message":"Insufficient balance"}}"""), "Leave"))
        assertEquals("Enter a reason", HrApplicationError.message(
            http(400, """{"message":"Enter a reason"}"""), "Permission"))
    }

    @Test fun htmlOutageDoesNotLeakMarkupAndConflictIsNotAnOutage() {
        assertTrue(HrApplicationError.message(http(503, "<html>gateway error</html>"), "Leave").startsWith("Leave service"))
        assertTrue(HrApplicationError.message(http(409, ""), "Leave").contains("request history"))
    }

    @Test fun ambiguousTimeoutWarnsAgainstBlindResubmission() {
        assertTrue(HrApplicationError.message(SocketTimeoutException(), "Leave").contains("history before submitting"))
    }

    @Test(expected = CancellationException::class) fun cancellationIsNeverShownAsAnApplicationFailure() {
        HrApplicationError.message(CancellationException(), "Leave")
    }
}
