package com.manjugroups.m_connect.notifications

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.manjugroups.m_connect.network.MobileDialerConfigResponse
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.telecaller.DialerRecentCall
import com.manjugroups.m_connect.ui.telecaller.DialerRecentCallStore
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.manjugroups.m_connect.network.ApiService

object ModernDialerWebViewBridge {
    private const val TAG = "ModernDialer"
    private const val DIALER_ORIGIN = "https://dialer.theairix.com"
    // Origin the host page runs as (the trusted PARENT the embed expects — the
    // same site the web dialer is embedded in). loadDataWithBaseURL gives the
    // host this origin so the iframe sees a proper parent, WebRTC/getUserMedia
    // is allowed (https), and control messages are accepted.
    private const val HOST_BASE_URL = "https://mg.theairix.com"
    private const val JS_INTERFACE = "MconnectDialerBridge"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<(DialerEvent) -> Unit>()
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val restartedMediaCallIds = mutableSetOf<String>()

    private var webView: WebView? = null
    private var loadedUrl: String? = null
    // The web dialer only sends a "call" once the embed page has loaded AND the
    // softphone has registered with Aster (phoneState==="registered"). Sending
    // before registration — which the app used to do on page-load — makes the
    // softphone silently drop the call, so it hangs on "Connecting" and never
    // rings. Track both and only flush queued commands once both are true.
    private var pageLoaded: Boolean = false
    private var phoneRegistered: Boolean = false
    private var pendingCommands = mutableListOf<Pair<String, Map<String, Any>>>()
    private var applicationContext: Context? = null
    private var callActive = false
    private var activeNumber: String? = null
    private var activeProviderCallId: String? = null
    private var callDirection = "outgoing"
    private var callStartedAt: Long? = null
    private var callAnsweredAt: Long? = null
    private var recentCallStore: DialerRecentCallStore? = null
    private val outgoingSetupTimeout = Runnable {
        if (callActive && callDirection == "outgoing" && callAnsweredAt == null) {
            if (pageLoaded && phoneRegistered) evaluateCommand("hangup", emptyMap())
            finishCall("no_answer")
            emit(DialerEvent("call:error", mapOf("message" to "Call timed out")))
        }
    }

    fun isPhoneReady(): Boolean = pageLoaded && phoneRegistered
    fun hasActiveCall(): Boolean = callActive
    fun activeCallNumber(): String? = activeNumber
    fun isCallAnswered(): Boolean = callAnsweredAt != null

    fun addListener(listener: (DialerEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (DialerEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun detachFromActivity(context: Context) {
        val activity = context.findActivity() ?: return
        mainHandler.post {
            val view = webView ?: return@post
            val parent = view.parent as? ViewGroup ?: return@post
            if (parent === activity.window?.decorView) parent.removeView(view)
        }
    }

    fun ensureLoaded(context: Context, config: MobileDialerConfigResponse, onReady: (Result<Unit>) -> Unit = {}) {
        val token = config.mapping?.token?.takeIf { it.isNotBlank() }
        if (token == null) {
            onReady(Result.failure(IllegalStateException("Modern Dialer token missing")))
            return
        }
        val url = buildEmbedUrl(context, token)
        applicationContext = context.applicationContext
        if (recentCallStore == null) {
            recentCallStore = DialerRecentCallStore(context.applicationContext, SessionManager(context).staffId)
        }
        mainHandler.post {
            try {
                val view = webView ?: createWebView(context.applicationContext).also { webView = it }
                // WebRTC (mic capture + audio) only runs when the WebView is
                // attached to a window. Without this the softphone loaded but
                // couldn't establish media, so calls showed "connecting" with
                // no audio and never actually rang the destination. Attach it
                // (invisibly) to the foreground Activity's window whenever we
                // (re)load, and re-attach if the Activity has changed.
                attachToActivityWindow(context, view)
                if (loadedUrl != url) {
                    pageLoaded = false
                    phoneRegistered = false
                    loadedUrl = url
                    Log.i(TAG, "loading dialer embed (iframe host): $url")
                    // Load a HOST page (origin = HOST_BASE_URL) that embeds the
                    // dialer in an IFRAME and relays postMessages to/from it —
                    // EXACTLY how the working web dialer runs. The embed page is
                    // built to talk to a PARENT window; loading it top-level and
                    // self-posting (the old approach) meant the softphone never
                    // received the call command or reported registration, so
                    // calls hung on "connecting". contentWindow.postMessage from
                    // a trusted parent origin is what makes it respond.
                    view.loadDataWithBaseURL(
                        HOST_BASE_URL,
                        buildHostHtml(url),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
                onReady(Result.success(Unit))
            } catch (e: Exception) {
                onReady(Result.failure(e))
            }
        }
    }

    fun send(type: String, payload: Map<String, Any> = emptyMap()) {
        mainHandler.post {
            // Queue until the page is loaded AND the softphone is registered —
            // matching the web dialer, which waits for phoneState==="registered"
            // before dispatching "call".
            if (!(pageLoaded && phoneRegistered)) {
                pendingCommands.add(type to payload)
                return@post
            }
            evaluateCommand(type, payload)
        }
    }

    private fun maybeFlush() {
        if (pageLoaded && phoneRegistered) flushPending()
    }

    fun call(destination: String): Boolean {
        if (callActive) {
            Log.w(TAG, "ignoring duplicate outbound call while a session is active")
            return false
        }
        pendingCommands.removeAll { it.first == "call" }
        callActive = true
        activeNumber = destination
        activeProviderCallId = null
        callDirection = "outgoing"
        callStartedAt = System.currentTimeMillis()
        callAnsweredAt = null
        mainHandler.removeCallbacks(outgoingSetupTimeout)
        mainHandler.postDelayed(outgoingSetupTimeout, OUTGOING_SETUP_TIMEOUT_MS)
        send("call", mapOf("destination" to destination))
        return true
    }

    fun pickup() {
        send("pickup")
    }

    fun reject() {
        send("hangup")
        scheduleLocalEndFallback("missed")
    }

    fun hangup() {
        send("hangup")
        scheduleLocalEndFallback("completed")
    }

    fun setMuted(muted: Boolean) {
        send("set-muted", mapOf("muted" to muted))
    }

    fun setHold(held: Boolean) {
        send("set-hold", mapOf("held" to held))
    }

    private fun sendStateProbe(type: String) {
        mainHandler.post {
            if (pageLoaded) evaluateCommand(type, emptyMap())
        }
    }

    fun cancelPendingCalls() {
        mainHandler.post {
            pendingCommands.removeAll { it.first == "call" }
            finishCall("failed")
        }
    }

    fun setAgentStatus(status: String) {
        require(status == "available" || status == "break")
        send("set-status", mapOf("status" to status))
    }

    fun requestState() {
        // Registration probes must reach the iframe before registration. Gating
        // them behind phoneRegistered creates a deadlock where mobile remains
        // on "Connecting" forever and a queued call can never be released.
        sendStateProbe("request-state")
        sendStateProbe("get-state")
    }

    fun remoteEnded() {
        mainHandler.post {
            pendingCommands.clear()
            if (pageLoaded && phoneRegistered) evaluateCommand("hangup", emptyMap())
            finishCall("completed")
            emit(DialerEvent("call:ended", emptyMap()))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.offscreenPreRaster = true
            settings.allowContentAccess = true
            settings.allowFileAccess = false
            addJavascriptInterface(AndroidBridge(), JS_INTERFACE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    mainHandler.post {
                        val resources = request?.resources ?: return@post
                        val hasMicPermission = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        val allowed = resources.filter {
                            hasMicPermission && it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        }.toTypedArray()
                        Log.i(TAG, "onPermissionRequest: mic=$hasMicPermission granted=${allowed.size}")
                        if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                    }
                }

                // Forward the softphone page's console output to logcat — the
                // fastest way to see WHY a call doesn't originate (WebRTC /
                // registration / SIP errors live here). Filter: `adb logcat -s
                // ModernDialer`.
                override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                    msg ?: return false
                    Log.i(TAG, "[web] ${msg.messageLevel()} ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    Log.i(TAG, "host page loaded: $url")
                    // The host page's own script relays messages to/from the
                    // iframe — no bridge injection needed here.
                    pageLoaded = true
                    requestState()
                    maybeFlush()
                }
            }
        }
    }

    private fun injectBridge(view: WebView) {
        val script = """
            (function() {
              if (window.__mconnectDialerBridgeInstalled) return;
              window.__mconnectDialerBridgeInstalled = true;
              function forward(data) {
                try {
                  if (data && data.source === 'modern-dialer') {
                    $JS_INTERFACE.postMessage(JSON.stringify(data));
                  }
                } catch (e) {}
              }
              window.addEventListener('message', function(event) { forward(event.data); });
              var originalPostMessage = window.postMessage;
              window.postMessage = function(message, targetOrigin, transfer) {
                forward(message);
                return originalPostMessage.call(window, message, targetOrigin || '*', transfer);
              };
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun flushPending() {
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach { (type, payload) -> evaluateCommand(type, payload) }
    }

    private fun evaluateCommand(type: String, payload: Map<String, Any>) {
        Log.i(TAG, "→ command to softphone: $type $payload")
        val data = mutableMapOf<String, Any>(
            "source" to "modern-dialer-control",
            "type" to type,
        )
        data.putAll(payload)
        val json = gson.toJson(data)
        // Hand the command to the host page, which posts it to the iframe's
        // contentWindow (the dialer) with the trusted parent origin.
        webView?.evaluateJavascript("window.__mdSend($json)", null)
    }

    private fun emit(event: DialerEvent) {
        listeners.forEach { listener -> listener(event) }
    }

    /**
     * Attach the softphone WebView (invisibly) to the foreground Activity's
     * window so WebRTC media works. A detached or fully transparent WebView can
     * be deprioritized by Chromium and lose its real-time media pipeline. Keep
     * a tiny compositor-active host outside the visible content instead.
     * No-op when called from a non-Activity context (e.g. the incoming-call
     * service in the background) — that path needs an overlay-window host.
     */
    private fun attachToActivityWindow(context: Context, view: WebView) {
        val activity = context.findActivity() ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val currentParent = view.parent as? ViewGroup
        if (currentParent === decor) return
        currentParent?.removeView(view)
        view.alpha = 0.01f
        view.translationX = 0f
        view.translationY = 0f
        decor.addView(view, ViewGroup.LayoutParams(2, 2))
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    /** Host page that embeds the dialer in an iframe and relays postMessages —
     *  mirrors the web ModernDialerProvider (iframe + sendDialer/onMessage). */
    private fun buildHostHtml(embedUrl: String): String {
        val safeSrc = embedUrl.replace("\"", "&quot;")
        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#fff}
            iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style></head>
            <body>
            <iframe id="mdframe" src="$safeSrc" allow="microphone; autoplay; camera"></iframe>
            <script>
            (function(){
              var ORIGIN = "$DIALER_ORIGIN";
              var frame = document.getElementById('mdframe');
              // native -> dialer: post the control message to the iframe window.
              window.__mdSend = function(d){
                try { frame.contentWindow.postMessage(d, ORIGIN); }
                catch(e){ console.error('mdSend failed', e); }
              };
              // dialer -> native: relay any 'modern-dialer' event from the iframe.
              window.addEventListener('message', function(ev){
                try {
                  if (ev.origin !== ORIGIN) return;
                  var d = ev.data;
                  if (d && d.source === 'modern-dialer') {
                    $JS_INTERFACE.postMessage(JSON.stringify(d));
                  }
                } catch(e){}
              });
              frame.addEventListener('load', function(){ console.log('md iframe loaded'); });
            })();
            </script>
            </body></html>
        """.trimIndent()
    }

    private fun buildEmbedUrl(context: Context, token: String): String {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "mconnect-android"
        return "$DIALER_ORIGIN/embed/agent?token=${urlEncode(token)}&deviceId=${urlEncode(deviceId)}"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /**
     * Update the softphone registration state from a dialer event, then flush
     * any queued command (e.g. the outbound "call") once registered. Mirrors the
     * web dialer's phoneState handling: a "ready" event implies registered
     * unless phoneState says otherwise; "phone:registered"/"phone:state" refine
     * it. Runs on the main thread (touches the WebView).
     */
    private fun onDialerMessage(type: String, parsed: Map<String, Any>) {
        Log.i(TAG, "← event from softphone: $type (registered=$phoneRegistered) $parsed")
        when (type) {
            "call:incoming" -> {
                callActive = true
                activeNumber = (parsed["from"] as? String) ?: (parsed["fromNumber"] as? String)
                callDirection = "incoming"
                callStartedAt = System.currentTimeMillis()
                callAnsweredAt = null
            }
            "call:ringing-out", "call:picked-up" -> {
                callActive = true
                rememberProviderCallId(parsed)
            }
            "call:channel" -> rememberProviderCallId(parsed)
            "call:progress" -> {
                callActive = true
                rememberProviderCallId(parsed)
                val status = ((parsed["status"] as? String) ?: (parsed["state"] as? String))
                    ?.lowercase()
                when (status) {
                    "answered", "connected", "in-call", "active" -> {
                        callAnsweredAt = callAnsweredAt ?: System.currentTimeMillis()
                        mainHandler.removeCallbacks(outgoingSetupTimeout)
                        activateCallAudio()
                    }
                    "ended", "completed", "hangup", "hung-up" -> finishCall("completed")
                    "failed", "busy", "rejected", "unavailable" -> finishCall("failed")
                }
            }
            "call:answered" -> {
                callActive = true
                rememberProviderCallId(parsed)
                callAnsweredAt = callAnsweredAt ?: System.currentTimeMillis()
                mainHandler.removeCallbacks(outgoingSetupTimeout)
                activateCallAudio()
            }
            "call:ended" -> {
                if (!eventBelongsToActiveCall(parsed)) {
                    Log.w(TAG, "ignoring stale call:ended event")
                    return
                }
                finishCall("completed")
            }
            "call:error" -> finishCall("failed")
            "call:incoming-suppressed" -> finishCall("missed")
            "media:diagnostic" -> {
                if (!eventBelongsToActiveCall(parsed)) {
                    Log.w(TAG, "ignoring stale media diagnostic")
                    return
                }
                val diagnostic = parsed["diagnostic"] as? Map<*, *>
                val connectionState = diagnostic?.get("connectionState") as? String
                if (connectionState == "failed") {
                    val callId = (parsed["callId"] as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?: activeProviderCallId
                    if (callId.isNullOrBlank()) {
                        emit(
                            DialerEvent(
                                "media:error",
                                mapOf("message" to "Call audio needs recovery, but the call ID is missing"),
                            ),
                        )
                    } else {
                        attemptMediaRestart(callId)
                    }
                }
            }
            "ready" -> {
                val ps = parsed["phoneState"] as? String
                phoneRegistered = ps == null || ps == "registered"
                maybeFlush()
            }
            "phone:registered" -> {
                phoneRegistered = true
                maybeFlush()
            }
            "phone:state", "phone:status" -> {
                val st = (parsed["state"] as? String) ?: (parsed["status"] as? String)
                phoneRegistered = st == "registered"
                if (phoneRegistered) maybeFlush()
            }
            "phone:unregistered" -> phoneRegistered = false
        }
        emit(DialerEvent(type, parsed))
    }

    private fun finishCall(status: String) {
        mainHandler.removeCallbacks(outgoingSetupTimeout)
        if (callActive) {
            val number = activeNumber?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
            val started = callStartedAt ?: System.currentTimeMillis()
            if (number != null) {
                val duration = ((System.currentTimeMillis() - (callAnsweredAt ?: started)) / 1_000L).coerceAtLeast(0)
                val finalStatus = if (status == "completed" && callAnsweredAt == null) {
                    if (callDirection == "incoming") "missed" else "no_answer"
                } else {
                    status
                }
                recentCallStore?.add(DialerRecentCall(number, callDirection, finalStatus, started, duration))
            }
        }
        callActive = false
        activeNumber = null
        activeProviderCallId?.let { restartedMediaCallIds.remove(it) }
        activeProviderCallId = null
        callStartedAt = null
        callAnsweredAt = null
        applicationContext?.let { context ->
            ModernDialerCallController.clearCallNotifications(context)
            context.stopService(android.content.Intent(context, ModernDialerCallService::class.java))
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    audioManager?.clearCommunicationDevice()
                }
                audioManager?.mode = android.media.AudioManager.MODE_NORMAL
            }
        }
    }

    private fun attemptMediaRestart(callId: String) {
        if (!restartedMediaCallIds.add(callId)) {
            return
        }
        val context = applicationContext ?: return
        apiScope.launch {
            val session = SessionManager(context)
            if (!session.isLoggedIn) return@launch
            val api = ApiService.create()
            val deviceId = MobileDialerApiCoordinator.deviceId(context)
            val result = runCatching {
                MobileDialerApiCoordinator.restartMedia(
                    api = api,
                    token = session.bearerToken,
                    callId = callId,
                    reason = "ice_failed",
                    deviceId = deviceId,
                )
            }
            result.onSuccess { restart ->
                if (!restart.success || !restart.iceRestarted) {
                    emitOnMain(
                        DialerEvent(
                            "media:error",
                            mapOf("message" to (restart.error ?: "Call audio could not restart")),
                        ),
                    )
                    return@onSuccess
                }
                emitOnMain(DialerEvent("media:restarting", mapOf("callId" to callId)))
                delay(1_500L)
                val diagnostics = runCatching {
                    api.getMobileDialerMedia(session.bearerToken, callId)
                }.getOrNull()
                val state = diagnostics?.media?.iceConnectionState
                emitOnMain(
                    DialerEvent(
                        "media:diagnostic-server",
                        mapOf(
                            "callId" to callId,
                            "connectionState" to (state ?: "checking"),
                            "candidateType" to (diagnostics?.media?.candidateType ?: "unknown"),
                        ),
                    ),
                )
                requestState()
            }.onFailure { error ->
                emitOnMain(
                    DialerEvent(
                        "media:error",
                        mapOf(
                            "message" to (
                                error.message?.takeIf { it.isNotBlank() }
                                    ?: "Call audio could not restart"
                                ),
                        ),
                    ),
                )
            }
        }
    }

    private fun emitOnMain(event: DialerEvent) {
        mainHandler.post { emit(event) }
    }

    private fun rememberProviderCallId(parsed: Map<String, Any>) {
        val callId = (parsed["callId"] as? String)?.takeIf { it.isNotBlank() } ?: return
        if (activeProviderCallId == null) activeProviderCallId = callId
    }

    private fun eventBelongsToActiveCall(parsed: Map<String, Any>): Boolean {
        val eventCallId = (parsed["callId"] as? String)?.takeIf { it.isNotBlank() }
        val diagnosticAt = ((parsed["diagnostic"] as? Map<*, *>)?.get("at") as? Number)?.toLong()
        return isCurrentDialerEvent(
            callActive = callActive,
            activeProviderCallId = activeProviderCallId,
            eventCallId = eventCallId,
            callStartedAt = callStartedAt,
            diagnosticAt = diagnosticAt,
        )
    }

    /**
     * Let Chromium open its WebRTC capture/playback streams before switching the
     * device into communication mode. On ColorOS, doing this from KEEP_ACTIVE
     * before getUserMedia caused AAudio to be denied and forced a fragile fallback
     * path. The service is already foreground; this action only acquires focus and
     * applies the selected route once the provider confirms the call is answered.
     */
    private fun activateCallAudio() {
        val context = applicationContext ?: return
        val callId = activeNumber?.takeIf { it.isNotBlank() } ?: return
        context.startService(
            android.content.Intent(context, ModernDialerCallService::class.java).apply {
                action = ModernDialerCallController.ACTION_SET_AUDIO_ROUTE
                putExtra(ModernDialerCallController.EXTRA_CALL_ID, callId)
            },
        )
    }

    private fun scheduleLocalEndFallback(status: String) {
        mainHandler.postDelayed({
            if (callActive) finishCall(status)
        }, 3_000L)
    }

    private class AndroidBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            val parsed = runCatching {
                @Suppress("UNCHECKED_CAST")
                ModernDialerWebViewBridge.gson.fromJson(raw, Map::class.java) as Map<String, Any>
            }.getOrNull() ?: return
            val type = parsed["type"] as? String ?: return
            // The JS bridge runs on a background thread; hop to main to touch
            // the WebView / flush commands.
            ModernDialerWebViewBridge.mainHandler.post {
                ModernDialerWebViewBridge.onDialerMessage(type, parsed)
            }
        }
    }

    private const val OUTGOING_SETUP_TIMEOUT_MS = 60_000L
}

data class DialerEvent(
    val type: String,
    val payload: Map<String, Any>,
)

internal fun isCurrentDialerEvent(
    callActive: Boolean,
    activeProviderCallId: String?,
    eventCallId: String?,
    callStartedAt: Long?,
    diagnosticAt: Long?,
): Boolean {
    if (!callActive) return false
    if (
        activeProviderCallId != null &&
        eventCallId != null &&
        activeProviderCallId != eventCallId
    ) return false
    return diagnosticAt == null ||
        callStartedAt == null ||
        diagnosticAt >= callStartedAt - 2_000L
}
