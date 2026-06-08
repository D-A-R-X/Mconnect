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

    fun load(bearerToken: String, canApprove: Boolean) {
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
