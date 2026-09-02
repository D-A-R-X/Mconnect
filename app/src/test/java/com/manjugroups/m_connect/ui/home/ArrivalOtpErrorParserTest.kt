package com.manjugroups.m_connect.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrivalOtpErrorParserTest {
    @Test
    fun `extracts actionable backend error instead of HTTP status`() {
        assertEquals(
            "Only the assigned field staff can request OTP help",
            parseArrivalOtpErrorBody(
                """{"success":false,"error":"Only the assigned field staff can request OTP help"}""",
            ),
        )
    }

    @Test
    fun `cleans convex wrapper and stack tail`() {
        assertEquals(
            "No reporting manager is configured for you",
            parseArrivalOtpErrorBody(
                """{"error":"Uncaught Error: No reporting manager is configured for you\n at handler"}""",
            ),
        )
    }

    @Test
    fun `ignores empty and malformed bodies`() {
        assertNull(parseArrivalOtpErrorBody(null))
        assertNull(parseArrivalOtpErrorBody("not-json"))
    }
}
