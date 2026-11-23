package com.manjugroups.m_connect.ui.marketing

import com.google.gson.JsonParser
import com.manjugroups.m_connect.network.CreateSiteVisitRequest
import com.manjugroups.m_connect.network.CreateSiteVisitResponse
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SiteVisitAttendeeRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class SiteVisitCreateApiContractTest {
    @Test fun `all web SV routes serialize to the shared endpoint without completing GM handoffs`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(GeoTrackApi::class.java)
            for (routing in listOf("direct_sv", "same_area", "out_of_station", "immediate_pickup")) {
                val held = routing == "out_of_station" || routing == "immediate_pickup"
                val responseBody = if (held) {
                    """{"success":true,"mode":"pending_gm_verification","handoffId":"handoff-fixture"}"""
                } else {
                    """{"success":true,"mode":"created","siteVisitId":"sv-fixture","clientPlaceVisitId":"cp-fixture"}"""
                }
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(responseBody))
                val draft = request(routing)
                val response = api.createSiteVisit("Bearer local-fixture", draft)
                assertTrue(response.success)
                assertNull(validateSiteVisitCreateResponse(routing, response))
                if (held) assertNull(response.siteVisitId)
                val sent = server.takeRequest()
                assertEquals("POST", sent.method)
                assertEquals("/api/marketing/siteVisits/create", sent.path)
                val json = JsonParser.parseString(sent.body.readUtf8()).asJsonObject
                assertEquals(routing, json["routing"].asString)
                assertEquals(draft.requestId, json["requestId"].asString)
                assertEquals(draft.coreLeadId, json["coreLeadId"].asString)
                assertFalse(json.has("leadId"))
                assertEquals(routing != "direct_sv", json.has("pickupTime"))
                assertEquals(routing == "same_area", json.has("fieldStaffId"))
            }
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun `a success flag without the required creation identity is not a completed flow`() {
        assertNotNull(validateSiteVisitCreateResponse("direct_sv", CreateSiteVisitResponse(true, mode = "created")))
        assertNotNull(validateSiteVisitCreateResponse("same_area", CreateSiteVisitResponse(true, siteVisitId = "sv", mode = "created")))
        assertNotNull(validateSiteVisitCreateResponse("out_of_station", CreateSiteVisitResponse(true, mode = "pending_gm_verification")))
        assertNotNull(validateSiteVisitCreateResponse("immediate_pickup", CreateSiteVisitResponse(true, handoffId = "handoff", mode = "created")))
    }

    private fun request(routing: String) = CreateSiteVisitRequest(
        requestId = "71fc34fa-673d-4f89-b043-f82008d03eac", routing = routing, origin = "telecaller",
        coreLeadId = "7b76f254-b620-4bdd-89de-a6de3b61d769", mobileNumber = "9000000001",
        projectId = "project", scheduledDate = "2026-12-01", scheduledTime = "11:00",
        pickupTime = "10:00".takeUnless { routing == "direct_sv" },
        travelMode = if (routing == "direct_sv") "own_vehicle" else "cab",
        telecallerId = "lmo", bdoStaffId = "bdo", inchargeStaffId = "incharge",
        fieldStaffId = "field".takeIf { routing == "same_area" }, hodStaffId = "hod",
        avpStaffId = "avp", gmStaffId = "gm", seniorManagerStaffId = "senior",
        pickupAddress = "Test location", pickupLat = 12.0, pickupLng = 80.0,
        attendees = listOf(SiteVisitAttendeeRequest(name = "Test visitor")), expectedAttendeeCount = 1,
    )
}
