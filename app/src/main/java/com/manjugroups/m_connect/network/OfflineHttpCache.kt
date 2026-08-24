package com.manjugroups.m_connect.network

import android.content.Context
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Interceptor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * App-wide HTTP response cache that makes every GET screen survive going
 * offline.
 *
 * Only a handful of screens had bespoke `LocalCache` snapshots, so turning the
 * network off emptied everything else — lists that had just rendered came back
 * blank because their re-fetch threw and nothing had been stored. Caching at
 * the OkHttp layer fixes all of them at once, without touching a single screen.
 *
 * Freshness is never traded away:
 *  - ONLINE  → [storeResponses] tags responses `max-age=0`, so they are STORED
 *              but treated as immediately stale; every online request still
 *              goes to the network exactly as before.
 *  - OFFLINE → [serveStaleWhenOffline] rewrites the request to `only-if-cached`
 *              with a long max-stale, so the last-known response is replayed
 *              instead of throwing.
 *
 * Safety:
 *  - Responses carry `Vary: Authorization`, so a cached entry is only ever
 *    replayed for the same bearer token — one staff can never see another's
 *    cached data.
 *  - Auth endpoints are never stored ([isNeverCacheable]).
 *  - Writes (POST/PUT/PATCH/DELETE) are not cached by OkHttp at all, so
 *    mutations still fail offline and keep their existing queue/retry paths.
 *  - [clear] wipes it on logout.
 */
object OfflineHttpCache {

    private const val MAX_CACHE_BYTES = 40L * 1024 * 1024 // 40 MB
    private val MAX_STALE_SECONDS = TimeUnit.DAYS.toSeconds(30).toInt()

    @Volatile
    private var cache: Cache? = null

    /** Shared on-disk cache, or null before the Application context exists. */
    fun cache(): Cache? {
        cache?.let { return it }
        val ctx = MconnectAppContext.get() ?: return null
        return synchronized(this) {
            cache ?: runCatching {
                Cache(File(ctx.cacheDir, "http_cache"), MAX_CACHE_BYTES)
            }.getOrNull()?.also { cache = it }
        }
    }

    /** Drop every cached response — called on logout so the next user starts clean. */
    fun clear() {
        runCatching { cache()?.evictAll() }
    }

    private fun isNeverCacheable(url: String): Boolean =
        url.contains("/api/auth/") || url.contains("/api/storage/upload")

    /**
     * Application interceptor: try the network first, and only if the call
     * actually FAILS (no network, DNS failure, timeout) replay the last-known
     * cached response.
     *
     * Deliberately not gated on a connectivity check: `NET_CAPABILITY_VALIDATED`
     * can read false while the network works (captive portal, validation still
     * pending), which would serve stale data to an online user. Reacting to a
     * real failure has no false positives — online users always get the network
     * response, and there is no added latency offline because a connect with no
     * network fails immediately rather than waiting for the timeout.
     *
     * A cache miss surfaces as HTTP 504, which callers already handle as a
     * failed load — the same empty state they showed offline before, so nothing
     * regresses.
     */
    val serveStaleWhenOffline = Interceptor { chain ->
        val request = chain.request()
        if (request.method != "GET") {
            chain.proceed(request)
        } else {
            try {
                chain.proceed(request)
            } catch (io: java.io.IOException) {
                val cached = runCatching {
                    chain.proceed(
                        request.newBuilder()
                            .cacheControl(
                                CacheControl.Builder()
                                    .onlyIfCached()
                                    .maxStale(MAX_STALE_SECONDS, TimeUnit.SECONDS)
                                    .build(),
                            )
                            .build(),
                    )
                }.getOrNull()
                // A cache MISS is not an exception — OkHttp answers
                // `only-if-cached` with a synthetic 504. Handing that back would
                // turn a "you are offline" failure into a confusing HTTP error,
                // so discard it and rethrow the original IOException: screens
                // then behave exactly as they did before this cache existed.
                if (cached != null && cached.isSuccessful) {
                    cached
                } else {
                    cached?.close()
                    throw io
                }
            }
        }
    }

    /**
     * Network interceptor: the backend sends no cache headers at all, so
     * nothing would ever be stored. Tag successful GETs as storable-but-stale
     * (`max-age=0`) and vary them by Authorization.
     */
    val storeResponses = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (
            request.method != "GET" ||
            !response.isSuccessful ||
            isNeverCacheable(request.url.toString())
        ) {
            response
        } else {
            response.newBuilder()
                .removeHeader("Pragma")
                .removeHeader("Expires")
                .header("Cache-Control", "max-age=0")
                .header("Vary", "Authorization")
                .build()
        }
    }
}

/** Process-wide Application context, set once in MconnectApp.onCreate(). */
object MconnectAppContext {
    @Volatile
    private var appContext: Context? = null

    fun set(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext
}
