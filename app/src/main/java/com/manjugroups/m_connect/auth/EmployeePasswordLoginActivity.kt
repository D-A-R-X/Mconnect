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
import kotlinx.coroutines.launch
import retrofit2.HttpException

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
        lifecycleScope.launch {
            syncPushTokenIfPossible()
            goNext()
        }
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
    }

    private fun submit() {
        val employeeId = binding.etEmployeeId.text.toString().trim()
        val password = binding.etEmployeePassword.text.toString()
        binding.tvEmployeeLoginError.visibility = View.GONE

        when {
            employeeId.isBlank() -> showError("Enter your Employee ID")
            password.isBlank() -> showError("Enter your password")
            else -> login(employeeId, password)
        }
    }

    private fun login(employeeId: String, password: String) {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                api.loginWithEmployeeId(
                    com.manjugroups.m_connect.network.EmployeePasswordLoginRequest(
                        employeeId = employeeId,
                        password = password
                    )
                )
            }.onSuccess { response ->
                if (!response.success || response.token.isNullOrBlank() || response.user == null) {
                    showError(response.error ?: "Login failed")
                    setLoading(false)
                    return@onSuccess
                }
                bootstrapSession(response)
            }.onFailure {
                showError(parseErrorMessage(it, "Unable to sign in"))
                setLoading(false)
            }
        }
    }

    private fun bootstrapSession(response: EmployeePasswordLoginResponse) {
        lifecycleScope.launch {
            val user = response.user!!
            session.saveSession(
                token = response.token!!,
                name = user.name,
                phone = user.phone
            )
            session.staffId = user.staffId
            session.employeeId = user.employeeId
            session.mustChangePassword = response.mustChangePassword || user.mustChangePassword
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

            // Backend fleet-driver probe — see OtpActivity for context.
            // Lets the app honour the backend's "fleetDrivers row by
            // phone" path even when designation isn't literally "Driver".
            session.fleetDriverByBackend = runCatching {
                geoApi.getMmsFleetDriverTrips(session.bearerToken)
            }.map { it.success }.getOrDefault(false)

            runCatching {
                api.getMyIamPermissions(session.bearerToken)
            }.onSuccess { iam ->
                session.iamPermissions = iam.permissions.toSet()
                session.isAdmin = iam.isAdmin
                session.role = iam.role
            }

            user.staffId?.takeIf { it.isNotBlank() }?.let { staffId ->
                runCatching {
                    api.getStaffDetail(session.bearerToken, staffId)
                }.onSuccess { resp ->
                    resp.staff?.let { staff ->
                        session.reportingToId = staff.reportingTo
                        session.reportingToName = staff.reportingToName
                        // Refresh designation only if the staff-detail
                        // call returned a non-blank value; otherwise
                        // keep the good value already cached from the
                        // login payload above.
                        staff.designation?.takeIf { it.isNotBlank() }?.let {
                            session.designation = it
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
        lifecycleScope.launch {
            syncPushTokenIfPossible()
            goNext()
        }
    }

    private suspend fun syncPushTokenIfPossible() {
        runCatching {
            PushTokenManager.syncCurrentToken(this@EmployeePasswordLoginActivity, session)
        }
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
        binding.tvEmployeeLoginError.visibility = View.VISIBLE
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

    private data class EmployeeLoginErrorResponse(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null
    )
}
