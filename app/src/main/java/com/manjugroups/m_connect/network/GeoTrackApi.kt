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

    @POST("api/marketing/clientPlaceVisits/convertToSiteVisit")
    suspend fun convertCpVisitToSiteVisit(
        @Header("Authorization") token: String,
        @Body body: ConvertCpVisitToSiteVisitRequest
    ): ConvertCpVisitToSiteVisitResponse

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
        @Query("toDate") toDate: String? = null
    ): MyMarketingCpVisitsResponse

    // ── Timeline (self-view) ──

    @GET("api/geotrack/timeline")
    suspend fun getTimeline(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null,
        @Query("dayStart") dayStart: Long,
        @Query("dayEnd") dayEnd: Long
    ): TimelineResponse

    @GET("api/geotrack/trips")
    suspend fun getTrips(
        @Header("Authorization") token: String,
        @Query("staffId") staffId: String? = null,
        @Query("startDate") startDate: Long,
        @Query("endDate") endDate: Long
    ): TripsResponse

    companion object {
        fun create(): GeoTrackApi {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            val client = OkHttpClient.Builder()
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
    val appVersion: String
)

data class TamperReportRequest(
    val eventType: String,
    val metadata: Map<String, Any?> = emptyMap()
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
    val lng: Double? = null
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
    val scheduledDate: String,
    val scheduledTime: String? = null,
    val visitAddress: String,
    val visitLat: Double? = null,
    val visitLng: Double? = null,
    val googleMapsLink: String? = null,
    val notes: String? = null,
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
    val notes: String? = null
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
    val convertedSiteVisitId: String? = null,
    val convertedBookingId: String? = null,
    val fieldVisitId: String? = null,
    val notes: String? = null,
    val completedAt: Long? = null,
    val cancelledAt: Long? = null,
    val expectedAttendeeCount: Int? = null,
    val foodPreferences: String? = null,
    val vehiclePreference: String? = null,
    val isBookingCompleted: Boolean? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    // SV-via-CP path: when the telecaller pre-fixed an SV via the
    // dialer's "same area" routing, the CP visit carries the full SV
    // payload here. Mobile uses this to lock the outcome sheet to the
    // Site Visit tab and pre-fill the form with the telecaller's plan.
    val proposedSiteVisit: ProposedSiteVisit? = null,
    val attendees: List<CpVisitAttendee>? = null,
    // Joined references the web `enrichVisit` helper attaches:
    val lead: CpVisitLead? = null,
    val client: CpVisitClient? = null,
    val telecaller: CpVisitStaff? = null,
    val assignedStaff: CpVisitStaff? = null,
    val clientPlace: CpVisitPlace? = null,
    val fieldVisit: CpVisitFieldVisit? = null,
    val arrivalProof: CpVisitArrivalProof? = null,
)

/**
 * Telecaller's pre-fixed SV details snapshot. When non-null on a
 * CP visit, the mobile bottom sheet locks to Site Visit mode and
 * surfaces these fields as read-only with Reject / Confirm buttons.
 */
data class ProposedSiteVisit(
    val projectId: String? = null,
    val scheduledDate: String? = null,
    val scheduledTime: String? = null,
    val inchargeStaffId: String? = null,
    val hodStaffId: String? = null,
    val bdoStaffId: String? = null,
    val avpStaffId: String? = null,
    val gmStaffId: String? = null,
    val seniorManagerStaffId: String? = null,
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
)

data class CpVisitClient(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
    val clientName: String? = null,
    val mobileNumber: String? = null,
    val city: String? = null,
)

data class CpVisitStaff(
    @com.google.gson.annotations.SerializedName("_id") val id: String? = null,
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
    // Convex auto-populates `_creationTime` on every doc; we surface it
    // so Today's Trip can sort newest-first regardless of source (legacy
    // fieldVisits route vs CP-merge path). For CP-merge rows where the
    // legacy field is absent, toTodayVisitOrNull seeds this from the
    // CpVisitDetail's stored `createdAt` (same numeric value).
    @com.google.gson.annotations.SerializedName("_creationTime")
    val creationTime: Double? = null,
)

data class CpVisitState(
    val clientMet: Boolean? = null,
    val clientMetAt: Long? = null,
    val clientNoShowReason: String? = null,
    val outcome: String? = null,
    val postponeReasons: List<String>? = null
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
