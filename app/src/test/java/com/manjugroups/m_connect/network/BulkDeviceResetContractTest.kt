package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.POST

class BulkDeviceResetContractTest {
    @Test
    fun `bulk reset request uses staffIds array`() {
        val json = Gson().toJsonTree(
            BulkDeviceResetRequest(listOf("staff-a", "staff-b")),
        ).asJsonObject

        assertEquals(listOf("staff-a", "staff-b"), json["staffIds"].asJsonArray.map { it.asString })
    }

    @Test
    fun `bulk reset response parses operation counts`() {
        val response = Gson().fromJson(
            """{
                "success": true,
                "selectedStaffCount": 68,
                "staffWithBindings": 65,
                "bindingsCleared": 65,
                "mobileSessionsSignedOut": 67
            }""".trimIndent(),
            BulkDeviceResetResponse::class.java,
        )

        assertTrue(response.success)
        assertEquals(68, response.selectedStaffCount)
        assertEquals(65, response.staffWithBindings)
        assertEquals(65, response.bindingsCleared)
        assertEquals(67, response.mobileSessionsSignedOut)
    }

    @Test
    fun `bulk reset method uses deployed REST route`() {
        val method = ApiService::class.java.methods.single { it.name == "resetStaffDevicesBulk" }

        assertEquals(
            "api/hr/staff/device-reset/bulk",
            method.getAnnotation(POST::class.java)?.value,
        )
    }
}
