package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.PolicyData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class HrDashboardState(
    val casualLeft: Int = 0,
    val sickLeft: Int = 0,
    val earnedLeft: Int = 0,
    val permissionUsedHours: Int = 0,
    val permissionLimitHours: Int = 0,
    val daysPresent: Int = 0,
    val policy: PolicyData? = null,
    val iamPermissions: Set<String> = emptySet(),
    val isAdmin: Boolean = false,
    val loaded: Boolean = false
)

class HrDashboardViewModel : ViewModel() {

    private val api = ApiService.create()
    private val _uiState = MutableStateFlow(HrDashboardState())
    val uiState: StateFlow<HrDashboardState> = _uiState.asStateFlow()

    fun load(bearerToken: String) {
        viewModelScope.launch {
            val balance = try { api.getLeaveBalance(bearerToken) } catch (_: Exception) { null }
            val permUsage = try { api.getPermissionUsage(bearerToken) } catch (_: Exception) { null }
            val policyResp = try { api.getPolicy(bearerToken) } catch (_: Exception) { null }
            val iamResp = try { api.getMyIamPermissions(bearerToken) } catch (_: Exception) { null }

            // Days present this month from attendance/my
            val cal = Calendar.getInstance()
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
            val monthStart = String.format("%04d-%02d-01", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
            val myAtt = try { api.getMyAttendance(bearerToken, fromDate = monthStart, toDate = today) } catch (_: Exception) { null }
            val daysPresent = myAtt?.records?.count { r ->
                r.approvedAttendance == "present" || r.status == "auto-approved" || r.status == "approved"
            } ?: 0

            val b = balance?.balance
            val policy = policyResp?.policy
            val perms = iamResp?.permissions?.toSet() ?: emptySet()

            // Respect policy — if allocation is 0, don't show balance
            val casualAlloc = policy?.leave?.casualPerYear ?: 0
            val sickAlloc = policy?.leave?.sickPerYear ?: 0
            val earnedAlloc = policy?.leave?.earnedPerYear ?: 0

            _uiState.value = HrDashboardState(
                casualLeft = if (casualAlloc > 0) (b?.casual ?: 0) - (b?.casualUsed ?: 0) else 0,
                sickLeft = if (sickAlloc > 0) (b?.sick ?: 0) - (b?.sickUsed ?: 0) else 0,
                earnedLeft = if (earnedAlloc > 0) (b?.earned ?: 0) - (b?.earnedUsed ?: 0) else 0,
                permissionUsedHours = permUsage?.usedHours ?: permUsage?.totalHours ?: 0,
                permissionLimitHours = permUsage?.limitHours ?: policy?.permission?.monthlyLimitHours ?: 0,
                daysPresent = daysPresent,
                policy = policy,
                iamPermissions = perms,
                isAdmin = iamResp?.isAdmin == true,
                loaded = true
            )
        }
    }
}
