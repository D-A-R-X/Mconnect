package com.manjugroups.m_connect.ui.marketing

import com.google.gson.Gson
import com.manjugroups.m_connect.network.CreateCpVisitRequest
import com.manjugroups.m_connect.network.CreateCpVisitResponse
import org.junit.Assert.*
import org.junit.Test

class CpCreateWireContractTest {
    private val gson = Gson()
    private fun request() = CreateCpVisitRequest(
        mobileNumber = "9876543210", assignedStaffId = "staff-a",
        scheduledDate = "2026-09-05", visitAddress = "Test address",
        cpType = "joint_cp", jointCpCategory = "old_client",
        jointStaffIds = listOf("staff-a", "staff-b"),
    )

    @Test
    fun `wire request preserves joint purpose and participants`() {
        val json = gson.toJsonTree(request()).asJsonObject
        assertEquals("joint_cp", json["cpType"].asString)
        assertEquals("old_client", json["jointCpCategory"].asString)
        assertEquals(2, json["jointStaffIds"].asJsonArray.size())
    }

    @Test
    fun `retry fingerprint is stable and includes notes and referral changes`() {
        val original = request()
        val fingerprint = gson.toJson(original)
        assertEquals(fingerprint, gson.toJson(original.copy()))
        assertNotEquals(fingerprint, gson.toJson(original.copy(notes = "Changed instructions")))
        assertNotEquals(fingerprint, gson.toJson(original.copy(referralSourceType = "client_referral", referringClientId = "client-a")))
    }

    @Test
    fun `create response accepts both existing ID keys and preserves errors`() {
        for (key in listOf("id", "visitId")) {
            val response = gson.fromJson("""{"success":true,"$key":"cp-a","requestId":"request-a","cpType":"old_client"}""", CreateCpVisitResponse::class.java)
            assertTrue(response.success)
            assertEquals("cp-a", response.id)
            assertEquals("request-a", response.requestId)
            assertEquals("old_client", response.cpType)
        }
        val failure = gson.fromJson("""{"success":false,"error":"Staff unavailable"}""", CreateCpVisitResponse::class.java)
        assertFalse(failure.success)
        assertEquals("Staff unavailable", failure.error)
        assertNull(failure.id)
    }
}
