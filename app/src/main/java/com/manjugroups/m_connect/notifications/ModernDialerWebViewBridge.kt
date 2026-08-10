package com.manjugroups.m_connect.notifications

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private const val DIALER_ORIGIN = "https://dialer.theairix.com"
    private const val JS_INTERFACE = "MconnectDialerBridge"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<(DialerEvent) -> Unit>()

    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var ready: Boolean = false
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
                if (loadedUrl != url) {
                    ready = false
                    loadedUrl = url
                    view.loadUrl(url)
                }
                if (ready) onReady(Result.success(Unit)) else onReady(Result.success(Unit))
            } catch (e: Exception) {
                onReady(Result.failure(e))
            }
        }
    }

    fun send(type: String, payload: Map<String, Any> = emptyMap()) {
        mainHandler.post {
            if (!ready) {
                pendingCommands.add(type to payload)
                return@post
            }
            evaluateCommand(type, payload)
        }
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
                        if (allowed.isNotEmpty()) request.grant(allowed) else request.deny()
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    injectBridge(view)
                    ready = true
                    emit(DialerEvent("ready", emptyMap()))
                    flushPending()
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

    private fun buildEmbedUrl(context: Context, token: String): String {
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "mconnect-android"
        return "$DIALER_ORIGIN/embed/agent?token=${urlEncode(token)}&deviceId=${urlEncode(deviceId)}"
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private class AndroidBridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            val parsed = runCatching {
                @Suppress("UNCHECKED_CAST")
                ModernDialerWebViewBridge.gson.fromJson(raw, Map::class.java) as Map<String, Any>
            }.getOrNull() ?: return
            val type = parsed["type"] as? String ?: return
            ModernDialerWebViewBridge.emit(DialerEvent(type, parsed))
        }
    }
}

data class DialerEvent(
    val type: String,
    val payload: Map<String, Any>,
)
