package com.manjugroups.m_connect.auth

/**
 * Pulls a login OTP out of whatever reached the OTP boxes: an SMS body (SMS
 * User Consent), a keyboard OTP suggestion, or a paste.
 */
internal object OtpCodeParser {
    const val OTP_LENGTH = 6

    private val standaloneCode = Regex("(?<!\\d)(\\d{$OTP_LENGTH})(?!\\d)")

    /**
     * The first standalone 6-digit run in [text], or null.
     *
     * "Standalone" matters for SMS bodies: a phone number or an amount must not
     * be mistaken for the code, so a longer digit run never matches.
     */
    fun extract(text: CharSequence?): String? =
        text?.let { standaloneCode.find(it)?.groupValues?.get(1) }

    /** Digits only, for text inserted into a box (keyboard fill or paste). */
    fun digits(text: CharSequence?): String = text?.filter(Char::isDigit)?.toString().orEmpty()
}
