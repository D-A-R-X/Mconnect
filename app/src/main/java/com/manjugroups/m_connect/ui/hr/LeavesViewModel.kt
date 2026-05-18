package com.manjugroups.m_connect.ui.hr

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
                val balance = try { api.getLeaveBalance(bearerToken) } catch (_: Exception) { null }
                val history = try { api.getMyLeaves(bearerToken) } catch (_: Exception) { null }
                val pending = if (canApprove) {
                    try { api.getPendingLeaveApprovals(bearerToken) } catch (_: Exception) { null }
                } else {
                    null
                }
                // Org-wide list only meaningful with `leaves.viewAll`. The
                // backend authorizes server-side too; fetching without the
                // permission just returns the bearer's own leaves so the
                // gate here is mostly to avoid an unnecessary round-trip.
                val all = if (canViewAll) {
                    try { api.getAllLeaves(bearerToken) } catch (_: Exception) { null }
                } else {
                    null
                }
                val policyResp = try { api.getPolicy(bearerToken) } catch (_: Exception) { null }

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

                _uiState.value = _uiState.value.copy(
                    casualLeft = if (casualAlloc > 0) (b?.casual ?: 0) - (b?.casualUsed ?: 0) else 0,
                    sickLeft = if (sickAlloc > 0) (b?.sick ?: 0) - (b?.sickUsed ?: 0) else 0,
                    earnedLeft = if (earnedAlloc > 0) (b?.earned ?: 0) - (b?.earnedUsed ?: 0) else 0,
                    casualTotal = if (casualAlloc > 0) b?.casual ?: 0 else 0,
                    sickTotal = if (sickAlloc > 0) b?.sick ?: 0 else 0,
                    earnedTotal = if (earnedAlloc > 0) b?.earned ?: 0 else 0,
                    myLeaves = history?.leaves ?: emptyList(),
                    pendingApprovals = pending?.leaves ?: emptyList(),
                    allLeaves = all?.leaves ?: emptyList(),
                    leaveTypes = if (types.isNotEmpty()) types else listOf("casual", "sick", "earned")
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
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
