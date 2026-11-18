package com.manjugroups.m_connect.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Listens for the login OTP SMS from the moment "Send OTP" is tapped.
 *
 * SMS Retriever (zero-tap) and SMS User Consent only catch an SMS that arrives
 * after they start. They used to start when the OTP screen opened, which came
 * after the send-otp reply, so a fast SMS landed during the "Sending OTP"
 * spinner and was missed. Started from the login screen with the application
 * context, a zero-tap code goes to [OtpCodeInbox] and a consent prompt is kept
 * in [pendingConsent] until the OTP screen shows it.
 */
object OtpSmsListener {

    private val _pendingConsent = MutableStateFlow<Intent?>(null)

    /** Google Play services' "Allow" prompt for an OTP SMS, waiting for the OTP screen. */
    val pendingConsent: StateFlow<Intent?> = _pendingConsent.asStateFlow()

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != SmsRetriever.SMS_RETRIEVED_ACTION) return
            val extras = intent.extras ?: return
            @Suppress("DEPRECATION")
            val status = extras.get(SmsRetriever.EXTRA_STATUS) as? Status ?: return
            if (status.statusCode != CommonStatusCodes.SUCCESS) return
            // Zero-tap (SMS Retriever): the SMS ends in this app's hash and
            // comes with its text, no prompt.
            extras.getString(SmsRetriever.EXTRA_SMS_MESSAGE)?.let { message ->
                OtpCodeParser.extract(message)?.let(OtpCodeInbox::post)
                return
            }
            @Suppress("DEPRECATION")
            val consent = extras.getParcelable<Intent>(SmsRetriever.EXTRA_CONSENT_INTENT) ?: return
            _pendingConsent.value = consent
        }
    }

    /** Starts (or restarts, for a resend) both listeners for the next five minutes. */
    fun start(context: Context) {
        val app = context.applicationContext
        _pendingConsent.value = null
        runCatching { SmsRetriever.getClient(app).startSmsRetriever() }
        runCatching { SmsRetriever.getClient(app).startSmsUserConsent(null) }
        if (!registered) {
            runCatching {
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(SmsRetriever.SMS_RETRIEVED_ACTION),
                    SmsRetriever.SEND_PERMISSION,
                    null,
                    ContextCompat.RECEIVER_EXPORTED,
                )
                registered = true
            }
        }
    }

    /** Takes the waiting consent prompt once. */
    fun takeConsent(): Intent? {
        val intent = _pendingConsent.value ?: return null
        _pendingConsent.value = null
        return intent
    }
}
