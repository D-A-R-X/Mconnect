package com.manjugroups.m_connect.network

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InteractiveApiCallFactoryTest {
    private fun saturatedReads(isolatedWrites: Boolean, shiftLookup: Boolean = false) {
        val server = MockWebServer()
        val readsStarted = CountDownLatch(2)
        val releaseReads = CountDownLatch(1)
        val writeStarted = CountDownLatch(1)
        val writeFinished = CountDownLatch(1)
        val writes = AtomicInteger()
        val intercepted = AtomicInteger()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/background/") == true) {
                    readsStarted.countDown()
                    releaseReads.await(10, TimeUnit.SECONDS)
                } else {
                    writes.incrementAndGet()
                    assertEquals("Bearer local-test", request.getHeader("Authorization"))
                    assertEquals("preserved", request.getHeader("X-Client-Test"))
                    assertEquals(if (shiftLookup) "" else "local-payload", request.body.readUtf8())
                    writeStarted.countDown()
                }
                return MockResponse().setBody("""{"success":true}""")
            }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 2 })
            .addInterceptor { chain ->
                intercepted.incrementAndGet()
                chain.proceed(chain.request().newBuilder().header("X-Client-Test", "preserved").build())
            }.build()
        val factory: Call.Factory = if (isolatedWrites) InteractiveApiCallFactory(client) else client
        val calls = mutableListOf<Call>()
        fun start(request: Request, finished: CountDownLatch? = null) {
            factory.newCall(request).also { calls += it }.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { finished?.countDown() }
                override fun onResponse(call: Call, response: Response) { response.close(); finished?.countDown() }
            })
        }
        try {
            repeat(2) { start(Request.Builder().url(server.url("/background/$it")).build()) }
            assertTrue("Read queue did not fill", readsStarted.await(5, TimeUnit.SECONDS))
            val interactive = Request.Builder().url(server.url(
                if (shiftLookup) "/api/hr/shifts/today?date=2026-10-31&staffId=local-test" else "/api/hr/permissions/apply"
            )).header("Authorization", "Bearer local-test")
            if (!shiftLookup) interactive.post("local-payload".toRequestBody())
            start(interactive.build(), writeFinished)
            if (isolatedWrites) {
                assertTrue("Submission queued behind blocked reads", writeStarted.await(3, TimeUnit.SECONDS))
            } else {
                assertFalse("Baseline should reproduce shared-queue blocking", writeStarted.await(250, TimeUnit.MILLISECONDS))
            }
            releaseReads.countDown()
            assertTrue(writeFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
            assertEquals(3, intercepted.get())
        } finally {
            releaseReads.countDown()
            calls.forEach { it.cancel() }
            server.shutdown()
        }
    }

    @Test fun oldSharedDispatcherReproducesSubmissionWaitingForBackgroundReads() = saturatedReads(false)
    @Test fun submissionRetainsBodyAndAuthAndFinishesWhileReadsAreBlocked() = saturatedReads(true)
    @Test fun oldSharedDispatcherAlsoBlocksThePermissionFormShiftLookup() = saturatedReads(false, shiftLookup = true)
    @Test fun permissionFormShiftLookupDoesNotQueueBehindBackgroundReads() = saturatedReads(true, shiftLookup = true)
}
