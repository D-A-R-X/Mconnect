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

data class AttendanceReviewState(
    val pending: List<AttendanceApprovalRecord> = emptyList(),
    val isLoading: Boolean = false,
)

class AttendanceReviewViewModel : ViewModel() {

    private val api = ApiService.create()

    private val _uiState = MutableStateFlow(AttendanceReviewState())
    val uiState: StateFlow<AttendanceReviewState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<String>()
    val event: SharedFlow<String> = _event.asSharedFlow()

    fun load(bearerToken: String) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                val resp = runCatching {
                    api.getPendingAttendanceApprovals(bearerToken)
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
