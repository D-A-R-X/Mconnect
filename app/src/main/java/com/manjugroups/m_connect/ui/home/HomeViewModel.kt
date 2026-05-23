package com.manjugroups.m_connect.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.util.Log
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    fun loadHomeData(bearerToken: String, context: Context? = null) {
        viewModelScope.launch {
            try {
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())

                val attendance = try { api.getMyAttendanceToday(bearerToken, today) } catch (_: Exception) { null }
                val daySessions = try { api.getDaySessions(bearerToken, today) } catch (_: Exception) { null }

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
                    val firstPunchIn = daySessions?.firstPunchIn ?: att.firstPunchIn
                    hasOpen = AttendanceTrackingGate.isClockedInForToday(
                        firstPunchIn = firstPunchIn,
                        hasOpenSession = att.hasOpenSession == true || daySessions?.hasOpenSession == true,
                    )
                    totalMin = att.totalMinutes ?: 0
                    firstPunchInMillis = firstPunchIn?.let { parseMillis(it) } ?: 0L

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

                if (context != null) {
                    if (hasOpen) {
                        runCatching {
                            applyTrackingBootstrap(
                                context = context,
                                bootstrap = geoApi.getTrackingBootstrap(
                                    bearerToken,
                                    SessionManager(context).trackingDeviceId,
                                ).data,
                                attendanceActive = true,
                            )
                        }
                    } else {
                        stopTrackingUntilClockIn(context)
                    }
                }

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

                // Step 2: Resolve a human-readable address from the punch
                // coordinates. The backend can geocode too, but sending an
                // on-device address gives the punch record a value even when
                // the server-side geocoder is rate-limited / offline.
                val address = reverseGeocode(context, lat, lng)

                // Step 3: Call punch API
                val request = PunchRequest(
                    latitude = lat,
                    longitude = lng,
                    address = address,
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
                    val bootstrap = response.trackingBootstrap ?: runCatching {
                        geoApi.getTrackingBootstrap(
                            bearerToken,
                            SessionManager(context).trackingDeviceId,
                        ).data
                    }.getOrNull()
                    applyTrackingBootstrap(
                        context = context,
                        bootstrap = bootstrap,
                        attendanceActive = true,
                    )
                    if (isPunchIn) {
                        loadTodayVisits(bearerToken)
                    }

                    // Reload data to refresh state
                    loadHomeData(bearerToken, context)
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

            // Load today's scheduled visits from the legacy fieldVisits
            // pipeline (rows that already have a fieldVisits child).
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val visitsResp = geoApi.getTodayVisits(bearerToken, todayStr)
            val legacyVisits = visitsResp.data?.filter { it.status != "cancelled" } ?: emptyList()
            Log.d(TAG, "Today visits (legacy fieldVisits): ${legacyVisits.size}")

            // Merge: CP visits assigned to me that may not have spawned
            // a fieldVisits row yet. We capture each step's result so the
            // empty-state Toast can tell the user exactly what came back
            // (server returned nothing vs returned N but filter dropped
            // all of them vs threw an exception).
            val merged = mutableListOf<TodayVisit>()
            merged.addAll(legacyVisits)
            var cpFetched: Int = -1            // -1 = never returned; 0+ = real count
            var cpKept: Int = 0
            var cpError: String? = null
            try {
                val cpResp = geoApi.getMyMarketingCpVisits(
                    bearerToken,
                    fromDate = null,
                    toDate = null,
                )
                Log.d(
                    TAG,
                    "CP merge: success=${cpResp.success} total=${cpResp.visits.size} " +
                        "error=${cpResp.error}",
                )
                if (cpResp.success) {
                    cpFetched = cpResp.visits.size
                    val legacyCpIds = legacyVisits.mapNotNull { it.clientPlaceVisitId }.toHashSet()
                    // Keep every CP visit the server returned, dedup'd
                    // against the legacy list. We deliberately do NOT
                    // filter by scheduledDate here: the previous
                    // today-only / today-or-overdue clamps dropped
                    // every visit on the test backend because they
                    // were all dated for future days. The trip card's
                    // own status pill ("Start", "Enroute", "Reaching",
                    // "Complete") tells the user where each visit is
                    // in its lifecycle; the date is just metadata.
                    // Only "cancelled" and "completed" are hard
                    // exclusions — cancelled visits aren't actionable,
                    // and completed ones already lived their day.
                    val extras = cpResp.visits
                        .filter { detail ->
                            val id = detail.id ?: return@filter false
                            if (id in legacyCpIds) return@filter false
                            val status = detail.status?.lowercase(Locale.getDefault())
                            if (status == "cancelled") return@filter false
                            if (status == "completed") return@filter false
                            true
                        }
                        .mapNotNull { detail -> detail.toTodayVisitOrNull() }
                    cpKept = extras.size
                    Log.d(TAG, "Today visits (CP merge): +${extras.size}")
                    merged.addAll(extras)
                } else {
                    cpError = cpResp.error ?: "success=false"
                }
            } catch (e: Exception) {
                cpError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "CP visit merge failed: ${e.message}")
            }

            val current = cachedState ?: return
            val updated = current.copy(
                todayVisits = merged,
                assignedPlaces = places,
                activeVisitId = merged.firstOrNull { it.status == "in-progress" }?.id,
            )
            cachedState = updated
            _uiState.value = updated
            // (Diagnostic Home-empty toast removed — it was firing after
            // legitimate flows like rejecting an SV-via-CP when the list
            // naturally went to zero, looking like a bug to the user.
            // The same counts are still useful for debugging, so we keep
            // a logcat trace instead of pinging the UI.)
            if (merged.isEmpty()) {
                val parts = mutableListOf(
                    "legacy=${legacyVisits.size}",
                    "places=${places.size}",
                    "cpFetched=$cpFetched",
                    "cpKept=$cpKept",
                )
                if (cpError != null) parts += "cpError=$cpError"
                android.util.Log.i(
                    "HomeViewModel",
                    "Home empty: ${parts.joinToString(", ")}",
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load visits/places: ${e.message}", e)
            _punchEvent.emit(
                PunchEvent.Error("Home load error: ${e.message ?: "unknown"}"),
            )
        } finally {
            _isVisitsLoading.value = false
        }
    }

    /**
     * Map a marketing-side CpVisitDetail row onto the legacy TodayVisit
     * shape the home card already knows how to render. Returns null
     * (skips the row) if the CP visit is too sparse to produce a
     * usable card — we need an id, a clientPlaceId proxy, and at
     * minimum a scheduledDate.
     */
    private fun com.manjugroups.m_connect.network.CpVisitDetail.toTodayVisitOrNull(): TodayVisit? {
        val cpId = this.id ?: return null
        val scheduled = this.scheduledDate ?: return null
        // We use the CP visit id as the row id because the server-side
        // resolver added earlier accepts either a fieldVisits id or a
        // clientPlaceVisits id on startVisit / OTP / completeVisit. That
        // keeps this merge minimal — no extra lookups to find the
        // companion fieldVisits id when one exists.
        //
        // Status precedence: the spawned fieldVisits row carries the
        // authoritative trip status ("in-progress" / "arrived" /
        // "completed"), while the CP visit's own status only tracks the
        // CP lifecycle ("scheduled" / "in_progress" / "completed"). If
        // a fieldVisits row exists we prefer its status so the trip
        // nav screen doesn't drop the user back on "Start Trip" after
        // they've already verified arrival.
        val effectiveStatus = this.fieldVisit?.status?.takeIf { it.isNotBlank() }
            ?: this.status?.takeIf { it.isNotBlank() }
            ?: "scheduled"
        // Detect "this CP was an SV-fix routed through CP first" using
        // the same three signals the outcome sheet uses for its locked
        // mode. Any one of these is enough: an explicit proposed SV
        // payload (the web's `same_area` create path writes it), the
        // lead being flagged sv_fixed by an upstream convert step, or
        // party data (expectedAttendeeCount / attendees / food /
        // vehicle) which only the SV-fix create path attaches.
        val proposedHasFields = this.proposedSiteVisit?.let { p ->
            !p.projectId.isNullOrBlank() ||
                !p.scheduledDate.isNullOrBlank() ||
                !p.scheduledTime.isNullOrBlank() ||
                !p.inchargeStaffId.isNullOrBlank() ||
                !p.hodStaffId.isNullOrBlank() ||
                !p.bdoStaffId.isNullOrBlank() ||
                !p.avpStaffId.isNullOrBlank() ||
                !p.gmStaffId.isNullOrBlank() ||
                !p.seniorManagerStaffId.isNullOrBlank()
        } ?: false
        val leadFlaggedSvFixed = this.lead?.followUpStatus
            ?.lowercase(Locale.getDefault())
            ?.let { s -> s == "sv_fixed" || s.contains("sv_fixed") || s.contains("sv-fixed") }
            ?: false
        val hasSvFixParty = (this.expectedAttendeeCount ?: 0) > 0 ||
            (this.attendees?.isNotEmpty() == true) ||
            !this.foodPreferences.isNullOrBlank() ||
            !this.vehiclePreference.isNullOrBlank()
        val category = if (proposedHasFields || leadFlaggedSvFixed || hasSvFixParty) {
            "sv_cum_cp"
        } else {
            "direct_cp"
        }
        // Prefer the canonical client name (manualProfile.clientName on
        // the server, surfaced as `client.clientName`) over the typed-in
        // dialer name (`lead.contactName`). The web Client Profile card
        // already shows the canonical form ("Abhi") — mobile was falling
        // back to the dialer string ("abi") because clientPlace.name was
        // blank for fresh CPs. Same ordering applied to `leadName` so
        // downstream surfaces that fall back to leadName stay aligned.
        val canonicalClient = this.client?.clientName?.takeIf { it.isNotBlank() }
        val typedContact = this.lead?.contactName?.takeIf { it.isNotBlank() }
        val placeLabel = this.clientPlace?.name?.takeIf { it.isNotBlank() }
        val displayName = canonicalClient
            ?: typedContact
            ?: placeLabel
            ?: "CP visit"
        return TodayVisit(
            id = cpId,
            clientPlaceId = this.clientPlaceId ?: cpId,
            scheduledDate = scheduled,
            status = effectiveStatus,
            visitCategory = category,
            placeName = displayName,
            placeAddress = this.clientPlace?.address
                ?: this.clientPlace?.formattedAddress,
            placeLat = this.clientPlace?.lat,
            placeLng = this.clientPlace?.lng,
            tripType = "client_place",
            clientPlaceVisitId = cpId,
            leadName = canonicalClient ?: typedContact,
            leadPhone = this.lead?.mobileNumber ?: this.client?.mobileNumber,
            scheduledStartTime = this.scheduledTime,
        )
    }

    fun loadTodayVisits(bearerToken: String) {
        viewModelScope.launch { loadTodayVisitsInternal(bearerToken) }
    }

    fun startVisit(context: Context, bearerToken: String, visitId: String, lat: Double?, lng: Double?) {
        viewModelScope.launch {
            try {
                val current = cachedState
                if (current?.hasOpenSession != true) {
                    _punchEvent.emit(PunchEvent.Error("Please clock in before starting a trip."))
                    return@launch
                }
                geoApi.startVisit(bearerToken, StartVisitRequest(visitId, lat, lng))
                applyTrackingBootstrap(
                    context = context,
                    bootstrap = geoApi.getTrackingBootstrap(
                        bearerToken,
                        SessionManager(context).trackingDeviceId,
                    ).data,
                    attendanceActive = true,
                )
                _punchEvent.emit(PunchEvent.Success("Visit started!"))
                val latest = cachedState ?: return@launch
                val updated = latest.copy(
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
                    context = context,
                    bootstrap = geoApi.getTrackingBootstrap(
                        bearerToken,
                        SessionManager(context).trackingDeviceId,
                    ).data,
                    attendanceActive = cachedState?.hasOpenSession == true,
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
                if (cachedState?.hasOpenSession != true) {
                    _punchEvent.emit(PunchEvent.Error("Please clock in before starting a trip."))
                    return@launch
                }
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
                        context = context,
                        bootstrap = geoApi.getTrackingBootstrap(
                            bearerToken,
                            SessionManager(context).trackingDeviceId,
                        ).data,
                        attendanceActive = true,
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

    private suspend fun reverseGeocode(context: Context, lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null) return null
        if (!Geocoder.isPresent()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                val results = Geocoder(context, Locale.getDefault())
                    .getFromLocation(lat, lng, 1)
                results?.firstOrNull()?.let { addr ->
                    (0..addr.maxAddressLineIndex)
                        .mapNotNull { addr.getAddressLine(it) }
                        .joinToString(", ")
                        .takeIf { it.isNotBlank() }
                }
            }.getOrNull()
        }
    }

    private suspend fun uploadPhoto(bearerToken: String, file: File): String? {
        return try {
            val requestBody = file.asRequestBody("image/jpeg".toMediaType())
            val response = api.uploadStorageFile(bearerToken, requestBody)
            response.storageId
        } catch (_: Exception) { null }
    }

    private fun applyTrackingBootstrap(
        context: Context,
        bootstrap: TrackingBootstrapData?,
        attendanceActive: Boolean,
    ) {
        val session = SessionManager(context)
        session.activeTrackingSessionId = bootstrap?.activeSession?.id
        session.shouldTrackNow = attendanceActive && bootstrap?.shouldTrack == true
        session.geoTrackingEnabled = bootstrap?.assignment?.attendance != null || bootstrap?.assignment?.siteVisit != null
        session.geoConsentGiven = bootstrap?.consent?.status == "granted"
        session.geoConsentDeclined = bootstrap?.consent?.status == "declined" || bootstrap?.consent?.status == "revoked"

        if (attendanceActive && bootstrap?.shouldPromptConsent == true) {
            context.startActivity(Intent(context, GeoTrackConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return
        }

        if (attendanceActive && bootstrap?.shouldTrack == true && !bootstrap.activeSession?.id.isNullOrBlank()) {
            GeoTrackService.start(context)
        } else {
            GeoTrackService.stop(context)
        }
    }

    private fun stopTrackingUntilClockIn(context: Context) {
        val session = SessionManager(context)
        session.shouldTrackNow = false
        session.activeTrackingSessionId = null
        GeoTrackService.stop(context)
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
