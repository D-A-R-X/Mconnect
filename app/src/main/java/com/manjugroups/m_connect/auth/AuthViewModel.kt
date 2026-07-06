package com.manjugroups.m_connect.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.SendOtpRequest
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
    data class OtpSent(val message: String) : AuthUiState
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
    private val gson = Gson()

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
                } else {
                    _uiState.value = AuthUiState.Error(response.message ?: "Failed to send OTP")
                }
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(parseErrorMessage(e, "Network error. Please try again."))
            }
        }
    }

    fun verifyOtp(phone: String, otp: String) {
        _uiState.value = AuthUiState.Loading
        if (AuthBypass.matches(phone, otp)) {
            _uiState.value = AuthUiState.Verified(AuthBypass.syntheticVerifyResponse(phone))
            return
        }
        viewModelScope.launch {
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
