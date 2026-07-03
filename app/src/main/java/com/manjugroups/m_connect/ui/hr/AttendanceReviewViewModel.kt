package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApproveAttendanceRequest
import com.manjugroups.m_connect.network.AttendanceApprovalRecord
import com.manjugroups.m_connect.network.RejectRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Approval scopes the queue can show, mirroring the web "Team Approvals"
 * sub-tabs. All are bounded to the caller's own reporting hierarchy.
 */
enum class ApprovalCategory(
    val label: String,
    val scope: String,
    val onlyOverdue: Boolean,
) {
    MY_TEAM("My Team", "direct", false),
    ALL_TEAM("All Team", "subtree", false),
    BLOCKING("Blocking Reviews", "subtree", true),
}

data class AttendanceReviewState(
    val pending: List<AttendanceApprovalRecord> = emptyList(),
    val isLoading: Boolean = false,
    val category: ApprovalCategory = ApprovalCategory.MY_TEAM,
)

class AttendanceReviewViewModel : ViewModel() {

    private val api = ApiService.create()

    private val _uiState = MutableStateFlow(AttendanceReviewState())
    val uiState: StateFlow<AttendanceReviewState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<String>()
    val event: SharedFlow<String> = _event.asSharedFlow()

    /** Switch category and reload. Ignores taps while a load is in flight. */
    fun selectCategory(bearerToken: String, category: ApprovalCategory) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(category = category)
        load(bearerToken)
    }

    fun load(bearerToken: String) {
        val category = _uiState.value.category
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val resp = runCatching {
                    api.getPendingAttendanceApprovals(
                        bearerToken,
                        scope = category.scope,
                        onlyOverdue = if (category.onlyOverdue) true else null,
                    )
                }.getOrNull()
                _uiState.value = _uiState.value.copy(
                    pending = resp?.records ?: emptyList(),
                    isLoading = false,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                _event.emit(e.message ?: "Failed to load approvals")
            }
        }
    }

    fun approve(bearerToken: String, id: String, approvedAttendance: String) {
        viewModelScope.launch {
            try {
                val resp = api.approveAttendance(
                    bearerToken,
                    ApproveAttendanceRequest(id = id, approvedAttendance = approvedAttendance),
                )
                if (resp.success) {
                    _event.emit("Attendance marked $approvedAttendance")
                    load(bearerToken)
                } else {
                    _event.emit(resp.error ?: "Failed to approve")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }

    fun reject(bearerToken: String, id: String, reason: String) {
        viewModelScope.launch {
            try {
                val resp = api.rejectAttendance(
                    bearerToken,
                    RejectRequest(id = id, reason = reason),
                )
                if (resp.success) {
                    _event.emit("Attendance rejected")
                    load(bearerToken)
                } else {
                    _event.emit(resp.error ?: "Failed to reject")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }
}
