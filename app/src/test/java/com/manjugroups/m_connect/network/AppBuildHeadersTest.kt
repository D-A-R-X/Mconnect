package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class AppBuildHeadersTest {
    @Test fun retainedSessionValidationReportsTheInstalledBuildWithoutLoggingInAgain() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            val client = OkHttpClient.Builder().addInterceptor(AppBuildHeaders.interceptor).build()
            val request = Request.Builder().url(server.url("/api/auth/validate-session"))
                .header("Authorization", "Bearer local-existing-session").build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("Bearer local-existing-session", sent.getHeader("Authorization"))
            assertEquals(BuildConfig.VERSION_CODE.toString(), sent.getHeader("X-App-Build"))
            assertEquals(BuildConfig.VERSION_NAME, sent.getHeader("X-App-Version"))
            assertEquals("android", sent.getHeader("X-App-Platform"))
        } finally { server.shutdown() }
    }
}
