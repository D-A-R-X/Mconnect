package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LeavesState(
    val casualLeft: Double = 0.0,
    val sickLeft: Double = 0.0,
    val earnedLeft: Double = 0.0,
    val casualTotal: Double = 0.0,
    val sickTotal: Double = 0.0,
    val earnedTotal: Double = 0.0,
    val myLeaves: List<LeaveData> = emptyList(),
    // pendingApprovals = the full "All Leaves" set for a leaves.viewAll /
    // super-admin caller; the caller's direct reports otherwise.
    // teamLeaves = the caller's direct reports only ("Team Leaves").
    val pendingApprovals: List<LeaveData> = emptyList(),
    val teamLeaves: List<LeaveData> = emptyList(),
    val leaveTypes: List<String> = listOf("casual", "sick", "earned"),
    val isLoading: Boolean = false,
    val isTeamLoading: Boolean = false,
    val isAllLoading: Boolean = false,
    val balanceLoaded: Boolean = false,
    val isApplying: Boolean = false
)

class LeavesViewModel(private val api: ApiService = ApiService.create()) : ViewModel() {

    private var loadJob: Job? = null
    private var loadKey: List<Any?>? = null
    private var loadGeneration = 0
    private var cachedBalance: LeaveBalance? = null
    private var cachedPolicy: LeavePolicy? = null
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
        force: Boolean = false,
    ) {
        val key = listOf(bearerToken, canApprove, fromDate, toDate, status, leaveType, staffId)
        if (!force && key == loadKey && loadJob?.isActive == true && _uiState.value.isLoading) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        if (loadKey?.firstOrNull() != bearerToken) {
            _uiState.value = LeavesState()
            cachedBalance = null
            cachedPolicy = null
        }
        loadKey = key
        _uiState.value = _uiState.value.copy(isLoading = true, isTeamLoading = canApprove, isAllLoading = canApprove)
        loadJob = viewModelScope.launch {
            launch {
                try {
                    val history = api.getMyLeaves(bearerToken, fromDate = fromDate, toDate = toDate,
                        status = status, leaveType = leaveType, staffId = staffId, pageSize = 100)
                    if (generation == loadGeneration && history.success) {
                        _uiState.value = _uiState.value.copy(myLeaves = history.leaves)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation == loadGeneration) _event.emit(HrApplicationError.message(error, "Leave"))
                } finally {
                    if (generation == loadGeneration) _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
            launch {
                val response = hrRead { api.getLeaveBalance(bearerToken) }
                if (generation == loadGeneration && response?.success == true && response.balance != null) {
                    cachedBalance = response.balance
                    publishBalance()
                }
            }
            launch {
                val response = hrRead { api.getPolicy(bearerToken) }
                if (generation == loadGeneration && response?.success == true) {
                    cachedPolicy = response.policy?.leave
                    publishBalance()
                }
            }
            if (canApprove) {
                launch {
                    try {
                        val response = hrRead { api.getPendingLeaveApprovals(bearerToken, scope = "direct",
                            fromDate = fromDate, toDate = toDate, status = status, leaveType = leaveType,
                            staffId = staffId, pageSize = 100) }
                        if (generation == loadGeneration && response?.success == true) {
                            _uiState.value = _uiState.value.copy(pendingApprovals = response.leaves)
                        }
                    } finally {
                        if (generation == loadGeneration) _uiState.value = _uiState.value.copy(isAllLoading = false)
                    }
                }
                launch {
                    try {
                        val response = hrRead { api.getPendingLeaveApprovals(bearerToken, teamOnly = true, scope = "direct",
                            fromDate = fromDate, toDate = toDate, status = status, leaveType = leaveType,
                            staffId = staffId, pageSize = 100) }
                        if (generation == loadGeneration && response?.success == true) {
                            _uiState.value = _uiState.value.copy(teamLeaves = response.leaves)
                        }
                    } finally {
                        if (generation == loadGeneration) _uiState.value = _uiState.value.copy(isTeamLoading = false)
                    }
                }
            }
        }
    }

    private fun publishBalance() {
        val b = cachedBalance
        val policy = cachedPolicy
        val types = mutableListOf<String>()
        if ((policy?.casualPerYear ?: 1) > 0) types.add("casual")
        if ((policy?.sickPerYear ?: 1) > 0) types.add("sick")
        if ((policy?.earnedPerYear ?: 1) > 0) types.add("earned")
        policy?.types?.forEach {
            if (it !in listOf("casual", "sick", "earned") && it !in types) types.add(it)
        }
        val casual = policy?.casualPerYear?.toDouble() ?: b?.casual ?: 0.0
        val sick = policy?.sickPerYear?.toDouble() ?: b?.sick ?: 0.0
        val earned = policy?.earnedPerYear?.toDouble() ?: b?.earned ?: 0.0
        _uiState.value = _uiState.value.copy(
            casualLeft = (casual - (b?.casualUsed ?: 0.0)).coerceAtLeast(0.0),
            sickLeft = (sick - (b?.sickUsed ?: 0.0)).coerceAtLeast(0.0),
            earnedLeft = (earned - (b?.earnedUsed ?: 0.0)).coerceAtLeast(0.0),
            casualTotal = casual, sickTotal = sick, earnedTotal = earned,
            balanceLoaded = b != null,
            leaveTypes = types.ifEmpty { listOf("casual", "sick", "earned") },
        )
    }

    fun applyLeave(bearerToken: String, type: String, from: String, to: String, reason: String) {
        if (_uiState.value.isApplying) return
        _uiState.value = _uiState.value.copy(isApplying = true)
        viewModelScope.launch {
            try {
                val resp = api.applyLeave(bearerToken, ApplyLeaveRequest(type, from, to, reason))
                if (resp.success) {
                    _event.emit("Leave applied successfully!")
                    load(bearerToken, false, force = true)
                } else {
                    _event.emit(resp.error ?: "Failed to apply leave")
                }
            } catch (e: Exception) {
                _event.emit(HrApplicationError.message(e, "Leave"))
            } finally {
                _uiState.value = _uiState.value.copy(isApplying = false)
            }
        }
    }

    fun approveLeave(bearerToken: String, id: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.approveLeave(bearerToken, IdRequest(id))
                if (resp.success) {
                    _event.emit("Leave approved")
                    load(bearerToken, canApprove, force = true)
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
                    load(bearerToken, canApprove, force = true)
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
                    load(bearerToken, canApprove, force = true)
                } else {
                    _event.emit(resp.error ?: "Failed to cancel leave")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }
}
