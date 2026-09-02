package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.manjugroups.m_connect.ui.marketing.validateSiteVisitCreateResponse

class MobileLoanCpContractTest {
    private val gson = Gson()

    @Test
    fun `loan upload consumes idempotency and authoritative case fields`() {
        val response = gson.fromJson(
            """{
                "success":true,
                "attached":true,
                "alreadyUploaded":true,
                "document":{"label":"PAN Card","storageId":"storage-1","fileName":"pan.pdf"},
                "loanCase":{"_id":"loan-1","caseId":"case-1","bookingId":"booking-1","documentsChecklist":[]}
            }""".trimIndent(),
            UploadLoanDocumentResponse::class.java,
        )

        assertTrue(response.success)
        assertTrue(response.alreadyUploaded)
        assertEquals("storage-1", response.document?.storageId)
        assertEquals("loan-1", response.loanCase?.id)
    }

    @Test
    fun `cp outcome consumes linked sv and follow-up fields`() {
        val response = gson.fromJson(
            """{
                "success":true,
                "status":"rejected",
                "siteVisitId":"sv-1",
                "confirmationStatus":"confirmed",
                "followUpTaskId":"task-1",
                "alreadyProcessed":false
            }""".trimIndent(),
            GeoTrackResponse::class.java,
        )

        assertEquals("rejected", response.status)
        assertEquals("sv-1", response.siteVisitId)
        assertEquals("confirmed", response.confirmationStatus)
        assertEquals("task-1", response.followUpTaskId)
        assertNotNull(response.alreadyProcessed)
    }

    @Test
    fun `cp create consumes matching idempotency response`() {
        val response = gson.fromJson(
            """{
                "success":true,
                "id":"cp-1",
                "fieldVisitId":"field-1",
                "requestId":"12d857dd-c31e-4fdb-b4f4-f28884c0955e",
                "alreadyCreated":true
            }""".trimIndent(),
            CreateCpVisitResponse::class.java,
        )

        assertEquals("cp-1", response.id)
        assertEquals("field-1", response.fieldVisitId)
        assertEquals("12d857dd-c31e-4fdb-b4f4-f28884c0955e", response.requestId)
        assertTrue(response.alreadyCreated == true)
    }

    @Test
    fun `same area sv requires both linked records`() {
        val complete = CreateSiteVisitResponse(
            success = true,
            mode = "created",
            siteVisitId = "sv-1",
            clientPlaceVisitId = "cp-1",
        )
        val missingCp = complete.copy(clientPlaceVisitId = null)

        assertEquals(null, validateSiteVisitCreateResponse("same_area", complete))
        assertTrue(validateSiteVisitCreateResponse("same_area", missingCp)?.contains("verification CP") == true)
    }

    @Test
    fun `gm routed sv requires pending handoff`() {
        val complete = CreateSiteVisitResponse(
            success = true,
            mode = "pending_gm_verification",
            handoffId = "handoff-1",
        )

        assertEquals(null, validateSiteVisitCreateResponse("out_of_station", complete))
        assertTrue(
            validateSiteVisitCreateResponse(
                "immediate_pickup",
                complete.copy(handoffId = null),
            )?.contains("GM handoff") == true,
        )
    }
}
