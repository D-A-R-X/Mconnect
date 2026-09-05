package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JointCpWorkflowContractTest {
    @Test
    fun `submit review sends server-required fresh location metadata`() {
        val json = Gson().toJson(
            JointCpSubmitReviewRequest(
                id = "cp-1",
                fieldVisitId = "field-1",
                lat = 11.0123,
                lng = 76.9876,
                accuracyMeters = 12.5f,
                capturedAt = 1_788_500_000_000,
                arrivalPhotoStorageId = "storage-1",
                expectedOutcomeRevision = 3,
            ),
        )
        val body = Gson().fromJson(json, Map::class.java)

        assertEquals(12.5, body["accuracyMeters"])
        assertEquals(1_788_500_000_000.0, body["capturedAt"])
        assertTrue(body.containsKey("expectedOutcomeRevision"))
    }

    @Test
    fun `outcome summary accepts legacy string and structured server object`() {
        val gson = Gson()
        val legacy = gson.fromJson("""{"outcomeSummary":"Converted to site visit"}""", JointCpWorkflow::class.java)
        val structured = gson.fromJson(
            """{"outcomeSummary":{"outcome":"converted_to_site_visit","notes":"Reviewed"}}""",
            JointCpWorkflow::class.java,
        )

        assertEquals("Converted to site visit", legacy.outcomeSummary)
        assertEquals("converted_to_site_visit", structured.outcomeSummary)
    }
}
