package com.manjugroups.m_connect.network

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompleteVisitWireContractTest {
    private val gson = Gson()

    @Test
    fun `CP completion sends outcome fields on the atomic visit request`() {
        val json = gson.toJsonTree(
            CompleteVisitRequest(
                visitId = "field-visit-1",
                clientMet = true,
                outcome = "converted_to_site_visit",
                cpOutcomeNotes = "Confirmed by field staff",
            ),
        ).asJsonObject

        assertEquals(true, json["clientMet"].asBoolean)
        assertEquals("converted_to_site_visit", json["outcome"].asString)
        assertEquals("Confirmed by field staff", json["cpOutcomeNotes"].asString)
    }

    @Test
    fun `postponed CP completion keeps required scheduling fields`() {
        val json = gson.toJsonTree(
            CompleteVisitRequest(
                visitId = "field-visit-2",
                clientMet = true,
                outcome = "postponed",
                cpOutcomeNotes = "Client requested another date",
                postponeReasons = listOf("Client requested another date"),
                followUpDate = "2026-09-10",
            ),
        ).asJsonObject

        assertEquals("Client requested another date", json["postponeReasons"].asJsonArray[0].asString)
        assertEquals("2026-09-10", json["followUpDate"].asString)
    }

    @Test
    fun `ordinary trip completion omits optional CP fields`() {
        val json = gson.toJsonTree(CompleteVisitRequest(visitId = "field-visit-3")).asJsonObject

        assertFalse(json.has("clientMet"))
        assertFalse(json.has("outcome"))
        assertFalse(json.has("cpOutcomeNotes"))
        assertFalse(json.has("postponeReasons"))
        assertFalse(json.has("followUpDate"))
        assertFalse(json.has("followUpTime"))
    }
}
