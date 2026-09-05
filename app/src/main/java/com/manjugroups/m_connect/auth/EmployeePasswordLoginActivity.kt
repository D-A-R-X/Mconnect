package com.manjugroups.m_connect.auth

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.databinding.ActivityEmployeePasswordLoginBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.EmployeePasswordLoginResponse
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.HttpException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class EmployeePasswordLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEmployeePasswordLoginBinding
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val geoApi = com.manjugroups.m_connect.network.GeoTrackApi.create()
    private val gson = Gson()
    private var passwordVisible = false

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        session.notificationPermissionPrompted = true
        goNext()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmployeePasswordLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = maxOf(ime.bottom, sys.bottom))
            insets
        }

        session = SessionManager(this)
        if (session.isLoggedIn) {
            goNext()
            return
        }

        binding.btnTogglePassword.setOnClickListener {
            passwordVisible = !passwordVisible
            binding.etEmployeePassword.inputType = if (passwordVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            binding.etEmployeePassword.setSelection(binding.etEmployeePassword.text?.length ?: 0)
            binding.btnTogglePassword.setImageResource(
                if (passwordVisible) com.manjugroups.m_connect.R.drawable.ic_auth_eye_off
                else com.manjugroups.m_connect.R.drawable.ic_auth_eye
            )
        }

        binding.btnEmployeeLogin.setOnClickListener { submit() }

        // Dismiss a stale error as soon as the user edits either field.
        val clearErrorWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = hideError()
        }
        binding.etEmployeeId.addTextChangedListener(clearErrorWatcher)
        binding.etEmployeePassword.addTextChangedListener(clearErrorWatcher)
    }

    private fun submit() {
        val employeeId = binding.etEmployeeId.text.toString().trim()
        val password = binding.etEmployeePassword.text.toString()
        // Hide the whole banner, not just its text view — hiding the inner
        // TextView alone left an empty red card with a lone icon.
        hideError()

        when {
            employeeId.isBlank() -> showError("Enter your Employee ID")
            password.isBlank() -> showError("Enter your password")
            else -> login(employeeId, password)
        }
    }

    private fun login(employeeId: String, password: String) {
        setLoading(true)
        // Bind this login to the device, same as the OTP path, so the password
        // login can't sidestep the single-device lock.
        val deviceInfo = LoginDeviceInfo.capture(applicationContext)
        val request = com.manjugroups.m_connect.network.EmployeePasswordLoginRequest(
            employeeId = employeeId,
            password = password,
            deviceId = deviceInfo?.deviceId,
            devicePlatform = deviceInfo?.platform,
            deviceModel = deviceInfo?.model,
            batteryPct = deviceInfo?.batteryPct,
        )
        lifecycleScope.launch {
            runCatching {
                try {
                    api.loginWithEmployeeId(request)
                } catch (error: Throwable) {
                    if (!EmployeeLoginRetryPolicy.shouldRetryInitialConnection(error)) throw error
                    delay(EmployeeLoginRetryPolicy.RETRY_DELAY_MS)
                    api.loginWithEmployeeId(request)
                }
            }.onSuccess { response ->
                if (!response.success || response.token.isNullOrBlank() || response.user == null) {
                    showError(response.error ?: "Login failed")
                    setLoading(false)
                    return@onSuccess
                }
                bootstrapSession(response, password)
            }.onFailure {
                showError(parseErrorMessage(it, "Unable to sign in"))
                setLoading(false)
            }
        }
    }

    private fun bootstrapSession(response: EmployeePasswordLoginResponse, verifiedPassword: String) {
        lifecycleScope.launch {
            val user = response.user!!
            session.saveSession(
                token = response.token!!,
                name = user.name,
                phone = user.phone
            )
            session.staffId = user.staffId
            session.employeeId = user.employeeId
            session.role = user.role
            session.isAdmin = user.isAdmin
            session.externalFleetCanBill = user.canBill
            session.mustChangePassword = response.mustChangePassword || user.mustChangePassword
            if (session.mustChangePassword) {
                PendingPasswordChangeCredential.set(verifiedPassword)
            } else {
                PendingPasswordChangeCredential.clear()
            }
            session.geoTrackingEnabled = user.geoTrackingEnabled
            session.geoConsentGiven = false
            session.geoConsentDeclined = false
            session.shouldTrackNow = false
            session.activeTrackingSessionId = null

            // Cache designation from the login response immediately —
            // see OtpActivity.bootstrapSession for the rationale. The
            // old runCatching wrapper around getStaffDetail silently
            // dropped designation whenever the secondary fetch failed,
            // leaving driver-role staff stuck in field-executive mode.
            user.designation?.takeIf { it.isNotBlank() }?.let {
                session.designation = it
            }
            // Department too — separates a Transport driver from a fleet-desk
            // administrator, which MainActivity routes on at startup.
            user.department?.takeIf { it.isNotBlank() }?.let {
                session.department = it
            }

            // These are independent enrichments. Run them together and cap the
            // pre-navigation wait; MainActivity refreshes IAM/push/tracking again,
            // so a slow optional service must never trap a valid login onscreen.
            withTimeoutOrNull(3_000L) {
                coroutineScope {
                    launch {
                        session.fleetDriverByBackend = runCatching {
                            geoApi.getMmsFleetDriverTrips(session.bearerToken)
                        }.map { response ->
                            response.success &&
                                response.diagnostics?.notDriver != true &&
                                response.diagnostics?.reason != "staff_not_driver"
                        }.getOrDefault(false)
                    }
                    launch {
                        runCatching { api.getMyIamPermissions(session.bearerToken) }
                            .onSuccess { iam ->
                                session.iamPermissions = iam.permissions.toSet()
                                session.isAdmin = iam.isAdmin
                                session.role = iam.role
                            }
                    }
                    launch {
                        user.staffId?.takeIf { it.isNotBlank() }?.let { staffId ->
                            runCatching { api.getStaffDetail(session.bearerToken, staffId) }
                                .onSuccess { resp ->
                                    resp.staff?.let { staff ->
                                        session.reportingToId = staff.reportingTo
                                        session.reportingToName = staff.reportingToName
                                        staff.designation?.takeIf { it.isNotBlank() }?.let {
                                            session.designation = it
                                        }
                                        staff.department?.takeIf { it.isNotBlank() }?.let {
                                            session.department = it
                                        }
                                    }
                                }
                        }
                    }
                }
            }

            if (session.mustChangePassword) {
                startActivity(Intent(this@EmployeePasswordLoginActivity, ForcePasswordChangeActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
                return@launch
            }

            requestNotificationAccessThenContinue()
        }
    }

    private fun requestNotificationAccessThenContinue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PushTokenManager.hasNotificationPermission(this)) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        goNext()
    }

    private fun goNext() {
        val next = if (session.mustChangePassword) {
            Intent(this, ForcePasswordChangeActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        next.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(next)
        finish()
    }

    private fun showError(message: String) {
        binding.tvEmployeeLoginError.text = message
        binding.layoutEmployeeLoginError.visibility = View.VISIBLE
    }

    /** Clear a stale error once the user starts fixing their input — leaving a
     *  red alert under fields the user has already edited reads as broken. */
    private fun hideError() {
        binding.layoutEmployeeLoginError.visibility = View.GONE
    }

    private fun setLoading(loading: Boolean) {
        binding.btnEmployeeLogin.isClickable = !loading
        binding.etEmployeeId.isEnabled = !loading
        binding.etEmployeePassword.isEnabled = !loading
        binding.btnTogglePassword.isEnabled = !loading
        binding.tvEmployeeLogin.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.skeletonEmployeeLogin.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) SkeletonUtils.startSkeletonPulse(binding.skeletonEmployeeLogin)
        else SkeletonUtils.stopSkeletonPulse(binding.skeletonEmployeeLogin)
    }

    private fun parseErrorMessage(error: Throwable, fallback: String): String {
        when {
            error.hasCause<UnknownHostException>() ||
                error.hasCause<ConnectException>() ||
                error.hasCause<NoRouteToHostException>() -> {
                return "No network connection. Check your internet and try again."
            }
            error.hasCause<SocketTimeoutException>() -> {
                return "Network connection timed out. Check your internet and try again."
            }
            error.hasCause<java.io.IOException>() -> {
                return "Unable to connect. Check your internet and try again."
            }
        }
        if (error is HttpException) {
            val body = error.response()?.errorBody()?.string()
            if (!body.isNullOrBlank()) {
                runCatching {
                    gson.fromJson(body, EmployeeLoginErrorResponse::class.java)
                }.getOrNull()?.let { parsed ->
                    parsed.error?.takeIf { it.isNotBlank() }?.let { return it }
                    parsed.message?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return error.message ?: fallback
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private data class EmployeeLoginErrorResponse(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null
    )
}

/**
 * The first request after process start can lose DNS/socket establishment on
 * some mobile networks while the same host succeeds immediately afterward.
 * Retry only failures that happen before an HTTP response exists. Credential,
 * validation, timeout and server responses must always reach the user once.
 */
internal object EmployeeLoginRetryPolicy {
    const val RETRY_DELAY_MS = 450L

    fun shouldRetryInitialConnection(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (
                current is UnknownHostException ||
                current is ConnectException ||
                current is NoRouteToHostException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
