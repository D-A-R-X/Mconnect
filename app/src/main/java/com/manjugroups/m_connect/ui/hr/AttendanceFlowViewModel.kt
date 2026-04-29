package com.manjugroups.m_connect.ui.hr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.PunchRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AttendanceFlowState(
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val isClockedIn: Boolean = false,
    val todayMinutes: Int = 0,
    val todayHours: String = "00:00 Hrs",
    val latestTotalHours: String = "00:00:00 hrs",
    val latestRange: String = "--",
    /** ISO timestamp of the first punch-in today, used to drive a live ticker. */
    val firstPunchInIso: String? = null,
    /** Sum of today's already-closed session minutes. While clocked-in we
     *  still tick `now - firstPunchIn` for live display, but on punch-out
     *  this becomes the source of truth. */
    val closedTodayMinutes: Int = 0,
    val payPeriodLabel: String = "",
    val payPeriodMinutes: Int = 0,
    val payPeriodHours: String = "00:00 Hrs",
)

enum class PunchMode {
    PUNCH_IN,
    PUNCH_OUT,
}

sealed interface AttendanceFlowEvent {
    data class Loading(val mode: PunchMode) : AttendanceFlowEvent
    data class Success(val mode: PunchMode, val message: String) : AttendanceFlowEvent
    data class Error(val mode: PunchMode, val message: String) : AttendanceFlowEvent
}

class AttendanceFlowViewModel(
    private val api: ApiService = ApiService.create(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(AttendanceFlowState())
    val uiState: StateFlow<AttendanceFlowState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<AttendanceFlowEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<AttendanceFlowEvent> = _events

    fun loadTodayAttendance(token: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val todayResp = api.getMyAttendanceToday(token)
                val dayResp = runCatching { api.getDaySessions(token) }.getOrNull()

                val attendance = if (todayResp.success) todayResp.attendance else null
                val totalMinutes = attendance?.totalMinutes ?: 0

                val firstPunchIn = dayResp?.firstPunchIn ?: attendance?.firstPunchIn
                val lastPunchOut = dayResp?.lastPunchOut ?: attendance?.lastPunchOut
                val hasOpenSession = attendance?.hasOpenSession == true ||
                    dayResp?.hasOpenSession == true
                val isClockedInForToday = shouldTreatAsClockedIn(
                    firstPunchIn = firstPunchIn,
                    lastPunchOut = lastPunchOut,
                    hasOpenSession = hasOpenSession,
                )
                val range = buildRangeLabel(firstPunchIn, lastPunchOut, isClockedInForToday)

                val aggregateMinutes = dayResp?.cumulativeMinutes ?: totalMinutes

                // Pay period = current calendar month sum
                val (periodLabel, periodMinutes) = loadCurrentMonthSummary(token)

                _uiState.value = AttendanceFlowState(
                    isLoading = false,
                    isSubmitting = false,
                    isClockedIn = isClockedInForToday,
                    todayMinutes = totalMinutes,
                    todayHours = formatMinutesForToday(totalMinutes),
                    latestTotalHours = formatMinutesForPeriod(aggregateMinutes),
                    latestRange = range,
                    firstPunchInIso = firstPunchIn,
                    closedTodayMinutes = totalMinutes,
                    payPeriodLabel = periodLabel,
                    payPeriodMinutes = periodMinutes,
                    payPeriodHours = formatMinutesForToday(periodMinutes),
                )
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Fetches the current calendar month's daily attendance and returns
     * (humanLabel, summedMinutes). Falls back to today-only on any failure.
     */
    private suspend fun loadCurrentMonthSummary(token: String): Pair<String, Int> {
        val tz = TimeZone.getTimeZone("Asia/Kolkata")
        val cal = java.util.Calendar.getInstance(tz)
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        val from = ymd.format(cal.time)
        val firstDate = cal.time
        cal.set(
            java.util.Calendar.DAY_OF_MONTH,
            cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        )
        val to = ymd.format(cal.time)
        val lastDate = cal.time

        val labelFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).apply {
            timeZone = tz
        }
        val label = "Period ${labelFmt.format(firstDate)} – ${labelFmt.format(lastDate)}"

        val summed = try {
            val resp = api.getMyAttendance(token, fromDate = from, toDate = to)
            resp.records.sumOf { it.totalMinutes ?: 0 }
        } catch (_: Exception) {
            0
        }
        return label to summed
    }

    fun punchIn(
        token: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        selfieFile: File?,
        deviceId: String?,
    ) {
        submitPunch(
            mode = PunchMode.PUNCH_IN,
            token = token,
            latitude = latitude,
            longitude = longitude,
            address = address,
            selfieFile = selfieFile,
            deviceId = deviceId,
        )
    }

    fun punchOut(
        token: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        selfieFile: File?,
        deviceId: String?,
    ) {
        submitPunch(
            mode = PunchMode.PUNCH_OUT,
            token = token,
            latitude = latitude,
            longitude = longitude,
            address = address,
            selfieFile = selfieFile,
            deviceId = deviceId,
        )
    }

    private fun submitPunch(
        mode: PunchMode,
        token: String,
        latitude: Double?,
        longitude: Double?,
        address: String?,
        selfieFile: File?,
        deviceId: String?,
    ) {
        viewModelScope.launch {
            if (selfieFile == null || !selfieFile.exists()) {
                _events.emit(AttendanceFlowEvent.Error(mode, "Selfie photo is required."))
                return@launch
            }
            if (latitude == null || longitude == null) {
                _events.emit(AttendanceFlowEvent.Error(mode, "Valid GPS location is required."))
                return@launch
            }

            _uiState.update { it.copy(isSubmitting = true) }
            _events.emit(AttendanceFlowEvent.Loading(mode))

            try {
                val storageId = uploadSelfie(token, selfieFile)
                if (storageId.isNullOrBlank()) {
                    _uiState.update { it.copy(isSubmitting = false) }
                    _events.emit(
                        AttendanceFlowEvent.Error(
                            mode,
                            "Failed to upload selfie. Please try again.",
                        ),
                    )
                    return@launch
                }

                val request = PunchRequest(
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                    photo = storageId,
                    deviceId = deviceId,
                    source = "mobile",
                )

                val response = if (mode == PunchMode.PUNCH_IN) {
                    api.punchIn(token, request)
                } else {
                    api.punchOut(token, request)
                }

                if (!response.success) {
                    _uiState.update { it.copy(isSubmitting = false) }
                    _events.emit(
                        AttendanceFlowEvent.Error(
                            mode,
                            response.error ?: "Punch request failed.",
                        ),
                    )
                    return@launch
                }

                _events.emit(
                    AttendanceFlowEvent.Success(
                        mode,
                        if (mode == PunchMode.PUNCH_IN) "Punched in successfully." else "Punched out successfully.",
                    ),
                )
                _uiState.update { it.copy(isSubmitting = false) }
                loadTodayAttendance(token)
            } catch (e: Exception) {
                _uiState.update { it.copy(isSubmitting = false) }
                _events.emit(
                    AttendanceFlowEvent.Error(
                        mode,
                        e.message ?: "Network error while submitting punch.",
                    ),
                )
            }
        }
    }

    private suspend fun uploadSelfie(token: String, file: File): String? {
        return try {
            val requestBody = file.asRequestBody("image/jpeg".toMediaType())
            val response = api.uploadStorageFile(token, requestBody)
            response.storageId
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        internal fun formatMinutesForToday(totalMinutes: Int): String {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return String.format(Locale.US, "%02d:%02d Hrs", hours, minutes)
        }

        internal fun formatMinutesForPeriod(totalMinutes: Int): String {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return String.format(Locale.US, "%02d:%02d:00 hrs", hours, minutes)
        }

        internal fun buildRangeLabel(
            firstPunchIn: String?,
            lastPunchOut: String?,
            hasOpenSession: Boolean,
        ): String {
            val inLabel = firstPunchIn?.let { formatIsoToTime(it) } ?: "--"
            val outLabel = when {
                lastPunchOut != null -> formatIsoToTime(lastPunchOut)
                hasOpenSession -> "--"
                else -> "--"
            }
            return "$inLabel - $outLabel"
        }

        internal fun shouldTreatAsClockedIn(
            firstPunchIn: String?,
            lastPunchOut: String?,
            hasOpenSession: Boolean,
        ): Boolean {
            if (hasOpenSession) return true
            return !firstPunchIn.isNullOrBlank() && lastPunchOut.isNullOrBlank()
        }

        private fun formatIsoToTime(iso: String): String {
            val millis = parseMillis(iso) ?: return "--"
            val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
            return formatter.format(Date(millis))
        }

        private fun parseMillis(iso: String): Long? {
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
                try {
                    val fmt = SimpleDateFormat(pattern, Locale.US)
                    return fmt.parse(iso)?.time
                } catch (_: Exception) {
                    // try next format
                }
            }
            return try {
                val fallback = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fallback.timeZone = TimeZone.getTimeZone("UTC")
                fallback.parse(iso.substringBefore("Z"))?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
