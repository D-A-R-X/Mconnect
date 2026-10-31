package com.manjugroups.m_connect.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class HrMobileContractTest {
    @Test fun halfDayLeaveBalanceDoesNotFailDeserialization() {
        val response = Gson().fromJson("""{"success":true,"balance":{"casual":12,"casualUsed":0.5,"sick":6,"sickUsed":1.5,"earned":6,"earnedUsed":0}}""", LeaveBalanceResponse::class.java)
        assertTrue(response.success)
        assertEquals(0.5, response.balance!!.casualUsed, 0.001)
        assertEquals(1.5, response.balance!!.sickUsed, 0.001)
    }

    @Test fun wholeDayLeaveBalancesRemainCompatible() {
        val balance = Gson().fromJson("""{"casual":12,"casualUsed":1,"sick":6,"sickUsed":0,"earned":6,"earnedUsed":0}""", LeaveBalance::class.java)
        assertEquals(12.0, balance.casual, 0.001)
        assertEquals(1.0, balance.casualUsed, 0.001)
    }

    @Test fun fractionalUsageAndOriginalRequestCountArePreserved() {
        val usage = Gson().fromJson("""{"success":true,"usedHours":1.5,"limitHours":4,"remainingHours":2.5,"usedRequests":1}""", PermissionUsageResponse::class.java)
        assertEquals(1.5, usage.usedHours!!, 0.001)
        assertEquals(2.5, usage.remainingHours!!, 0.001)
        assertEquals(1, usage.count)
    }

    @Test fun legacyCountAndWholeHoursStillDecode() {
        val usage = Gson().fromJson("""{"success":true,"totalHours":1,"limitHours":4,"count":2}""", PermissionUsageResponse::class.java)
        assertEquals(1.0, usage.totalHours!!, 0.001)
        assertEquals(2, usage.count)
    }

    @Test fun selectedDateShiftLookupUsesExistingAuthenticatedEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"date":"2026-11-02","shift":{"schedule":{"monday":{"isWorkDay":true,"startTime":"10:00","endTime":"19:00"}}}}"""))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
            val response = api.getTodayShift("Bearer local-test", "test-staff", "2026-11-02")
            val request = server.takeRequest()
            assertEquals("/api/hr/shifts/today?staffId=test-staff&date=2026-11-02", request.path)
            assertEquals("Bearer local-test", request.getHeader("Authorization"))
            assertEquals("10:00", response.shift?.schedule?.monday?.startTime)
        } finally { server.shutdown() }
    }

    @Test fun permissionUsesUnchangedPayloadAnd201Success() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"permissionId":"local-permission"}"""))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
            val response = api.applyPermission("Bearer local-test", ApplyPermissionRequest("2026-11-02", "10:00", "11:00", "Personal work"))
            val request = server.takeRequest()
            assertEquals("/api/hr/permissions/apply", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer local-test", request.getHeader("Authorization"))
            val payload = JsonParser.parseString(request.body.readUtf8()).asJsonObject
            assertEquals(setOf("date", "fromTime", "toTime", "reason"), payload.keySet())
            assertEquals("10:00", payload["fromTime"].asString)
            assertTrue(response.success)
            assertEquals("local-permission", response.permissionId)
        } finally { server.shutdown() }
    }
}
