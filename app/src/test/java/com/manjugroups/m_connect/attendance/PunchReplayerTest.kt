package com.manjugroups.m_connect.attendance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PunchReplayerTest {

    @Test
    fun `a server reason is a refusal, not a retry`() {
        val outcome = PunchReplayer.httpRejection(500, """{"error":"You are at home. Clock in from a work location."}""")
        assertEquals("You are at home. Clock in from a work location.", outcome?.error)
    }

    @Test
    fun `message field is read when there is no error field`() {
        assertEquals("No active punch-in found for today", PunchReplayer.httpRejection(400, """{"message":"No active punch-in found for today"}""")?.error)
    }

    @Test
    fun `gateway outages are retried even with a JSON body`() {
        assertNull(PunchReplayer.httpRejection(503, """{"error":"Service unavailable"}"""))
        assertNull(PunchReplayer.httpRejection(502, """{"message":"Bad gateway"}"""))
        assertNull(PunchReplayer.httpRejection(504, null))
    }

    @Test
    fun `an answer without a reason is retried`() {
        assertNull(PunchReplayer.httpRejection(500, "<html>Internal Server Error</html>"))
        assertNull(PunchReplayer.httpRejection(500, ""))
        assertNull(PunchReplayer.httpRejection(500, """{"error":"   "}"""))
        assertNull(PunchReplayer.httpRejection(500, null))
    }
}
