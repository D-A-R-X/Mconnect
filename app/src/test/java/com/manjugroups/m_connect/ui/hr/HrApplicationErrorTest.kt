package com.manjugroups.m_connect.ui.hr

import kotlinx.coroutines.CancellationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun convexWrapperIsStrippedFromTheReason() {
        // The 4 Nov report: the weekly limit came through as "Uncaught Error: …".
        val body = """{"success":false,"error":"Uncaught Error: You can apply for only 1 permission request per week.\n    at handler (../convex/permissions.ts:712:13)"}"""
        assertEquals("You can apply for only 1 permission request per week.",
            HrApplicationError.message(http(500, body), "Permission"))
        assertEquals("Invalid permission date", HrApplicationError.clean(
            "[CONVEX M(permissions:apply)] [Request ID: abc123] Server Error\nUncaught Error: Invalid permission date\n  at x"))
        assertEquals("Shift not found", HrApplicationError.clean("Error: Shift not found"))
        assertEquals("Enter a reason", HrApplicationError.message(http(400, "Uncaught Error: Enter a reason"), "Permission"))
    }

    @Test fun reasonlessServerErrorIsAmbiguousNotShownRaw() {
        val bare = http(500, """{"success":false,"error":"[Request ID: abc123] Server Error"}""")
        assertTrue(HrApplicationError.message(bare, "Permission").startsWith("Permission service"))
        assertTrue(HrApplicationError.isAmbiguous(http(500, """{"success":false,"error":"[Request ID: abc123] Server Error"}""")))
        assertTrue(HrApplicationError.isAmbiguous(http(502, "<html>bad gateway</html>")))
        assertTrue(HrApplicationError.isAmbiguous(SocketTimeoutException()))
        // A server that gave a reason was not ambiguous, nor is a 4xx.
        assertFalse(HrApplicationError.isAmbiguous(http(500, """{"error":"Uncaught Error: Weekly limit"}""")))
        assertFalse(HrApplicationError.isAmbiguous(http(400, "")))
        assertNull(HrApplicationError.clean("<html>x</html>"))
        assertNull(HrApplicationError.clean("   "))
    }

    @Test fun readingTheReasonTwiceGivesTheSameAnswer() {
        val e = http(500, """{"error":"Uncaught Error: Weekly limit"}""")
        assertFalse(HrApplicationError.isAmbiguous(e))
        assertEquals("Weekly limit", HrApplicationError.message(e, "Permission"))
    }
}
