package com.manjugroups.m_connect.network

import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Client for the standalone Map microservice (address search, reverse
 * geocode, distance) documented at https://api-map-service.aivida.in.
 *
 * It has its OWN base URL — separate from BuildConfig.BASE_URL — and needs
 * NO auth header (the Google key lives on that server). Kept as a tiny
 * dedicated Retrofit client so the address-search-with-suggestions widget
 * can call it from anywhere the app picks a location.
 */
interface MapServiceApi {

    /** Autocomplete-style address/place search. */
    @GET("api/address/search")
    suspend fun searchAddress(
        @Query("query") query: String,
        @Query("limit") limit: Int = 6,
    ): MapAddressSearchResponse

    /** Turn a dropped pin into a readable address. */
    @GET("api/geocode/reverse")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
    ): MapReverseGeocodeResponse

    companion object {
        const val BASE_URL = "https://api-map-service.aivida.in/"

        @Volatile
        private var instance: MapServiceApi? = null

        fun create(): MapServiceApi = instance ?: synchronized(this) {
            instance ?: build().also { instance = it }
        }

        private fun build(): MapServiceApi {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logging)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MapServiceApi::class.java)
        }
    }
}

// ── Response models (nullable throughout — Gson-safe per this app's rules) ──

data class MapLatLng(
    val lat: Double? = null,
    val lng: Double? = null,
)

data class MapAddressResult(
    val placeId: String? = null,
    val name: String? = null,
    val address: String? = null,
    val location: MapLatLng? = null,
    val types: List<String>? = null,
) {
    /** Best short label for a suggestion row (place name, else address). */
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address.orEmpty()
}

data class MapAddressSearchResponse(
    val results: List<MapAddressResult> = emptyList(),
    val error: String? = null,
)

data class MapReverseResult(
    val placeId: String? = null,
    val address: String? = null,
    val location: MapLatLng? = null,
    val locationType: String? = null,
    val types: List<String>? = null,
)

data class MapReverseGeocodeResponse(
    val results: List<MapReverseResult> = emptyList(),
    val error: String? = null,
)
