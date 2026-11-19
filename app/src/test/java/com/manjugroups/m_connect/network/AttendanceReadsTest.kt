package com.manjugroups.m_connect.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.ResponseBody.Companion.toResponseBody
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import java.lang.reflect.Proxy

class AttendanceReadsTest {

    @Test
    fun overlappingReadsShareOneRequestAndNothingIsKeptAfterward() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val fetch: suspend () -> String = {
            calls.incrementAndGet()
            gate.await()
            "answer-${calls.get()}"
        }
        val a = async { AttendanceReads.shared("k1", fetch) }
        val b = async { AttendanceReads.shared("k1", fetch) }
        val c = async { AttendanceReads.shared("k1", fetch) }
        withTimeout(2_000) { while (calls.get() == 0) kotlinx.coroutines.delay(5) }
        gate.complete(Unit)
        assertEquals(listOf("answer-1", "answer-1", "answer-1"), listOf(a.await(), b.await(), c.await()))
        assertEquals(1, calls.get())
        // Finished reads are not cached: the next read (e.g. after a punch) goes out again.
        withTimeout(2_000) { while (AttendanceReads.inFlightCount() > 0) kotlinx.coroutines.delay(5) }
        assertEquals("answer-2", AttendanceReads.shared("k1", fetch))
        assertEquals(2, calls.get())
    }

    @Test
    fun whenTheFirstCallerIsCancelledTheWaiterFetchesItself() = runBlocking {
        val calls = AtomicInteger()
        val hang = CompletableDeferred<Unit>()
        val leader = async {
            AttendanceReads.shared("k3") { calls.incrementAndGet(); hang.await(); "never" }
        }
        withTimeout(2_000) { while (calls.get() == 0) kotlinx.coroutines.delay(5) }
        val waiter = async { AttendanceReads.shared("k3") { calls.incrementAndGet(); "own" } }
        kotlinx.coroutines.delay(20)
        leader.cancel()
        assertEquals("own", waiter.await())
        assertEquals(2, calls.get())
    }

    @Test
    fun differentKeysAreNotShared() = runBlocking {
        val calls = AtomicInteger()
        val fetch: suspend () -> Int = { calls.incrementAndGet() }
        AttendanceReads.shared("today|t1|", fetch)
        AttendanceReads.shared("today|t2|", fetch)
        assertEquals(2, calls.get())
    }

    @Test
    fun aFailureReachesEveryWaiterAndIsNotKept() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val failing: suspend () -> String = {
            calls.incrementAndGet()
            gate.await()
            throw java.io.IOException("offline")
        }
        val a = async { runCatching { AttendanceReads.shared("k2", failing) } }
        val b = async { runCatching { AttendanceReads.shared("k2", failing) } }
        withTimeout(2_000) { while (calls.get() == 0) kotlinx.coroutines.delay(5) }
        gate.complete(Unit)
        assertTrue(a.await().exceptionOrNull() is java.io.IOException)
        assertTrue(b.await().exceptionOrNull() is java.io.IOException)
        withTimeout(2_000) { while (AttendanceReads.inFlightCount() > 0) kotlinx.coroutines.delay(5) }
        assertEquals("ok", AttendanceReads.shared("k2") { "ok" })
    }

    @Test
    fun cancellingOneLeaderDoesNotFanOutIntoTwentyReplacementRequests() = runBlocking {
        val calls = AtomicInteger()
        val original = CompletableDeferred<Unit>()
        val replacement = CompletableDeferred<Unit>()
        val leader = async(start = CoroutineStart.UNDISPATCHED) {
            AttendanceReads.shared("cancel-fanout") { calls.incrementAndGet(); original.await(); "old" }
        }
        val waiters = List(20) {
            async(start = CoroutineStart.UNDISPATCHED) {
                AttendanceReads.shared("cancel-fanout") {
                    calls.incrementAndGet()
                    replacement.await()
                    "fresh"
                }
            }
        }
        leader.cancelAndJoin()
        kotlinx.coroutines.yield()
        try {
            assertEquals("Only one replacement request may serve all surviving callers", 2, calls.get())
        } finally {
            replacement.complete(Unit)
        }
        assertTrue(waiters.map { it.await() }.all { it == "fresh" })
        assertEquals(0, AttendanceReads.inFlightCount())
    }

    @Test
    fun cancellingAWaiterDoesNotCancelTheLeaderOrOtherWaiters() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val fetch: suspend () -> String = { calls.incrementAndGet(); gate.await(); "current" }
        val leader = async(start = CoroutineStart.UNDISPATCHED) { AttendanceReads.shared("cancel-waiter", fetch) }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { AttendanceReads.shared("cancel-waiter", fetch) }
        val survivor = async(start = CoroutineStart.UNDISPATCHED) { AttendanceReads.shared("cancel-waiter", fetch) }
        cancelled.cancelAndJoin()
        gate.complete(Unit)
        assertEquals("current", leader.await())
        assertEquals("current", survivor.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun overloadResponsesReachWaitersWithoutAutomaticRetriesAndANewReadIsFresh() = runBlocking {
        for (status in listOf(429, 503)) {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val fetch: suspend () -> String = {
                calls.incrementAndGet()
                gate.await()
                throw retrofit2.HttpException(retrofit2.Response.error<String>(status,
                    "{}".toResponseBody(null)))
            }
            val readers = List(8) {
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { AttendanceReads.shared("overload-$status", fetch) }
                }
            }
            gate.complete(Unit)
            assertTrue(readers.map { it.await().exceptionOrNull() }.all {
                it is retrofit2.HttpException && it.code() == status
            })
            assertEquals("No retry is added for $status", 1, calls.get())
            assertEquals("fresh-after-action", AttendanceReads.shared("overload-$status") {
                calls.incrementAndGet()
                "fresh-after-action"
            })
            assertEquals(2, calls.get())
        }
    }

    @Test
    fun concurrentDatesAndAccountsDoNotShareAttendance() = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val keys = listOf("today|account-a|2026-11-19", "today|account-a|2026-11-20", "today|account-b|2026-11-19")
        val readers = keys.map { key -> async(start = CoroutineStart.UNDISPATCHED) {
            AttendanceReads.shared(key) { calls.incrementAndGet(); gate.await(); key }
        } }
        assertEquals(3, calls.get())
        gate.complete(Unit)
        assertEquals(keys, readers.map { it.await() })
    }

    @Test
    fun realTrackingGateKeepsOverloadUnknownWithoutStoppingAnExistingRouteOrRetrying() = runBlocking {
        for (status in listOf(429, 503)) {
            val calls = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val api = Proxy.newProxyInstance(ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java)) { _, method, args ->
                check(method.name in setOf("getMyAttendanceToday", "getDaySessions"))
                val fetch: suspend () -> Any? = {
                    calls.incrementAndGet()
                    gate.await()
                    throw retrofit2.HttpException(retrofit2.Response.error<String>(status, "{}".toResponseBody(null)))
                }
                @Suppress("UNCHECKED_CAST")
                fetch.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
            } as ApiService
            val readers = List(6) {
                async { AttendanceTrackingGate.hasOpenSessionNow("test-overload-$status", api) }
            }
            withTimeout(2_000) { while (calls.get() < 2) kotlinx.coroutines.yield() }
            gate.complete(Unit)
            assertTrue(readers.map { it.await() }.all { it == null })
            assertEquals("One actual read per endpoint, no overload retry", 2, calls.get())
            assertTrue("Unknown must not stop an existing route", AttendanceTrackingGate.mayContinueTracking(null))
            org.junit.Assert.assertFalse("Unknown must not start an unverified route", AttendanceTrackingGate.mayStartTracking(null))
        }
    }
}
