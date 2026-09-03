package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.*
import kotlinx.coroutines.async
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
    // pendingApprovals = the full "All Leaves" set for a leaves.viewAll /
    // super-admin caller; the caller's direct reports otherwise.
    // teamLeaves = the caller's direct reports only ("Team Leaves").
    val pendingApprovals: List<LeaveData> = emptyList(),
    val teamLeaves: List<LeaveData> = emptyList(),
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

    fun load(
        bearerToken: String,
        canApprove: Boolean,
        fromDate: String? = null,
        toDate: String? = null,
        status: String? = null,
        leaveType: String? = null,
        staffId: String? = null,
    ) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                // Independent reads fired in PARALLEL — previously serial, so the
                // screen blocked on the SUM of four round-trips; now the slowest.
                val balanceD = async { runCatching { api.getLeaveBalance(bearerToken) }.getOrNull() }
                val historyD = async {
                    runCatching {
                        api.getMyLeaves(
                            bearerToken,
                            fromDate = fromDate,
                            toDate = toDate,
                            status = status,
                            leaveType = leaveType,
                            staffId = staffId,
                            pageSize = 200,
                        )
                    }.getOrNull()
                }
                val pendingD = async {
                    if (canApprove) {
                        runCatching {
                            api.getPendingLeaveApprovals(
                                bearerToken,
                                scope = "direct",
                                fromDate = fromDate,
                                toDate = toDate,
                                status = status,
                                leaveType = leaveType,
                                staffId = staffId,
                                pageSize = 200,
                            )
                        }.getOrNull()
                    } else null
                }
                // Team Leaves = direct reports only, matching the web's
                // default My Team scope. For a non-viewAll
                // approver this equals the full set; for a viewAll/super-admin
                // it correctly narrows from "all org leaves" to just their team.
                val teamD = async {
                    if (canApprove) {
                        runCatching {
                            api.getPendingLeaveApprovals(
                                bearerToken,
                                teamOnly = true,
                                scope = "direct",
                                fromDate = fromDate,
                                toDate = toDate,
                                status = status,
                                leaveType = leaveType,
                                staffId = staffId,
                                pageSize = 200,
                            )
                        }.getOrNull()
                    } else null
                }
                val policyD = async { runCatching { api.getPolicy(bearerToken) }.getOrNull() }
                val balance = balanceD.await()
                val history = historyD.await()
                val pending = pendingD.await()
                val team = teamD.await()
                val policyResp = policyD.await()

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

                // Allocation (total) comes straight from HR policy — the source
                // of truth — so changing casual/sick/earned per-year in HR
                // settings updates the Available card on the next load. The
                // balance record only supplies how much has been *used*. Fall
                // back to the balance's policy-corrected total if the policy
                // fetch itself failed, so a transient error doesn't zero the card.
                val casualAlloc = policy?.casualPerYear ?: (b?.casual ?: 0)
                val sickAlloc = policy?.sickPerYear ?: (b?.sick ?: 0)
                val earnedAlloc = policy?.earnedPerYear ?: (b?.earned ?: 0)

                _uiState.value = _uiState.value.copy(
                    casualLeft = (casualAlloc - (b?.casualUsed ?: 0)).coerceAtLeast(0),
                    sickLeft = (sickAlloc - (b?.sickUsed ?: 0)).coerceAtLeast(0),
                    earnedLeft = (earnedAlloc - (b?.earnedUsed ?: 0)).coerceAtLeast(0),
                    casualTotal = casualAlloc,
                    sickTotal = sickAlloc,
                    earnedTotal = earnedAlloc,
                    myLeaves = history?.leaves ?: emptyList(),
                    pendingApprovals = pending?.leaves ?: emptyList(),
                    teamLeaves = team?.leaves ?: emptyList(),
                    leaveTypes = if (types.isNotEmpty()) types else listOf("casual", "sick", "earned")
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun applyLeave(bearerToken: String, type: String, from: String, to: String, reason: String) {
        _uiState.value = _uiState.value.copy(isApplying = true)
        viewModelScope.launch {
            try {
                val resp = api.applyLeave(bearerToken, ApplyLeaveRequest(type, from, to, reason))
                if (resp.success) {
                    _event.emit("Leave applied successfully!")
                    load(bearerToken, false)
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
                    load(bearerToken, canApprove)
                } else {
                    _event.emit(resp.error ?: "Failed to approve leave")
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
                    load(bearerToken, canApprove)
                } else {
                    _event.emit(resp.error ?: "Failed to reject leave")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }

    /**
     * Cancel one of the user's own pending leave requests. Hits
     * /api/hr/leaves/cancel with the row id; reloads on success so
     * the cancelled row disappears (or flips to a terminal state per
     * backend semantics). Mirrors PermissionsViewModel.cancelPermission.
     */
    fun cancelLeave(bearerToken: String, id: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.cancelLeave(bearerToken, IdRequest(id))
                if (resp.success) {
                    _event.emit("Leave request cancelled")
                    load(bearerToken, canApprove)
                } else {
                    _event.emit(resp.error ?: "Failed to cancel leave")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }
}
