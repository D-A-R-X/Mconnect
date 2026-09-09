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

    @Test
    fun `review completion sends required remarks and outcome revision`() {
        val json = Gson().toJson(
            JointCpCompleteReviewRequest(
                id = "cp-1",
                expectedOutcomeRevision = 4,
                reviewerRemark = "Outcome reviewed with the client",
            ),
        )
        val body = Gson().fromJson(json, Map::class.java)

        assertEquals(4.0, body["expectedOutcomeRevision"])
        assertEquals("Outcome reviewed with the client", body["reviewerRemark"])
    }

    @Test
    fun `completion response accepts visit reviewer readiness and participant credits`() {
        val response = Gson().fromJson(
            """{
                "success":true,
                "visit":{"_id":"cp-1","status":"completed","effectiveStatus":"completed"},
                "workflow":{
                    "state":"completed",
                    "reviewerRemark":"Outcome checked with the client",
                    "creditedStaffIds":["staff-low","staff-high"]
                },
                "creditedStaffIds":["staff-low","staff-high"]
            }""".trimIndent(),
            JointCpWorkflowResponse::class.java,
        )

        assertEquals("completed", response.visit?.effectiveStatus)
        assertEquals("Outcome checked with the client", response.workflow?.reviewerRemark)
        assertEquals(listOf("staff-low", "staff-high"), response.creditedStaffIds)
    }

    @Test
    fun `participant response accepts reviewer readiness coordinates`() {
        val participant = Gson().fromJson(
            """{
                "staffId":"staff-high",
                "readyAt":1788503400000,
                "readyLat":13.0831,
                "readyLng":80.1754,
                "readyAccuracyMeters":12.5,
                "readyFieldVisitId":"field-high"
            }""".trimIndent(),
            JointCpParticipant::class.java,
        )

        assertEquals(1_788_503_400_000L, participant.readyAt)
        assertEquals("field-high", participant.readyFieldVisitId)
    }

    @Test
    fun `completed count response accepts participant aware visit ids`() {
        val response = Gson().fromJson(
            """{
                "success":true,
                "date":"2026-09-05",
                "staffId":"staff-low",
                "completedCount":3,
                "visitIds":["cp1","jointCp1","cp3"]
            }""".trimIndent(),
            CpCompletedCountResponse::class.java,
        )

        assertEquals(3, response.completedCount)
        assertEquals(listOf("cp1", "jointCp1", "cp3"), response.safeVisitIds)
    }
}
