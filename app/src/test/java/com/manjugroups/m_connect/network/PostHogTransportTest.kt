package com.manjugroups.m_connect.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PostHogTransportTest {
    @Test fun `batch client has no auth reporting interceptors cache cookies or retries`() {
        val client = PostHogTransport.client()
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
        assertTrue(client.callTimeoutMillis <= 15_000)
        val request = PostHogTransport.request("{}")
        assertEquals("us.i.posthog.com", request.url.host)
        assertEquals("/batch/", request.url.encodedPath)
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("X-App-Build"))
    }
    @Test fun `telemetry unauthorized and redirect responses cause only one isolated request`() {
        MockWebServer().use { server ->
            server.start()
            for (status in listOf(401, 429, 503, 307)) {
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Location", server.url("/forbidden-retry")))
                val request = PostHogTransport.request("{\"batch\":[]}").newBuilder().url(server.url("/batch/")).build()
                PostHogTransport.client().newCall(request).execute().use { assertEquals(status, it.code) }
                val sent = server.takeRequest()
                assertEquals("/batch/", sent.path)
                assertNull(sent.getHeader("Authorization"))
                assertNull(sent.getHeader("Cookie"))
                assertEquals("{\"batch\":[]}", sent.body.readUtf8())
            }
            assertEquals(4, server.requestCount)
        }
    }
}
