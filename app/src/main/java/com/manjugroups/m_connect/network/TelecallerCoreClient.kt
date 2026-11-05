package com.manjugroups.m_connect.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.manjugroups.m_connect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Talks to Telecaller Core, the service behind the web "Insights" page, the
 * same way the web does:
 *  1. exchange the MFPL session for a short-lived core JWT
 *     (POST {BASE_URL}api/internal/telecaller/session/exchange), cached until
 *     a minute before it expires;
 *  2. POST {core}/api with {namespace, apiName, auth, data}.
 * A core "unauthorized" drops the cached JWT and retries once.
 */
object TelecallerCoreClient {

    private const val CORE_BASE_URL = "https://api-telecaller.theairix.com"
    private const val REFRESH_SKEW_MS = 60_000L

    private val gson = Gson()
    private val json = "application/json".toMediaType()
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private data class CachedJwt(val sessionToken: String, val token: String, val expiresAtMs: Long)

    private val jwtLock = Mutex()
    @Volatile private var cached: CachedJwt? = null

    class CoreException(message: String) : Exception(message)

    private data class ExchangeResponse(
        val success: Boolean = false,
        val accessToken: String? = null,
        val expiresIn: Long? = null,
        val error: String? = null,
    )

    private suspend fun jwt(bearer: String, forceRefresh: Boolean): String = jwtLock.withLock {
        val session = bearer.removePrefix("Bearer ").trim()
        val hit = cached
        if (!forceRefresh && hit != null && hit.sessionToken == session &&
            hit.expiresAtMs - REFRESH_SKEW_MS > System.currentTimeMillis()
        ) {
            return@withLock hit.token
        }
        val request = Request.Builder()
            .url(BuildConfig.BASE_URL.trimEnd('/') + "/api/internal/telecaller/session/exchange")
            .header("Authorization", "Bearer $session")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val body = http.newCall(request).execute().use { it.body?.string() }
        val parsed = runCatching { gson.fromJson(body, ExchangeResponse::class.java) }.getOrNull()
        val token = parsed?.accessToken?.takeIf { parsed.success && it.isNotBlank() }
            ?: throw CoreException(parsed?.error ?: "Couldn't sign in to Insights")
        val ttl = (parsed.expiresIn ?: 300L).coerceAtLeast(30L) * 1000L
        cached = CachedJwt(session, token, System.currentTimeMillis() + ttl)
        token
    }

    /** Calls a core API and returns its `data` object. */
    suspend fun call(bearer: String, namespace: String, apiName: String, data: Map<String, Any?>): JsonObject =
        withContext(Dispatchers.IO) {
            suspend fun attempt(refresh: Boolean): JsonObject {
                val payload = gson.toJson(
                    mapOf("namespace" to namespace, "apiName" to apiName, "auth" to jwt(bearer, refresh), "data" to data),
                )
                val request = Request.Builder()
                    .url("$CORE_BASE_URL/api")
                    .post(payload.toRequestBody(json))
                    .build()
                val (code, text) = http.newCall(request).execute().use { it.code to it.body?.string() }
                val envelope = runCatching { gson.fromJson(text, JsonObject::class.java) }.getOrNull()
                val status = envelope?.get("status")?.takeIf { it.isJsonPrimitive }?.asString
                val message = envelope?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                if ((code == 401 || message == "unauthorized") && !refresh) return attempt(true)
                if (code !in 200..299 || status == "error" || envelope == null) {
                    throw CoreException(message ?: "Insights is unavailable ($code)")
                }
                return envelope.getAsJsonObject("data") ?: JsonObject()
            }
            attempt(false)
        }

    /** Forget the core JWT (sign-out). */
    fun reset() {
        cached = null
    }
}

/** One metric on the Team Performance card: actual against target. */
data class CockpitMetric(
    val actual: Double = 0.0,
    val target: Double = 0.0,
    val percentage: Double = 0.0,
)

/** The cockpitPerformance answer, trimmed to what the dashboard shows. */
data class CockpitPerformance(
    val metrics: Map<String, CockpitMetric> = emptyMap(),
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val availableFrom: String? = null,
    val canSeeAll: Boolean = false,
    @SerializedName("generatedAt") val generatedAt: String? = null,
)
