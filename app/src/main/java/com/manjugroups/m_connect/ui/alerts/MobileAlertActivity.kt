package com.manjugroups.m_connect.ui.alerts

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.MobileAlert
import com.manjugroups.m_connect.network.MobileStorageFiles
import com.manjugroups.m_connect.notifications.MobileAlerts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One mandatory alert, full screen. Back is blocked and Close stays disabled
 * for the alert's minimum view time (5 s); closing records it as viewed and
 * moves on to the next pending alert, if any.
 */
class MobileAlertActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ALERT = "mobile_alert_json"

        fun intent(context: Context, alert: MobileAlert): Intent =
            Intent(context, MobileAlertActivity::class.java)
                .putExtra(EXTRA_ALERT, MobileAlerts.toJson(alert))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.manjugroups.m_connect.util.FontScale.cap(newBase))
    }

    private lateinit var scroll: NestedScrollView
    private lateinit var tvSentAt: TextView
    private lateinit var tvTitle: TextView
    private lateinit var cardImage: MaterialCardView
    private lateinit var ivImage: ImageView
    private lateinit var webBody: WebView
    private lateinit var progress: ProgressBar
    private lateinit var btnClose: MaterialButton

    private var current: MobileAlert? = null
    private var countdown: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mobile_alert)
        MobileAlerts.screenShowing = true
        scroll = findViewById(R.id.svMobileAlert)
        tvSentAt = findViewById(R.id.tvMobileAlertSentAt)
        tvTitle = findViewById(R.id.tvMobileAlertTitle)
        cardImage = findViewById(R.id.cardMobileAlertImage)
        ivImage = findViewById(R.id.ivMobileAlertImage)
        webBody = findViewById(R.id.wvMobileAlertBody)
        progress = findViewById(R.id.pbMobileAlert)
        btnClose = findViewById(R.id.btnMobileAlertClose)
        setUpWebView()

        // Mandatory: only the Close button (after the minimum view time) ends it.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        btnClose.setOnClickListener { closeCurrent() }

        if (!SessionManager(this).isLoggedIn) {
            finish()
            return
        }
        open(MobileAlerts.fromJson(intent.getStringExtra(EXTRA_ALERT)))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) MobileAlerts.screenShowing = false
    }

    /** From a notification the alert has no body yet: load it from the server. */
    private fun open(alert: MobileAlert?) {
        if (alert != null && !alert.bodyHtml.isNullOrBlank()) {
            show(alert)
            return
        }
        showLoading()
        lifecycleScope.launch {
            val pending = MobileAlerts.fetchPending(this@MobileAlertActivity)
            val match = pending?.firstOrNull { it.deliveryId == alert?.deliveryId } ?: pending?.firstOrNull()
            if (match == null) finish() else show(match)
        }
    }

    private fun showLoading() {
        progress.visibility = View.VISIBLE
        webBody.visibility = View.GONE
        cardImage.visibility = View.GONE
        tvTitle.text = ""
        tvSentAt.text = ""
        setCloseEnabled(false, "Loading…")
    }

    private fun show(alert: MobileAlert) {
        current = alert
        progress.visibility = View.GONE
        scroll.scrollTo(0, 0)
        tvTitle.text = alert.title?.takeIf { it.isNotBlank() } ?: "Alert"
        tvSentAt.text = alert.sentAt?.let {
            SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.getDefault()).format(Date(it))
        }.orEmpty()

        val imageUrl = MobileStorageFiles.resolve(alert.imageUrl)
        if (imageUrl != null) {
            cardImage.visibility = View.VISIBLE
            ivImage.load(imageUrl) {
                addHeader("Authorization", SessionManager(this@MobileAlertActivity).bearerToken)
                crossfade(true)
                listener(onError = { _, _ -> cardImage.visibility = View.GONE })
            }
        } else {
            cardImage.visibility = View.GONE
        }

        webBody.visibility = View.VISIBLE
        webBody.loadDataWithBaseURL(null, page(alert.bodyHtml.orEmpty()), "text/html", "utf-8", null)
        startCountdown(MobileAlerts.minViewSeconds(alert))
    }

    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        if (seconds <= 0) {
            setCloseEnabled(true, "Close")
            return
        }
        countdown = lifecycleScope.launch {
            for (left in seconds downTo 1) {
                setCloseEnabled(false, "You can close in ${left}s")
                delay(1_000)
            }
            setCloseEnabled(true, "Close")
        }
    }

    private fun setCloseEnabled(enabled: Boolean, label: String) {
        btnClose.isEnabled = enabled
        btnClose.alpha = if (enabled) 1f else 0.55f
        btnClose.text = label
    }

    private fun closeCurrent() {
        val alert = current ?: return
        if (!btnClose.isEnabled) return
        MobileAlerts.markViewed(this, alert)
        current = null
        showLoading()
        lifecycleScope.launch {
            val next = MobileAlerts.fetchPending(this@MobileAlertActivity)
                ?.firstOrNull { it.deliveryId != alert.deliveryId }
            if (next == null) finish() else show(next)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        webBody.settings.apply {
            javaScriptEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            textZoom = 100
        }
        webBody.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webBody.isVerticalScrollBarEnabled = false
        webBody.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }
        }
    }

    /** The alert body (already sanitized on the server) with the app's type scale. */
    private fun page(bodyHtml: String): String = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { margin: 0; color: #344054; font-family: sans-serif; font-size: 16px; line-height: 1.55; word-wrap: break-word; }
          h1 { font-size: 24px; line-height: 1.25; color: #101828; margin: 18px 0 8px; }
          h2 { font-size: 20px; line-height: 1.3; color: #101828; margin: 16px 0 6px; }
          h3 { font-size: 17px; line-height: 1.35; color: #101828; margin: 14px 0 6px; }
          p { margin: 8px 0; }
          ul, ol { padding-left: 22px; margin: 8px 0; }
          li { margin: 4px 0; }
          blockquote { margin: 10px 0; padding: 4px 0 4px 12px; border-left: 4px solid #C7D7FE; color: #475467; }
          a { color: #0B61CA; }
          hr { border: none; border-top: 1px solid #EAECF0; margin: 16px 0; }
          code, pre { background: #F2F4F7; border-radius: 6px; padding: 1px 5px; font-size: 14px; }
          pre { padding: 10px; white-space: pre-wrap; }
          body > :first-child { margin-top: 0; }
        </style></head><body>${if (TextUtils.isEmpty(bodyHtml)) "" else bodyHtml}</body></html>
    """.trimIndent()
}
