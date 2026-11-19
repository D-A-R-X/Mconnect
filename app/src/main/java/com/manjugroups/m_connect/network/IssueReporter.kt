package com.manjugroups.m_connect.network

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import com.manjugroups.m_connect.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Existing hooks, now direct to PostHog. Never calls MMS for telemetry. */
object IssueReporter {
    private const val QUEUE_FILE = "mobile_posthog_queue_v1.json"
    private const val CRASH_FILE = "mobile_posthog_crash_v1.json"
    private lateinit var app: Application
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { r -> Thread(r, "mobile-telemetry").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
        ThreadPoolExecutor.AbortPolicy())
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mobile-telemetry-timer").apply { isDaemon = true }
    }
    private val pending = ArrayList<JSONObject>()
    @Volatile private var scheduled = false
    private var retryAt = 0L
    private var failures = 0
    private var lastAttempt = -MobileTelemetryPolicy.FLUSH_MS
    private var loaded = false
    private lateinit var budget: MobileTelemetryBudget
    private var anonymousId = ""
    @Volatile private var screen = "App"
    private val authority by lazy { URI(BuildConfig.BASE_URL).host.orEmpty() }
    private val client by lazy { PostHogTransport.client() }

    @Synchronized fun install(application: Application) {
        if (::app.isInitialized || !PostHogConfig.ENABLED) return
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Save code frames only; never send a network request on a crashing thread.
            runCatching {
                val file = AtomicFile(File(app.filesDir, CRASH_FILE))
                val stream = file.startWrite()
                try { stream.write(errorEvent(error).toString().toByteArray()); file.finishWrite(stream) }
                catch (failure: Exception) { file.failWrite(stream) }
            }
            previous?.uncaughtException(thread, error)
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                screen = MobileTelemetryPolicy.symbol(activity.javaClass.simpleName)
                submit { load(); schedule() }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
        submit {
            load()
            // Old server reports contain raw text. Do not migrate them to a third party.
            File(app.filesDir, "mobile_issue_queue.json").delete()
            File(app.filesDir, "mobile_issue_crash.json").delete()
            val crash = File(app.filesDir, CRASH_FILE)
            if (crash.exists()) {
                runCatching { JSONObject(crash.readText()) }.getOrNull()?.let { add(it, false, false) }
                crash.delete()
            }
            schedule()
        }
    }

    fun currentScreen(): String = screen
    // These call sites can contain user or server text; never retain that text.
    @Suppress("UNUSED_PARAMETER") fun crumb(text: String) = Unit

    val httpInterceptor = Interceptor { chain ->
        val request = chain.request()
        val service = MobileTelemetryPolicy.service(request.url.host, authority)
        if (!::app.isInitialized || service == null || request.url.encodedPath.startsWith("/api/mobile/issues/")) {
            return@Interceptor chain.proceed(request)
        }
        val started = SystemClock.elapsedRealtime()
        val response = try { chain.proceed(request) } catch (error: IOException) {
            if (!chain.call().isCanceled() && hasInternet()) runCatching {
                observe(request, service, 0, SystemClock.elapsedRealtime() - started, networkKind(error))
            }
            throw error
        }
        runCatching {
            if (!request.cacheControl.onlyIfCached && response.networkResponse != null) {
                observe(request, service, response.code, SystemClock.elapsedRealtime() - started, null)
            }
        }
        response
    }

    private fun observe(request: Request, service: String, status: Int, duration: Long, error: String?) {
        val path = MobileTelemetryPolicy.route(request.url.encodedPath)
        val method = MobileTelemetryPolicy.method(request.method)
        val timing = status in 200..399
        val healthy = timing && duration < 3_000
        val signature = "$service $method $path ${error ?: status.toString()}"
        // Only constant dimensions are copied; no headers, bodies, query or host strings.
        val properties = JSONObject().put("service", service).put("endpoint", "$method $path")
            .put("method", method).put("status", status).put("duration_ms", duration.coerceIn(0, 300_000))
            .put("outcome", error ?: if (status >= 400) "http_error" else if (healthy) "success" else "slow")
            .put("sample_rate", if (healthy) 0.05 else 1.0)
        submit {
            val report = event("mobile_endpoint", properties, signature)
            add(report, timing, healthy)
        }
    }

    private fun networkKind(error: IOException): String = when (error) {
        is java.net.SocketTimeoutException -> "Timeout"
        is java.net.UnknownHostException -> "DNS"
        is javax.net.ssl.SSLException -> "TLS"
        else -> "Connection"
    }

    @Suppress("UNUSED_PARAMETER") fun onNotice(text: String, kind: String) {
        if (kind != "ERROR") return
        val atScreen = screen
        submit {
            val report = event("\$exception", JSONObject().put("screen", atScreen), "flow $atScreen")
            exception(report, "MobileFlowError", true)
            add(report, false, false)
        }
    }

    private fun event(name: String, properties: JSONObject, signature: String): JSONObject = JSONObject()
        .put("event", name).put("properties", properties).put("fingerprint", signature)
        .put("occurredAt", System.currentTimeMillis()).put("uuid", UUID.randomUUID().toString())

    private fun exception(event: JSONObject, type: String, handled: Boolean, frames: JSONArray? = null) {
        val detail = JSONObject().put("type", type).put("value", type)
            .put("mechanism", JSONObject().put("type", "mobile").put("handled", handled).put("synthetic", frames == null))
        if (frames != null) detail.put("stacktrace", JSONObject().put("type", "raw").put("frames", frames))
        event.getJSONObject("properties").put("\$exception_list", JSONArray().put(detail))
            .put("\$exception_fingerprint", event.getString("fingerprint"))
    }

    private fun errorEvent(error: Throwable): JSONObject {
        val type = MobileTelemetryPolicy.symbol(error.javaClass.name)
        val frames = JSONArray()
        error.stackTrace.take(20).reversed().forEach { frame ->
            frames.put(JSONObject().put("platform", "custom").put("lang", "java")
                .put("function", MobileTelemetryPolicy.symbol("${frame.className}.${frame.methodName}"))
                .put("lineno", frame.lineNumber.coerceAtLeast(0))
                .put("in_app", frame.className.startsWith("com.manjugroups.")))
        }
        val report = event("\$exception", JSONObject().put("screen", screen),
            "crash $type ${frames.optJSONObject(frames.length() - 1)?.optString("function")}")
        exception(report, type, false, frames)
        return report
    }

    private fun submit(block: () -> Unit): Boolean = ::app.isInitialized &&
        runCatching { worker.execute { runCatching(block) }; true }.getOrDefault(false)

    private fun load() {
        if (loaded) return
        loaded = true
        val prefs = app.getSharedPreferences("mobile_posthog", Context.MODE_PRIVATE)
        anonymousId = prefs.getString("anonymous_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("anonymous_id", it).apply()
        }
        budget = MobileTelemetryBudget(prefs.getLong("day", -1), prefs.getInt("errors", 0),
            prefs.getInt("timings", 0), prefs.getInt("exceptions", 0))
        retryAt = prefs.getLong("retry_at", 0)
        val file = File(app.filesDir, QUEUE_FILE)
        if (file.exists() && file.length() <= 512_000) runCatching {
            val saved = JSONArray(file.readText())
            for (index in 0 until minOf(saved.length(), MobileTelemetryPolicy.MAX_QUEUE)) {
                val item = saved.optJSONObject(index) ?: continue
                if (System.currentTimeMillis() - item.optLong("occurredAt") in 0..MobileTelemetryPolicy.MAX_AGE_MS) pending.add(item)
            }
        }
    }

    private fun add(report: JSONObject, timing: Boolean, healthy: Boolean) {
        load()
        val key = report.getString("fingerprint")
        if (pending.any { it.optString("fingerprint") == key }) return
        if (!budget.accept(key, timing, healthy, System.currentTimeMillis(), report.optString("event") == "\$exception")) return
        app.getSharedPreferences("mobile_posthog", Context.MODE_PRIVATE).edit()
            .putLong("day", budget.day).putInt("errors", budget.errors).putInt("timings", budget.timings)
            .putInt("exceptions", budget.exceptions).apply()
        if (pending.size >= MobileTelemetryPolicy.MAX_QUEUE) pending.removeAt(0)
        pending.add(report)
        persist()
        schedule()
    }

    private fun persist() {
        val file = AtomicFile(File(app.filesDir, QUEUE_FILE))
        val stream = file.startWrite()
        try { stream.write(JSONArray(pending).toString().toByteArray()); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream) }
    }

    private fun schedule() {
        if (scheduled || pending.isEmpty()) return
        scheduled = true
        val delay = maxOf(MobileTelemetryPolicy.FLUSH_MS, retryAt - System.currentTimeMillis(),
            lastAttempt + MobileTelemetryPolicy.FLUSH_MS - SystemClock.elapsedRealtime())
        timer.schedule({
            if (!submit { scheduled = false; send() }) scheduled = false
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun send() {
        load()
        pending.removeAll { System.currentTimeMillis() - it.optLong("occurredAt") > MobileTelemetryPolicy.MAX_AGE_MS }
        if (pending.isEmpty() || !hasInternet() || System.currentTimeMillis() < retryAt) return
        lastAttempt = SystemClock.elapsedRealtime()
        val batch = pending.take(MobileTelemetryPolicy.BATCH_SIZE)
        val events = batch.map { stored ->
            val properties = JSONObject(stored.getJSONObject("properties").toString())
                .put("\$process_person_profile", false).put("\$geoip_disable", true)
                .put("platform", "android").put("app_version", BuildConfig.VERSION_NAME)
                .put("build", BuildConfig.VERSION_CODE).put("os_version", Build.VERSION.RELEASE.orEmpty())
            JSONObject().put("event", stored.getString("event")).put("distinct_id", anonymousId)
                .put("uuid", stored.getString("uuid")).put("properties", properties)
                .put("timestamp", Instant.ofEpochMilli(stored.getLong("occurredAt")).toString())
        }
        val body = JSONObject().put("api_key", PostHogConfig.PROJECT_TOKEN).put("batch", JSONArray(events))
        val request = PostHogTransport.request(body.toString())
        var status: Int? = null
        var retryAfter: Long? = null
        val ok = runCatching {
            client.newCall(request).execute().use { response ->
                status = response.code
                retryAfter = response.header("Retry-After")?.toLongOrNull()
                response.isSuccessful
            }
        }.getOrDefault(false)
        if (ok) {
            val sent = batch.map { it.getString("uuid") }.toSet()
            pending.removeAll { it.optString("uuid") in sent }
            failures = 0; retryAt = 0
            persist()
        } else {
            failures++
            retryAt = System.currentTimeMillis() + MobileTelemetryPolicy.retryDelayMs(status, failures, retryAfter)
        }
        app.getSharedPreferences("mobile_posthog", Context.MODE_PRIVATE).edit().putLong("retry_at", retryAt).apply()
        // No recurring jobs/probes. A later real event or foreground resume retries failures.
        if (ok) schedule()
    }

    private fun hasInternet(): Boolean {
        if (!::app.isInitialized) return false
        return runCatching {
            val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            manager.getNetworkCapabilities(manager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }.getOrDefault(false)
    }
}
