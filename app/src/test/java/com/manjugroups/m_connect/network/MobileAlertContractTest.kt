package com.manjugroups.m_connect.network

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MobileAlertContractTest {
    @Test
    fun `detail request is authenticated and preserves selected delivery id`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"alert":{"deliveryId":"selected-id","title":"Office update","bodyHtml":"<p>Details</p>","minViewSeconds":0}}"""))
            val api = Retrofit.Builder().baseUrl(server.url("/"))
                .addConverterFactory(GsonConverterFactory.create()).build().create(ApiService::class.java)
            val result = api.getMobileAlertDetail("Bearer test-session", "selected-id")
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/mobile/alerts/detail?deliveryId=selected-id", request.path)
            assertEquals("Bearer test-session", request.getHeader("Authorization"))
            assertTrue(result.success)
            assertEquals("selected-id", result.alert?.deliveryId)
            assertEquals(0, result.alert?.minViewSeconds)
        } finally { server.shutdown() }
    }

    @Test
    fun `missing alert is not decoded as another pending alert`() {
        val response = Gson().fromJson("""{"success":false,"error":"Alert is no longer available"}""", MobileAlertDetailResponse::class.java)
        assertEquals(false, response.success)
        assertEquals(null, response.alert)
    }
}
