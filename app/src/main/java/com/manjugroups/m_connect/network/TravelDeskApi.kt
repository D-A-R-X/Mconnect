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
