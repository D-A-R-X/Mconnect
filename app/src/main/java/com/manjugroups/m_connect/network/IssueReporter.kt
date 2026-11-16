package com.manjugroups.m_connect.network

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.auth.SessionManager
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reports what goes wrong on the phone to Settings → Mobile → Issues, so a
 * failure is known without the staff member having to describe it:
 *
 * - crash: an uncaught exception (saved at crash time, sent on next launch);
 * - http: a server call that failed (4xx except 401, 5xx), with the server's
 *   reply;
 * - network: a call that timed out / couldn't connect while the phone did
 *   have internet (no report when the phone is simply offline);
 * - flow: an error notice the user saw (Floater) — attached to the call that
 *   caused it when there is one, otherwise reported on its own.
 *
 * Every report carries the screen, the last steps (screens opened, calls
 * made, notices shown), build, phone model and network type. Reports wait in
 * a small file queue and go out in batches; the same problem within 10
 * minutes is counted, not sent again. Request bodies and query values are
 * never sent (they can hold OTPs and personal data).
 */
object IssueReporter {

    private const val TAG = "IssueReporter"
    private const val QUEUE_FILE = "mobile_issue_queue.json"
    private const val CRASH_FILE = "mobile_issue_crash.json"
    private const val MAX_QUEUE = 60
    private const val MAX_BREADCRUMBS = 30
    private const val REPEAT_WINDOW_MS = 10 * 60 * 1000L
    private const val LINK_WINDOW_MS = 6_000L
    private const val SEND_DELAY_MS = 8_000L
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000
    private const val REPORT_PATH = "/api/mobile/issues/"

    private lateinit var app: Application
    private val io = Executors.newSingleThreadExecutor { Thread(it, "issue-reporter").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val breadcrumbs = ArrayDeque<String>()
    private val queue = ArrayList<JSONObject>()
    private val lastSentAt = HashMap<String, Long>()
    private var loaded = false
    private var retryAfter = 0L
    private var sendScheduled = false

    private var currentActivity: WeakReference<Activity>? = null
    private val resumedFragments = ArrayList<String>()

    private val sendClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun install(application: Application) {
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { saveCrash(thread, error) }
            previous?.uncaughtException(thread, error)
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as? FragmentActivity)?.supportFragmentManager
                    ?.registerFragmentLifecycleCallbacks(fragmentCallbacks, true)
            }
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                crumb("Opened ${activity.javaClass.simpleName}")
                if (queueSize() > 0) scheduleSend(2_000L)
            }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }
        })
        // A crash from the last run, and anything still queued.
        io.execute {
            loadQueue()
            promoteSavedCrash()
        }
        scheduleSend(5_000L)
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            val name = f.javaClass.simpleName
            if (name.isBlank() || name.startsWith("SupportRequestManager")) return
            synchronized(lock) {
                resumedFragments.remove(name)
                resumedFragments.add(name)
            }
            crumb(if (f is DialogFragment) "Opened sheet $name" else "Opened $name")
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            synchronized(lock) { resumedFragments.remove(f.javaClass.simpleName) }
        }
    }

    /** "MainActivity › CpVisitsFragment › CreateCpVisitBottomSheet". */
    fun currentScreen(): String {
        val activity = currentActivity?.get()?.javaClass?.simpleName
        val fragments = synchronized(lock) { resumedFragments.takeLast(2) }
        return (listOfNotNull(activity) + fragments).joinToString(" › ").ifBlank { "App" }
    }

    fun crumb(text: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(lock) {
            breadcrumbs.addLast("$stamp $text".take(200))
            while (breadcrumbs.size > MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        }
    }

    private fun crumbs(): JSONArray = synchronized(lock) { JSONArray(breadcrumbs.toList()) }

    // ── HTTP ──

    /** Add to every API client (after the build headers, before ApiErrors). */
    val httpInterceptor = Interceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        if (path.startsWith(REPORT_PATH)) return@Interceptor chain.proceed(request)
        val started = SystemClock.elapsedRealtime()
        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            val took = SystemClock.elapsedRealtime() - started
            crumb("${request.method} $path → ${error.javaClass.simpleName} (${took} ms)")
            if (!isCanceled(error) && hasInternet()) reportNetwork(request, error, took)
            throw error
        }
        val took = SystemClock.elapsedRealtime() - started
        crumb("${request.method} $path → ${response.code} (${took} ms)")
        // OkHttp's own 504 "Unsatisfiable Request (only-if-cached)" is the
        // offline cache saying it has nothing saved after the real call
        // failed — not a server reply. The real failure is already reported,
        // so this only made a second issue for the same problem.
        val offlineCacheMiss = request.cacheControl.onlyIfCached ||
            (response.code == 504 && response.message.contains("only-if-cached", ignoreCase = true))
        if (!response.isSuccessful && response.code != 401 && !response.isRedirect && !offlineCacheMiss) {
            val body = runCatching { response.peekBody(4_096).string() }.getOrNull()
            reportHttp(request, response.code, response.message, body, took)
        }
        response
    }

    private fun isCanceled(error: IOException) =
        error.message?.contains("Canceled", ignoreCase = true) == true ||
            error.javaClass.simpleName.contains("Cancel")

    private fun reportHttp(request: Request, code: Int, reason: String, body: String?, took: Long) {
        val endpoint = "${request.method} ${normalizedPath(request.url.encodedPath)}"
        val serverText = ApiErrors.extract(body)
            ?: reason.takeIf { it.isNotBlank() }
            ?: ApiErrors.forStatus(code).text
        enqueue(
            JSONObject()
                .put("kind", "http")
                .put("fingerprint", "$endpoint $code")
                .put("title", "$endpoint → HTTP $code")
                .put("message", "HTTP $code $serverText".take(2_000))
                .put("errorCode", "HTTP $code")
                .put("endpoint", endpoint)
                .put("screen", currentScreen())
                .put("requestMethod", request.method)
                .put("requestUrl", safeUrl(request))
                .put("responseStatus", code)
                .put("durationMs", took)
                .apply { if (!body.isNullOrBlank()) put("responseBody", body.take(3_000)) },
        )
    }

    private fun reportNetwork(request: Request, error: IOException, took: Long) {
        val endpoint = "${request.method} ${normalizedPath(request.url.encodedPath)}"
        val type = error.javaClass.simpleName.ifBlank { "IOException" }
        enqueue(
            JSONObject()
                .put("kind", "network")
                .put("fingerprint", "$endpoint $type")
                .put("title", "$endpoint → $type")
                .put("message", "$type: ${error.message.orEmpty()}".take(2_000))
                .put("errorCode", type)
                .put("endpoint", endpoint)
                .put("screen", currentScreen())
                .put("requestMethod", request.method)
                .put("requestUrl", safeUrl(request))
                .put("durationMs", took)
                .put("stack", stackOf(error).take(4_000)),
        )
    }

    /** Scheme, host and path; query keys only (values can be phones/OTPs). */
    private fun safeUrl(request: Request): String {
        val url = request.url
        val keys = url.queryParameterNames
        val base = "${url.scheme}://${url.host}${url.encodedPath}"
        return if (keys.isEmpty()) base else "$base?${keys.joinToString("&") { "$it=…" }}"
    }

    /** /api/x/k97mw73m5j81reyfb2kvqz8hc98ey4jh → /api/x/:id so repeats group. */
    private fun normalizedPath(path: String): String =
        path.split('/').joinToString("/") { segment ->
            when {
                segment.length >= 16 && segment.any(Char::isDigit) -> ":id"
                segment.isNotEmpty() && segment.all(Char::isDigit) -> ":n"
                else -> segment
            }
        }

    // ── Notices the user saw ──

    /** From Floater: an error the user was shown is a flow that didn't finish. */
    fun onNotice(text: String, kind: String) {
        if (!::app.isInitialized) return
        crumb("Notice ($kind): $text")
        val screen = currentScreen()
        // Same thread as enqueue(), so the failed call is already queued.
        io.execute { linkOrReportNotice(text, kind, screen) }
    }

    private fun linkOrReportNotice(text: String, kind: String, screen: String) {
        loadQueue()
        val serious = kind == "ERROR"
        val linked = synchronized(lock) {
            val now = System.currentTimeMillis()
            queue.lastOrNull {
                (it.optString("kind") == "http" || it.optString("kind") == "network") &&
                    now - it.optLong("occurredAt") <= LINK_WINDOW_MS &&
                    !it.has("userMessage")
            }?.also {
                it.put("userMessage", text.take(500))
                it.put("flow", screen)
            }
        }
        if (linked != null) {
            persist()
            return
        }
        if (!serious) return
        val pattern = text.replace(Regex("\\d+"), "#").take(120)
        addToQueue(baseReport(
            JSONObject()
                .put("kind", "flow")
                .put("fingerprint", "flow $screen $pattern")
                .put("title", "$screen: ${text.take(140)}")
                .put("message", text.take(2_000))
                .put("userMessage", text.take(500))
                .put("errorCode", "Flow failed")
                .put("screen", screen)
                .put("flow", screen),
        ))
        persist()
        scheduleSend(SEND_DELAY_MS)
    }

    // ── Crashes ──

    private fun saveCrash(thread: Thread, error: Throwable) {
        val root = generateSequence(error) { it.cause }.last()
        val appFrame = (error.stackTrace + root.stackTrace)
            .firstOrNull { it.className.startsWith("com.manjugroups") }
            ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: error.stackTrace.firstOrNull()?.toString().orEmpty()
        val type = error.javaClass.simpleName
        val report = baseReport(
            JSONObject()
                .put("kind", "crash")
                .put("fingerprint", "crash $type $appFrame")
                .put("title", "Crash: $type at $appFrame".take(200))
                .put("message", "$type: ${error.message.orEmpty()}".take(2_000))
                .put("errorCode", type)
                .put("screen", currentScreen())
                .put("flow", "Thread ${thread.name}")
                .put("stack", stackOf(error).take(12_000)),
        )
        // The process is about to die: write synchronously, send next launch.
        File(app.filesDir, CRASH_FILE).writeText(report.toString())
    }

    private fun promoteSavedCrash() {
        val file = File(app.filesDir, CRASH_FILE)
        if (!file.exists()) return
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let { addToQueue(it) }
        file.delete()
        persist()
    }

    private fun stackOf(error: Throwable): String =
        StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

    // ── Queue ──

    private fun baseReport(report: JSONObject): JSONObject = report
        .put("occurredAt", System.currentTimeMillis())
        .put("breadcrumbs", crumbs())
        .put("count", 1)

    private fun enqueue(report: JSONObject) {
        if (!::app.isInitialized) return
        val full = baseReport(report)
        io.execute {
            loadQueue()
            addToQueue(full)
            persist()
        }
        scheduleSend(SEND_DELAY_MS)
    }

    private fun addToQueue(report: JSONObject) {
        synchronized(lock) {
            val fingerprint = report.optString("fingerprint")
            // Same problem still waiting to go out: count it instead.
            queue.firstOrNull { it.optString("fingerprint") == fingerprint }?.let { waiting ->
                waiting.put("count", waiting.optInt("count", 1) + 1)
                if (!waiting.has("userMessage") && report.has("userMessage")) {
                    waiting.put("userMessage", report.optString("userMessage"))
                }
                return
            }
            // Sent recently: drop (the server already has a fresh occurrence).
            val sentAt = lastSentAt[fingerprint]
            if (sentAt != null && System.currentTimeMillis() - sentAt < REPEAT_WINDOW_MS) return
            queue.add(report)
            while (queue.size > MAX_QUEUE) queue.removeAt(0)
        }
    }

    private fun queueSize() = synchronized(lock) { queue.size }

    private fun loadQueue() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val file = File(app.filesDir, QUEUE_FILE)
            if (!file.exists()) return
            val now = System.currentTimeMillis()
            runCatching { JSONArray(file.readText()) }.getOrNull()?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (now - item.optLong("occurredAt") <= MAX_AGE_MS) queue.add(item)
                }
            }
        }
    }


    private fun persist() {
        val snapshot = synchronized(lock) { JSONArray(queue.map { JSONObject(it.toString()) }) }
        runCatching { File(app.filesDir, QUEUE_FILE).writeText(snapshot.toString()) }
    }

    private fun scheduleSend(delayMs: Long) {
        synchronized(lock) {
            if (sendScheduled) return
            sendScheduled = true
        }
        main.postDelayed({
            synchronized(lock) { sendScheduled = false }
            io.execute { send() }
        }, delayMs)
    }

    private fun send() {
        if (!::app.isInitialized) return
        loadQueue()
        if (SystemClock.elapsedRealtime() < retryAfter || !hasInternet()) return
        val batch = synchronized(lock) { queue.take(20).map { JSONObject(it.toString()) } }
        if (batch.isEmpty()) return
        val session = runCatching { SessionManager(app) }.getOrNull()
        val body = JSONObject()
            .put("platform", "android")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("buildNumber", BuildConfig.VERSION_CODE)
            .put("deviceModel", deviceModel())
            .put("osVersion", Build.VERSION.RELEASE.orEmpty())
            .put("networkType", networkType())
            .put("reports", JSONArray(batch))
        val request = Request.Builder()
            .url(BuildConfig.BASE_URL.trimEnd('/') + REPORT_PATH + "report")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (session?.isLoggedIn == true) header("Authorization", session.bearerToken) }
            .build()
        val ok = runCatching { sendClient.newCall(request).execute().use { it.isSuccessful } }
            .onFailure { Log.w(TAG, "Issue report not sent: ${it.message}") }
            .getOrDefault(false)
        if (ok) {
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val sent = batch.map { it.optString("fingerprint") }.toSet()
                sent.forEach { lastSentAt[it] = now }
                queue.removeAll { it.optString("fingerprint") in sent }
            }
            persist()
            retryAfter = 0L
            if (queueSize() > 0) scheduleSend(2_000L)
        } else {
            // Server down or route not live yet: try again later, keep the queue.
            retryAfter = SystemClock.elapsedRealtime() + REPEAT_WINDOW_MS
        }
    }

    // ── Device ──

    private fun deviceModel(): String {
        val model = Build.MODEL.orEmpty()
        val maker = Build.MANUFACTURER.orEmpty()
        return if (maker.isBlank() || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
    }

    private fun capabilities(): NetworkCapabilities? {
        if (!::app.isInitialized) return null
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
    }

    private fun hasInternet(): Boolean {
        val caps = capabilities() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun networkType(): String {
        val caps = capabilities() ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}
