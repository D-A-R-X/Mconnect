package com.manjugroups.m_connect.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
import com.manjugroups.m_connect.geotrack.GeoTrackBootstrapSync
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CompleteVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MmsFleetDriverTrip
import com.manjugroups.m_connect.network.PunchRequest
import com.manjugroups.m_connect.network.StorageUploader
import com.manjugroups.m_connect.network.TrackingBootstrapData
import com.manjugroups.m_connect.network.StartVisitRequest
import com.manjugroups.m_connect.network.AssignedPlace
import com.manjugroups.m_connect.network.TodayVisit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

    // Last error from /api/mms-fleet/driver/trips, surfaced so the driver
    // mode My Trips screen can explain why the list is empty instead of
    // pretending nothing came back. null when the driver call succeeded
    // (or wasn't applicable for the current session). Cleared on a fresh
    // load attempt.
    private val _driverTripsError = MutableStateFlow<String?>(null)
    val driverTripsError: StateFlow<String?> = _driverTripsError.asStateFlow()

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
                // Today's row is provisional — the day still has hours to
                // run, and once midnight passes it enters the RO Team
                // Approval → HR Review flow before being final. Counting
                // today the moment you punch in inflates the tile by a
                // day that hasn't actually closed yet. The web side
                // already defers today's verdict to the midnight cron;
                // this mirrors that on mobile.
                val daysPresent = myAttendance?.records?.count { r ->
                    if (r.date == today) return@count false
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

                // IMPORTANT: copy from the existing cachedState so the
                // attendance refresh PRESERVES today's visits. Building a
                // fresh Loaded() here reset todayVisits to empty before
                // loadTodayVisitsInternal re-fetched them, which made the
                // home card flash: trips → empty → skeleton → trips. The
                // copy keeps the current trips on screen until the fresh
                // ones land.
                val loaded = (cachedState ?: HomeUiState.Loaded()).copy(
                    hasOpenSession = hasOpen,
                    completedMinutes = completedMin,
                    openSessionStartMillis = openSessionStartMillis,
                    firstPunchInMillis = firstPunchInMillis,
                    totalMinutes = totalMin,
                    sessions = sessions,
                    daysPresent = daysPresent,
                    permissionsLeftHrs = permLeft,
                    isPunching = false,
                )
                cachedState = loaded
                _uiState.value = loaded

                if (context != null) {
                    runCatching {
                        GeoTrackBootstrapSync.sync(context, allowPromptConsent = true, api = geoApi)
                    }
                }

                // Load today's visits
                loadTodayVisitsInternal(bearerToken, context)
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

    private suspend fun loadTodayVisitsInternal(bearerToken: String, context: Context? = null) {
        _isVisitsLoading.value = true
        try {
            val session = context?.let { SessionManager(it) }
            val isDriverMode = session?.isDriverMode == true
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val shouldProbeDriverTrips = isDriverMode ||
                session?.fleetDriverByBackend != true

          coroutineScope {
            // Fire every independent fetch CONCURRENTLY. These used to be
            // awaited one after another — 3+ sequential network round-trips
            // — which is what made the trip list sit on a skeleton for so
            // long. The merging below is purely local, so only the network
            // calls need to overlap; wall-clock now ≈ the slowest single
            // call instead of their sum.
            val placesDeferred = async {
                runCatching { geoApi.getAssignedPlaces(bearerToken) }.getOrNull()
            }
            val visitsDeferred = async {
                runCatching { geoApi.getTodayVisits(bearerToken, todayStr) }.getOrNull()
            }
            // Wrapped in runCatching so a failure resolves to a Result
            // (never throws inside the async) — that keeps a failed CP /
            // driver fetch from cancelling the sibling fetches in this
            // scope. We re-throw via getOrThrow() at the call site so the
            // existing per-section try/catch handles it exactly as before.
            val cpDeferred = if (!isDriverMode) async {
                runCatching {
                    geoApi.getMyMarketingCpVisits(bearerToken, fromDate = null, toDate = null)
                }
            } else null
            val driverDeferred = if (shouldProbeDriverTrips) async {
                runCatching { geoApi.getMmsFleetDriverTrips(bearerToken) }
            } else null

            // Load assigned places
            val places = placesDeferred.await()?.data ?: emptyList()
            Log.d(TAG, "Assigned places: ${places.size}")

            // Load today's scheduled visits from the legacy fieldVisits
            // pipeline (rows that already have a fieldVisits child).
            val legacyVisits = visitsDeferred.await()?.data
                ?.filter { it.status != "cancelled" } ?: emptyList()
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
            if (!isDriverMode) {
                try {
                    val cpResp = cpDeferred!!.await().getOrThrow()
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
            } else {
                Log.d(TAG, "CP merge skipped for driver mode")
            }

            // Speculatively probe driver-trips even when the cached
            // isDriverMode flag is false. The backend's gate is
            // authoritative; if it returns success, this account IS a
            // driver per the backend (a designation-or-fleetDrivers
            // match) and we should both show the trips AND persist the
            // backend-driver flag so subsequent navigation lights up
            // the driver UI immediately. Skips the probe only when we
            // already know the account is a driver — saves the round
            // trip on every refresh for confirmed drivers and avoids
            // it entirely for clearly-non-driver flows after the first
            // miss.
            if (shouldProbeDriverTrips) {
                // Reset before re-attempting so the surfaced banner
                // doesn't go stale on a refresh that now succeeds.
                _driverTripsError.value = null
                try {
                    val driverResp = driverDeferred!!.await().getOrThrow()
                    // Persist the backend's verdict so the rest of the
                    // app (SessionManager.isDriverMode) reflects it on
                    // the very next access, without waiting for a
                    // logout/login cycle.
                    if (session != null) {
                        session.fleetDriverByBackend = driverResp.success
                    }
                    if (driverResp.success) {
                        val existingIds = merged.map { it.id }.toHashSet()
                        val driverTrips = driverResp.trips
                            .mapNotNull { it.toTodayVisitOrNull() }
                            .filter { it.id !in existingIds }
                        Log.d(
                            TAG,
                            "MMS fleet driver trips: server=${driverResp.trips.size} " +
                                "kept=${driverTrips.size}",
                        )
                        if (driverTrips.isNotEmpty()) {
                            merged.addAll(driverTrips)
                        } else if (driverResp.trips.isEmpty()) {
                            // Endpoint authorised the driver but returned
                            // zero rows — most often a driverPhone mismatch
                            // between the assigned siteVisit and the
                            // staff phone we're logged in with.
                            _driverTripsError.value =
                                "No fleet trips found for your phone. " +
                                "Make sure the dispatcher assigned the vehicle " +
                                "to this driver's phone number."
                        }
                    } else {
                        val msg = driverResp.error ?: "Unknown error"
                        Log.w(TAG, "MMS fleet driver trips failed: $msg")
                        _driverTripsError.value =
                            "Driver trips couldn't load: $msg"
                    }
                } catch (e: Exception) {
                    val msg = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "MMS fleet driver trips failed: $msg")
                    _driverTripsError.value = "Driver trips couldn't load: $msg"
                }
            }

            // Push completed trips to the bottom of every category-
            // agnostic list, then sort the rest newest-first so a
            // freshly-fixed SV-cum-CP lands at the top of Today's Trip
            // instead of getting pushed below older legacy fieldVisits
            // rows. The completed-last primary key keeps actionable
            // work (Enroute, Ready, Upcoming) above the day's history
            // on the Home dashboard and the My Trips "All" tab; the
            // explicit "Completed" filter tab still surfaces them
            // because it filters by status, not position. Falls back
            // to scheduled start time and then id for stability when
            // creationTime is missing on either side.
            val completedStatuses = setOf("completed", "complete", "done", "closed")
            val sortedMerged = merged.sortedWith(
                compareBy<TodayVisit> {
                    if (it.status.lowercase(Locale.getDefault()) in completedStatuses) 1 else 0
                }
                    .thenByDescending { it.creationTime ?: 0.0 }
                    .thenByDescending { it.scheduledStartTime ?: "" }
                    .thenBy { it.id }
            )
            // Seed a default Loaded state when nothing has populated the
            // dashboard yet (My Trips opened before Home). Previously this
            // returned early when cachedState was null, so a visits-only
            // load never emitted — the list could never appear without a
            // prior full home load.
            val current = cachedState ?: HomeUiState.Loaded()
            val updated = current.copy(
                todayVisits = sortedMerged,
                assignedPlaces = places,
                activeVisitId = sortedMerged.firstOrNull { it.status == "in-progress" }?.id,
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
          } // end coroutineScope — parallel fetches + local merge
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
        val profileClient = this.lead?.manualProfile?.clientName.asClientNameOrNull()
        val canonicalClient = this.client?.clientName.asClientNameOrNull()
        val typedContact = this.lead?.contactName.asClientNameOrNull()
        val placeLabel = this.clientPlace?.name.asClientNameOrNull()
        val phoneLabel = this.lead?.mobileNumber?.takeIf { it.isNotBlank() }
            ?: this.client?.mobileNumber?.takeIf { it.isNotBlank() }
            ?: this.clientPlace?.contactPhone?.takeIf { it.isNotBlank() }
        val resolvedClientName = profileClient
            ?: canonicalClient
            ?: typedContact
            ?: placeLabel
        val displayName = resolvedClientName
            ?: phoneLabel
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
            leadName = resolvedClientName,
            leadPhone = phoneLabel,
            scheduledStartTime = this.scheduledTime,
            // CpVisitDetail.createdAt is the same monotonic ms value
            // Convex uses for `_creationTime` (the createCpVisitRows
            // mutation seeds it from Date.now() at insert). Forwarding
            // it lets the Home sort treat legacy and CP-merge rows the
            // same way — newest first.
            creationTime = this.createdAt?.toDouble(),
            // Mirror the cpVisit sub-object the legacy fieldVisits-
            // enriched path already populates. Without this the CP-
            // merge rows show up on Home with cpVisit=null, which
            // means the Type cell reads "Direct CP" and the trip flow
            // can't branch into gift_distribution / old_client /
            // collection_cp on arrival — the booking-outcome sheet
            // opens for every CP regardless of type. Carrying these
            // fields aligns the merged shape with what
            // enrichFieldVisitForMobile already returns server-side.
            cpVisit = com.manjugroups.m_connect.network.CpVisitState(
                clientMet = this.clientMet,
                clientMetAt = this.clientMetAt,
                clientNoShowReason = this.clientNoShowReason,
                outcome = this.outcome,
                postponeReasons = this.postponeReasons,
                cpType = this.cpType,
            ),
        )
    }

    private fun String?.asClientNameOrNull(): String? {
        val value = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = value.filterNot { it.isWhitespace() || it == '+' || it == '-' || it == '(' || it == ')' }
        val digitCount = value.count { it.isDigit() }
        val phoneLike = digitCount >= 8 && compact.all { it.isDigit() }
        return value.takeUnless { phoneLike }
    }

    private fun MmsFleetDriverTrip.toTodayVisitOrNull(): TodayVisit? {
        val tripId = this.id ?: return null
        val scheduled = this.scheduledDate ?: return null
        // canOperateToday is a "can the driver hit Start now" flag —
        // backend sets it false when the trip's scheduledDate isn't
        // today (e.g. trip is for tomorrow). It is NOT a visibility
        // flag. Dropping the row here meant a driver assigned to a
        // trip dated for tomorrow saw an empty My Trips screen, even
        // though both the dispatcher and the staff knew the trip
        // existed. We now keep every row; the card's status pill /
        // action button reflects whether the trip is actionable.
        val status = when (this.phase?.lowercase(Locale.getDefault())) {
            "completed" -> "completed"
            "on_site" -> "on_site"
            "in_progress" -> "in-progress"
            else -> "scheduled"
        }
        val title = this.project?.name
            ?: this.vehicle?.vehicleNumber
            ?: "Driver trip"
        return TodayVisit(
            id = tripId,
            clientPlaceId = this.project?.id ?: tripId,
            scheduledDate = scheduled,
            status = status,
            mobileStatus = status,
            placeName = title,
            placeAddress = this.pickupAddress,
            placeType = "project",
            tripType = "site_visit",
            visitCategory = "site_visit",
            scheduledStartTime = this.scheduledTime ?: this.pickupTime,
            travelMode = "cab",
            vehicleAssigned = true,
        )
    }

    /**
     * Loads ONLY today's visits — no attendance / permission / month
     * stats. Used by My Trips, which shows trips and nothing else, so it
     * shouldn't pay for the full home-dashboard load (four sequential
     * attendance/permission calls + a GeoTrack sync) before the trip list
     * can fill in. `context` is required for driver detection + the fleet
     * driver-trips probe.
     */
    fun loadTodayVisits(bearerToken: String, context: Context? = null) {
        viewModelScope.launch { loadTodayVisitsInternal(bearerToken, context) }
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
        // Retries transient failures; punch selfies from field locations
        // regularly hit flaky networks and a single attempt loses the punch.
        return StorageUploader.upload(api, bearerToken, file).storageId
    }

    private fun applyTrackingBootstrap(
        context: Context,
        bootstrap: TrackingBootstrapData?,
        attendanceActive: Boolean,
    ) {
        GeoTrackBootstrapSync.apply(context, bootstrap, allowPromptConsent = attendanceActive)
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
