package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// Retrofit client for the /api/travel-desk/* HTTP routes the external
// fleet agency portal calls. Same routes are used by the travel-desk web
// app — we just call them from the mobile Admin Fleet screen now. The
// bearer token is the agency mobile session token (issued by
// /api/auth/verify-otp once the agency phone was recognised against
// travelAgencies). See TravelDeskModels.kt for the request/response shapes.

interface TravelDeskApi {

    // ── Auth ──────────────────────────────────────────────────────────────
    // The dedicated travel-desk auth path recognises agency drivers (rows in
    // travelDeskDrivers), which the MMS /api/auth/* path does not on the
    // live backend. The app falls back to these when the MMS login rejects a
    // phone as "not registered", so an agency-created driver can sign in.

    @POST("api/travel-desk/auth/send-otp")
    suspend fun sendOtp(@Body body: TravelDeskSendOtpRequest): TravelDeskSendOtpResponse

    // Register the agency driver's FCM token so trip allocations can push
    // them — they're not staff, so this is their own channel.
    @POST("api/travel-desk/push/register")
    suspend fun registerPushToken(
        @Header("Authorization") token: String,
        @Body body: TravelDeskPushRegisterRequest,
    ): TravelDeskSimpleResponse

    @POST("api/travel-desk/auth/verify-otp")
    suspend fun verifyOtp(@Body body: TravelDeskVerifyOtpRequest): TravelDeskVerifyOtpResponse

    @GET("api/travel-desk/trips/pending")
    suspend fun listPending(
        @Header("Authorization") token: String
    ): TravelDeskTripsResponse

    @GET("api/travel-desk/trips/assigned")
    suspend fun listAssigned(
        @Header("Authorization") token: String
    ): TravelDeskTripsResponse

    @POST("api/travel-desk/trips/allocate")
    suspend fun allocate(
        @Header("Authorization") token: String,
        @Body body: AllocateTripRequest
    ): TravelDeskAllocateResponse

    // Take a trip back off its vehicle/driver — returns it to Pending. The
    // backend refuses once the driver has set off.
    @POST("api/travel-desk/trips/unallocate")
    suspend fun unallocate(
        @Header("Authorization") token: String,
        @Body body: TravelDeskDriverTripRequest
    ): TravelDeskAllocateResponse

    @GET("api/travel-desk/vehicles")
    suspend fun listVehicles(
        @Header("Authorization") token: String
    ): TravelDeskVehiclesResponse

    @POST("api/travel-desk/vehicles/create")
    suspend fun createVehicle(
        @Header("Authorization") token: String,
        @Body body: CreateVehicleRequest
    ): TravelDeskCreateResponse

    // Edit an existing agency vehicle. Needs the vehicles/update backend
    // route + mutation (added alongside).
    @POST("api/travel-desk/vehicles/update")
    suspend fun updateVehicle(
        @Header("Authorization") token: String,
        @Body body: UpdateVehicleRequest
    ): TravelDeskSimpleResponse

    // ── Drivers ───────────────────────────────────────────────────────────

    @GET("api/travel-desk/drivers")
    suspend fun listDrivers(
        @Header("Authorization") token: String
    ): TravelDeskDriversResponse

    @POST("api/travel-desk/drivers/create")
    suspend fun createDriver(
        @Header("Authorization") token: String,
        @Body body: CreateDriverRequest
    ): TravelDeskCreateResponse

    @POST("api/travel-desk/drivers/update")
    suspend fun updateDriver(
        @Header("Authorization") token: String,
        @Body body: UpdateDriverRequest
    ): TravelDeskSimpleResponse

    @POST("api/travel-desk/drivers/set-status")
    suspend fun setDriverStatus(
        @Header("Authorization") token: String,
        @Body body: SetDriverStatusRequest
    ): TravelDeskSimpleResponse

    @GET("api/travel-desk/settings")
    suspend fun getAgencySettings(
        @Header("Authorization") token: String
    ): TravelDeskSettingsResponse

    @POST("api/travel-desk/settings/update")
    suspend fun updateAgencySettings(
        @Header("Authorization") token: String,
        @Body body: UpdateTravelDeskSettingsRequest
    ): TravelDeskSettingsResponse

    @GET("api/travel-desk/staff")
    suspend fun listAgencyStaff(
        @Header("Authorization") token: String
    ): TravelDeskAgencyStaffResponse

    @POST("api/travel-desk/staff/create")
    suspend fun createAgencyStaff(
        @Header("Authorization") token: String,
        @Body body: CreateAgencyStaffRequest
    ): TravelDeskCreateResponse

    @POST("api/travel-desk/staff/update")
    suspend fun updateAgencyStaff(
        @Header("Authorization") token: String,
        @Body body: UpdateAgencyStaffRequest
    ): TravelDeskSimpleResponse

    // ── Agency-owned driver ───────────────────────────────────────────────
    // Same bearer token as the agency routes; the backend resolves the
    // travelDeskSessions row to a *driver* principal and scopes the list to
    // trips allocated to that driver.

    @GET("api/travel-desk/trips/driver")
    suspend fun listDriverTrips(
        @Header("Authorization") token: String
    ): TravelDeskDriverTripsResponse

    @POST("api/travel-desk/trips/arrive")
    suspend fun driverMarkArrived(
        @Header("Authorization") token: String,
        @Body body: TravelDeskDriverTripRequest
    ): TravelDeskSimpleResponse

    // Start needs the client OTP (a fixed dummy on this backend), the
    // dashboard/odometer photo, and the start-km reading — same capture the
    // MMS fleet driver does, but on the travel-desk route.
    @POST("api/travel-desk/trips/start")
    suspend fun driverStartTrip(
        @Header("Authorization") token: String,
        @Body body: TravelDeskStartTripRequest
    ): TravelDeskSimpleResponse

    @POST("api/travel-desk/trips/on-site")
    suspend fun driverMarkOnSite(
        @Header("Authorization") token: String,
        @Body body: TravelDeskDriverTripRequest
    ): TravelDeskSimpleResponse

    // The return pickup — enabled 60s after "reached site" in the app.
    @POST("api/travel-desk/trips/picked-from-site")
    suspend fun driverMarkPickedFromSite(
        @Header("Authorization") token: String,
        @Body body: TravelDeskDriverTripRequest
    ): TravelDeskSimpleResponse

    @POST("api/travel-desk/trips/end")
    suspend fun driverEndTrip(
        @Header("Authorization") token: String,
        @Body body: TravelDeskEndTripRequest
    ): TravelDeskSimpleResponse

    // Raw-blob upload, mirroring StorageUploader's MMS path. Returns a
    // storageId to attach to start/end as a photoId.
    @POST("api/travel-desk/storage/upload")
    suspend fun uploadStorageFile(
        @Header("Authorization") token: String,
        @Body body: RequestBody,
    ): TravelDeskStorageResponse

    // ── MMS (in-house) fleet dispatcher ───────────────────────────────────
    // Same payload shapes as the agency routes above, but authenticated with an
    // ordinary staff token and gated on marketing.fleet.* — the travel-desk
    // routes only accept agency sessions and reject internal agencies outright.

    @GET("api/mms-fleet/dispatch/pending")
    suspend fun listMmsPending(
        @Header("Authorization") token: String
    ): TravelDeskTripsResponse

    @GET("api/mms-fleet/dispatch/assigned")
    suspend fun listMmsAssigned(
        @Header("Authorization") token: String
    ): TravelDeskTripsResponse

    @POST("api/mms-fleet/dispatch/allocate")
    suspend fun allocateMms(
        @Header("Authorization") token: String,
        @Body body: AllocateTripRequest
    ): TravelDeskAllocateResponse

    @GET("api/mms-fleet/dispatch/vehicles")
    suspend fun listMmsVehicles(
        @Header("Authorization") token: String
    ): TravelDeskVehiclesResponse

    @GET("api/mms-fleet/dispatch/agencies")
    suspend fun listMmsAgencies(
        @Header("Authorization") token: String
    ): TravelDeskAgenciesResponse

    // Allot a visit to an external travel agency (sets travelAgencyId; the
    // agency then assigns the cab in Travel Desk).
    @POST("api/mms-fleet/dispatch/allot-agency")
    suspend fun allotMmsAgency(
        @Header("Authorization") token: String,
        @Body body: AllotAgencyRequest
    ): TravelDeskAllocateResponse

    // Unassign an MFPL/agency-allotted trip (staff token). NOT the travel-desk
    // /trips/unallocate route — that only accepts an agency session.
    @POST("api/mms-fleet/dispatch/unassign")
    suspend fun unassignMms(
        @Header("Authorization") token: String,
        @Body body: TravelDeskDriverTripRequest
    ): TravelDeskAllocateResponse

    @POST("api/mms-fleet/dispatch/complete-offline")
    suspend fun completeOfflineMms(
        @Header("Authorization") token: String,
        @Body body: CompleteOfflineTripRequest
    ): TravelDeskAllocateResponse

    @POST("api/travel-desk/trips/complete-offline")
    suspend fun completeOfflineAgency(
        @Header("Authorization") token: String,
        @Body body: CompleteOfflineTripRequest
    ): TravelDeskAllocateResponse

    @POST("api/travel-desk/trips/extra-km")
    suspend fun submitExtraKmClaim(
        @Header("Authorization") token: String,
        @Body body: ExtraKmClaimRequest
    ): TravelDeskSimpleResponse

    @GET("api/mms-fleet/dispatch/drivers")
    suspend fun listMmsDrivers(
        @Header("Authorization") token: String
    ): TravelDeskDriversResponse

    @POST("api/mms-fleet/dispatch/drivers/create")
    suspend fun createMmsDriver(
        @Header("Authorization") token: String,
        @Body body: CreateDriverRequest
    ): TravelDeskCreateResponse

    @POST("api/mms-fleet/dispatch/drivers/update")
    suspend fun updateMmsDriver(
        @Header("Authorization") token: String,
        @Body body: UpdateDriverRequest
    ): TravelDeskSimpleResponse

    @POST("api/mms-fleet/dispatch/drivers/set-status")
    suspend fun setMmsDriverStatus(
        @Header("Authorization") token: String,
        @Body body: SetDriverStatusRequest
    ): TravelDeskSimpleResponse

    companion object {
        fun create(): TravelDeskApi {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            // Auto-logout on 401 — mirrors ApiService / GeoTrackApi so a stale
            // agency session bounces back to login instead of silently failing.
            val authWatchdog = okhttp3.Interceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                if (response.code == 401) {
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
                .create(TravelDeskApi::class.java)
        }
    }
}
