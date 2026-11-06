package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.ui.hr.ApprovalOverrideReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiErrorsTest {

    @Test
    fun convexWrappedErrorKeepsOnlyTheMessage() {
        val raw = "[CONVEX M(staffAttendance:approve)] [Request ID: 1a2b3c] Server Error\n" +
            "Uncaught Error: Approval differs from the shift-based system result (absent). " +
            "Enter an override reason before approving.\n    at resolveOverrideReason (../convex/staffAttendance.ts:7316:6)"
        assertEquals(
            "Approval differs from the shift-based system result (absent). Enter an override reason before approving.",
            ApiErrors.clean(raw),
        )
    }

    @Test
    fun plainErrorBodyIsReadAsIs() {
        assertEquals("id is required", ApiErrors.extract("""{"success":false,"error":"id is required"}"""))
        assertEquals("Not allowed", ApiErrors.extract("""{"message":"Not allowed"}"""))
    }

    @Test
    fun nonJsonOrEmptyBodiesGiveNothing() {
        assertNull(ApiErrors.extract("<html>502 Bad Gateway</html>"))
        assertNull(ApiErrors.extract(""))
        assertNull(ApiErrors.extract("""{"success":false}"""))
    }

    @Test
    fun overrideErrorNamesTheSystemResult() {
        assertEquals(
            "half-day",
            ApprovalOverrideReason.systemResultIn(
                "HTTP 500 Approval differs from the shift-based system result (half-day). Enter an override reason before approving.",
            ),
        )
        assertNull(ApprovalOverrideReason.systemResultIn("HTTP 500 Attendance record not found"))
    }
}
