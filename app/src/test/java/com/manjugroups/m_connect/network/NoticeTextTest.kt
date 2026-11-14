package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.network.ApiErrors.NoticeKind
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class NoticeTextTest {

    private fun notice(text: String) = ApiErrors.noticeForText(text)

    @Test
    fun httpPrefixGivesTheServerReason() {
        val n = notice("Error: HTTP 500 Leave overlaps an approved leave")
        assertEquals("Leave overlaps an approved leave", n.text)
        assertEquals(NoticeKind.ERROR, n.kind)
    }

    @Test
    fun bareStatusGetsPlainWording() {
        assertEquals("The server is busy right now. Try again shortly.", notice("HTTP 503").text)
        assertEquals("Something went wrong on the server. Try again.", notice("HTTP 500 Internal Server Error").text)
        assertEquals(NoticeKind.WARNING, notice("HTTP 401").kind)
        assertEquals("Your session expired. Sign in again.", notice("Failed: HTTP 401").text)
    }

    @Test
    fun screenPrefixIsKept() {
        assertEquals("File upload failed. That file is too large to upload.", notice("File upload failed (HTTP 413).").text)
        assertEquals("Something went wrong on the server. Try again.", notice("Server returned HTTP 500.").text)
        assertEquals("Couldn't save leave: Leave overlaps", notice("Couldn't save leave: HTTP 409 Leave overlaps").text)
        assertEquals(NoticeKind.WARNING, notice("Couldn't save leave: HTTP 409 Leave overlaps").kind)
    }

    @Test
    fun networkExceptionsBecomeSentences() {
        assertEquals(NoticeKind.OFFLINE, notice("Unable to resolve host \"api-mfpl.theairix.com\": No address associated with hostname").kind)
        assertEquals("The server took too long to respond. Try again.", notice("java.net.SocketTimeoutException: timeout").text)
        assertEquals("The server took too long to respond. Try again.", notice("timeout").text)
    }

    @Test
    fun jsonAndConvexNoiseAreCleaned() {
        assertEquals("id is required", notice("""{"success":false,"error":"id is required"}""").text.lowercase())
        assertEquals(
            "Staff not found",
            notice("[CONVEX M(x:y)] [Request ID: 1] Server Error Uncaught Error: Staff not found\n at h (../convex/x.ts:1:1)").text,
        )
    }

    @Test
    fun plainMessagesAreClassified() {
        assertEquals(NoticeKind.SUCCESS, notice("Leave applied").kind)
        assertEquals(NoticeKind.ERROR, notice("Failed to upload photo").kind)
        assertEquals(NoticeKind.WARNING, notice("Select a project").kind)
        assertEquals(NoticeKind.OFFLINE, notice("No internet connection").kind)
        assertEquals(NoticeKind.INFO, notice("Opening map").kind)
    }

    @Test
    fun throwablesMapByType() {
        assertEquals(NoticeKind.OFFLINE, ApiErrors.noticeFor(UnknownHostException("x")).kind)
        assertEquals(NoticeKind.OFFLINE, ApiErrors.noticeFor(SocketTimeoutException("timeout")).kind)
        val http = HttpException(Response.error<Any>(429, "".toResponseBody(null)))
        assertEquals("Too many requests. Wait a moment and try again.", ApiErrors.noticeFor(http).text)
    }
}
