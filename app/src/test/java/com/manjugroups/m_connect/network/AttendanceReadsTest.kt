package com.manjugroups.m_connect.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

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
}
