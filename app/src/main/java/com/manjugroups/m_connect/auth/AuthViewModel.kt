package com.manjugroups.m_connect.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DeviceBindingRecoveryConfirmResponse
import com.manjugroups.m_connect.network.SendOtpRequest
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskSendOtpRequest
import com.manjugroups.m_connect.network.TravelDeskVerifyOtpRequest
import com.manjugroups.m_connect.network.UserInfo
import com.manjugroups.m_connect.network.VerifyOtpRequest
import com.manjugroups.m_connect.network.VerifyOtpResponse
import com.manjugroups.m_connect.network.VerifiedOtpDeviceRecoveryConfirmRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.net.SocketTimeoutException

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    /**
     * @param agencyDriver the OTP went out on the travel-desk path (an agency
     * driver), so verify must be routed there too — the MMS OTP store won't
     * have it.
     */
    data class OtpSent(val message: String, val agencyDriver: Boolean = false) : AuthUiState
    data class Verified(val response: VerifyOtpResponse) : AuthUiState
    data class OtpDeviceRecoveryRequired(
        val message: String,
        val recoveryToken: String,
        val expiresInSeconds: Int,
        val deviceInfo: LoginDeviceInfo,
    ) : AuthUiState
    data class DeviceLinkedToAnotherAccount(
        val message: String = "This phone is already linked to another staff account. Sign in with that account or contact admin.",
    ) : AuthUiState
    data class Error(val message: String) : AuthUiState
}

private data class ApiErrorResponse(
    val success: Boolean? = null,
    val error: String? = null,
    val message: String? = null,
    val code: String? = null,
    val boundAccountName: String? = null,
)

class AuthViewModel : ViewModel() {

    private val api = ApiService.create()
    private val travelDeskApi = TravelDeskApi.create()
    private val gson = Gson()

    // The designation the backend would synthesise for an agency driver if the
    // MMS auth branch were live. We stamp it locally so MainActivity routes a
    // travel-desk driver login into the stripped agency-driver shell.
    private companion object {
        const val AGENCY_DRIVER_DESIGNATION = "External Fleet Driver"
        const val AGENCY_STAFF_DESIGNATION = "External Fleet Staff"
        const val AGENCY_DRIVER_DEPARTMENT = "Fleet"
        const val DEVICE_LINKED_TO_ANOTHER_ACCOUNT = "DEVICE_BOUND_TO_ANOTHER_ACCOUNT"
    }

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun sendOtp(phone: String, deviceInfo: LoginDeviceInfo? = null) {
        _uiState.value = AuthUiState.Loading
        if (AuthBypass.matchesPhone(phone)) {
            _uiState.value = AuthUiState.OtpSent("OTP sent (Bypass Mode)")
            return
        }
        viewModelScope.launch {
            try {
                val response = withAuthInitialConnectionRetry {
                    api.sendOtp(
                        SendOtpRequest(
                            phone = phone,
                            deviceId = deviceInfo?.deviceId,
                            devicePlatform = deviceInfo?.platform,
                            deviceModel = deviceInfo?.model,
                        )
                    )
                }
                if (otpDispatchWasAcknowledged(response.success, response.message, response.error)) {
                    _uiState.value = AuthUiState.OtpSent(response.message ?: "OTP sent")
                } else if (response.code.equals(DEVICE_LINKED_TO_ANOTHER_ACCOUNT, ignoreCase = true)) {
                    _uiState.value = deviceAccountConflict(response.boundAccountName)
                } else if (isNotRegistered(response.error ?: response.message)) {
                    // Not a staff/agency phone — it may be an agency driver,
                    // whom only the travel-desk auth path knows.
                    sendAgencyDriverOtp(phone)
                } else {
                    _uiState.value = AuthUiState.Error(response.error ?: response.message ?: "Failed to send OTP")
                }
            } catch (e: Exception) {
                // Parse ONCE — an HttpException's error body is a one-shot stream,
                // so calling parseErrorMessage twice leaves the second read empty
                // and collapses a real reason (e.g. "Your account is inactive.
                // Contact admin.") into the generic 4xx message.
                if (e is HttpException) {
                    val body = e.response()?.errorBody()?.string()
                    val decoded = runCatching {
                        gson.fromJson(body, ApiErrorResponse::class.java)
                    }.getOrNull()
                    if (decoded?.code.equals(DEVICE_LINKED_TO_ANOTHER_ACCOUNT, ignoreCase = true)) {
                        _uiState.value = deviceAccountConflict(decoded?.boundAccountName)
                        return@launch
                    }
                    if (otpDispatchWasAcknowledged(decoded?.success, decoded?.message, decoded?.error)) {
                        _uiState.value = AuthUiState.OtpSent(
                            decoded?.message ?: decoded?.error ?: "OTP sent",
                        )
                        return@launch
                    }
                    val parsed = decoded?.error ?: decoded?.message
                        ?: EmployeeLoginErrorParser.message(e.code(), body, "Unable to send OTP")
                    if (isNotRegistered(parsed)) {
                        sendAgencyDriverOtp(phone)
                    } else {
                        _uiState.value = AuthUiState.Error(parsed)
                    }
                    return@launch
                }
                if (otpRequestMayHaveReachedServer(e)) {
                    _uiState.value = AuthUiState.OtpSent(
                        "OTP request submitted. Enter the code if received, or use Resend.",
                    )
                    return@launch
                }
                val parsed = parseErrorMessage(e, "Network error. Please try again.")
                if (isNotRegistered(parsed)) {
                    sendAgencyDriverOtp(phone)
                } else {
                    _uiState.value = AuthUiState.Error(parsed)
                }
            }
        }
    }

    private suspend fun sendAgencyDriverOtp(phone: String) {
        // The travel-desk path is only a LAST-RESORT fallback (external agency
        // drivers). Reaching here just means MMS didn't recognise the phone —
        // the person could be ordinary field / office staff who simply isn't
        // registered yet, NOT necessarily a driver. So if travel-desk ALSO
        // rejects the phone, show a neutral "contact admin" message instead of
        // its driver-specific "ask your agency to add you as a driver" text.
        val neutralNotRegistered = "Phone number not registered. Contact admin."
        try {
            val td = withAuthInitialConnectionRetry {
                travelDeskApi.sendOtp(TravelDeskSendOtpRequest(phone))
            }
            if (otpDispatchWasAcknowledged(td.success, td.message, td.error)) {
                _uiState.value = AuthUiState.OtpSent(td.message ?: "OTP sent", agencyDriver = true)
            } else {
                _uiState.value = AuthUiState.Error(neutralNotRegistered)
            }
        } catch (e: Exception) {
            // Genuine network/connectivity errors keep their real message; a
            // travel-desk 4xx (phone unknown there too) collapses to neutral.
            if (otpRequestMayHaveReachedServer(e)) {
                _uiState.value = AuthUiState.OtpSent(
                    "OTP request submitted. Enter the code if received, or use Resend.",
                    agencyDriver = true,
                )
            } else {
                val message =
                    if (e is HttpException) neutralNotRegistered
                    else parseErrorMessage(e, neutralNotRegistered)
                _uiState.value = AuthUiState.Error(message)
            }
        }
    }

    /** The MMS auth path returns this exact text for an unknown phone. */
    private fun isNotRegistered(message: String?): Boolean =
        message?.contains("not registered", ignoreCase = true) == true

    private fun deviceAccountConflict(accountName: String?): AuthUiState.DeviceLinkedToAnotherAccount {
        val owner = accountName?.trim()?.takeIf(String::isNotEmpty)
        return AuthUiState.DeviceLinkedToAnotherAccount(
            if (owner != null) {
                "This phone is already linked to $owner. Sign in with that account or contact admin."
            } else {
                "This phone is already linked to another staff account. Sign in with that account or contact admin."
            }
        )
    }

    fun verifyOtp(
        phone: String,
        otp: String,
        agencyDriver: Boolean = false,
        deviceInfo: LoginDeviceInfo? = null,
    ) {
        _uiState.value = AuthUiState.Loading
        if (AuthBypass.matches(phone, otp)) {
            _uiState.value = AuthUiState.Verified(AuthBypass.syntheticVerifyResponse(phone))
            return
        }
        viewModelScope.launch {
            // The OTP was sent on the travel-desk path (agency driver), so its
            // code lives in the travel-desk OTP store, not the MMS one — verify
            // there directly rather than bouncing off an MMS "No OTP found".
            if (agencyDriver) {
                verifyAgencyDriverOtp(phone, otp)
                return@launch
            }
            try {
                val response = withAuthInitialConnectionRetry {
                    api.verifyOtp(
                        VerifyOtpRequest(
                            phone = phone,
                            otp = otp,
                            deviceId = deviceInfo?.deviceId,
                            devicePlatform = deviceInfo?.platform,
                            deviceModel = deviceInfo?.model,
                            batteryPct = deviceInfo?.batteryPct,
                        ),
                    )
                }
                handleOtpVerificationResponse(response, deviceInfo)
            } catch (e: Exception) {
                handleOtpVerificationFailure(e, deviceInfo)
            }
        }
    }

    private fun handleOtpVerificationResponse(
        response: VerifyOtpResponse,
        deviceInfo: LoginDeviceInfo?,
    ) {
        if (response.success && response.token != null) {
            _uiState.value = AuthUiState.Verified(response)
            return
        }
        if (response.code.equals(DEVICE_LINKED_TO_ANOTHER_ACCOUNT, ignoreCase = true)) {
            _uiState.value = deviceAccountConflict(response.boundAccountName)
            return
        }
        val isBound = response.code.equals("DEVICE_BOUND_TO_OTHER_DEVICE", ignoreCase = true)
        val recoveryToken = response.recoveryToken?.trim().orEmpty()
        if (isBound && recoveryToken.isNotEmpty() && deviceInfo != null) {
            _uiState.value = AuthUiState.OtpDeviceRecoveryRequired(
                message = "Your OTP is verified. Please verify this phone to continue.",
                recoveryToken = recoveryToken,
                expiresInSeconds = response.recoveryExpiresInSeconds ?: 300,
                deviceInfo = deviceInfo,
            )
            return
        }
        val message = response.error ?: "Invalid OTP"
        _uiState.value = AuthUiState.Error(
            if (isBound) "$message Use Employee ID sign-in and verify this device." else message
        )
    }

    private fun handleOtpVerificationFailure(error: Exception, deviceInfo: LoginDeviceInfo?) {
        if (error is HttpException) {
            val body = error.response()?.errorBody()?.string()
            val decoded = runCatching {
                gson.fromJson(body, VerifyOtpResponse::class.java)
            }.getOrNull()
            if (decoded?.code.equals(DEVICE_LINKED_TO_ANOTHER_ACCOUNT, ignoreCase = true)) {
                _uiState.value = deviceAccountConflict(decoded?.boundAccountName)
                return
            }
            val isBound = decoded?.code.equals("DEVICE_BOUND_TO_OTHER_DEVICE", ignoreCase = true) ||
                EmployeeLoginErrorParser.isDeviceBound(body)
            val recoveryToken = decoded?.recoveryToken?.trim().orEmpty()
            if (isBound && recoveryToken.isNotEmpty() && deviceInfo != null) {
                _uiState.value = AuthUiState.OtpDeviceRecoveryRequired(
                    message = "Your OTP is verified. Please verify this phone to continue.",
                    recoveryToken = recoveryToken,
                    expiresInSeconds = decoded?.recoveryExpiresInSeconds ?: 300,
                    deviceInfo = deviceInfo,
                )
                return
            }
            val message = decoded?.error
                ?: EmployeeLoginErrorParser.message(error.code(), body, "Unable to verify OTP")
            _uiState.value = AuthUiState.Error(
                if (isBound) "$message Use Employee ID sign-in and verify this device." else message
            )
            return
        }
        _uiState.value = AuthUiState.Error(parseErrorMessage(error, "Network error. Please try again."))
    }

    fun confirmVerifiedOtpDeviceRecovery(
        recoveryToken: String,
        deviceInfo: LoginDeviceInfo,
    ) {
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            runCatching {
                api.confirmVerifiedOtpDeviceRecovery(
                    VerifiedOtpDeviceRecoveryConfirmRequest(
                        recoveryToken = recoveryToken,
                        deviceId = deviceInfo.deviceId,
                        devicePlatform = deviceInfo.platform,
                        deviceModel = deviceInfo.model,
                    )
                )
            }.onSuccess { response ->
                if (response.isUsableRecoverySession()) {
                    _uiState.value = AuthUiState.Verified(
                        VerifyOtpResponse(
                            success = true,
                            token = response.token,
                            user = response.user,
                        )
                    )
                } else {
                    _uiState.value = AuthUiState.Error(
                        response.error ?: "Device recovery failed. Please retry login."
                    )
                }
            }.onFailure { failure ->
                _uiState.value = AuthUiState.Error(
                    parseErrorMessage(failure, "Device recovery failed. Please retry login.")
                )
            }
        }
    }

    private fun DeviceBindingRecoveryConfirmResponse.isUsableRecoverySession(): Boolean =
        success && recovered && !token.isNullOrBlank() && user != null

    private suspend fun verifyAgencyDriverOtp(phone: String, otp: String) {
        try {
            val td = withAuthInitialConnectionRetry {
                travelDeskApi.verifyOtp(TravelDeskVerifyOtpRequest(phone, otp))
            }
            if (!td.success || td.token == null) {
                _uiState.value = AuthUiState.Error(td.error ?: "Invalid OTP")
                return
            }
            val travelDeskRole = td.user?.role?.trim()?.lowercase()
            if (travelDeskRole != "driver" && travelDeskRole != "agency_staff") {
                _uiState.value = AuthUiState.Error(
                    "External fleet agencies sign in on the travel-desk web, not the app."
                )
                return
            }
            val isAgencyStaff = travelDeskRole == "agency_staff"
            _uiState.value = AuthUiState.Verified(
                VerifyOtpResponse(
                    success = true,
                    token = td.token,
                    error = null,
                    user = UserInfo(
                        staffId = td.user?.id,
                        name = td.user?.name,
                        phone = td.user?.phone ?: phone,
                        role = if (isAgencyStaff) "agency_staff" else "external_fleet_driver",
                        designation = if (isAgencyStaff) {
                            AGENCY_STAFF_DESIGNATION
                        } else {
                            AGENCY_DRIVER_DESIGNATION
                        },
                        department = AGENCY_DRIVER_DEPARTMENT,
                        status = "active",
                        canBill = td.user?.canBill == true,
                    ),
                )
            )
        } catch (e: Exception) {
            _uiState.value =
                AuthUiState.Error(parseErrorMessage(e, "Network error. Please try again."))
        }
    }

    fun resetState() {
        _uiState.value = AuthUiState.Idle
    }

    private fun parseErrorMessage(error: Throwable, fallback: String): String {
        if (error is java.net.UnknownHostException) {
            return "No internet connection. Please check your network settings."
        }
        if (error is java.net.SocketTimeoutException) {
            return "The server took too long to respond. Please try again."
        }
        if (error is java.io.IOException) {
            // connect/read failures, TLS, no route, etc.
            return "Couldn't reach the server. Please check your connection and try again."
        }
        if (error is HttpException) {
            // Prefer a structured message from the backend when the body is JSON.
            val body = error.response()?.errorBody()?.string()
            if (!body.isNullOrBlank()) {
                runCatching {
                    gson.fromJson(body, ApiErrorResponse::class.java)
                }.getOrNull()?.let { parsed ->
                    parsed.error?.takeIf { it.isNotBlank() }?.let { return it }
                    parsed.message?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
            // No usable JSON body (a gateway HTML page, empty body, etc.) —
            // map the status code to a human message instead of surfacing the
            // raw "HTTP 502".
            return when (error.code()) {
                500 -> "Something went wrong on our end. Please try again."
                502, 503, 504 -> "The server is temporarily unavailable. Please try again in a moment."
                408, 429 -> "The server is busy right now. Please try again in a moment."
                in 400..499 -> "We couldn't process that request. Please check your details and try again."
                else -> fallback
            }
        }
        // Any other throwable — never leak a raw technical / "HTTP …" message.
        return fallback
    }
}

/** Accepts explicit success plus legacy/gateway envelopes that acknowledge delivery in text. */
internal fun otpDispatchWasAcknowledged(
    success: Boolean?,
    message: String?,
    error: String?,
): Boolean {
    if (success == true) return true
    return listOfNotNull(message, error).any { raw ->
        val text = raw.trim().lowercase()
        val negative = listOf(
            "not sent",
            "failed to send",
            "unable to send",
            "could not send",
            "couldn't send",
        ).any(text::contains)
        !negative && (
            Regex("\\botp(?: has been| was)? sent\\b").containsMatchIn(text) ||
                Regex("\\bverification code(?: has been| was)? sent\\b").containsMatchIn(text)
            )
    }
}

/** A read timeout can happen after the server stored and dispatched the OTP. */
internal fun otpRequestMayHaveReachedServer(error: Throwable): Boolean {
    var current: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is SocketTimeoutException) return true
        current = current.cause
    }
    return false
}
