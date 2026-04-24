package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AttendanceFlowState(
    val isClockedIn: Boolean = false,
    val todayHours: String = "00:00 Hrs",
    val latestTotalHours: String = "08:00:00 hrs",
    val latestRange: String = "09:00 AM  — 05:00 PM"
)

class AttendanceFlowViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AttendanceFlowState())
    val uiState: StateFlow<AttendanceFlowState> = _uiState.asStateFlow()

    fun markClockIn() {
        _uiState.value = _uiState.value.copy(
            isClockedIn = true,
            todayHours = "04:10 Hrs"
        )
    }

    fun markClockOut() {
        _uiState.value = _uiState.value.copy(
            isClockedIn = false,
            todayHours = "00:00 Hrs",
            latestTotalHours = "08:10:00 hrs",
            latestRange = "09:00 AM  — 05:10 PM"
        )
    }
}
