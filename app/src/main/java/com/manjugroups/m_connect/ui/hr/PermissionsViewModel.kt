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

data class PermissionsState(
    val usedHours: Double = 0.0,
    val limitHours: Double = 0.0,
    val count: Int = 0,
    val myPermissions: List<PermissionData> = emptyList(),
    // Team Permission scope — direct-report pending approvals.
    val pendingApprovals: List<PermissionData> = emptyList(),
    // All Permission scope — every request company-wide (admins / viewAll).
    val allApprovals: List<PermissionData> = emptyList(),
    val isApplying: Boolean = false,
    val isLoading: Boolean = false,
    val isTeamLoading: Boolean = false,
    val isAllLoading: Boolean = false,
    val usageLoaded: Boolean = false,
)

class PermissionsViewModel(private val api: ApiService = ApiService.create()) : ViewModel() {

    private var loadJob: Job? = null
    private var loadKey: List<Any?>? = null
    private var loadGeneration = 0
    private val _uiState = MutableStateFlow(PermissionsState())
    val uiState: StateFlow<PermissionsState> = _uiState.asStateFlow()

    private val _event = MutableSharedFlow<String>()
    val event: SharedFlow<String> = _event.asSharedFlow()

    fun load(
        bearerToken: String,
        canApprove: Boolean,
        fromDate: String? = null,
        toDate: String? = null,
        status: String? = null,
        staffId: String? = null,
        force: Boolean = false,
    ) {
        val key = listOf(bearerToken, canApprove, fromDate, toDate, status, staffId)
        if (!force && key == loadKey && loadJob?.isActive == true && _uiState.value.isLoading) return
        val generation = ++loadGeneration
        loadJob?.cancel()
        if (loadKey?.firstOrNull() != bearerToken) _uiState.value = PermissionsState()
        loadKey = key
        _uiState.value = _uiState.value.copy(isLoading = true, isTeamLoading = canApprove, isAllLoading = canApprove)
        loadJob = viewModelScope.launch {
            // Publish each dataset independently; a slow optional read must not
            // keep the selected list hidden or a refresh spinner running.
            launch {
                try {
                    val history = api.getMyPermissions(bearerToken, staffId = staffId, fromDate = fromDate,
                        toDate = toDate, status = status, pageSize = 100)
                    if (generation == loadGeneration && history.success) {
                        _uiState.value = _uiState.value.copy(myPermissions = history.permissions)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation == loadGeneration) _event.emit(HrApplicationError.message(error, "Permission"))
                } finally {
                    if (generation == loadGeneration) _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
            launch {
                val usage = hrRead { api.getPermissionUsage(bearerToken) }
                if (generation == loadGeneration && usage?.success == true) {
                    _uiState.value = _uiState.value.copy(
                        usedHours = usage.usedHours ?: usage.totalHours ?: _uiState.value.usedHours,
                        limitHours = usage.limitHours ?: _uiState.value.limitHours,
                        count = usage.count ?: _uiState.value.count,
                        usageLoaded = true,
                    )
                }
            }
            launch {
                val policy = hrRead { api.getPolicy(bearerToken) }
                if (generation == loadGeneration && policy?.success == true && !_uiState.value.usageLoaded) {
                    policy?.policy?.permission?.monthlyLimitHours?.let {
                        _uiState.value = _uiState.value.copy(limitHours = it)
                    }
                }
            }
            if (canApprove) {
                launch {
                    try {
                        val response = hrRead { api.getPendingPermissionApprovals(
                            bearerToken, scope = "direct", fromDate = fromDate, toDate = toDate,
                            status = status, staffId = staffId, pageSize = 100,
                        ) }
                        if (generation == loadGeneration && response?.success == true) {
                            _uiState.value = _uiState.value.copy(pendingApprovals = response.permissions)
                        }
                    } finally {
                        if (generation == loadGeneration) _uiState.value = _uiState.value.copy(isTeamLoading = false)
                    }
                }
                launch {
                    try {
                        val response = hrRead { api.getPendingPermissionApprovals(
                            bearerToken, scope = "direct", all = true, fromDate = fromDate, toDate = toDate,
                            status = status, staffId = staffId, pageSize = 100,
                        ) }
                        if (generation == loadGeneration && response?.success == true) {
                            _uiState.value = _uiState.value.copy(allApprovals = response.permissions)
                        }
                    } finally {
                        if (generation == loadGeneration) _uiState.value = _uiState.value.copy(isAllLoading = false)
                    }
                }
            }
        }
    }

    fun applyPermission(bearerToken: String, date: String, fromTime: String, toTime: String, reason: String) {
        if (_uiState.value.isApplying) return
        _uiState.value = _uiState.value.copy(isApplying = true)
        viewModelScope.launch {
            try {
                val resp = api.applyPermission(bearerToken, ApplyPermissionRequest(date, fromTime, toTime, reason))
                if (resp.success) {
                    _event.emit("Permission applied successfully!")
                    load(bearerToken, false, force = true)
                } else {
                    _event.emit(resp.error ?: "Failed to apply")
                }
            } catch (e: Exception) {
                _event.emit(HrApplicationError.message(e, "Permission"))
            } finally {
                _uiState.value = _uiState.value.copy(isApplying = false)
            }
        }
    }

    fun approvePermission(bearerToken: String, id: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.approvePermission(bearerToken, IdRequest(id))
                if (resp.success) {
                    _event.emit("Permission approved")
                    load(bearerToken, canApprove, force = true)
                } else {
                    _event.emit(resp.error ?: "Failed to approve permission")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }

    fun rejectPermission(bearerToken: String, id: String, reason: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.rejectPermission(bearerToken, RejectRequest(id, reason))
                if (resp.success) {
                    _event.emit("Permission rejected")
                    load(bearerToken, canApprove, force = true)
                } else {
                    _event.emit(resp.error ?: "Failed to reject permission")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }

    /**
     * Cancel one of the user's own pending permission requests. Hits
     * /api/hr/permissions/cancel with the row id; reloads on success
     * so the cancelled row disappears (or flips to a terminal state,
     * depending on backend semantics).
     */
    fun cancelPermission(bearerToken: String, id: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.cancelPermission(bearerToken, IdRequest(id))
                if (resp.success) {
                    _event.emit("Permission request cancelled")
                    load(bearerToken, canApprove, force = true)
                } else {
                    _event.emit(resp.error ?: "Failed to cancel permission")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }
}
