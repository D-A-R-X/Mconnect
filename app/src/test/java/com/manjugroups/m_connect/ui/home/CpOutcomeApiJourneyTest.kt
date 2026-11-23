package com.manjugroups.m_connect.ui.home

import com.google.gson.JsonParser
import com.manjugroups.m_connect.network.CompleteVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SetSiteVisitOutcomeRequest
import com.manjugroups.m_connect.ui.marketing.confirmedCpOutcome
import com.manjugroups.m_connect.ui.marketing.isCpDecisionCommitted
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Real Retrofit envelopes, with no production visits or location requests. */
class CpOutcomeApiJourneyTest {
    @Test fun `confirmed SV receipt completes without an extra detail request`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(GeoTrackApi::class.java)
            server.enqueue(json("""{"success":true,"visit":{"_id":"sv-test","status":"completed","outcome":"postponed"}}"""))
            val response = api.setSiteVisitOutcomeConfirmed("Bearer local-test", SetSiteVisitOutcomeRequest(
                id = "sv-test", outcome = "follow_up",
            ))
            assertTrue(response.success)
            assertEquals(1, server.requestCount)
            assertEquals("/api/marketing/siteVisits/setOutcome", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun `precreated SV requires selection then completion carries outcome and preserves GM hold`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(GeoTrackApi::class.java)
            server.enqueue(json("""{"success":true,"visit":{"_id":"cp-test","status":"in_progress","clientMet":true,"convertedSiteVisitId":"sv-pending","fieldVisit":{"_id":"field-test","status":"arrived"}}}"""))
            val visit = api.getCpVisitDetail("Bearer local-test", "cp-test").visit!!
            assertNull(confirmedCpOutcome(visit.status, visit.outcome, visit.convertedSiteVisitId, visit.convertedBookingId))
            assertFalse(isCpDecisionCommitted(visit.status))
            assertEquals(1, server.requestCount) // Reading the pending link never completes it.
            assertEquals("GET", server.takeRequest().method)

            server.enqueue(json("""{"success":true,"status":"completed","visit":{"_id":"cp-test","status":"pending_gm_approval","outcome":"interested"}}"""))
            val response = api.completeVisit("Bearer local-test", CompleteVisitRequest(
                visitId = "field-test", clientMet = true, outcome = "interested",
                arrivalPhotoStorageId = "proof-test", cpOutcomeNotes = "Confirmed by client",
            ))
            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/api/geotrack/visit/complete", sent.path)
            val body = JsonParser.parseString(sent.body.readUtf8()).asJsonObject
            assertEquals("interested", body["outcome"].asString)
            assertEquals("proof-test", body["arrivalPhotoStorageId"].asString)
            assertTrue(body["clientMet"].asBoolean)
            val status = resolvedCpCompletionStatus(response.effectiveStatus, response.status, response.visit?.effectiveStatus, response.visit?.status)
            assertEquals("pending_gm_approval", status)
            assertTrue(isCompatibleCpCompletionStatus(status))
            assertTrue(isCpDecisionCommitted(status))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `real completed legacy conversion remains decided without resubmitting outcome`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(GeoTrackApi::class.java)
            server.enqueue(json("""{"success":true,"visit":{"_id":"cp-saved","status":"completed","convertedSiteVisitId":"sv-confirmed"}}"""))
            val visit = api.getCpVisitDetail("Bearer local-test", "cp-saved").visit!!
            assertTrue(isCpDecisionCommitted(visit.status))
            assertEquals("converted_to_site_visit", confirmedCpOutcome(visit.status, visit.outcome, visit.convertedSiteVisitId))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
