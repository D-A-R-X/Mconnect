package com.manjugroups.m_connect.network

import com.google.gson.annotations.SerializedName
import com.manjugroups.m_connect.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Project-management Daily Log (DPR) API. Mirrors the web feature at
 * `app/projects/[id]/daily-log` — a manual daily log entry plus a WhatsApp DPR
 * recipient list + on-demand send. The underlying Convex functions
 * (dailyLogs.*, projectDpr.*, projectDprActions.sendNow) are exposed to mobile
 * via new HTTP routes under /api/projects/daily-log and /api/projects/dpr.
 */
interface DailyLogApi {

    // ── Manual daily log entry ──
    @POST("api/projects/daily-log/create")
    suspend fun createDailyLog(
        @Header("Authorization") token: String,
        @Body body: CreateDailyLogRequest,
    ): CreateDailyLogResponse

    @GET("api/projects/daily-log/list")
    suspend fun listDailyLogs(
        @Header("Authorization") token: String,
        @Query("projectId") projectId: String,
    ): DailyLogListResponse

    // The caller's recent logs across every project they can access —
    // powers the loans-style "my entries" list (no page-level project pick).
    @GET("api/projects/daily-log/mine")
    suspend fun listMyDailyLogs(
        @Header("Authorization") token: String,
    ): DailyLogListResponse

    // Recipients + reports across accessible projects, in one call.
    @GET("api/projects/dpr/mine")
    suspend fun getMyDpr(
        @Header("Authorization") token: String,
    ): MyDprResponse

    // ── DPR WhatsApp recipients ──
    @GET("api/projects/dpr/recipients")
    suspend fun listDprRecipients(
        @Header("Authorization") token: String,
        @Query("projectId") projectId: String,
    ): DprRecipientsResponse

    @POST("api/projects/dpr/recipients/add")
    suspend fun addDprRecipient(
        @Header("Authorization") token: String,
        @Body body: AddDprRecipientRequest,
    ): SimpleOkResponse

    @POST("api/projects/dpr/recipients/update")
    suspend fun updateDprRecipient(
        @Header("Authorization") token: String,
        @Body body: UpdateDprRecipientRequest,
    ): SimpleOkResponse

    @POST("api/projects/dpr/recipients/remove")
    suspend fun removeDprRecipient(
        @Header("Authorization") token: String,
        @Body body: IdOnlyRequest,
    ): SimpleOkResponse

    // ── DPR reports (history) + send-now ──
    @GET("api/projects/dpr/reports")
    suspend fun listDprReports(
        @Header("Authorization") token: String,
        @Query("projectId") projectId: String,
    ): DprReportsResponse

    @POST("api/projects/dpr/send")
    suspend fun sendDprNow(
        @Header("Authorization") token: String,
        @Body body: SendDprRequest,
    ): SendDprResponse

    companion object {
        private val instance: DailyLogApi by lazy { build() }
        fun create(): DailyLogApi = instance

        private fun build(): DailyLogApi {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.NONE
            }
            val authWatchdog = okhttp3.Interceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 401) {
                    com.manjugroups.m_connect.auth.SessionInvalidationBus.reportUnauthorized()
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
                .create(DailyLogApi::class.java)
        }
    }
}

// ── Request / response models ──

data class DailyLogMaterial(
    val name: String,
    val quantity: Double,
    val unit: String? = null,
)

data class DailyLogEquipment(
    val name: String,
    val hours: Double,
)

data class CreateDailyLogRequest(
    val projectId: String,
    val date: String,
    val weather: String? = null,
    val siteConditions: String? = null,
    val workSummary: String,
    val labourCount: Int? = null,
    val labourHours: Double? = null,
    val materialsUsed: List<DailyLogMaterial>? = null,
    val equipmentUsed: List<DailyLogEquipment>? = null,
    val issuesEncountered: String? = null,
    val safetyObservations: String? = null,
    val supervisorName: String? = null,
)

data class CreateDailyLogResponse(
    val success: Boolean,
    @SerializedName("_id") val id: String? = null,
    val error: String? = null,
)

data class DailyLogEntry(
    @SerializedName("_id") val id: String,
    val date: String? = null,
    val weather: String? = null,
    val siteConditions: String? = null,
    val workSummary: String? = null,
    val labourCount: Int? = null,
    val labourHours: Double? = null,
    val supervisorName: String? = null,
    val createdBy: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
)

data class DailyLogListResponse(
    val success: Boolean,
    val logs: List<DailyLogEntry> = emptyList(),
    val error: String? = null,
)

data class SimpleOkResponse(val success: Boolean, val error: String? = null)

data class IdOnlyRequest(val id: String)

data class AddDprRecipientRequest(
    val projectId: String,
    val staffId: String? = null,
    val name: String,
    val phone: String,
)

data class UpdateDprRecipientRequest(
    val id: String,
    val isActive: Boolean? = null,
    val name: String? = null,
    val phone: String? = null,
)

data class DprRecipient(
    @SerializedName("_id") val id: String,
    val name: String? = null,
    val phone: String? = null,
    val normalizedPhone: String? = null,
    val isActive: Boolean = true,
    val staffId: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
)

data class MyDprResponse(
    val success: Boolean,
    val recipients: List<DprRecipient> = emptyList(),
    val reports: List<DprReport> = emptyList(),
    val error: String? = null,
)

data class DprRecipientsResponse(
    val success: Boolean,
    val recipients: List<DprRecipient> = emptyList(),
    val error: String? = null,
)

data class DprReport(
    @SerializedName("_id") val id: String,
    val date: String? = null,
    val status: String? = null,
    val sentCount: Int? = null,
    val failedCount: Int? = null,
    val taskUpdateCount: Int? = null,
    val pdfUrl: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
)

data class DprReportsResponse(
    val success: Boolean,
    val reports: List<DprReport> = emptyList(),
    val error: String? = null,
)

data class SendDprRequest(
    val projectId: String,
    val date: String? = null,
)

data class SendDprResponse(
    val success: Boolean,
    val skipped: Boolean? = null,
    val sentCount: Int? = null,
    val failedCount: Int? = null,
    val error: String? = null,
)
