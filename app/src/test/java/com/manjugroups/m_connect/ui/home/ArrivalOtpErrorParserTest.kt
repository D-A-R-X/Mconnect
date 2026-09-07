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

    @Test
    fun `retries a validation 400 with a distinct CP id`() {
        assertEquals(
            true,
            shouldRetryArrivalOtpWithCpId(
                httpCode = 400,
                fieldVisitId = "field-visit-id",
                cpVisitId = "cp-visit-id",
            ),
        )
    }

    @Test
    fun `does not retry non-400 or duplicate identifiers`() {
        assertEquals(false, shouldRetryArrivalOtpWithCpId(500, "field", "cp"))
        assertEquals(false, shouldRetryArrivalOtpWithCpId(400, "same", "same"))
        assertEquals(false, shouldRetryArrivalOtpWithCpId(400, "field", ""))
    }

    @Test
    fun `does not retry OTP business rejection`() {
        assertEquals(
            false,
            shouldRetryArrivalOtpWithCpId(400, "field", "cp", "Invalid OTP."),
        )
        assertEquals(
            false,
            shouldRetryArrivalOtpWithCpId(400, "field", "cp", "No active OTP."),
        )
    }

    @Test
    fun `retries an identifier validation response`() {
        assertEquals(
            true,
            shouldRetryArrivalOtpWithCpId(
                400,
                "field",
                "cp",
                "A valid client place visit id is required",
            ),
        )
    }
}
