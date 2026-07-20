package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    @GET("api/travel-desk/vehicles")
    suspend fun listVehicles(
        @Header("Authorization") token: String
    ): TravelDeskVehiclesResponse

    @POST("api/travel-desk/vehicles/create")
    suspend fun createVehicle(
        @Header("Authorization") token: String,
        @Body body: CreateVehicleRequest
    ): TravelDeskCreateResponse

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

    @POST("api/travel-desk/trips/start")
    suspend fun driverStartTrip(
        @Header("Authorization") token: String,
        @Body body: TravelDeskDriverTripRequest
    ): TravelDeskSimpleResponse

    @POST("api/travel-desk/trips/on-site")
    suspend fun driverMarkOnSite(
        @Header("Authorization") token: String,
        @Body body: TravelDeskDriverTripRequest
    ): TravelDeskSimpleResponse

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
