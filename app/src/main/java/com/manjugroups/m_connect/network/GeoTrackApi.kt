package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface GeoTrackApi {

    // ── Tracking bootstrap / device ──

    @GET("api/tracking/bootstrap")
    suspend fun getTrackingBootstrap(
        @Header("Authorization") token: String,
        @Query("deviceId") deviceId: String? = null
    ): TrackingBootstrapResponse

    @POST("api/tracking/device/sync")
    suspend fun syncTrackingDevice(
        @Header("Authorization") token: String,
        @Body body: TrackingDeviceSyncRequest
    ): TrackingDeviceSyncResponse

    @POST("api/tracking/consent")
    suspend fun recordTrackingConsent(
        @Header("Authorization") token: String,
        @Body body: ConsentRequest
    ): TrackingConsentResponse

    // ── Location ──

    @POST("api/tracking/location/batch")
    suspend fun pushBatch(
        @Header("Authorization") token: String,
        @Body body: PushBatchRequest
    ): GeoTrackResponse

    // ── Heartbeat ──

    @POST("api/tracking/heartbeat")
    suspend fun heartbeat(
        @Header("Authorization") token: String,
        @Body body: HeartbeatRequest
    ): GeoTrackResponse

    // ── Tamper ──

    @POST("api/geotrack/tamper/report")
    suspend fun reportTamper(
        @Header("Authorization") token: String,
        @Body body: TamperReportRequest
    ): GeoTrackResponse

    // ── Consent ──

    @POST("api/geotrack/consent")
    suspend fun recordConsent(
        @Header("Authorization") token: String,
        @Body body: ConsentRequest
    ): GeoTrackResponse

    @GET("api/geotrack/consent/status")
    suspend fun getConsentStatus(
        @Header("Authorization") token: String
    ): ConsentStatusResponse

    // ── Visits / Trips ──

    @GET("api/geotrack/assigned-places")
    suspend fun getAssignedPlaces(
        @Header("Authorization") token: String
    ): AssignedPlacesResponse

    @GET("api/geotrack/today-visits")
    suspend fun getTodayVisits(
        @Header("Authorization") token: String,
        @Query("date") date: String
    ): TodayVisitsResponse

    @GET("api/sitevisits/my")
    suspend fun getMySiteVisits(
        @Header("Authorization") token: String,
        @Query("fromDate") fromDate: String? = null,
        @Query("toDate") toDate: String? = null
    ): MySiteVisitsResponse

    @GET("api/marketing/projects")
    suspend fun getMarketingProjects(
        @Header("Authorization") token: String
    ): MarketingProjectsResponse

    @GET("api/tracking/places/search")
    suspend fun searchPlaces(
        @Header("Authorization") token: String,
        @Query("q") query: String
    ): PlaceSearchResponse

    @GET("api/geotrack/live-status")
    suspend fun getLiveStatus(
        @Header("Authorization") token: String
    ): LiveStatusResponse

    @POST("api/geotrack/visit/create")
    suspend fun createVisit(
        @Header("Authorization") token: String,
        @Body body: CreateVisitRequest
    ): CreateVisitResponse

    @POST("api/geotrack/visit/start")
    suspend fun startVisit(
        @Header("Authorization") token: String,
        @Body body: StartVisitRequest
    ): GeoTrackResponse

    @POST("api/geotrack/visit/complete")
    suspend fun completeVisit(
        @Header("Authorization") token: String,
        @Body body: CompleteVisitRequest
    ): GeoTrackResponse

    @GET("api/mms-fleet/driver/trips")
    suspend fun getMmsFleetDriverTrips(
        @Header("Authorization") token: String
    ): MmsFleetDriverTripsResponse

    @POST("api/mms-fleet/driver/arrive")
    suspend fun markMmsFleetDriverArrived(
        @Header("Authorization") token: String,
        @Body body: MmsFleetDriverSiteVisitRequest
    ): MmsFleetDriverActionResponse

    @POST("api/mms-fleet/driver/start")
    suspend fun startMmsFleetDriverTrip(
        @Header("Authorization") token: String,
        @Body body: MmsFleetDriverStartRequest
    ): MmsFleetDriverActionResponse

    @POST("api/mms-fleet/driver/on-site")
    suspend fun markMmsFleetDriverOnSite(
        @Header("Authorization") token: String,
        @Body body: MmsFleetDriverSiteVisitRequest
    ): MmsFleetDriverActionResponse

    @POST("api/mms-fleet/driver/picked-from-site")
    suspend fun markMmsFleetDriverPickedFromSite(
        @Header("Authorization") token: String,
        @Body body: MmsFleetDriverSiteVisitRequest
    ): MmsFleetDriverActionResponse

    @POST("api/mms-fleet/driver/end")
    suspend fun endMmsFleetDriverTrip(
        @Header("Authorization") token: String,
        @Body body: MmsFleetDriverEndRequest
    ): MmsFleetDriverActionResponse

    @POST("api/geotrack/route")
    suspend fun getRoute(
        @Header("Authorization") token: String,
        @Body body: RouteRequest
    ): RouteResponse

    @POST("api/geotrack/geocode-address")
    suspend fun geocodeAddress(
        @Header("Authorization") token: String,
        @Body body: GeocodeAddressRequest
    ): GeocodeAddressResponse

    @POST("api/geotrack/visit/arrival-otp/request")
    suspend fun requestArrivalOtp(
        @Header("Authorization") token: String,
        @Body body: ArrivalOtpRequestBody
    ): ArrivalOtpRequestResponse

    @POST("api/geotrack/visit/arrival-otp/verify")
    suspend fun verifyArrivalOtp(
        @Header("Authorization") token: String,
        @Body body: ArrivalOtpVerifyBody
    ): ArrivalOtpVerifyResponse

    @POST("api/geotrack/visit/arrival-otp/cancel")
    suspend fun cancelArrivalOtp(
        @Header("Authorization") token: String,
        @Body body: ArrivalOtpCancelBody
    ): GeoTrackResponse

    // ── KOS-37: marketing CP-visit decisions from mobile ──

    @POST("api/marketing/clientPlaceVisits/markClientMet")
    suspend fun markClientMet(
        @Header("Authorization") token: String,
        @Body body: MarkClientMetRequest
    ): GeoTrackResponse

    @POST("api/marketing/clientPlaceVisits/create")
    suspend fun createCpVisit(
        @Header("Authorization") token: String,
        @Body body: CreateCpVisitRequest
    ): CreateCpVisitResponse

    @POST("api/marketing/clientPlaceVisits/setOutcome")
    suspend fun setCpVisitOutcome(
        @Header("Authorization") token: String,
        @Body body: SetOutcomeRequest
    ): GeoTrackResponse

    // Out-of-geofence CP completion GM approval queue.
    @GET("api/marketing/cp-visits/pending-approvals")
    suspend fun getPendingCpApprovals(
        @Header("Authorization") token: String,
    ): CpApprovalsResponse

    @POST("api/marketing/cp-visits/approve")
    suspend fun approveCpCompletion(
        @Header("Authorization") token: String,
        @Body body: CpApprovalActionRequest,
    ): GeoTrackResponse

    @POST("api/marketing/cp-visits/reject")
    suspend fun rejectCpCompletion(
        @Header("Authorization") token: String,
        @Body body: CpApprovalRejectRequest,
    ): GeoTrackResponse

    // Stashes the staff's out-of-geofence reason on the visit (shown to the GM).
    @POST("api/marketing/cp-visits/geofence-remark")
    suspend fun setCpGeofenceRemark(
        @Header("Authorization") token: String,
        @Body body: CpGeofenceRemarkRequest,
    ): GeoTrackResponse

    @POST("api/marketing/clientPlaceVisits/convertToSiteVisit")
    suspend fun convertCpVisitToSiteVisit(
        @Header("Authorization") token: String,
        @Body body: ConvertCpVisitToSiteVisitRequest
    ): ConvertCpVisitToSiteVisitResponse

    // ── Address service — paste → OpenAI parse, autocomplete, place ──
    // Drives the unified address widget on the CP create form (and
    // every other form once the rollout finishes). Bearer-auth. Mirror
    // the web /api/address/* endpoints.

    @POST("api/address/parse")
    suspend fun parseAddress(
        @Header("Authorization") token: String,
        @Body body: AddressParseRequest,
    ): AddressParseResponse

    @GET("api/address/autocomplete")
    suspend fun autocompleteAddress(
        @Header("Authorization") token: String,
        @Query("q") query: String,
        @Query("sessionToken") sessionToken: String? = null,
    ): AddressAutocompleteResponse

    @GET("api/address/place")
    suspend fun resolveAddressPlace(
        @Header("Authorization") token: String,
        @Query("placeId") placeId: String? = null,
        @Query("lat") lat: Double? = null,
        @Query("lng") lng: Double? = null,
        @Query("sessionToken") sessionToken: String? = null,
    ): AddressPlaceResponse

    // ── Collection CP — post-sales lookup + collection submit ──
    // Drives both the creation-side gate (no completed booking →
    // block selecting "Collection CP") and the in-trip Payment
    // Entry sheet (lists bookings to collect against, then writes a
    // customerCollections row in `pending_accounts` for Accounts).

    @GET("api/postsales/cases/byMobile")
    suspend fun getPostSaleCasesByMobile(
        @Header("Authorization") token: String,
        @Query("mobile") mobile: String,
    ): PostSaleCasesByMobileResponse

    // Powers the Collection Creations sheet's Select Booking
    // dropdown. Returns the same trimmed PostSaleCaseSummary shape so
    // the mobile DTO is reused.
    @GET("api/postsales/cases/list")
    suspend fun listOpenBookings(
        @Header("Authorization") token: String,
    ): PostSaleCasesByMobileResponse

    @POST("api/postsales/collections/submit")
    suspend fun submitCustomerCollection(
        @Header("Authorization") token: String,
        @Body body: SubmitCollectionRequest,
    ): SubmitCollectionResponse

    // Collector self-correction while still pending Accounts. Backend enforces
    // collector-ownership + pending status and stamps collectorEditedAt.
    @POST("api/postsales/collections/correct")
    suspend fun correctCustomerCollection(
        @Header("Authorization") token: String,
        @Body body: CorrectCollectionRequest,
    ): VerifyCollectionResponse

    // ── Collections: Library list + Accounts verification queue ─────
    // Wraps customerCollections.listByStaff / listForAccounts so the
    // two new Library screens (Sales-Executive Collections + Accounts
    // Post-Sales Verification) can populate without going through the
    // Convex JS client. Approve / reject hit updateVerification with
    // the bearer-auth caller stamped as verifiedByStaffId.

    @GET("api/postsales/collections/my")
    suspend fun listMyCustomerCollections(
        @Header("Authorization") token: String,
        @Query("verificationStatus") verificationStatus: String? = null,
    ): CustomerCollectionsListResponse

    @GET("api/postsales/collections/for-accounts")
    suspend fun listCustomerCollectionsForAccounts(
        @Header("Authorization") token: String,
    ): CustomerCollectionsListResponse

    @POST("api/postsales/collections/approve")
    suspend fun approveCustomerCollection(
        @Header("Authorization") token: String,
        @Body body: ApproveCollectionRequest,
    ): VerifyCollectionResponse

    @POST("api/postsales/collections/reject")
    suspend fun rejectCustomerCollection(
        @Header("Authorization") token: String,
        @Body body: RejectCollectionRequest,
    ): VerifyCollectionResponse

    // ── Loan Desk: 3-role workflow ──────────────────────────────────
    // Sales Team submits with 4 documents; Legal Manager assigns the
    // case to a Legal Team staffer (server-side guard ensures only the
    // assignee can act); Legal Team accepts or rejects with required
    // remarks. See convex/postSales.ts for the state machine.

    @GET("api/postsales/loans/forSales")
    suspend fun listLoanDeskForSales(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null,
    ): LoanDeskCasesResponse

    @GET("api/postsales/loans/forLegalManager")
    suspend fun listLoanDeskForLegalManager(
        @Header("Authorization") token: String,
    ): LoanDeskCasesResponse

    @GET("api/postsales/loans/forLegalTeam")
    suspend fun listLoanDeskForLegalTeam(
        @Header("Authorization") token: String,
    ): LoanDeskCasesResponse

    @GET("api/postsales/loans/legalStaff")
    suspend fun listLegalStaffForLoanDesk(
        @Header("Authorization") token: String,
    ): LegalStaffListResponse

    @POST("api/postsales/loans/submit")
    suspend fun submitLoanRequest(
        @Header("Authorization") token: String,
        @Body body: SubmitLoanRequest,
    ): LoanCaseEnvelope

    @POST("api/postsales/loans/assign")
    suspend fun assignLoanToLegalStaff(
        @Header("Authorization") token: String,
        @Body body: AssignLoanRequest,
    ): LoanCaseEnvelope

    @POST("api/postsales/loans/accept")
    suspend fun legalAcceptLoan(
        @Header("Authorization") token: String,
        @Body body: LoanCaseIdBody,
    ): LoanCaseEnvelope

    @POST("api/postsales/loans/reject")
    suspend fun legalRejectLoan(
        @Header("Authorization") token: String,
        @Body body: LegalRejectLoanRequest,
    ): LoanCaseEnvelope

    // ── Site Visits (mobile outcome sheet) ──────────────────────────
    // The CompleteCpVisitBottomSheet drives the same outcome capture
    // for pure-SV visits (not CP-converted). These wrappers mirror
    // the CP setOutcome / convertToSiteVisit shape but write to the
    // new siteVisits table.

    @POST("api/marketing/siteVisits/setOutcome")
    suspend fun setSiteVisitOutcome(
        @Header("Authorization") token: String,
        @Body body: SetSiteVisitOutcomeRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/scanQr")
    suspend fun scanSiteVisitQr(
        @Header("Authorization") token: String,
        @Body body: SiteVisitQrScanRequest,
    ): SiteVisitQrScanResponse

    @POST("api/marketing/siteVisits/markOnCounselling")
    suspend fun markSiteVisitOnCounselling(
        @Header("Authorization") token: String,
        @Body body: SiteVisitIdRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/postpone")
    suspend fun postponeSiteVisit(
        @Header("Authorization") token: String,
        @Body body: PostponeSiteVisitRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/cancel")
    suspend fun cancelSiteVisit(
        @Header("Authorization") token: String,
        @Body body: CancelSiteVisitRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/convertToBooking")
    suspend fun convertSiteVisitToBooking(
        @Header("Authorization") token: String,
        @Body body: ConvertSiteVisitToBookingRequest,
    ): ConvertSiteVisitToBookingResponse

    // ── SV lifecycle transitions ────────────────────────────────────
    // Drive a siteVisits row through the same web state machine the
    // SV Overview stepper visualises. Each takes the SV's _id and
    // patches the row to the next state. assertTransition on the
    // server-side mutation enforces the legal source state — calling
    // markArrivedSite from scheduled (skipping picked_up) will 500
    // with a transition error, so the mobile flow stays linear.
    // Order: markPickedUp (at the CP) -> markArrivedSite ->
    // markPickedFromSite (return pickup) -> markDropped.

    @POST("api/marketing/siteVisits/markPickedUp")
    suspend fun markSiteVisitPickedUp(
        @Header("Authorization") token: String,
        @Body body: SiteVisitIdRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/markClientStarted")
    suspend fun markSiteVisitClientStarted(
        @Header("Authorization") token: String,
        @Body body: SiteVisitIdRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/markArrivedSite")
    suspend fun markSiteVisitArrivedSite(
        @Header("Authorization") token: String,
        @Body body: SiteVisitIdRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/markPickedFromSite")
    suspend fun markSiteVisitPickedFromSite(
        @Header("Authorization") token: String,
        @Body body: SiteVisitIdRequest,
    ): GeoTrackResponse

    @POST("api/marketing/siteVisits/markDropped")
    suspend fun markSiteVisitDropped(
        @Header("Authorization") token: String,
        @Body body: SiteVisitIdRequest,
    ): GeoTrackResponse

    // Returns the enriched CP visit (lead + client + place + fieldVisit +
    // arrivalProof) used by the Completed Visit Detail screen. Mirrors the
    // web's clientPlaceVisits.get() Convex query.
    @GET("api/marketing/clientPlaceVisits/get")
    suspend fun getCpVisitDetail(
        @Header("Authorization") token: String,
        @Query("id") id: String
    ): CpVisitDetailResponse

    // Marketing CP visits assigned to the bearer in a date range.
    // Used by Home's "Today's Trip" merge so visits that exist in
    // `clientPlaceVisits` but have no companion fieldVisits row yet
    // still surface on the home screen.
    @GET("api/marketing/clientPlaceVisits/my")
    suspend fun getMyMarketingCpVisits(
        @Header("Authorization") token: String,
        @Query("fromDate") fromDate: String? = null,
        @Query("toDate") toDate: String? = null,
        // Backend gates scope=all on IAM (marketing.cpVisits.viewAll /
        // .view / projects.viewAll / isAdmin). Non-privileged callers
        // get scoped assignment-only results regardless of what we
        // send, so it's safe to default this to "all" — admins and
        // managers get the full pool, field staff get their own
        // assignments via the fallback path.
        @Query("scope") scope: String = "all",
        // Browsable list screens pass a high limit so client-side search can
        // reach a specific client; Home/today merges leave it null (default).
        @Query("limit") limit: Int? = null,
        // Server-side full-text search — reaches visits beyond the recency cap
        // (e.g. a super-admin searching for an older client).
        @Query("search") search: String? = null,
    ): MyMarketingCpVisitsResponse

    // ── Timeline (self-view) ──

    @GET("api/geotrack/timeline")
    suspend fun getTimeline(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null,
        @Query("dayStart") dayStart: Long,
        @Query("dayEnd") dayEnd: Long
    ): TimelineResponse

    @GET("api/geotrack/session-route")
    suspend fun getSessionRoute(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null,
        @Query("dayStart") dayStart: Long,
        @Query("dayEnd") dayEnd: Long,
        @Query("minStopMinutes") minStopMinutes: Int? = null,
    ): SessionRouteResponse

    @GET("api/geotrack/trips")
    suspend fun getTrips(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null,
        @Query("startDate") startDate: Long,
        @Query("endDate") endDate: Long
    ): TripsResponse

    companion object {
        // Single shared client (see ApiService.create() — same rationale):
        // build once, reuse the connection pool across every trip/visit call
        // instead of rebuilding an OkHttpClient per call site.
        private val instance: GeoTrackApi by lazy { buildService() }

        fun create(): GeoTrackApi = instance

        private fun buildService(): GeoTrackApi {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
            }
            // Same auto-logout-on-401 watchdog as ApiService.create() —
            // GeoTrack endpoints also need it because every trip /
            // visit call goes through this client, and a stale token
            // would silently fail tracking + outcome flows otherwise.
            val authWatchdog = okhttp3.Interceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                // Skip auto-logout when the outgoing request used the dev
                // bypass token (see ApiService.isBypassAuth for context).
                if (response.code == 401 && !isBypassAuth(request)) {
                    com.manjugroups.m_connect.auth.SessionInvalidationBus
                        .reportUnauthorized()
                }
                response
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(authWatchdog)
                .addInterceptor(logging)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BuildConfig.BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GeoTrackApi::class.java)
        }

        private fun isBypassAuth(request: okhttp3.Request): Boolean {
            val header = request.header("Authorization") ?: return false
            val token = header.removePrefix("Bearer ").trim()
            return com.manjugroups.m_connect.auth.AuthBypass.isBypassToken(token)
        }
    }
}

// ── Request Models ──

data class LocationPoint(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val altitude: Double? = null,
    val activity: String,
    val activityConfidence: Int,
    val isMock: Boolean,
    val batteryPct: Int,
    val networkType: String,
    val gpsEnabled: Boolean,
    val airplaneMode: Boolean,
    val recordedAt: Long
)

data class PushBatchRequest(
    val sessionId: String? = null,
    val deviceId: String? = null,
    val points: List<LocationPoint>
)

data class HeartbeatRequest(
    val sessionId: String? = null,
    val deviceId: String? = null,
    val batteryPct: Int,
    val appVersion: String,
    // Client tick time (ms epoch). The server stores this as the heartbeat
    // timestamp so offline-queued heartbeats backfill the web battery/uptime
    // history at their ORIGINAL time instead of the replay time.
    val recordedAt: Long? = null,
    // Device state at this tick. Lets the server attribute a gap ACCURATELY:
    // a missed-heartbeat window whose surrounding (buffered) heartbeats show
    // airplaneMode=true / locationEnabled=false is FLIGHT MODE / LOCATION OFF
    // tampering — not just a no-signal dead zone.
    val airplaneMode: Boolean? = null,
    val locationEnabled: Boolean? = null,
)

data class TamperReportRequest(
    val eventType: String,
    val metadata: Map<String, Any?> = emptyMap(),
    // Original occurrence time (ms epoch) for offline-queued events, so a
    // replayed GPS_DISABLED/REBOOT surfaces in the feed when it HAPPENED.
    val detectedAt: Long? = null,
)

data class ConsentRequest(
    val consented: Boolean = true,
    val appVersion: String,
    val policyKey: String? = null,
    val consentVersionKey: String? = null,
    val status: String? = null,
    val deviceId: String? = null
)

data class TrackingDeviceSyncRequest(
    val deviceId: String,
    val platform: String = "android",
    val appVersion: String,
    val pushToken: String? = null,
    val notificationPermission: Boolean,
    val fineLocationPermission: Boolean,
    val backgroundLocationPermission: Boolean,
    val activityRecognitionPermission: Boolean,
    val batteryOptimizationIgnored: Boolean? = null,
    val manufacturer: String? = null,
    val model: String? = null
)

// ── Response Models ──

data class TrackingBootstrapResponse(
    val success: Boolean,
    val data: TrackingBootstrapData? = null,
    val error: String? = null
)

data class TrackingDeviceSyncResponse(
    val success: Boolean,
    val device: TrackingDevice? = null,
    val bootstrap: TrackingBootstrapData? = null,
    val error: String? = null
)

data class TrackingConsentResponse(
    val success: Boolean,
    val consent: TrackingConsentRecord? = null,
    val bootstrap: TrackingBootstrapData? = null,
    val error: String? = null
)

data class GeoTrackResponse(
    val success: Boolean,
    val error: String? = null,
    val inserted: Int? = null,
    val tamperDetected: Boolean? = null
)

data class ConsentStatusResponse(
    val success: Boolean,
    val data: ConsentData? = null
)

data class ConsentData(
    val staffId: String? = null,
    val consented: Boolean = false,
    val consentedAt: Long? = null,
    val appVersion: String? = null
)

data class TrackingConsentRecord(
    val staffId: String? = null,
    val consentVersionKey: String? = null,
    val policyKey: String? = null,
    val status: String? = null,
    val appVersion: String? = null,
    val actedAt: Long? = null,
    val source: String? = null
)

data class TrackingAssignmentInfo(
    val policyKey: String? = null,
    val scopeType: String? = null
)

data class TrackingAssignments(
    val attendance: TrackingAssignmentInfo? = null,
    val siteVisit: TrackingAssignmentInfo? = null
)

data class TrackingPolicy(
    val key: String? = null,
    val label: String? = null,
    val consentVersionKey: String? = null,
    val requiresConsent: Boolean = false,
    val requiresFineLocation: Boolean = false,
    val requiresBackgroundLocation: Boolean = false,
    val requiresActivityRecognition: Boolean = false,
    val requiresNotificationPermission: Boolean = false,
    val samplingMovingSeconds: Int = 0,
    val samplingStationarySeconds: Int = 0,
    val routeOptimizationEnabled: Boolean = false,
    val routeDeviationThresholdMeters: Int = 0
)

data class TrackingSession(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val staffId: String,
    val policyKey: String? = null,
    val contextType: String? = null,
    val contextId: String? = null,
    val sessionState: String? = null,
    val deviceId: String? = null,
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    val lastHeartbeatAt: Long? = null,
    val lastLocationAt: Long? = null,
    val routeExpectedDistanceMeters: Int? = null,
    val routeActualDistanceMeters: Int? = null,
    val routeVarianceMeters: Int? = null
)

data class TrackingDevice(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val staffId: String,
    val deviceId: String,
    val platform: String? = null,
    val appVersion: String? = null,
    val pushToken: String? = null,
    val notificationPermission: Boolean = false,
    val fineLocationPermission: Boolean = false,
    val backgroundLocationPermission: Boolean = false,
    val activityRecognitionPermission: Boolean = false,
    val status: String? = null,
    val lastSyncedAt: Long? = null
)

data class TrackingBootstrapData(
    val staffId: String,
    val assignment: TrackingAssignments? = null,
    val consentVersion: ConsentVersionInfo? = null,
    val consent: TrackingConsentRecord? = null,
    val activeSession: TrackingSession? = null,
    val currentPolicy: TrackingPolicy? = null,
    val device: TrackingDevice? = null,
    val shouldTrack: Boolean = false,
    val shouldPromptConsent: Boolean = false
)

data class ConsentVersionInfo(
    val key: String,
    val title: String? = null,
    val body: String? = null,
    val locale: String? = null
)

data class PlaceSearchResponse(
    val success: Boolean,
    val data: List<PlaceSuggestion> = emptyList(),
    val error: String? = null
)

data class PlaceSuggestion(
    val id: String,
    val name: String,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null
)

data class TimelineResponse(
    val success: Boolean,
    val data: List<TimelinePoint>? = null
)

data class SessionRouteResponse(
    val success: Boolean,
    val data: SessionRouteData? = null,
    val error: String? = null
)

data class SessionRouteData(
    val session: TrackingSession? = null,
    val timeline: List<TimelinePoint> = emptyList(),
    val trips: List<GeoTrip> = emptyList(),
    val stops: List<GeoTripStop> = emptyList(),
    val routeStart: Long = 0L,
    val routeEnd: Long = 0L,
    val distanceMeters: Int = 0
)

data class LiveStatusResponse(
    val success: Boolean,
    val data: List<GeoLiveStatus>? = null,
    val error: String? = null
)

data class GeoLiveStatus(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val staffId: String,
    val staffName: String? = null,
    val staffPhoto: String? = null,
    val designation: String? = null,
    val department: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val speed: Double = 0.0,
    val bearing: Double = 0.0,
    val activity: String? = null,
    val batteryPct: Int = 0,
    val isOnline: Boolean = false,
    val lastSeen: Long = 0L,
    val hasTamperAlert: Boolean = false,
    val movementMode: String? = null,
    val trackingActive: Boolean = false
)

data class TimelinePoint(
    val lat: Double,
    val lng: Double,
    val speed: Double,
    val activity: String,
    val movementMode: String? = null,
    val recordedAt: Long
)

// ── Visit / Trip Models ──

data class CreateVisitRequest(
    val clientPlaceId: String,
    val scheduledDate: String,
    val notes: String? = null
)

data class CreateVisitResponse(
    val success: Boolean,
    val visitId: String? = null,
    val error: String? = null
)

data class StartVisitRequest(
    val visitId: String,
    val lat: Double? = null,
    val lng: Double? = null
)

data class CompleteVisitRequest(
    val visitId: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val remarks: String? = null,
    val arrivalPhotoStorageId: String? = null,
)

data class RouteRequest(
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double
)

data class RouteResponse(
    val success: Boolean,
    val encodedPolyline: String? = null,
    val distanceMeters: Double? = null,
    val durationSeconds: Double? = null,
    val error: String? = null
)

data class GeocodeAddressRequest(
    val address: String
)

data class GeocodeAddressResponse(
    val success: Boolean,
    val lat: Double? = null,
    val lng: Double? = null,
    val formattedAddress: String? = null,
    val placeId: String? = null,
    val name: String? = null,
    val error: String? = null
)

data class ArrivalOtpRequestBody(
    val visitId: String,
    val lat: Double,
    val lng: Double
)

data class ArrivalOtpRequestResponse(
    val success: Boolean,
    val error: String? = null,
    val contactPhoneMasked: String? = null,
    val otpExpiresInSeconds: Int? = null,
    val resendCooldownSeconds: Int? = null,
    val maxResends: Int? = null,
    val attemptsRemaining: Int? = null,
    val distance: Int? = null,
    val radius: Int? = null
)

data class ArrivalOtpVerifyBody(
    val visitId: String,
    val otp: String,
    val lat: Double? = null,
    val lng: Double? = null,
    // Storage id of the arrival photo we just uploaded. Sending it
    // along with OTP verify links the photo to the fieldVisit row
    // immediately, instead of having to wait for completeVisit to
    // fire at trip-end. The web admin CP visit detail page was
    // showing "No arrival photo yet" for that whole window — this
    // closes the gap.
    val arrivalPhotoStorageId: String? = null,
)

data class ArrivalOtpVerifyResponse(
    val success: Boolean,
    val error: String? = null,
    val attemptsRemaining: Int? = null,
    val arrivalDistanceFromPlaceMeters: Int? = null
)

data class ArrivalOtpCancelBody(val visitId: String)

// KOS-37: marketing CP-visit mutations exposed over HTTP for the mobile client.
data class MarkClientMetRequest(
    val id: String,
    val clientMet: Boolean,
    val clientNoShowReason: String? = null
)

data class CreateCpVisitRequest(
    val leadId: String? = null,
    val clientName: String? = null,
    val mobileNumber: String,
    val assignedStaffId: String,
    // Explicit LMO / Channel Partner / BDO owner (parity with the web CP form).
    // The backend validates eligibility and falls back to the lead owner /
    // creator when omitted.
    val lmoStaffId: String? = null,
    val scheduledDate: String,
    val scheduledTime: String? = null,
    val visitAddress: String,
    val visitLat: Double? = null,
    val visitLng: Double? = null,
    val googleMapsLink: String? = null,
    val notes: String? = null,
    val projectId: String? = null,
    // CP Type — visit intent. One of: sv_cum_cp, follow_up, booking_cp,
    // collection_cp, old_client, gift_distribution. Optional so older
    // builds without the picker still create successfully.
    val cpType: String? = null,
)

data class CreateCpVisitResponse(
    val success: Boolean,
    val id: String? = null,
    val fieldVisitId: String? = null,
    val followupId: String? = null,
    val clientPlaceId: String? = null,
    val error: String? = null,
)

data class SetOutcomeRequest(
    val id: String,
    val outcome: String,
    val postponeReasons: List<String>? = null,
    val notes: String? = null,
    // Gift-distribution handover photo (captured post-OTP). The backend attaches
    // it to the field visit before the completion-proof check so the outcome
    // doesn't fail on a photo that completeVisit links a moment later.
    val arrivalPhotoStorageId: String? = null
)

// ── Out-of-geofence CP completion GM approval ──
data class CpApprovalActionRequest(val id: String)
data class CpApprovalRejectRequest(val id: String, val remark: String)
data class CpGeofenceRemarkRequest(val id: String, val remark: String)

data class CpApprovalsResponse(
    val success: Boolean = false,
    val error: String? = null,
    val items: List<CpApprovalItem> = emptyList(),
)

data class CpApprovalItem(
    val id: String,
    val outcome: String? = null,
    val notes: String? = null,
    val cpType: String? = null,
    val clientName: String? = null,
    val staffName: String? = null,
    val staffId: String? = null,
    val placeName: String? = null,
    val placeAddress: String? = null,
    val distanceMeters: Double? = null,
    val completionLat: Double? = null,
    val completionLng: Double? = null,
    val staffRemark: String? = null,
    val photoUrl: String? = null,
    val requestedAt: Double? = null,
    val scheduledDate: String? = null,
)

data class ConvertCpVisitToSiteVisitRequest(
    val id: String,
    val projectId: String,
    val scheduledDate: String,
    val scheduledTime: String? = null,
    val telecallerId: String? = null,
    val convertedByStaffId: String? = null,
    val assignedTelecallerStaffId: String? = null,
    val inchargeStaffId: String? = null,
    val hodStaffId: String? = null,
    val avpStaffId: String? = null,
    val gmStaffId: String? = null,
    val seniorManagerStaffId: String? = null,
    val expectedAttendeeCount: Int? = null,
    val attendees: List<SiteVisitAttendeeRequest>? = null,
    val pickupAddress: String? = null,
    val pickupLat: Double? = null,
    val pickupLng: Double? = null,
    val pickupGoogleMapsLink: String? = null,
    val pickupTime: String? = null,
    val travelMode: String? = null,
    val vehiclePreference: String? = null,
    val foodPreferences: String? = null,
    val notes: String? = null,
)

data class SiteVisitAttendeeRequest(
    val name: String? = null,
    val relation: String? = null,
    val age: String? = null,
    val isVeg: Boolean? = null
)

data class ConvertCpVisitToSiteVisitResponse(
    val success: Boolean,
    val siteVisitId: String? = null,
    val visitId: String? = null,
    val error: String? = null,
)

// ── Address service ─────────────────────────────────────────────
// Wire-level shape for the 7-field address-parser endpoint, the
// Places-Autocomplete proxy, and the Places-Details / reverse-Geocode
// resolver. See convex/http.ts for the matching server side.

data class AddressParseRequest(val raw: String)

data class AddressParseFields(
    val doorNo: String? = null,
    val street: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
)

data class AddressParseResponse(
    val success: Boolean = false,
    val fields: AddressParseFields? = null,
    val error: String? = null,
)

data class AddressAutocompleteSuggestion(
    val placeId: String,
    val description: String,
    val mainText: String,
    val secondaryText: String? = null,
)

data class AddressAutocompleteResponse(
    val success: Boolean = false,
    val suggestions: List<AddressAutocompleteSuggestion> = emptyList(),
    val error: String? = null,
)

data class AddressPlaceComponents(
    val doorNo: String? = null,
    val street: String? = null,
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
)

data class AddressPlaceResponse(
    val success: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    val formattedAddress: String? = null,
    val googleMapsLink: String? = null,
    val components: AddressPlaceComponents? = null,
    val error: String? = null,
)

// Collection CP — post-sales booking lookup + collection submit.
// Returned by /api/postsales/cases/byMobile. One row per confirmed
// booking the client has. balanceAmount drives the Payment Entry
// sheet's "Balance ₹X" caption on each option, totalAmount/
// approvedCollectedAmount let mobile pick a milestone label
// without re-asking the user.
data class PostSaleCaseSummary(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val bookingId: String? = null,
    val bookingRefNo: String,
    val clientName: String,
    val mobileNumber: String,
    val projectName: String? = null,
    val plotNo: String? = null,
    val totalAmount: Double = 0.0,
    val approvedCollectedAmount: Double = 0.0,
    val pendingCollectedAmount: Double = 0.0,
    val balanceAmount: Double = 0.0,
    val tenPercentAmount: Double = 0.0,
    val currentStage: String? = null,
) : java.io.Serializable

data class PostSaleCasesByMobileResponse(
    val success: Boolean = false,
    val cases: List<PostSaleCaseSummary> = emptyList(),
    val error: String? = null,
)

/** POST /api/postsales/collections/submit — Payment Entry submission
 *  from the in-trip Collection CP flow. `collectedByName` /
 *  `collectedByStaffId` come from the authenticated session on the
 *  server, so we only send the form fields. `proofStorageId` is the
 *  id returned by /api/storage/upload after the user attaches a
 *  proof file. `paymentMode` mirrors the web union (cash/upi/neft/
 *  rtgs/cheque/dd/bank). */
data class SubmitCollectionRequest(
    val cpVisitId: String? = null,
    val caseId: String,
    val amount: Double,
    val paymentMode: String,
    val transactionReference: String? = null,
    val bankName: String? = null,
    val branchName: String? = null,
    val paymentInstrumentDate: String? = null,
    val proofStorageId: String? = null,
    val proofFileName: String? = null,
    val notes: String? = null,
)

data class SubmitCollectionResponse(
    val success: Boolean = false,
    val collectionId: String? = null,
    val collectionRefNo: String? = null,
    val alreadySubmitted: Boolean = false,
    val error: String? = null,
)

/**
 * One customerCollections row enriched with the case/booking caption
 * strings the two Library screens need. The "my" feed and the
 * "for-accounts" feed both return this shape — only `receipt` is
 * accounts-only and stays null on the executive's list.
 *
 * `customerPaymentCategory` is what the executive's UI uses to bucket
 * a row as SELF_FINANCE (`cash_in_hand`) vs BANK_LOAN (`loan`). It
 * comes from the booking's payment category, not from any field on
 * the collection itself, so it can be null if the underlying case is
 * deleted.
 */
data class CustomerCollectionRow(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val collectionRefNo: String,
    val caseId: String,
    val bookingId: String,
    val amount: Double = 0.0,
    val collectionDate: String? = null,
    val paymentMode: String,
    val transactionReference: String? = null,
    val bankName: String? = null,
    val notes: String? = null,
    val collectedByName: String? = null,
    val collectedByStaffId: String? = null,
    val proofStorageId: String? = null,
    val proofFileName: String? = null,
    val verificationStatus: String,
    val verificationNotes: String? = null,
    val verifiedByName: String? = null,
    val verifiedAt: String? = null,
    val collectorEditedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val customerName: String? = null,
    val bookingRefNo: String? = null,
    val projectName: String? = null,
    val plotNo: String? = null,
    val customerPaymentCategory: String? = null,
)

data class CustomerCollectionsListResponse(
    val success: Boolean = false,
    val collections: List<CustomerCollectionRow> = emptyList(),
    val error: String? = null,
)

/** Accountant taps Approve. `notes` is optional remarks attached to
 *  the approval — most flows leave it empty. */
data class ApproveCollectionRequest(
    val collectionId: String,
    val notes: String? = null,
)

/** Accountant taps Reject. `remarks` is required — server returns 400
 *  if missing. Drives the rejected-row's "Rectify" prefill on the
 *  executive's side. */
data class RejectCollectionRequest(
    val collectionId: String,
    val remarks: String,
)

data class VerifyCollectionResponse(
    val success: Boolean = false,
    val collection: CustomerCollectionRow? = null,
    val error: String? = null,
)

/** Collector corrects their own still-pending collection. All fields but
 *  `collectionId` are optional — only the changed ones are sent. */
data class CorrectCollectionRequest(
    val collectionId: String,
    val amount: Double? = null,
    val collectionDate: String? = null,
    val paymentMode: String? = null,
    val transactionReference: String? = null,
    val notes: String? = null,
)

// ── Loan Desk DTOs ──────────────────────────────────────────────────
//
// Mirrors `enrichLoanCaseForMobile` on the server: the same row shape
// is returned by all three list endpoints (Sales / Legal Manager /
// Legal Team). The mobile UI only renders a subset of these fields,
// but keeping the full payload lets later screens (case detail,
// audit log) reuse the same DTO without a second fetch.

data class LoanCaseDocument(
    val label: String = "",
    val storageId: String? = null,
    val fileName: String? = null,
    val approved: Boolean? = null,
)

data class LoanCaseRow(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val caseId: String,
    val bookingId: String,
    val name: String = "",
    val phone: String = "",
    val amount: Double = 0.0,
    val location: String = "",
    val date: String? = null,
    val statusLabel: String = "",
    val status: String = "",
    val applicantType: String? = null,
    val requestedAmount: Double? = null,
    val sanctionedAmount: Double? = null,
    val documentsChecklist: List<LoanCaseDocument> = emptyList(),
    val documentLabels: List<String> = emptyList(),
    val documentCount: Int = 0,
    val legalAssignedStaffId: String? = null,
    val legalAssignedName: String? = null,
    val legalAssignedAt: String? = null,
    val legalRejectionRemarks: String? = null,
    val legalClearedAt: String? = null,
    val legalClearedByName: String? = null,
    val submittedByStaffId: String? = null,
    val submittedByName: String? = null,
    val bookingRefNo: String? = null,
    val projectName: String? = null,
    val plotNo: String? = null,
)

data class LoanDeskCasesResponse(
    val success: Boolean = false,
    val cases: List<LoanCaseRow> = emptyList(),
    val error: String? = null,
)

data class LegalStaffRow(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val name: String = "",
    val designation: String? = null,
    val department: String? = null,
    val employeeId: String? = null,
)

data class LegalStaffListResponse(
    val success: Boolean = false,
    val staff: List<LegalStaffRow> = emptyList(),
    val error: String? = null,
)

/** One document slot — paired storageId + fileName. The server fills
 *  the checklist labels from the loan applicant type, matching on
 *  label name (case-insensitive) so the mobile picker only has to
 *  send the four files it actually has. */
data class SubmitLoanDocument(
    val label: String,
    val storageId: String,
    val fileName: String? = null,
)

data class SubmitLoanRequest(
    val caseId: String,
    val applicantType: String, // business | salaried | pension
    val requestedAmount: Double? = null,
    val documents: List<SubmitLoanDocument>,
)

data class AssignLoanRequest(
    val loanCaseId: String,
    val legalStaffId: String,
    val legalStaffName: String,
)

data class LoanCaseIdBody(val loanCaseId: String)

data class LegalRejectLoanRequest(
    val loanCaseId: String,
    val remarks: String,
)

data class LoanCaseEnvelope(
    val success: Boolean = false,
    val loanCase: LoanCaseRow? = null,
    val error: String? = null,
)

// ── SV outcome (mobile) ───────────────────────────────────────────
// Used when the field staff records an outcome on a pure SV from
// the CompleteCpVisitBottomSheet. Mirrors the CP SetOutcomeRequest
// but accepts the richer not_interested fields the SV backend
// allows. `id` is a siteVisits._id.
data class SetSiteVisitOutcomeRequest(
    val id: String,
    /** interested | not_interested | follow_up | converted_to_booking | other */
    val outcome: String,
    val postponeReasons: List<String>? = null,
    val notInterestedReasons: List<String>? = null,
    val notInterestedDetails: List<SvNotInterestedDetail>? = null,
    val notes: String? = null,
    /** For outcome=follow_up: when the assigned telecaller should call back. */
    val followupDueDate: String? = null,
    val followupDueTime: String? = null,
)

data class SiteVisitQrScanRequest(val qrData: String)

data class SiteVisitQrScanResponse(
    val success: Boolean = false,
    val visit: ScannedSiteVisit? = null,
    val error: String? = null,
)

data class ScannedSiteVisit(
    val _id: String,
    val status: String? = null,
    val outcome: String? = null,
    val notes: String? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val canStartCounselling: Boolean = false,
    // The assigned BDO / Site Incharge / admin can open the outcome page while
    // counselling is already in progress (on_counselling).
    val canRecordOutcome: Boolean = false,
    val expectedAttendeeCount: Int? = null,
    // Nullable: Gson writes null (not the default) when the backend sends
    // "attendees": null, and iterating that null crashed the QR scan flow.
    val attendees: List<ScannedSiteVisitAttendee>? = null,
    val foodPreferences: String? = null,
    val project: ScannedSiteVisitProject? = null,
    val lead: ScannedSiteVisitLead? = null,
    val client: ScannedSiteVisitClient? = null,
    val bdoStaff: ScannedSiteVisitStaff? = null,
    val telecallerStaff: ScannedSiteVisitStaff? = null,
    val inchargeStaff: ScannedSiteVisitStaff? = null,
)

data class ScannedSiteVisitProject(
    val name: String? = null,
    val projectName: String? = null,
)

data class ScannedSiteVisitLead(
    val clientName: String? = null,
    val contactName: String? = null,
    val mobileNumber: String? = null,
    val mobileNumberNormalized: String? = null,
    val profession: String? = null,
    val temperature: String? = null,
)

data class ScannedSiteVisitClient(
    val clientName: String? = null,
    val mobileNumber: String? = null,
    val mobileNumberNormalized: String? = null,
    val profession: String? = null,
)

data class ScannedSiteVisitStaff(
    // Backend staffSummary sends the staff `_id`; the app uses it to authorise
    // the assigned Site Incharge / BDO for start-counselling + outcome even
    // before the broadened backend flags are deployed.
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val name: String? = null,
)

data class ScannedSiteVisitAttendee(
    val name: String? = null,
    val relation: String? = null,
    val age: String? = null,
    val isVeg: Boolean? = null,
    val notes: String? = null,
)

data class PostponeSiteVisitRequest(
    val id: String,
    val scheduledDate: String,
    val scheduledTime: String? = null,
    val reason: String? = null,
)

data class CancelSiteVisitRequest(
    val id: String,
    val reason: String? = null,
)

data class SvNotInterestedDetail(
    val reason: String,
    val detail: String? = null,
)

/**
 * Booking outcome path — drops a booking row keyed off the SV's
 * project. The mobile sheet collects clientName, mobileNumber, plot,
 * bookingDate, plus optional pricing fields. Backend approval
 * workflow (pending_gm → CRM → VP) kicks in once the row lands.
 */
data class ConvertSiteVisitToBookingRequest(
    val id: String,
    val plotId: String,
    val clientName: String,
    val mobileNumber: String,
    val bookingDate: String,
    val bookingType: String? = null,
    val propertyType: String? = null,
    val bookingMode: String? = null,
    val bookingCost: Double? = null,
    val advanceAmount: Double? = null,
    val paymentMode: String? = null,
    val notes: String? = null,
    val originalTelecallerStaffId: String? = null,
)

data class ConvertSiteVisitToBookingResponse(
    val success: Boolean,
    val bookingId: String? = null,
    val siteVisitId: String? = null,
    val error: String? = null,
)

/** Single-id payload shared by every SV lifecycle transition route. */
data class SiteVisitIdRequest(val id: String)

// ── Enriched CP visit detail (mirrors web clientPlaceVisits.get) ──────────
// Every field is optional because (a) older rows pre-date some columns and
// (b) the backend returns the doc as-is. Defensive nullability prevents
// Gson from blowing up when a key is missing.

data class CpVisitDetailResponse(
    val success: Boolean,
    val visit: CpVisitDetail? = null,
    val error: String? = null,
)

// Marketing CP visits list response — used by Home today's trip merge.
// Each visit is the enriched clientPlaceVisits row (same shape as
// `CpVisitDetail` minus arrival proof we don't need for the home card).
data class MyMarketingCpVisitsResponse(
    val success: Boolean,
    val total: Int? = null,
    val visits: List<CpVisitDetail> = emptyList(),
    val error: String? = null,
)

data class CpVisitDetail(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val leadId: String? = null,
    val clientId: String? = null,
    val clientPlaceId: String? = null,
    val origin: String? = null,
    val telecallerStaffId: String? = null,
    val assignedStaffId: String? = null,
    val assignedAt: Long? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val status: String? = null,
    val clientMet: Boolean? = null,
    val clientMetAt: Long? = null,
    val clientNoShowReason: String? = null,
    val outcome: String? = null,
    val postponeReasons: List<String>? = null,
    // CP type — set at creation, drives the Gift Distribution /
    // Old Client / Collection CP post-arrival branches on mobile.
    // Server-side this lives on the clientPlaceVisits row and is
    // returned by /api/marketing/clientPlaceVisits/my; Gson silently
    // drops it without this field, leaving every merged CP row
    // looking like a generic Direct CP downstream.
    val cpType: String? = null,
    val convertedSiteVisitId: String? = null,
    val convertedBookingId: String? = null,
    val fieldVisitId: String? = null,
    val notes: String? = null,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    // Out-of-geofence completion approval (top-level in the mobile compact
    // list shape — this is what /clientPlaceVisits/my deserializes into).
    val approvalGmName: String? = null,
    val rejectRemark: String? = null,
    val reassignedFromRejection: Boolean? = null,
    // Client-not-met auto-reschedule (see TodayVisit for semantics).
    val rescheduleCount: Int? = null,
    val lastNotMetDate: String? = null,
    val clientUnavailableWarning: Boolean? = null,
    val expectedAttendeeCount: Int? = null,
    val foodPreferences: String? = null,
    val vehiclePreference: String? = null,
    // Pure-SV detail envelopes expose the assigned driver on the root visit,
    // while CP-linked SVs keep the same values inside proposedSiteVisit.
    val driverName: String? = null,
    val driverPhone: String? = null,
    val isBookingCompleted: Boolean? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    // SV-via-CP path: when the telecaller pre-fixed an SV via the
    // dialer's "same area" routing, the CP visit carries the full SV
    // payload here. Mobile uses this to lock the outcome sheet to the
    // Site Visit tab and pre-fill the form with the telecaller's plan.
    val proposedSiteVisit: ProposedSiteVisit? = null,
    val attendees: List<CpVisitAttendee>? = null,
    // CP-level projectId picked when the CP was created (see the
    // mobile / web CP create form's Project field). Used by the SV
    // outcome form to pre-fill the project picker when the CP was
    // manually created (no proposedSiteVisit) instead of forcing
    // the field staff to re-pick the same project.
    val projectId: String? = null,
    // Joined references the web `enrichVisit` helper attaches:
    val lead: CpVisitLead? = null,
    val client: CpVisitClient? = null,
    val telecaller: CpVisitStaff? = null,
    val assignedStaff: CpVisitStaff? = null,
    val clientPlace: CpVisitPlace? = null,
    val fieldVisit: CpVisitFieldVisit? = null,
    val arrivalProof: CpVisitArrivalProof? = null,
    /** Resolved project row from `enrichVisit` — used to label the SV picker. */
    val project: CpVisitProject? = null,
    /**
     * Pre-resolved site-incharge staff row. Only the pure-SV detail
     * path populates this (`getForMobileId` synthesizes it when there
     * is no linked CP). For CP-converted SVs the incharge still has to
     * be looked up via `proposedSiteVisit.inchargeStaffId` against the
     * staff list endpoint.
     */
    val inchargeStaff: CpVisitStaff? = null,
    // Fleet "completed offline" signal — mirrors the TodayVisit field
    // so the SV overview can unlock outcome buttons for staff who
    // didn't start a live trip.
    val completedOffline: Boolean? = null,
)

data class CpVisitProject(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val name: String? = null,
)

/**
 * Telecaller's pre-fixed SV details snapshot. When non-null on a
 * CP visit, the mobile bottom sheet locks to Site Visit mode and
 * surfaces these fields as read-only with Reject / Confirm buttons.
 */
data class ProposedSiteVisit(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val projectId: String? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val inchargeStaffId: String? = null,
    val hodStaffId: String? = null,
    val bdoStaffId: String? = null,
    val avpStaffId: String? = null,
    val gmStaffId: String? = null,
    val seniorManagerStaffId: String? = null,
    // SV status + the surrounding signals the stepper needs to mirror
    // the web's progress logic. Without these, mobile only reads
    // `status` and misses the "vehicle assigned" auto-advance + the
    // driver-side timestamp boosts (travelDeskStartedAt etc.).
    val status: String? = null,
    // The SV's OWN terminal fields. For a CP-linked SV the top-level envelope
    // carries the linked CP's identity (including the CP's outcome), so the SV
    // outcome must be read from here — otherwise a converted CP's outcome falsely
    // locks the SV's outcome buttons.
    val outcome: String? = null,
    val convertedBookingId: String? = null,
    val cancelledAt: Long? = null,
    val travelMode: String? = null,
    val vehicleId: String? = null,
    val travelAgencyId: String? = null,
    // Cab driver contact (for the "Call driver" button on the SV overview).
    val driverName: String? = null,
    val driverPhone: String? = null,
    val pickedUpAt: Long? = null,
    val arrivedSiteAt: Long? = null,
    val consultingAt: Long? = null,
    val consultingVerifiedByStaffId: String? = null,
    val pickedFromSiteAt: Long? = null,
    val droppedAt: Long? = null,
    val completedAt: Long? = null,
    val travelDeskArrivedAt: Long? = null,
    val travelDeskStartedAt: Long? = null,
    val travelDeskOnSiteAt: Long? = null,
    val travelDeskPickedFromSiteAt: Long? = null,
    val travelDeskEndedAt: Long? = null,
)

data class MmsFleetDriverTripsResponse(
    val success: Boolean = false,
    val trips: List<MmsFleetDriverTrip> = emptyList(),
    // Sent only when trips is empty — says which filter dropped the rows.
    // Null against a backend that predates it.
    val diagnostics: MmsFleetDriverTripsDiagnostics? = null,
    val error: String? = null,
)

/** Counts explaining an empty driver trip list. See diagnoseForStaff. */
data class MmsFleetDriverTripsDiagnostics(
    val searchedPhone: String? = null,
    val notDriver: Boolean? = null,
    val reason: String? = null,
    val indexedRows: Int? = null,
    val scannedRows: Int? = null,
    val phoneMatched: Int? = null,
    val droppedCancelled: Int? = null,
    val droppedNoVehicle: Int? = null,
    val droppedExternalAgency: Int? = null,
    val kept: Int? = null,
) {
    /** One-line summary for the empty state. */
    fun summary(): String = when {
        notDriver == true || reason == "staff_not_driver" ->
            "This staff account is not configured as a fleet driver."
        (phoneMatched ?: 0) == 0 ->
            "No trip is assigned to $searchedPhone (checked ${scannedRows ?: 0} visits)."
        (droppedExternalAgency ?: 0) > 0 ->
            "${droppedExternalAgency} trip(s) matched your number but their travel " +
                "agency is marked External, so they belong to the Travel Desk, not the fleet."
        (droppedCancelled ?: 0) > 0 ->
            "${droppedCancelled} matching trip(s) are cancelled."
        (droppedNoVehicle ?: 0) > 0 ->
            "${droppedNoVehicle} matching trip(s) have no vehicle assigned yet."
        else -> "Matched ${phoneMatched} trip(s) but none are active fleet trips."
    }
}

data class MmsFleetDriverActionResponse(
    val success: Boolean = false,
    val trip: MmsFleetDriverTrip? = null,
    val error: String? = null,
)

data class MmsFleetDriverSiteVisitRequest(
    val siteVisitId: String,
)

data class MmsFleetDriverStartRequest(
    val siteVisitId: String,
    val photoIds: List<String>,
    val startKm: Double? = null,
)

data class MmsFleetDriverEndRequest(
    val siteVisitId: String,
    val photoIds: List<String>,
    val endKm: Double? = null,
)

data class MmsFleetDriverTrip(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val pickupAddress: String? = null,
    val pickupLat: Double? = null,
    val pickupLng: Double? = null,
    val pickupGoogleMapsLink: String? = null,
    val pickupTime: String? = null,
    val driverName: String? = null,
    val driverPhone: String? = null,
    val travelDeskArrivedAt: Long? = null,
    val travelDeskStartedAt: Long? = null,
    val travelDeskOnSiteAt: Long? = null,
    val travelDeskPickedFromSiteAt: Long? = null,
    val travelDeskEndedAt: Long? = null,
    val travelDeskStartKm: Double? = null,
    val travelDeskEndKm: Double? = null,
    val travelDeskStartPhotoIds: List<String> = emptyList(),
    val travelDeskEndPhotoIds: List<String> = emptyList(),
    val phase: String? = null,
    val canOperateToday: Boolean? = null,
    val km: Double? = null,
    val project: MmsFleetDriverProject? = null,
    val vehicle: MmsFleetDriverVehicle? = null,
)

data class MmsFleetDriverProject(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val name: String? = null,
)

data class MmsFleetDriverVehicle(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val vehicleNumber: String? = null,
    val type: String? = null,
)

data class CpVisitAttendee(
    val name: String? = null,
    val relation: String? = null,
    val age: String? = null,
    val isVeg: Boolean? = null,
    val notes: String? = null,
)

data class CpVisitLead(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val contactName: String? = null,
    val mobileNumber: String? = null,
    val city: String? = null,
    val preferredArea: String? = null,
    val followUpStatus: String? = null,
    val temperature: String? = null,
    val manualProfile: CpVisitLeadManualProfile? = null,
)

data class CpVisitLeadManualProfile(
    val clientName: String? = null,
)

data class CpVisitClient(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val clientName: String? = null,
    val mobileNumber: String? = null,
    val city: String? = null,
)

data class CpVisitStaff(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    // The Convex `staff` row stores the display label as `name`. The
    // enrichVisit helper returns the raw row, so the wire field really
    // is `name` — not `staffName`. Previously we only declared
    // `staffName`, which meant assignedStaff/telecaller/incharge reads
    // were silently null (mobile BDO showed "AKASH.B" forever because
    // the real value was hiding behind the wrong field name).
    val name: String? = null,
    val staffName: String? = null,
    val staffCode: String? = null,
)

data class CpVisitPlace(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val name: String? = null,
    val address: String? = null,
    val formattedAddress: String? = null,
    val landmark: String? = null,
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val googleMapsLink: String? = null,
    val contactPerson: String? = null,
    val contactPhone: String? = null,
)

data class CpVisitFieldVisit(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val status: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val distanceMeters: Double? = null,
    val durationMinutes: Double? = null,
)

data class CpVisitArrivalProof(
    val photoStorageId: String? = null,
    val photoUrl: String? = null,
    val otpVerifiedAt: Long? = null,
    val otpRequestedAt: Long? = null,
    val gpsLat: Double? = null,
    val gpsLng: Double? = null,
    val distanceFromPlaceMeters: Double? = null,
)

data class AssignedPlace(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val name: String,
    val address: String? = null,
    val type: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val contactPerson: String? = null,
    val contactPhone: String? = null
)

data class AssignedPlacesResponse(
    val success: Boolean,
    val data: List<AssignedPlace>? = null
)

data class TodayVisit(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val clientPlaceId: String,
    val scheduledDate: String,
    val status: String,
    val mobileStatus: String? = null,
    val reachingRadiusMeters: Int? = null,
    val placeName: String? = null,
    val placeAddress: String? = null,
    val placeType: String? = null,
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    // KOS-37: surfaced for CP-visit aware UI on home today-card and Complete-Visit screen.
    val tripType: String? = null,
    val clientPlaceVisitId: String? = null,
    val leadName: String? = null,
    val leadPhone: String? = null,
    val cpVisit: CpVisitState? = null,
    // Out-of-geofence completion approval (top-level in the compact list shape).
    // approvalGmName is the GM the staff is waiting on; rejectRemark +
    // reassignedFromRejection surface a bounced-back completion.
    val approvalGmName: String? = null,
    val rejectRemark: String? = null,
    val reassignedFromRejection: Boolean? = null,
    // Client-not-met auto-reschedule: how many times this visit has bounced on
    // "client not met", the last not-met date, and — once the client has been
    // not-met 3+ times in a row — a warning flag for the card.
    val rescheduleCount: Int? = null,
    val lastNotMetDate: String? = null,
    val clientUnavailableWarning: Boolean? = null,
    @com.google.gson.annotations.SerializedName(
        value = "scheduledStartTime",
        alternate = ["scheduledStart", "startTime", "meetingStartTime", "scheduledFrom", "fromTime", "startAt", "scheduledTime", "time", "visitTime", "timeFrom", "appointmentTime"]
    )
    val scheduledStartTime: String? = null,
    @com.google.gson.annotations.SerializedName(
        value = "scheduledEndTime",
        alternate = ["scheduledEnd", "endTime", "meetingEndTime", "scheduledTo", "toTime", "endAt", "timeTo", "visitEndTime"]
    )
    val scheduledEndTime: String? = null,
    // Mobile-only annotation set by the Home merge once we know whether
    // the underlying CP visit carries an SV-fix payload. Server never
    // sends this key so Gson leaves it as the default null on legacy
    // /today-visits rows; the Home merge copies the data class with a
    // non-null value for CP-merge rows. Values:
    //   "sv_cum_cp"  → telecaller-fixed SV verified through a CP
    //   "direct_cp"  → regular CP visit (no SV-fix payload)
    //   "site_visit" → direct site visit (no CP intermediary)
    //   null         → unknown
    val visitCategory: String? = null,
    // Travel-mode metadata for the SV list pill. Populated by the new
    // siteVisits query (`marketing.siteVisits.listForViewerAsMobileVisits`)
    // so the Android list can show "Own Vehicle" for SVs that the
    // office fixed on the client's own vehicle instead of falling
    // through to "No Vehicle Assigned". Legacy /today-visits rows
    // leave these as null and the renderer falls back to the prior
    // visitCategory heuristic. Values mirror the backend's
    // travelModeValidator: "own_vehicle" | "cab" | "external".
    val travelMode: String? = null,
    val vehiclePreference: String? = null,
    val vehicleAssigned: Boolean? = null,
    // Granular (timestamp-merged) SV status from the backend's mobile list
    // query — lets the SV filter separate Started / Picked Up / Completed,
    // which the 5-bucket `status` field above can't. Null on legacy rows;
    // the filter falls back to `status` when it's absent.
    val rawStatus: String? = null,
    // LMO (Lead Management Officer) — the telecaller who owns/created the
    // visit. Shown on the SV/CP cards. SV rows get it from the backend list
    // query; CP rows are mapped from the CpVisitDetail's telecaller.
    val lmoName: String? = null,
    // Convex auto-populates `_creationTime` on every doc; we surface it
    // so Today's Trip can sort newest-first regardless of source (legacy
    // fieldVisits route vs CP-merge path). For CP-merge rows where the
    // legacy field is absent, toTodayVisitOrNull seeds this from the
    // CpVisitDetail's stored `createdAt` (same numeric value).
    @com.google.gson.annotations.SerializedName("_creationTime")
    val creationTime: Double? = null,
    // Fleet "completed offline" flow: when the fleet admin marks an
    // expired SV as completed without a live trip, the backend sets
    // completedOffline=true and outcome=null. The site incharge must
    // then record the outcome from their mobile app.
    val completedOffline: Boolean? = null,
    val outcome: String? = null,
    // Free-text notes; carries the "[CP rejected by <name>] <reason>" marker so
    // the SV list can show a distinct "Rejected" badge + reason for an SV whose
    // CP verification was rejected.
    val notes: String? = null,
    // Confirmation state of a fixed SV — "pending" (awaiting CP verification)
    // or "confirmed". Drives the SV list "Fixed" tab (parity with the web
    // pipeline). Staged on the backend mobile mapper; null on rows that
    // predate that deploy, in which case the row falls under Scheduled.
    val confirmationStatus: String? = null,
)

data class CpVisitState(
    val clientMet: Boolean? = null,
    val clientMetAt: Long? = null,
    val clientNoShowReason: String? = null,
    val outcome: String? = null,
    val postponeReasons: List<String>? = null,
    // Visit intent from the CP create form. "gift_distribution"
    // tells the trip flow to skip the booking-outcome sheet after
    // arrival and finalise directly with outcome=gift_distributed.
    val cpType: String? = null,
)

data class TodayVisitsResponse(
    val success: Boolean,
    val data: List<TodayVisit>? = null
)

data class MySiteVisitsResponse(
    val success: Boolean,
    val total: Int? = null,
    val visits: List<TodayVisit> = emptyList(),
    val error: String? = null
)

data class TripsResponse(
    val success: Boolean,
    val data: List<GeoTrip>? = null,
    val error: String? = null
)

data class GeoTrip(
    @com.google.gson.annotations.SerializedName("_id") val id: String,
    val staffId: String,
    val status: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val distanceMeters: Int = 0,
    val durationSeconds: Int = 0,
    val pointCount: Int = 0,
    val placeName: String? = null,
    val placeAddress: String? = null,
    // Trip classification — drives the line colour on the map, mirroring web:
    // on-duty (onDutyCategory set) → amber, field visit (fieldVisitId set) →
    // green, otherwise → blue. vehicleType picks the vehicle marker icon.
    val onDutyCategory: String? = null,
    val fieldVisitId: String? = null,
    val vehicleType: String? = null,
    val vehicleOwnership: String? = null,
    val snappedPath: List<LatLngPoint>? = null,
    val stops: List<GeoTripStop>? = null
)

data class GeoTripStop(
    val lat: Double,
    val lng: Double,
    val arrivedAt: Long,
    val departedAt: Long,
    val durationMinutes: Int,
    val address: String? = null
)

data class LatLngPoint(
    val lat: Double,
    val lng: Double
)
