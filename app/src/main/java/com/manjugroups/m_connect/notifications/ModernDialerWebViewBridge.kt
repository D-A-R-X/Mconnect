package com.manjugroups.m_connect.notifications

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.ViewGroup
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

object ModernDialerWebViewBridge {
    private const val TAG = "ModernDialer"
    private const val DIALER_ORIGIN = "https://dialer.theairix.com"
    private const val JS_INTERFACE = "MconnectDialerBridge"
    // If the softphone never reports registration, flush queued commands anyway
    // after this long so a call attempt isn't silently stuck "connecting".
    private const val FALLBACK_FLUSH_MS = 6_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<(DialerEvent) -> Unit>()

    private var webView: WebView? = null
    private var loadedUrl: String? = null
    // The web dialer only sends a "call" once the embed page has loaded AND the
    // softphone has registered with Aster (phoneState==="registered"). Sending
    // before registration — which the app used to do on page-load — makes the
    // softphone silently drop the call, so it hangs on "Connecting" and never
    // rings. Track both and only flush queued commands once both are true.
    private var pageLoaded: Boolean = false
    private var phoneRegistered: Boolean = false
    private var fallbackFlushPosted: Boolean = false
    private var pendingCommands = mutableListOf<Pair<String, Map<String, Any>>>()

    fun addListener(listener: (DialerEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (DialerEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun ensureLoaded(context: Context, config: MobileDialerConfigResponse, onReady: (Result<Unit>) -> Unit = {}) {
        val token = config.mapping?.token?.takeIf { it.isNotBlank() }
        if (token == null) {
            onReady(Result.failure(IllegalStateException("Modern Dialer token missing")))
            return
        }
        val url = buildEmbedUrl(context, token)
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
                    fallbackFlushPosted = false
                    loadedUrl = url
                    Log.i(TAG, "loading dialer embed: $url")
                    view.loadUrl(url)
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

    /** Safety net: if the softphone never reports registration, send any queued
     *  command anyway after a short delay so a call isn't stuck "connecting"
     *  forever. Logs when it fires so a stuck registration is visible. */
    private fun scheduleFallbackFlush() {
        if (fallbackFlushPosted) return
        fallbackFlushPosted = true
        mainHandler.postDelayed({
            if (!phoneRegistered && pageLoaded && pendingCommands.isNotEmpty()) {
                Log.w(TAG, "registration not confirmed in ${FALLBACK_FLUSH_MS}ms — flushing ${pendingCommands.size} queued cmd(s) anyway")
                flushPending()
            }
        }, FALLBACK_FLUSH_MS)
    }

    fun call(destination: String) {
        send("call", mapOf("destination" to destination))
    }

    fun pickup() {
        send("pickup")
    }

    fun reject() {
        send("hangup")
    }

    fun hangup() {
        send("hangup")
    }

    fun setMuted(muted: Boolean) {
        send("set-muted", mapOf("muted" to muted))
    }

    fun setHold(held: Boolean) {
        send("set-hold", mapOf("held" to held))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowContentAccess = true
            settings.allowFileAccess = false
            addJavascriptInterface(AndroidBridge(), JS_INTERFACE)
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
                    Log.i(TAG, "page loaded: $url")
                    injectBridge(view)
                    pageLoaded = true
                    // Prefer waiting for the softphone's registration event, but
                    // never stay stuck: if it doesn't arrive, flush anyway.
                    maybeFlush()
                    scheduleFallbackFlush()
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
        val script = """
            (function() {
              var data = $json;
              window.postMessage(data, '$DIALER_ORIGIN');
              window.dispatchEvent(new MessageEvent('message', { data: data, origin: 'mconnect-android' }));
            })();
        """.trimIndent()
        webView?.evaluateJavascript(script, null)
    }

    private fun emit(event: DialerEvent) {
        listeners.forEach { listener -> listener(event) }
    }

    /**
     * Attach the softphone WebView (invisibly) to the foreground Activity's
     * window so WebRTC media works. A detached WebView cannot capture the mic
     * or play audio, so calls placed from it produce no sound and never
     * originate. Uses a 1x1, fully-transparent view so the user never sees it.
     * No-op when called from a non-Activity context (e.g. the incoming-call
     * service in the background) — that path needs an overlay-window host.
     */
    private fun attachToActivityWindow(context: Context, view: WebView) {
        val activity = context.findActivity() ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val currentParent = view.parent as? ViewGroup
        if (currentParent === decor) return
        currentParent?.removeView(view)
        view.alpha = 0f
        decor.addView(view, ViewGroup.LayoutParams(1, 1))
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
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
}

data class DialerEvent(
    val type: String,
    val payload: Map<String, Any>,
)
