package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.google.gson.Gson
import com.manjugroups.m_connect.network.*
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class HrLoadingTest(private val leave: Boolean) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "leave={0}")
        fun features() = listOf(arrayOf(true), arrayOf(false))
    }

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val calls = mutableListOf<Pair<String, List<Any?>>>()
    private var handler: suspend (String, List<Any?>) -> Any? = { name, _ -> error("Unexpected $name") }
    @Suppress("UNCHECKED_CAST")
    private val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
        val parameters = args!!.dropLast(1)
        calls += method.name to parameters
        val block: suspend () -> Any? = { handler(method.name, parameters) }
        block.startCoroutineUninterceptedOrReturn(args.last() as Continuation<Any?>)
    } as ApiService
    private lateinit var vm: ViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        vm = if (leave) LeavesViewModel(api) else PermissionsViewModel(api)
        store.put("hr", vm)
    }

    @After fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
    }

    private val historyMethod get() = if (leave) "getMyLeaves" else "getMyPermissions"
    private val totalsMethod get() = if (leave) "getLeaveBalance" else "getPermissionUsage"
    private fun load(status: String? = null, force: Boolean = false, token: String = "Bearer local-test") {
        when (val model = vm) {
            is LeavesViewModel -> model.load(token, true, status = status, force = force)
            is PermissionsViewModel -> model.load(token, true, status = status, force = force)
        }
    }
    private fun ids(): List<String?> = when (val model = vm) {
        is LeavesViewModel -> model.uiState.value.myLeaves.map { it.id }
        is PermissionsViewModel -> model.uiState.value.myPermissions.map { it.id }
        else -> error("Unknown model")
    }
    private fun loading(): List<Boolean> = when (val model = vm) {
        is LeavesViewModel -> model.uiState.value.let { listOf(it.isLoading, it.isTeamLoading, it.isAllLoading) }
        is PermissionsViewModel -> model.uiState.value.let { listOf(it.isLoading, it.isTeamLoading, it.isAllLoading) }
        else -> error("Unknown model")
    }
    private fun balanceLoaded() = when (val model = vm) {
        is LeavesViewModel -> model.uiState.value.balanceLoaded
        is PermissionsViewModel -> model.uiState.value.usageLoaded
        else -> false
    }
    private fun history(id: String): Any = if (leave) {
        Gson().fromJson("""{"success":true,"leaves":[{"_id":"$id"}]}""", MyLeavesResponse::class.java)
    } else {
        Gson().fromJson("""{"success":true,"permissions":[{"_id":"$id"}]}""", MyPermissionsResponse::class.java)
    }
    private fun optional(name: String): Any = when (name) {
        "getPolicy" -> PolicyResponse(true, PolicyData(LeavePolicy(6, 6, 0), PermissionPolicy(2.5), null))
        "getLeaveBalance" -> LeaveBalanceResponse(true, LeaveBalance(casual = 6.0, casualUsed = 0.5))
        "getPermissionUsage" -> PermissionUsageResponse(true, usedHours = 0.5, limitHours = 2.5, count = 1)
        "getPendingLeaveApprovals", "getPendingPermissionApprovals" -> history("approval")
        else -> error("Unexpected $name")
    }

    @Test fun historyRendersBeforeOptionalReadsAndEachScopeFinishesIndependently() = runTest(dispatcher) {
        val totals = CompletableDeferred<Unit>()
        val team = CompletableDeferred<Unit>()
        val all = CompletableDeferred<Unit>()
        handler = { name, args ->
            when (name) {
                historyMethod -> history("mine")
                totalsMethod -> { totals.await(); optional(name) }
                "getPolicy" -> optional(name)
                else -> {
                    val teamRequest = if (leave) args[1] == true else args[2] != true
                    (if (teamRequest) team else all).await()
                    optional(name)
                }
            }
        }
        load()
        runCurrent()
        assertEquals(listOf("mine"), ids())
        assertEquals(listOf(false, true, true), loading())
        assertFalse(balanceLoaded())
        totals.complete(Unit)
        runCurrent()
        assertTrue(balanceLoaded())
        assertEquals(listOf(false, true, true), loading())
        team.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, false, true), loading())
        all.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, false, false), loading())
    }

    @Test fun duplicateInFlightLoadsAreCoalesced() = runTest(dispatcher) {
        val pending = CompletableDeferred<Unit>()
        handler = { name, _ -> if (name == historyMethod) { pending.await(); history("mine") } else optional(name) }
        load()
        load()
        runCurrent()
        assertEquals(1, calls.count { it.first == historyMethod })
        pending.complete(Unit)
        runCurrent()
        assertEquals(listOf("mine"), ids())
    }

    @Test fun forcedPostMutationRefreshIsNotDroppedWhileOldHistoryIsPending() = runTest(dispatcher) {
        val stale = CompletableDeferred<Unit>()
        var requests = 0
        handler = { name, _ ->
            if (name == historyMethod) {
                if (++requests == 1) { withContext(NonCancellable) { stale.await() }; history("stale") }
                else history("after-submit")
            } else optional(name)
        }
        load()
        runCurrent()
        load(force = true)
        runCurrent()
        assertEquals(2, requests)
        assertEquals(listOf("after-submit"), ids())
        stale.complete(Unit)
        runCurrent()
        assertEquals(listOf("after-submit"), ids())
        assertFalse(loading().first())
    }

    @Test fun oldFilterCannotOverwriteNewFilterResults() = runTest(dispatcher) {
        val stale = CompletableDeferred<Unit>()
        handler = { name, args ->
            if (name == historyMethod) {
                val status = args[if (leave) 3 else 4]
                if (status == "pending") { withContext(NonCancellable) { stale.await() }; history("old") }
                else history("approved")
            } else optional(name)
        }
        load(status = "pending")
        runCurrent()
        load(status = "approved")
        runCurrent()
        assertEquals(listOf("approved"), ids())
        stale.complete(Unit)
        runCurrent()
        assertEquals(listOf("approved"), ids())
    }

    @Test fun optionalFailuresKeepPriorDataAndSessionChangeClearsIt() = runTest(dispatcher) {
        handler = { name, _ -> if (name == historyMethod) history("mine") else optional(name) }
        load()
        runCurrent()
        assertTrue(balanceLoaded())
        handler = { _, _ -> throw IOException("Local test failure") }
        load()
        runCurrent()
        assertEquals(listOf("mine"), ids())
        assertTrue(balanceLoaded())
        assertEquals(listOf(false, false, false), loading())
        load(token = "Bearer another-local-session")
        assertTrue(ids().isEmpty())
        assertFalse(balanceLoaded())
        runCurrent()
    }
}
