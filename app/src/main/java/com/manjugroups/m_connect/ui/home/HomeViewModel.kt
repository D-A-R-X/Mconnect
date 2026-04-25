package com.manjugroups.m_connect.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.util.Log
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.GeoTrackConsentActivity
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CompleteVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.PunchRequest
import com.manjugroups.m_connect.network.TrackingBootstrapData
import com.manjugroups.m_connect.network.StartVisitRequest
import com.manjugroups.m_connect.network.AssignedPlace
import com.manjugroups.m_connect.network.TodayVisit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.TimeZone

data class SessionItem(val type: String, val source: String, val time: String)

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Loaded(
        val hasOpenSession: Boolean = false,
        val completedMinutes: Int = 0,
        val openSessionStartMillis: Long = 0L,
        val firstPunchInMillis: Long = 0L,
        val totalMinutes: Int = 0,
        val sessions: List<SessionItem> = emptyList(),
        val daysPresent: Int = 0,
        val permissionsLeftHrs: Int = 0,
        val isPunching: Boolean = false,
        // Trip selection
        val todayVisits: List<TodayVisit> = emptyList(),
        val assignedPlaces: List<AssignedPlace> = emptyList(),
        val showTripSelector: Boolean = false,
        val activeVisitId: String? = null
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

sealed interface PunchEvent {
    data class Success(val message: String) : PunchEvent
    data class Error(val message: String) : PunchEvent
}

class HomeViewModel : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val api = ApiService.create()
    private val geoApi = GeoTrackApi.create()
    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _punchEvent = MutableSharedFlow<PunchEvent>()
    val punchEvent: SharedFlow<PunchEvent> = _punchEvent.asSharedFlow()

    private val _isVisitsLoading = MutableStateFlow(false)
    val isVisitsLoading: StateFlow<Boolean> = _isVisitsLoading.asStateFlow()

    private var cachedState: HomeUiState.Loaded? = null

    fun loadHomeData(bearerToken: String) {
        viewModelScope.launch {
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

                val attendance = try { api.getMyAttendanceToday(bearerToken, today) } catch (_: Exception) { null }

                // Days present this month: fetch attendance from 1st of month to today
                val cal = Calendar.getInstance()
                val monthStart = String.format("%04d-%02d-01", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                val myAttendance = try { api.getMyAttendance(bearerToken, fromDate = monthStart, toDate = today) } catch (_: Exception) { null }
                val daysPresent = myAttendance?.records?.count { r ->
                    r.approvedAttendance == "present" || r.status == "auto-approved" || r.status == "approved"
                } ?: 0

                // Permission hours remaining this month
                val permUsage = try { api.getPermissionUsage(bearerToken) } catch (_: Exception) { null }
                val permLeft = permUsage?.remainingHours ?: 0

                val att = attendance?.attendance
                val sessions = mutableListOf<SessionItem>()
                var hasOpen = false
                var completedMin = 0
                var openSessionStartMillis = 0L
                var firstPunchInMillis = 0L
                var totalMin = 0

                if (att != null) {
                    hasOpen = att.hasOpenSession == true
                    totalMin = att.totalMinutes ?: 0

                    att.sessions?.forEachIndexed { index, s ->
                        val punchInTime = s.punchInTime?.let { parseTime(it, timeFmt) } ?: ""
                        sessions.add(SessionItem("Punch In", "${s.source ?: "Auto"}", punchInTime))

                        // Track the very first punch-in of the day
                        if (index == 0 && s.punchInTime != null) {
                            firstPunchInMillis = parseMillis(s.punchInTime)
                        }

                        if (s.punchOutTime != null) {
                            // Closed session — add to completed minutes
                            completedMin += s.totalMinutes ?: 0
                            sessions.add(SessionItem("Punch Out", "${s.source ?: "Auto"}", parseTime(s.punchOutTime, timeFmt)))
                        } else {
                            // Open session — grab its start time for live counter
                            s.punchInTime?.let { openSessionStartMillis = parseMillis(it) }
                        }
                    }
                }

                val loaded = HomeUiState.Loaded(
                    hasOpenSession = hasOpen,
                    completedMinutes = completedMin,
                    openSessionStartMillis = openSessionStartMillis,
                    firstPunchInMillis = firstPunchInMillis,
                    totalMinutes = totalMin,
                    sessions = sessions,
                    daysPresent = daysPresent,
                    permissionsLeftHrs = permLeft
                )
                cachedState = loaded
                _uiState.value = loaded

                // Load today's visits
                loadTodayVisitsInternal(bearerToken)
            } catch (e: Exception) {
                _uiState.value = HomeUiState.Error(e.message ?: "Failed to load")
            }
        }
    }

    fun punch(context: Context, bearerToken: String, lat: Double?, lng: Double?, photoFile: File?) {
        val current = cachedState ?: return
        val isPunchIn = !current.hasOpenSession

        // Show loading
        _uiState.value = current.copy(isPunching = true)

        viewModelScope.launch {
            try {
                // Step 1: Upload photo if available
                var storageId: String? = null
                if (photoFile != null && photoFile.exists()) {
                    storageId = uploadPhoto(bearerToken, photoFile)
                }

                // Step 2: Call punch API
                val request = PunchRequest(
                    latitude = lat,
                    longitude = lng,
                    photo = storageId,
                    deviceId = SessionManager(context).trackingDeviceId,
                    source = "mobile"
                )

                val response = if (isPunchIn) {
                    api.punchIn(bearerToken, request)
                } else {
                    api.punchOut(bearerToken, request)
                }

                if (response.success) {
                    _punchEvent.emit(PunchEvent.Success(if (isPunchIn) "Punched In!" else "Punched Out!"))
                    applyTrackingBootstrap(context, response.trackingBootstrap)
                    if (isPunchIn) {
                        loadTodayVisits(bearerToken)
                    }

                    // Reload data to refresh state
                    loadHomeData(bearerToken)
                } else {
                    _uiState.value = current.copy(isPunching = false)
                    _punchEvent.emit(PunchEvent.Error(response.error ?: "Punch failed"))
                }
            } catch (e: Exception) {
                _uiState.value = current.copy(isPunching = false)
                _punchEvent.emit(PunchEvent.Error(e.message ?: "Network error"))
            }
        }
    }

    // ── Trip / Visit Selection ──

    private suspend fun loadTodayVisitsInternal(bearerToken: String) {
        _isVisitsLoading.value = true
        try {
            // Load assigned places
            val placesResp = geoApi.getAssignedPlaces(bearerToken)
            val places = placesResp.data ?: emptyList()
            Log.d(TAG, "Assigned places: ${places.size}")

            // Load today's scheduled visits
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val visitsResp = geoApi.getTodayVisits(bearerToken, todayStr)
            val visits = visitsResp.data?.filter { it.status != "cancelled" } ?: emptyList()
            Log.d(TAG, "Today visits: ${visits.size}")

            val current = cachedState ?: return
            val updated = current.copy(
                todayVisits = visits,
                assignedPlaces = places,
                activeVisitId = visits.firstOrNull { it.status == "in-progress" }?.id
            )
            cachedState = updated
            _uiState.value = updated
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load visits/places: ${e.message}", e)
        } finally {
            _isVisitsLoading.value = false
        }
    }

    fun loadTodayVisits(bearerToken: String) {
        viewModelScope.launch { loadTodayVisitsInternal(bearerToken) }
    }

    fun startVisit(context: Context, bearerToken: String, visitId: String, lat: Double?, lng: Double?) {
        viewModelScope.launch {
            try {
                geoApi.startVisit(bearerToken, StartVisitRequest(visitId, lat, lng))
                applyTrackingBootstrap(
                    context,
                    geoApi.getTrackingBootstrap(bearerToken, SessionManager(context).trackingDeviceId).data
                )
                _punchEvent.emit(PunchEvent.Success("Visit started!"))
                val current = cachedState ?: return@launch
                val updated = current.copy(
                    showTripSelector = false,
                    activeVisitId = visitId
                )
                cachedState = updated
                _uiState.value = updated
                // Reload visits to show updated status
                loadTodayVisitsInternal(bearerToken)
            } catch (e: Exception) {
                _punchEvent.emit(PunchEvent.Error("Failed to start visit: ${e.message}"))
                // Reload to reset button states
                loadTodayVisitsInternal(bearerToken)
            }
        }
    }

    fun completeVisit(context: Context, bearerToken: String, visitId: String, lat: Double?, lng: Double?) {
        viewModelScope.launch {
            try {
                geoApi.completeVisit(bearerToken, CompleteVisitRequest(visitId, lat, lng))
                applyTrackingBootstrap(
                    context,
                    geoApi.getTrackingBootstrap(bearerToken, SessionManager(context).trackingDeviceId).data
                )
                _punchEvent.emit(PunchEvent.Success("Visit completed!"))
                val current = cachedState ?: return@launch
                val updated = current.copy(activeVisitId = null)
                cachedState = updated
                _uiState.value = updated
                // Reload visits
                loadTodayVisits(bearerToken)
            } catch (e: Exception) {
                _punchEvent.emit(PunchEvent.Error("Failed to complete visit: ${e.message}"))
            }
        }
    }

    fun startTripToPlace(context: Context, bearerToken: String, placeId: String, placeName: String, lat: Double?, lng: Double?) {
        viewModelScope.launch {
            try {
                // Create a visit for today and immediately start it
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val createResp = geoApi.createVisit(bearerToken, com.manjugroups.m_connect.network.CreateVisitRequest(
                    clientPlaceId = placeId,
                    scheduledDate = todayStr,
                    notes = "Ad-hoc trip started from mobile"
                ))
                if (createResp.success && createResp.visitId != null) {
                    // Start the visit
                    geoApi.startVisit(bearerToken, StartVisitRequest(createResp.visitId, lat, lng))
                    applyTrackingBootstrap(
                        context,
                        geoApi.getTrackingBootstrap(bearerToken, SessionManager(context).trackingDeviceId).data
                    )
                    _punchEvent.emit(PunchEvent.Success("Trip to $placeName started!"))
                    val current = cachedState ?: return@launch
                    val updated = current.copy(activeVisitId = createResp.visitId)
                    cachedState = updated
                    _uiState.value = updated
                    // Reload to get updated visit list
                    loadTodayVisitsInternal(bearerToken)
                } else {
                    _punchEvent.emit(PunchEvent.Error(createResp.error ?: "Failed to create visit"))
                }
            } catch (e: Exception) {
                _punchEvent.emit(PunchEvent.Error("Failed to start trip: ${e.message}"))
            }
        }
    }

    fun dismissTripSelector() {
        val current = cachedState ?: return
        cachedState = current.copy(showTripSelector = false)
        _uiState.value = cachedState!!
    }

    private suspend fun uploadPhoto(bearerToken: String, file: File): String? {
        return try {
            val requestBody = file.asRequestBody("image/jpeg".toMediaType())
            val response = api.uploadStorageFile(bearerToken, requestBody)
            response.storageId
        } catch (_: Exception) { null }
    }

    private fun applyTrackingBootstrap(context: Context, bootstrap: TrackingBootstrapData?) {
        val session = SessionManager(context)
        session.activeTrackingSessionId = bootstrap?.activeSession?.id
        session.shouldTrackNow = bootstrap?.shouldTrack == true
        session.geoTrackingEnabled = bootstrap?.assignment?.attendance != null || bootstrap?.assignment?.siteVisit != null
        session.geoConsentGiven = bootstrap?.consent?.status == "granted"
        session.geoConsentDeclined = bootstrap?.consent?.status == "declined" || bootstrap?.consent?.status == "revoked"

        if (bootstrap?.shouldPromptConsent == true) {
            context.startActivity(Intent(context, GeoTrackConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        if (bootstrap?.shouldTrack == true && !bootstrap.activeSession?.id.isNullOrBlank()) {
            GeoTrackService.start(context)
        } else {
            GeoTrackService.stop(context)
        }
    }

    /** Parse ISO timestamp like "2026-04-08T14:40:10+05:30" to display format "02:40 PM" */
    private fun parseTime(iso: String, outFmt: SimpleDateFormat): String {
        return try {
            outFmt.format(Date(parseMillis(iso)))
        } catch (_: Exception) { iso }
    }

    /** Parse ISO timestamp to epoch millis — handles +05:30 offset correctly */
    private fun parseMillis(iso: String): Long {
        // Try XXX pattern first (handles +05:30)
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                return fmt.parse(iso)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        // Fallback: manually extract offset and compute
        try {
            val offsetRegex = Regex("([+-])(\\d{2}):(\\d{2})$")
            val match = offsetRegex.find(iso)
            if (match != null) {
                val sign = if (match.groupValues[1] == "+") -1 else 1
                val offsetH = match.groupValues[2].toInt()
                val offsetM = match.groupValues[3].toInt()
                val offsetMs = (offsetH * 3600 + offsetM * 60) * 1000L * sign
                val bare = iso.substring(0, match.range.first)
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                val date = fmt.parse(bare) ?: return 0L
                return date.time + offsetMs
            }
            // No offset — treat as IST
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
            return fmt.parse(iso.substringBefore("Z"))?.time ?: 0L
        } catch (_: Exception) { return 0L }
    }
}
