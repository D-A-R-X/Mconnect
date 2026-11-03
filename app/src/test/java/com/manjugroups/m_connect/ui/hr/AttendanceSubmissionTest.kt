package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModelStore
import com.manjugroups.m_connect.attendance.AttendanceFailure
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AttendanceData
import com.manjugroups.m_connect.network.AttendanceTodayResponse
import com.manjugroups.m_connect.network.DaySessionsResponse
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MyAttendanceResponse
import com.manjugroups.m_connect.network.PunchRequest
import com.manjugroups.m_connect.network.PunchResponse
import com.manjugroups.m_connect.network.StorageUploader
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceSubmissionTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var today = AttendanceTodayResponse(true, attendance())
    private var day = DaySessionsResponse(true)
    private var punch: suspend (PunchRequest) -> PunchResponse = { PunchResponse(false, null, "Outside the allowed attendance location.") }
    private var upload: suspend () -> StorageUploader.Result = { StorageUploader.Result("photo-id", null) }
    private var uploadCount = 0
    private var punchCount = 0
    private val uploadStarted = CompletableDeferred<Unit>()
    private lateinit var file: File
    private lateinit var vm: AttendanceFlowViewModel

    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        val block: suspend () -> Any? = {
            when (method.name) {
                "getMyAttendanceToday" -> today
                "getDaySessions" -> day
                "getMyAttendance" -> MyAttendanceResponse(true, 0, emptyList())
                "punchIn", "punchOut" -> {
                    punchCount++
                    punch(args!![1] as PunchRequest)
                }
                else -> error("Unexpected ${method.name}")
            }
        }
        block.startCoroutineUninterceptedOrReturn(args!!.last() as Continuation<Any?>)
    } as ApiService
    private val geo = Proxy.newProxyInstance(GeoTrackApi::class.java.classLoader, arrayOf(GeoTrackApi::class.java)) { _, method, _ ->
        error("Unexpected tracking ${method.name}")
    } as GeoTrackApi

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        file = File.createTempFile("attendance-test-", ".jpg")
        vm = AttendanceFlowViewModel(api, geo) { _, _ ->
            uploadCount++
            uploadStarted.complete(Unit)
            upload()
        }
        store.put("attendance", vm)
    }

    @After fun cleanup() {
        store.clear()
        file.delete()
        Dispatchers.resetMain()
    }

    private fun submit() = vm.punchIn("Bearer local-test", 12.9, 80.2, "Office", file, "device", remarks = "Note")

    @Test fun failedTodayEnvelopeKeepsKnownAttendanceAndUnloadedStateDoesNotBecomeAuthoritative() = runTest(dispatcher) {
        today = AttendanceTodayResponse(false, null)
        vm.loadTodayAttendance("local-test")
        runCurrent()
        assertFalse(vm.uiState.value.hasLoaded)
        today = AttendanceTodayResponse(true, attendance())
        vm.loadTodayAttendance("local-test")
        runCurrent()
        val known = vm.uiState.value
        assertTrue(known.isClockedIn)
        today = AttendanceTodayResponse(false, null)
        vm.loadTodayAttendance("local-test")
        runCurrent()
        assertEquals(known, vm.uiState.value)
    }

    @Test fun failedDayEnvelopeCannotInjectDataAndSuccessfulEmptyDayStillClearsOldState() = runTest(dispatcher) {
        vm.loadTodayAttendance("local-test")
        runCurrent()
        assertTrue(vm.uiState.value.isClockedIn)
        today = AttendanceTodayResponse(true, null)
        day = DaySessionsResponse(false, firstPunchIn = "2026-11-01T09:00:00+05:30", hasOpenSession = true)
        vm.loadTodayAttendance("local-test")
        runCurrent()
        assertTrue(vm.uiState.value.hasLoaded)
        assertFalse(vm.uiState.value.isClockedIn)
        assertNull(vm.uiState.value.firstPunchInIso)
        day = DaySessionsResponse(true)
        vm.loadTodayAttendance("local-test")
        runCurrent()
        assertFalse(vm.uiState.value.isClockedIn)
    }

    @Test fun duplicateSubmissionIsIgnoredWhileUploadIsPendingEvenAfterAttendanceRefresh() = runTest(dispatcher) {
        val release = CompletableDeferred<StorageUploader.Result>()
        upload = { release.await() }
        val failed = async(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.first { it is AttendanceFlowEvent.SubmissionFailed }
        }
        submit()
        submit()
        uploadStarted.await()
        vm.loadTodayAttendance("local-test")
        runCurrent()
        assertTrue(vm.uiState.value.isSubmitting)
        assertTrue(vm.uiState.value.isClockedIn)
        submit()
        assertEquals(1, uploadCount)
        release.complete(StorageUploader.Result("photo-id", null))
        failed.await()
        runCurrent()
        assertEquals(1, punchCount)
        assertFalse(vm.uiState.value.isSubmitting)
    }

    @Test fun failedPunchEnvelopeRollsBackAndDoesNotEmitSuccessOrLoseRequestFields() = runTest(dispatcher) {
        val events = mutableListOf<AttendanceFlowEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        punch = { request ->
            assertEquals(12.9, request.latitude!!, 0.0)
            assertEquals(80.2, request.longitude!!, 0.0)
            assertEquals("photo-id", request.photo)
            assertEquals("device", request.deviceId)
            assertEquals("Office", request.address)
            assertEquals("Note", request.remarks)
            assertFalse(request.clientPunchTime.isNullOrBlank())
            PunchResponse(false, null, "Uncaught ReferenceError: checkStaffEscalationPenalty is not defined")
        }
        val failed = async(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.first { it is AttendanceFlowEvent.SubmissionFailed }
        }
        submit()
        assertEquals(AttendanceFlowEvent.SubmissionFailed(PunchMode.PUNCH_IN, AttendanceFailure.UNCONFIRMED), failed.await())
        runCurrent()
        assertFalse(vm.uiState.value.isClockedIn)
        assertFalse(vm.uiState.value.hasClockedInToday)
        assertFalse(vm.uiState.value.isSubmitting)
        assertTrue(events.none { it is AttendanceFlowEvent.Success })
    }

    @Test fun cancelledSubmissionRollsBackAndReleasesGuardWithoutSuccessOrFailureEvents() = runTest(dispatcher) {
        val events = mutableListOf<AttendanceFlowEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        for (failure in listOf(CancellationException("Cancelled upload"), IOException("Wrapped", CancellationException()))) {
            upload = { throw failure }
            val attempt = uploadCount + 1
            val completed = async(UnconfinedTestDispatcher(testScheduler)) {
                vm.uiState.first { !it.isSubmitting && uploadCount == attempt }
            }
            submit()
            completed.await()
            assertEquals(0, punchCount)
            assertFalse(vm.uiState.value.isClockedIn)
            assertTrue(events.all { it is AttendanceFlowEvent.Loading })
        }
        upload = { StorageUploader.Result("photo-id", null) }
        val failed = async(UnconfinedTestDispatcher(testScheduler)) { vm.events.first { it is AttendanceFlowEvent.SubmissionFailed } }
        submit()
        failed.await()
        assertEquals(3, uploadCount)
        assertEquals(1, punchCount)
    }

    @Test fun punchAlreadyRecordedElsewhereIsReportedAndRefreshedNotFailed() = runTest(dispatcher) {
        // The staff clocked in elsewhere while the clock-in screen was open:
        // the backend answers HTTP 500 "Uncaught Error: Already punched in...".
        val events = mutableListOf<AttendanceFlowEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        punch = { PunchResponse(false, null, "Uncaught Error: Already punched in. Punch out first.\n    at handler (../convex/staffAttendance.ts:6612:12)") }
        val already = async(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.first { it is AttendanceFlowEvent.AlreadyRecorded }
        }
        submit()
        assertEquals(
            AttendanceFlowEvent.AlreadyRecorded(PunchMode.PUNCH_IN, AttendanceFailure.AlreadyRecorded.CLOCKED_IN.message, clockedIn = true),
            already.await(),
        )
        runCurrent()
        assertEquals(1, punchCount)
        assertFalse(vm.uiState.value.isSubmitting)
        assertTrue(events.none { it is AttendanceFlowEvent.SubmissionFailed || it is AttendanceFlowEvent.Success })
    }

    @Test fun activeSubmissionRefreshReducerPreservesOptimisticClockStateButIdleEmptyRefreshWorks() {
        val current = AttendanceFlowState(isSubmitting = true, isClockedIn = true, hasClockedInToday = true)
        val empty = AttendanceFlowState(hasLoaded = true)
        val active = AttendanceFlowViewModel.mergeAttendanceRefresh(empty, current, true)
        assertTrue(active.isSubmitting)
        assertTrue(active.isClockedIn)
        assertTrue(active.hasClockedInToday)
        assertEquals(empty, AttendanceFlowViewModel.mergeAttendanceRefresh(empty, current, false))
    }

    private fun attendance() = AttendanceData(
        totalMinutes = 30, hasOpenSession = true, firstPunchIn = "2026-11-01T09:00:00+05:30",
        lastPunchOut = null, sessions = emptyList(), status = "present",
    )
}
