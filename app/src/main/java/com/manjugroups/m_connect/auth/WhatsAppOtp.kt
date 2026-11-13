package com.manjugroups.m_connect.auth

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WhatsApp OTP autofill (Meta "one-tap" and "zero-tap" authentication
 * templates).
 *
 * 1. Before asking the server for an OTP, [sendHandshake] tells WhatsApp this
 *    app is waiting for a code.
 * 2. The login OTP template's button is set to one-tap (or zero-tap) for
 *    package com.manjugroups.mconnect + the Play signing hash. WhatsApp then
 *    shows "Autofill" (one-tap, opens [WhatsAppOtpCodeActivity]) or hands the
 *    code over directly (zero-tap, [WhatsAppOtpCodeReceiver]).
 * 3. [codeFrom] only accepts a code whose `_ci_` PendingIntent was created by
 *    WhatsApp, so no other app can push a code in.
 *
 * Until the template is changed, WhatsApp keeps showing "Copy code"; the OTP
 * screen then picks the copied code up when the app comes back to the front.
 */
object WhatsAppOtp {

    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    private const val ACTION_OTP_REQUESTED = "com.whatsapp.otp.OTP_REQUESTED"
    const val ACTION_OTP_RETRIEVED = "com.whatsapp.otp.OTP_RETRIEVED"
    private const val EXTRA_CALLER_INFO = "_ci_"
    private const val EXTRA_CODE = "code"

    /** Tells installed WhatsApp apps that an OTP is about to be requested. */
    fun sendHandshake(context: Context) {
        val app = context.applicationContext
        for (pkg in WHATSAPP_PACKAGES) {
            if (!isInstalled(app, pkg)) continue
            runCatching {
                // An empty, immutable PendingIntent identifies this app to
                // WhatsApp (Meta's documented handshake).
                val callerInfo = PendingIntent.getActivity(app, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
                val intent = Intent(ACTION_OTP_REQUESTED).apply {
                    setPackage(pkg)
                    putExtras(Bundle().apply { putParcelable(EXTRA_CALLER_INFO, callerInfo) })
                }
                app.sendBroadcast(intent)
            }
        }
    }

    /** The 6-digit code in a WhatsApp OTP intent, or null when it isn't from WhatsApp. */
    fun codeFrom(intent: Intent?): String? {
        if (intent?.action != ACTION_OTP_RETRIEVED) return null
        val callerInfo: PendingIntent? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_CALLER_INFO, PendingIntent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_CALLER_INFO)
            }
        }.getOrNull()
        if (callerInfo?.creatorPackage !in WHATSAPP_PACKAGES) return null
        val code = intent.getStringExtra(EXTRA_CODE) ?: return null
        return OtpCodeParser.extract(code)
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }
    }.isSuccess
}

/**
 * Hands a code from outside the OTP screen (WhatsApp one-tap / zero-tap) to the
 * OTP screen while it is open. Codes older than [MAX_AGE_MS] are ignored.
 */
object OtpCodeInbox {
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    data class Delivery(val code: String, val at: Long)

    private val _latest = MutableStateFlow<Delivery?>(null)
    val latest: StateFlow<Delivery?> = _latest.asStateFlow()

    /** True while an OTP screen is showing and waiting for a code. */
    @Volatile
    var otpScreenOpen: Boolean = false

    fun post(code: String) {
        _latest.value = Delivery(code, System.currentTimeMillis())
    }

    /** Takes the pending code once; null when there is none or it is stale. */
    fun take(): String? {
        val delivery = _latest.value ?: return null
        _latest.value = null
        return delivery.code.takeIf { System.currentTimeMillis() - delivery.at <= MAX_AGE_MS }
    }
}

/** One-tap: WhatsApp's "Autofill" button opens this; it passes the code on and closes. */
class WhatsAppOtpCodeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = WhatsAppOtp.codeFrom(intent)
        if (code != null) {
            OtpCodeInbox.post(code)
            if (OtpCodeInbox.otpScreenOpen) {
                // Bring the waiting OTP screen back; it verifies the code.
                startActivity(
                    Intent(this, OtpActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
        }
        finish()
    }
}

/** Zero-tap: WhatsApp delivers the code straight to the app. */
class WhatsAppOtpCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WhatsAppOtp.codeFrom(intent)?.let(OtpCodeInbox::post)
    }
}
