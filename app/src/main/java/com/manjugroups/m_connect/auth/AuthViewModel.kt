package com.manjugroups.m_connect.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.SendOtpRequest
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskSendOtpRequest
import com.manjugroups.m_connect.network.TravelDeskVerifyOtpRequest
import com.manjugroups.m_connect.network.UserInfo
import com.manjugroups.m_connect.network.VerifyOtpRequest
import com.manjugroups.m_connect.network.VerifyOtpResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

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
    data class Error(val message: String) : AuthUiState
}

private data class ApiErrorResponse(
    val success: Boolean? = null,
    val error: String? = null,
    val message: String? = null
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
        const val AGENCY_DRIVER_DEPARTMENT = "Fleet"
    }

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun sendOtp(phone: String) {
        _uiState.value = AuthUiState.Loading
        if (AuthBypass.matchesPhone(phone)) {
            _uiState.value = AuthUiState.OtpSent("OTP sent (Bypass Mode)")
            return
        }
        viewModelScope.launch {
            try {
                val response = api.sendOtp(SendOtpRequest(phone))
                if (response.success) {
                    _uiState.value = AuthUiState.OtpSent(response.message ?: "OTP sent")
                } else if (isNotRegistered(response.message)) {
                    // Not a staff/agency phone — it may be an agency driver,
                    // whom only the travel-desk auth path knows.
                    sendAgencyDriverOtp(phone)
                } else {
                    _uiState.value = AuthUiState.Error(response.message ?: "Failed to send OTP")
                }
            } catch (e: Exception) {
                if (isNotRegistered(parseErrorMessage(e, ""))) {
                    sendAgencyDriverOtp(phone)
                } else {
                    _uiState.value =
                        AuthUiState.Error(parseErrorMessage(e, "Network error. Please try again."))
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
            val td = travelDeskApi.sendOtp(TravelDeskSendOtpRequest(phone))
            if (td.success) {
                _uiState.value = AuthUiState.OtpSent(td.message ?: "OTP sent", agencyDriver = true)
            } else {
                _uiState.value = AuthUiState.Error(neutralNotRegistered)
            }
        } catch (e: Exception) {
            // Genuine network/connectivity errors keep their real message; a
            // travel-desk 4xx (phone unknown there too) collapses to neutral.
            val message =
                if (e is HttpException) neutralNotRegistered
                else parseErrorMessage(e, neutralNotRegistered)
            _uiState.value = AuthUiState.Error(message)
        }
    }

    /** The MMS auth path returns this exact text for an unknown phone. */
    private fun isNotRegistered(message: String?): Boolean =
        message?.contains("not registered", ignoreCase = true) == true

    fun verifyOtp(phone: String, otp: String, agencyDriver: Boolean = false) {
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
                val response = api.verifyOtp(VerifyOtpRequest(phone, otp))
                if (response.success && response.token != null) {
                    _uiState.value = AuthUiState.Verified(response)
                } else {
                    _uiState.value = AuthUiState.Error(response.error ?: "Invalid OTP")
                }
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(parseErrorMessage(e, "Network error. Please try again."))
            }
        }
    }

    private suspend fun verifyAgencyDriverOtp(phone: String, otp: String) {
        try {
            val td = travelDeskApi.verifyOtp(TravelDeskVerifyOtpRequest(phone, otp))
            if (!td.success || td.token == null) {
                _uiState.value = AuthUiState.Error(td.error ?: "Invalid OTP")
                return
            }
            if (!td.user?.role.equals("driver", ignoreCase = true)) {
                // Agencies belong on the travel-desk web, not the app.
                _uiState.value = AuthUiState.Error(
                    "External fleet agencies sign in on the travel-desk web, not the app."
                )
                return
            }
            // The travel-desk payload carries no designation, so synthesise the
            // one MainActivity keys the stripped driver shell off.
            _uiState.value = AuthUiState.Verified(
                VerifyOtpResponse(
                    success = true,
                    token = td.token,
                    error = null,
                    user = UserInfo(
                        staffId = td.user?.id,
                        name = td.user?.name,
                        phone = td.user?.phone ?: phone,
                        role = "external_fleet_driver",
                        designation = AGENCY_DRIVER_DESIGNATION,
                        department = AGENCY_DRIVER_DEPARTMENT,
                        status = "active",
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
