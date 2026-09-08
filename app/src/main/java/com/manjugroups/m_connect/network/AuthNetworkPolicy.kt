package com.manjugroups.m_connect.network

internal object AuthNetworkPolicy {
    const val READ_TIMEOUT_SECONDS = 90

    private val mobileEntryPaths = setOf(
        "/api/auth/send-otp",
        "/api/auth/verify-otp",
        "/api/auth/login-with-employee-id",
        "/api/auth/device-binding/recovery/request",
        "/api/auth/device-binding/recovery/confirm",
        "/api/auth/device-binding/recovery/confirm-verified-otp",
    )

    fun usesMobileEntryContract(path: String): Boolean = path in mobileEntryPaths
}
