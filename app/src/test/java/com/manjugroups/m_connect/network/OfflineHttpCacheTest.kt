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

    /**
     * The regression that made the app behave offline WHILE ONLINE: once a
     * response was cached, the next online request must still hit the network
     * and must show the NEW data. If a stale entry can win here, every screen
     * freezes on whatever was captured first.
     */
    @Test
    fun `second online request returns fresh data, not the cached copy`() {
        val url = server.url("/api/attendance").toString()
        server.enqueue(MockResponse().setBody("""{"present":0}"""))
        server.enqueue(MockResponse().setBody("""{"present":42}"""))

        get(url).use { assertEquals("""{"present":0}""", it.body!!.string()) }
        get(url).use { response ->
            assertEquals("""{"present":42}""", response.body!!.string())
            assertNotNull("second online call must reach the network", response.networkResponse)
        }
        assertEquals(2, server.requestCount)
    }

    /**
     * Same thing when the server answers the revalidation with 304. OkHttp
     * replays the stored BODY on a 304, so a server that validates rather than
     * re-sends must not leave the screen showing nothing.
     */
    @Test
    fun `a 304 revalidation replays the stored body rather than an empty one`() {
        val url = server.url("/api/attendance").toString()
        server.enqueue(
            MockResponse()
                .setBody("""{"present":7}""")
                .setHeader("ETag", "v1"),
        )
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "v1"))

        get(url).use { assertEquals("""{"present":7}""", it.body!!.string()) }
        get(url).use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"present":7}""", response.body!!.string())
        }
        assertEquals(2, server.requestCount)
    }

    /**
     * A server ERROR must never displace the last good response in the cache.
     * The 502 itself is now answered from cache (see the 5xx test below), so
     * what this pins is that the good entry SURVIVES the error and is still
     * intact for the offline path afterwards.
     */
    @Test
    fun `a server error is not stored over the last good response`() {
        val url = server.url("/api/attendance").toString()
        server.enqueue(MockResponse().setBody("""{"present":9}"""))
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))

        get(url).use { assertEquals("""{"present":9}""", it.body!!.string()) }
        get(url).use { assertEquals("""{"present":9}""", it.body!!.string()) }

        // Network now fails; the good entry must still be intact.
        server.shutdown()
        get(url).use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"present":9}""", response.body!!.string())
        }
    }


    /**
     * The production failure this was written for: the device is ONLINE but the
     * backend is 5xx-ing. Before, every screen went blank — and a blank
     * Attendance month renders as a wall of synthesized "Absent". Now the last
     * known response is shown instead.
     */
    @Test
    fun `a 5xx while online replays the last known response`() {
        val url = server.url("/api/hr/attendance/all").toString()
        server.enqueue(MockResponse().setBody("""{"records":["mon","tue"]}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        get(url).use { assertEquals("""{"records":["mon","tue"]}""", it.body!!.string()) }
        get(url).use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"records":["mon","tue"]}""", response.body!!.string())
        }
    }

    /**
     * With nothing cached there is nothing better to show, so the server error
     * must reach the caller unchanged rather than being swallowed.
     */
    @Test
    fun `a 5xx with nothing cached is passed through`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
        get(server.url("/api/hr/attendance/all").toString()).use {
            assertEquals(502, it.code)
        }
    }

    /**
     * Auth and not-found answers are REAL answers about this request — the auth
     * watchdog logs the user out on 401, and screens show proper empty states
     * on 404. Replaying stale data over those would hide both.
     */
    @Test
    fun `401 and 404 are never replaced by cached data`() {
        val url = server.url("/api/hr/attendance/all").toString()
        server.enqueue(MockResponse().setBody("""{"records":["mon"]}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))

        get(url).use { assertEquals(200, it.code) }
        get(url).use { assertEquals(401, it.code) }
        get(url).use { assertEquals(404, it.code) }
    }

}
