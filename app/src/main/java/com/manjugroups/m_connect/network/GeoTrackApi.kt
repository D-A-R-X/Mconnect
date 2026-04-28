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
    val placeName: String? = null,
    val placeAddress: String? = null,
    val placeType: String? = null,
    val placeLat: Double? = null,
    val placeLng: Double? = null,
    @com.google.gson.annotations.SerializedName(
        value = "scheduledStartTime",
        alternate = ["scheduledStart", "startTime", "meetingStartTime", "scheduledFrom", "fromTime", "startAt", "scheduledTime", "time", "visitTime", "timeFrom", "appointmentTime"]
    )
    val scheduledStartTime: String? = null,
    @com.google.gson.annotations.SerializedName(
        value = "scheduledEndTime",
        alternate = ["scheduledEnd", "endTime", "meetingEndTime", "scheduledTo", "toTime", "endAt", "timeTo", "visitEndTime"]
    )
    val scheduledEndTime: String? = null
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
