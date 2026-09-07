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

data class PermissionsState(
    val usedHours: Int = 0,
    val limitHours: Int = 0,
    val count: Int = 0,
    val myPermissions: List<PermissionData> = emptyList(),
    // Team Permission scope — direct-report pending approvals.
    val pendingApprovals: List<PermissionData> = emptyList(),
    // All Permission scope — every request company-wide (admins / viewAll).
    val allApprovals: List<PermissionData> = emptyList(),
    val isApplying: Boolean = false,
    val isLoading: Boolean = false,
)

class PermissionsViewModel : ViewModel() {

    private val api = ApiService.create()
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
    ) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            try {
                // Independent reads fired in PARALLEL — previously serial, so the
                // screen blocked on the SUM of up to five round-trips.
                val usageD = async { runCatching { api.getPermissionUsage(bearerToken) }.getOrNull() }
                val historyD = async {
                    runCatching {
                        api.getMyPermissions(
                            bearerToken,
                            staffId = staffId,
                            fromDate = fromDate,
                            toDate = toDate,
                            status = status,
                            pageSize = 200,
                        )
                    }.getOrNull()
                }
                val pendingD = async {
                    if (canApprove) {
                        runCatching {
                            api.getPendingPermissionApprovals(
                                bearerToken,
                                scope = "direct",
                                fromDate = fromDate,
                                toDate = toDate,
                                status = status,
                                staffId = staffId,
                                pageSize = 200,
                            )
                        }.getOrNull()
                    } else null
                }
                // All-scope dataset (company-wide) — backend gates it to admins
                // / permissions.viewAll and falls back to team scope otherwise.
                val allD = async {
                    if (canApprove) {
                        runCatching {
                            api.getPendingPermissionApprovals(
                                bearerToken,
                                scope = "direct",
                                all = true,
                                fromDate = fromDate,
                                toDate = toDate,
                                status = status,
                                staffId = staffId,
                                pageSize = 200,
                            )
                        }.getOrNull()
                    } else null
                }
                val policyD = async { runCatching { api.getPolicy(bearerToken) }.getOrNull() }
                val usage = usageD.await()
                val history = historyD.await()
                val pending = pendingD.await()
                val all = allD.await()
                val policyResp = policyD.await()

                val limitFromApi = usage?.limitHours ?: policyResp?.policy?.permission?.monthlyLimitHours ?: 0

                _uiState.value = _uiState.value.copy(
                    usedHours = usage?.usedHours ?: usage?.totalHours ?: 0,
                    limitHours = limitFromApi,
                    count = usage?.count ?: 0,
                    myPermissions = history?.permissions ?: emptyList(),
                    pendingApprovals = pending?.permissions ?: emptyList(),
                    allApprovals = all?.permissions ?: emptyList()
                )
            } finally {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun applyPermission(bearerToken: String, date: String, fromTime: String, toTime: String, reason: String) {
        _uiState.value = _uiState.value.copy(isApplying = true)
        viewModelScope.launch {
            try {
                val resp = api.applyPermission(bearerToken, ApplyPermissionRequest(date, fromTime, toTime, reason))
                if (resp.success) {
                    _event.emit("Permission applied successfully!")
                    load(bearerToken, false)
                } else {
                    _event.emit(resp.error ?: "Failed to apply")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
            _uiState.value = _uiState.value.copy(isApplying = false)
        }
    }

    fun approvePermission(bearerToken: String, id: String, canApprove: Boolean) {
        viewModelScope.launch {
            try {
                val resp = api.approvePermission(bearerToken, IdRequest(id))
                if (resp.success) {
                    _event.emit("Permission approved")
                    load(bearerToken, canApprove)
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
                    load(bearerToken, canApprove)
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
                    load(bearerToken, canApprove)
                } else {
                    _event.emit(resp.error ?: "Failed to cancel permission")
                }
            } catch (e: Exception) {
                _event.emit(e.message ?: "Network error")
            }
        }
    }
}
