package com.manjugroups.m_connect.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.manjugroups.m_connect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Mobile-side India Post pincode lookup.
 *
 * Mirrors the web's `/api/pincode` proxy at
 * `app/api/pincode/route.ts` — we hit the SAME proxy the web uses so the
 * mobile app benefits from the same TLS-bypass + caching policy without
 * having to replicate the insecure-https-agent trick in Kotlin.
 *
 * Why not hit `api.postalpincode.in` directly:
 *   • India Post has been shipping an expired TLS certificate since
 *     2026-05-22. OkHttp validates upstream certs and rejects the
 *     connection. The web proxy isolates the bypass.
 *   • Going through the proxy also gets us the same response shape the
 *     web parses, so locality cleaning logic stays identical.
 *
 * Why `APP_URL` not `BASE_URL`:
 *   • `BASE_URL` is the Convex deployment (`.convex.site`). `/api/pincode`
 *     does NOT live on Convex — it's a Next.js route in the web project.
 *   • `APP_URL` (default `https://mms.aivida.in/`) points to the web
 *     deployment that owns the proxy.
 */
object PincodeLookup {
    private val gson = Gson()

    // Single shared client — short timeouts because this is best-effort
    // background enrichment, not a blocking gate. If India Post is slow
    // we drop the lookup silently and the operator types the locality by
    // hand.
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // Tiny in-process cache + dedup. The booking-form pincode watcher
    // re-fires on every keystroke and on view-restore; without this the
    // same 6-digit pin can trigger 3-4 lookups per modal open. 60s TTL
    // matches the web's PINCODE_CACHE_TTL_MS.
    private data class CachedResult(val at: Long, val data: PincodeResponse)
    private val cache = mutableMapOf<String, CachedResult>()
    private const val CACHE_TTL_MS = 60_000L

    /**
     * Look up a 6-digit pincode and return the FIRST non-NA locality the
     * India Post API knows about, with the postal-branch suffix stripped
     * (`Ashok Nagar S.O.` → `Ashok Nagar`). Plus the district and state
     * for parity with the web's enrichment effect.
     *
     * Returns `null` on invalid input, network error, empty result set,
     * or any other failure. Callers treat null as "leave fields alone."
     */
    suspend fun lookup(pin: String): EnrichedPincode? = withContext(Dispatchers.IO) {
        val cleaned = pin.trim()
        if (!cleaned.matches(Regex("^\\d{6}$"))) return@withContext null

        val now = System.currentTimeMillis()
        val cached = cache[cleaned]
        val response = if (cached != null && (now - cached.at) < CACHE_TTL_MS) {
            cached.data
        } else {
            val url = BuildConfig.APP_URL.trimEnd('/') + "/api/pincode?pin=" + cleaned
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body?.string() ?: return@withContext null
                    // India Post returns a single-element JSON array,
                    // wrapped by our proxy unchanged.
                    val arr = gson.fromJson(body, Array<PincodeResponse>::class.java)
                    val first = arr?.firstOrNull() ?: return@withContext null
                    cache[cleaned] = CachedResult(now, first)
                    first
                }
            } catch (_: Throwable) {
                // Silent — same policy as the web. The operator can keep
                // typing by hand. We DON'T cache failures so the next
                // pincode change re-attempts cleanly.
                return@withContext null
            }
        }

        val offices = response.postOffices ?: emptyList()
        if (offices.isEmpty()) return@withContext null

        fun pick(extractor: (PincodeOffice) -> String?, skipNa: Boolean = false): String {
            for (office in offices) {
                val value = extractor(office)?.trim().orEmpty()
                if (value.isEmpty()) continue
                if (skipNa && value.equals("NA", ignoreCase = true)) continue
                return value
            }
            return ""
        }

        val rawLocality = pick({ it.name }, skipNa = true)
        // Strip postal-branch suffixes that India Post appends so the
        // value reads like a human neighbourhood name. Same regex as
        // mms-external-leads.tsx.
        val locality = rawLocality.replace(
            Regex("\\s+(S\\.O\\.|B\\.O\\.|H\\.O\\.|GPO|HPO|SO|BO|HO)\\s*$", RegexOption.IGNORE_CASE),
            "",
        ).trim()
        val district = pick({ it.district })
        val state = pick({ it.state })

        EnrichedPincode(
            locality = locality.ifEmpty { null },
            district = district.ifEmpty { null },
            state = state.ifEmpty { null },
        )
    }
}

data class EnrichedPincode(
    val locality: String?,
    val district: String?,
    val state: String?,
)

// JSON shapes — match exactly what India Post returns through our proxy.
data class PincodeResponse(
    @SerializedName("Status") val status: String?,
    @SerializedName("PostOffice") val postOffices: List<PincodeOffice>?,
)

data class PincodeOffice(
    @SerializedName("Name") val name: String?,
    @SerializedName("Block") val block: String?,
    @SerializedName("Division") val division: String?,
    @SerializedName("District") val district: String?,
    @SerializedName("State") val state: String?,
)
