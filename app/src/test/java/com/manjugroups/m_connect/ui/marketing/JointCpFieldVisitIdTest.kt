package com.manjugroups.m_connect.ui.marketing

import com.google.gson.Gson
import com.manjugroups.m_connect.network.JointCpSubmitReviewRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "ArgumentValidationError: Found ID k97… " on a Joint CP trip.
 *
 * When a staff member's leg has no field-visit id yet, the trip screen falls
 * back to the CP id. The Joint CP mutations validate `fieldVisitId` as a
 * strict fieldVisits id, so sending the CP id there rejected the request.
 */
class JointCpFieldVisitIdTest {

    @Test
    fun `a real leg id is sent`() {
        assertEquals("fv_1", jointCpFieldVisitIdOrNull("fv_1", "cp_1"))
    }

    @Test
    fun `the CP id fallback is never sent as a field visit id`() {
        assertNull(jointCpFieldVisitIdOrNull("cp_1", "cp_1"))
        assertNull(jointCpFieldVisitIdOrNull(" cp_1 ", "cp_1"))
    }

    @Test
    fun `missing or blank ids are omitted`() {
        assertNull(jointCpFieldVisitIdOrNull(null, "cp_1"))
        assertNull(jointCpFieldVisitIdOrNull("  ", "cp_1"))
    }

    @Test
    fun `a null field visit id is left out of the request body`() {
        val json = Gson().toJson(JointCpSubmitReviewRequest(id = "cp_1", fieldVisitId = null))
        assertFalse(json.contains("fieldVisitId"))
    }
}
