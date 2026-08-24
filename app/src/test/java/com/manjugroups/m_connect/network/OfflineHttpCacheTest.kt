package com.manjugroups.m_connect.network

import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Proves the app-wide offline response cache actually behaves as designed:
 * online requests still hit the network, the response is stored, and the last
 * known response is replayed once the network fails. These run against a real
 * OkHttp client + MockWebServer, so they exercise the genuine cache machinery
 * rather than a stand-in.
 */
class OfflineHttpCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        cacheDir = Files.createTempDirectory("offline_cache_test").toFile()
        client = OkHttpClient.Builder()
            .cache(Cache(cacheDir, 8L * 1024 * 1024))
            .addInterceptor(OfflineHttpCache.serveStaleWhenOffline)
            .addNetworkInterceptor(OfflineHttpCache.storeResponses)
            .build()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        runCatching { client.cache?.close() }
        cacheDir.deleteRecursively()
    }

    private fun get(url: String, token: String = "Bearer staff-1"): okhttp3.Response =
        client.newCall(
            Request.Builder().url(url).header("Authorization", token).build(),
        ).execute()

    @Test
    fun `online response is served from network and stored`() {
        server.enqueue(MockResponse().setBody("""{"success":true,"rows":1}"""))
        val url = server.url("/api/attendance").toString()

        get(url).use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"success":true,"rows":1}""", response.body!!.string())
            // Came from the network, not the cache.
            assertNotNull(response.networkResponse)
            assertNull(response.cacheResponse)
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `cached response is replayed when the network fails`() {
        server.enqueue(MockResponse().setBody("""{"success":true,"rows":42}"""))
        val url = server.url("/api/collections").toString()

        // Online: populate the cache.
        get(url).use { assertEquals("""{"success":true,"rows":42}""", it.body!!.string()) }

        // Go offline — the server is gone, so the call genuinely fails.
        server.shutdown()

        get(url).use { response ->
            assertEquals(200, response.code)
            // The screen still gets its data...
            assertEquals("""{"success":true,"rows":42}""", response.body!!.string())
            // ...and it demonstrably came from disk, not the network.
            assertNotNull(response.cacheResponse)
            assertNull(response.networkResponse)
        }
    }

    @Test
    fun `offline call with nothing cached still fails like before`() {
        server.shutdown()
        var threw = false
        try {
            get("http://localhost:${server.port}/api/never-visited").close()
        } catch (_: IOException) {
            threw = true
        }
        assertTrue("A cache miss must surface as the original network failure", threw)
    }

    @Test
    fun `another staff token is never served the cached response`() {
        server.enqueue(MockResponse().setBody("""{"staff":"one"}"""))
        val url = server.url("/api/collections/my").toString()
        get(url, token = "Bearer staff-1").use { it.body!!.string() }

        server.shutdown()

        // A different bearer token must NOT receive staff-1's cached data.
        var threw = false
        try {
            get(url, token = "Bearer staff-2").close()
        } catch (_: IOException) {
            threw = true
        }
        assertTrue("Cached entries must be scoped to the Authorization header", threw)
    }

    @Test
    fun `writes are never served from cache`() {
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        val url = server.url("/api/attendance/punch-in").toString()
        val body = okhttp3.RequestBody.create(null, "{}")
        client.newCall(Request.Builder().url(url).post(body).build()).execute()
            .use { assertEquals(200, it.code) }

        server.shutdown()

        // A queued punch must still fail offline so the app's offline queue runs.
        var threw = false
        try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute().close()
        } catch (_: IOException) {
            threw = true
        }
        assertTrue("POSTs must never be replayed from cache", threw)
    }
}
