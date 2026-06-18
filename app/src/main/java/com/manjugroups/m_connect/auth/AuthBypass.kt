package com.manjugroups.m_connect.auth

import com.manjugroups.m_connect.network.UserInfo
import com.manjugroups.m_connect.network.VerifyOtpResponse

// Dev-only super admin shortcut. When the user enters [PHONE] and OTP [OTP],
// AuthViewModel skips the network round-trip and emits a synthetic verified
// response so a developer can reach the post-login shell without a working
// OTP delivery path. The fake [TOKEN] will not authenticate against the real
// backend, so any screen that hits an authed endpoint will see 401s — this
// shortcut is only for UI/navigation exploration.
object AuthBypass {
    const val PHONE = "8807588547"
    const val PHONE_TEST = "9000000001"
    const val OTP = "000000"
    const val TOKEN = "dev-super-admin-bypass"
    const val NAME = "Super Admin"
    const val STAFF_ID = "dev-super-admin"
    const val ROLE = "Super Admin"

    fun matchesPhone(phone: String): Boolean = phone == PHONE || phone == PHONE_TEST

    fun matches(phone: String, otp: String): Boolean =
        (phone == PHONE || phone == PHONE_TEST) && otp == OTP

    fun isBypassToken(token: String?): Boolean = token == TOKEN

    fun syntheticVerifyResponse(matchedPhone: String = PHONE): VerifyOtpResponse = VerifyOtpResponse(
        success = true,
        token = TOKEN,
        user = UserInfo(
            staffId = STAFF_ID,
            name = NAME,
            role = ROLE,
            phone = matchedPhone,
            geoTrackingEnabled = false
        ),
        error = null
    )
}
