package com.manjugroups.m_connect.ui.hr

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class LeavesState(
    val casualLeft: Int = 0,
    val sickLeft: Int = 0,
    val earnedLeft: Int = 0,
    val casualTotal: Int = 0,
    val sickTotal: Int = 0,
    val earnedTotal: Int = 0,
    val myLeaves: List<LeaveData> = emptyList(),
    val pendingApprovals: List<LeaveData> = emptyList(),
    /**
     * Org-wide list — populated only for users with `leaves.viewAll`.
     * Drives the "All Leaves" scope on the summary screen.
     */
    val allLeaves: List<LeaveData> = emptyList(),
    val leaveTypes: List<String> = listOf("casual", "sick", "earned"),
    val isLoading: Boolean = false,
    val isApplying: Boolean = false
)

class LeavesViewModel : ViewModel() {

    private val api = ApiService.create()
    private val _uiState = MutableStateFlow(LeavesState())
    val uiState: StateFlow<LeavesState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<String>()
    val event: SharedFlow<String> = _event.asSharedFlow()

    // Cache the permission flags + token from the most recent explicit load
    // so that mutation reloads (approve / reject / cancel / apply) and the
    // fragment's onResume can re-fetch every list — including `allLeaves` —
    // without losing scope information. Before this, reloads defaulted
    // canViewAll to false and silently wiped the All-Leaves cache.
    private var lastCanApprove: Boolean = false
    private var lastCanViewAll: Boolean = false
    private var lastBearerToken: String? = null

    fun load(bearerToken: String, canApprove: Boolean, canViewAll: Boolean = false) {
        lastCanApprove = canApprove
        lastCanViewAll = canViewAll
        lastBearerToken = bearerToken
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                Log.d(TAG, "load → canApprove=$canApprove canViewAll=$canViewAll")
                val previous = _uiState.value
                val balance = safeCall("getLeaveBalance") { api.getLeaveBalance(bearerToken) }
                val history = safeCall("getMyLeaves") { api.getMyLeaves(bearerToken) }
                val pending = if (canApprove) {
                    safeCall("getPendingLeaveApprovals") { api.getPendingLeaveApprovals(bearerToken) }
                } else {
                    null
                }
                // Org-wide list only meaningful with `leaves.viewAll`. The
                // backend authorizes server-side too; fetching without the
                // permission just returns the bearer's own leaves so the
                // gate here is mostly to avoid an unnecessary round-trip.
                val all = if (canViewAll) {
                    safeCall("getAllLeaves") { api.getAllLeaves(bearerToken) }
                } else {
                    null
                }
                val policyResp = safeCall("getPolicy") { api.getPolicy(bearerToken) }

                val b = balance?.balance
                val policy = policyResp?.policy?.leave

                // Filter leave types — only show types with >0 allocation
                val types = mutableListOf<String>()
                if ((policy?.casualPerYear ?: 1) > 0) types.add("casual")
                if ((policy?.sickPerYear ?: 1) > 0) types.add("sick")
                if ((policy?.earnedPerYear ?: 1) > 0) types.add("earned")
                // Add extra types from policy (unpaid, compensatory, etc)
                policy?.types?.forEach { t ->
                    if (t !in listOf("casual", "sick", "earned") && t !in types) types.add(t)
                }

                // Only show balance for types that have >0 allocation in policy
                val casualAlloc = policy?.casualPerYear ?: 0
                val sickAlloc = policy?.sickPerYear ?: 0
                val earnedAlloc = policy?.earnedPerYear ?: 0

                // Don't wipe a previously-loaded list when a refresh's network
                // call fails. The empty fallback ONLY applies when no prior
                // value existed; otherwise we keep the last good list so a
                // transient blip (returning from ApplyLeave on a flaky
                // connection, etc.) doesn't flicker the screen to empty.
                val nextMyLeaves = history?.leaves
                    ?: previous.myLeaves.takeIf { it.isNotEmpty() }
                    ?: emptyList()
                val nextPending = pending?.leaves
                    ?: previous.pendingApprovals.takeIf { canApprove && it.isNotEmpty() }
                    ?: emptyList()
                val nextAll = all?.leaves
                    ?: previous.allLeaves.takeIf { canViewAll && it.isNotEmpty() }
                    ?: emptyList()

                Log.d(
                    TAG,
                    "load result → balance=${b != null} my=${nextMyLeaves.size} " +
                        "pending=${nextPending.size} all=${nextAll.size}",
                )

                _uiState.value = _uiState.value.copy(
                    casualLeft = if (casualAlloc > 0) (b?.casual ?: 0) - (b?.casualUsed ?: 0) else 0,
                    sickLeft = if (sickAlloc > 0) (b?.sick ?: 0) - (b?.sickUsed ?: 0) else 0,
                    earnedLeft = if (earnedAlloc > 0) (b?.earned ?: 0) - (b?.earnedUsed ?: 0) else 0,
                    casualTotal = if (casualAlloc > 0) b?.casual ?: 0 else 0,
                    sickTotal = if (sickAlloc > 0) b?.sick ?: 0 else 0,
                    earnedTotal = if (earnedAlloc > 0) b?.earned ?: 0 else 0,
                    myLeaves = nextMyLeaves,
                    pendingApprovals = nextPending,
                    allLeaves = nextAll,
                    leaveTypes = if (types.isNotEmpty()) types else listOf("casual", "sick", "earned")
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Wraps a network call so transport failures are visible to the user
     * instead of vanishing into a null. The empty-state-with-no-toast UX
     * we had before made it impossible to tell whether the user actually
     * has no leaves or whether the request 401'd / timed out.
     */
    private suspend fun <T> safeCall(label: String, block: suspend () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            val detail = when (e) {
                is HttpException -> "${e.code()} ${e.message()}"
                else -> e.message ?: e::class.java.simpleName
            }
            Log.w(TAG, "$label failed: $detail", e)
            _event.emit("Couldn't load $label: $detail")
            null
        }
    }

    private companion object {
        const val TAG = "LeavesViewModel"
    }

    /**
     * Re-run the most recent load() with its cached permission flags.
     * Called from the fragment's onResume (so a submitted leave shows up
     * after the user pops back from ApplyLeaveFragment) and from every
     * mutation (so manager scopes stay populated after approve / reject).
     * No-op until load() has been called at least once.
     */
    fun refresh() {
        val token = lastBearerToken ?: return
        load(token, lastCanApprove, lastCanViewAll)
    }

    fun applyLeave(bearerToken: String, type: String, from: String, to: String, reason: String) {
        _uiState.value = _uiState.value.copy(isApplying = true)
        viewModelScope.launch {
            try {
                val resp = api.applyLeave(bearerToken, ApplyLeaveRequest(type, from, to, reason))
                if (resp.success) {
                    _event.emit("Leave applied successfully!")
                    refresh()
                } else {
                    _event.emit(resp.error ?: "Failed to apply leave")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
            _uiState.value = _uiState.value.copy(isApplying = false)
        }
    }

    fun approveLeave(bearerToken: String, id: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.approveLeave(bearerToken, IdRequest(id))
                if (resp.success) {
                    _event.emit("Leave approved")
                    refresh()
                } else {
                    _event.emit(resp.error ?: "Failed to approve leave")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }

    /**
     * Owner-side cancel — invoked from the trash icon on a user's own
     * still-pending leave card. Mirrors the web's `/api/hr/leaves/cancel`
     * which only succeeds while the leave is in `pending` status.
     */
    fun cancelLeave(bearerToken: String, id: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.cancelLeave(bearerToken, IdRequest(id))
                if (resp.success) {
                    _event.emit("Leave cancelled")
                    refresh()
                } else {
                    _event.emit(resp.error ?: "Failed to cancel leave")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }

    fun rejectLeave(bearerToken: String, id: String, reason: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.rejectLeave(bearerToken, RejectRequest(id, reason))
                if (resp.success) {
                    _event.emit("Leave rejected")
                    refresh()
                } else {
                    _event.emit(resp.error ?: "Failed to reject leave")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }
}
