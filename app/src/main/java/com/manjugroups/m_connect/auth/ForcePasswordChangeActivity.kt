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
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ActivityForcePasswordChangeBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ChangeOwnPasswordRequest
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

class ForcePasswordChangeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityForcePasswordChangeBinding
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private var newPasswordVisible = false
    private var confirmPasswordVisible = false

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        session.notificationPermissionPrompted = true
        lifecycleScope.launch {
            syncPushTokenIfPossible()
            goMain()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityForcePasswordChangeBinding.inflate(layoutInflater)
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
        if (!session.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        if (!session.mustChangePassword) {
            goMain()
            return
        }

        binding.tvPasswordChangeSubtitle.text =
            "Choose a personal password for Employee ID ${session.employeeId ?: ""}."

        binding.btnToggleNewPassword.setOnClickListener {
            newPasswordVisible = !newPasswordVisible
            setPasswordVisibility(binding.etNewPassword, newPasswordVisible)
            binding.btnToggleNewPassword.setImageResource(
                if (newPasswordVisible) R.drawable.ic_auth_eye_off else R.drawable.ic_auth_eye
            )
        }

        binding.btnToggleConfirmPassword.setOnClickListener {
            confirmPasswordVisible = !confirmPasswordVisible
            setPasswordVisibility(binding.etConfirmPassword, confirmPasswordVisible)
            binding.btnToggleConfirmPassword.setImageResource(
                if (confirmPasswordVisible) R.drawable.ic_auth_eye_off else R.drawable.ic_auth_eye
            )
        }

        binding.btnUpdatePassword.setOnClickListener { submit() }
    }

    private fun setPasswordVisibility(view: android.widget.EditText, visible: Boolean) {
        view.inputType = if (visible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        view.setSelection(view.text?.length ?: 0)
    }

    private fun submit() {
        val newPassword = binding.etNewPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()
        binding.tvPasswordChangeError.visibility = View.GONE

        val ruleError = validateStrongPassword(newPassword)
        when {
            ruleError != null -> showError(ruleError)
            newPassword != confirmPassword -> showError("New password and confirmation do not match")
            else -> changePassword(newPassword)
        }
    }

    /**
     * Strong-password rule, kept verbatim in step order with the web policy
     * (lib/password-policy.ts → validateStrongPassword) so a password that
     * passes here passes server-side too. Surfaced to the user as the helper
     * line "Use 8+ characters with uppercase, lowercase, number, and special
     * character." Returns the first failure message, or null when valid.
     */
    private fun validateStrongPassword(password: String): String? {
        // ASCII alphanumeric, matching the web regex [A-Za-z0-9]; anything
        // else counts as a "special character" exactly as [^A-Za-z0-9] does.
        fun isAsciiAlnum(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'
        return when {
            password.length < 8 -> "Password must be at least 8 characters"
            password.length > 128 -> "Password is too long"
            password.any { it.isWhitespace() } -> "Password cannot contain spaces"
            !password.any { it in 'A'..'Z' } -> "Password must include at least one uppercase letter"
            !password.any { it in 'a'..'z' } -> "Password must include at least one lowercase letter"
            !password.any { it in '0'..'9' } -> "Password must include at least one number"
            !password.any { !isAsciiAlnum(it) } -> "Password must include at least one special character"
            else -> null
        }
    }

    private fun changePassword(newPassword: String) {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                api.changeOwnPassword(
                    token = session.bearerToken,
                    body = ChangeOwnPasswordRequest(
                        currentPassword = PendingPasswordChangeCredential.peek(),
                        newPassword = newPassword
                    )
                )
            }.onSuccess { response ->
                if (!response.success) {
                    val clean = sanitizeServerMessage(response.error)
                    if (isDeadSessionMessage(clean)) {
                        redirectToLoginSessionExpired()
                        return@onSuccess
                    }
                    if (isReauthenticationRequiredMessage(clean)) {
                        restartPasswordLogin()
                        return@onSuccess
                    }
                    showError(clean ?: "Failed to change password")
                    setLoading(false)
                    return@onSuccess
                }
                PendingPasswordChangeCredential.clear()
                session.mustChangePassword = false
                requestNotificationAccessThenContinue()
            }.onFailure {
                // The stored token can be DEAD by the time this screen submits —
                // an admin "Reset Device" or password reset deactivates the
                // mobile session server-side while the app still holds
                // isLoggedIn + mustChangePassword. This screen blocks back
                // navigation, so without this branch the user is permanently
                // stuck on a "Not authenticated" error they can't act on. Clear
                // the dead session and return to login; after re-login the
                // backend still reports mustChangePassword, so they land back
                // here with a token that works.
                val httpCode = (it as? retrofit2.HttpException)?.code()
                val message = sanitizeServerMessage(extractHttpErrorMessage(it))
                if (httpCode == 401 || isDeadSessionMessage(message) || isDeadSessionMessage(it.message)) {
                    redirectToLoginSessionExpired()
                    return@onFailure
                }
                // A process-restored force-change screen may not have the
                // one-time credential captured by EmployeePasswordLoginActivity.
                // Re-authenticate instead of bypassing the password change.
                if (isReauthenticationRequiredMessage(message)) {
                    restartPasswordLogin()
                    return@onFailure
                }
                // The backend returns HTTP 400 with a {error:"…"} body when it
                // rejects the new password (e.g. fails the strength policy on an
                // older app build with no client-side check). Surface that real
                // reason instead of a bare "HTTP 400".
                showError(
                    message
                        ?: it.message?.takeIf { m -> !m.startsWith("HTTP ") }
                        ?: "Couldn't update password. Please try again.",
                )
                setLoading(false)
            }
        }
    }

    /** True when the server message means the stored session token is no longer
     *  valid ("Not authenticated" from requireSession, or the friendlier
     *  "Session expired…" the backend now throws). */
    private fun isDeadSessionMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains("Not authenticated", ignoreCase = true) ||
            message.contains("Session expired", ignoreCase = true)
    }

    /** The server requires the current credential when its force-change state
     *  changed after login or this Activity was restored after process death. */
    private fun isReauthenticationRequiredMessage(message: String?): Boolean =
        message?.contains("Current password is required", ignoreCase = true) == true ||
            message?.contains("Current password is incorrect", ignoreCase = true) == true

    private fun restartPasswordLogin() {
        PendingPasswordChangeCredential.clear()
        runCatching { com.manjugroups.m_connect.ui.common.LocalCache.clearAll(this) }
        session.clearSession()
        android.widget.Toast.makeText(
            this,
            "Please sign in with your current password, then set the new password again.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(this, EmployeePasswordLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    /** The self-hosted Convex backend wraps thrown errors as
     *  "Uncaught Error: <message>\n    at handler (…)" — strip the wrapper and
     *  the stack tail so users see the actual sentence, not a stack trace. */
    private fun sanitizeServerMessage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .removePrefix("Uncaught Error: ")
            .removePrefix("Uncaught ConvexError: ")
            .substringBefore("\n")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /** Wipe the dead session (and per-user disk cache) and restart at login
     *  with a message the user can actually act on. */
    private fun redirectToLoginSessionExpired() {
        PendingPasswordChangeCredential.clear()
        runCatching { com.manjugroups.m_connect.ui.common.LocalCache.clearAll(this) }
        session.clearSession()
        android.widget.Toast.makeText(
            this,
            "Session expired. Please sign in again.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    /**
     * Pull the {error:"…"} (or {message}) field out of a Retrofit HttpException
     * body so the user sees the server's real message ("Password must include
     * at least one uppercase letter", etc.) instead of "HTTP 400".
     */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun requestNotificationAccessThenContinue() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PushTokenManager.hasNotificationPermission(this)) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        lifecycleScope.launch {
            syncPushTokenIfPossible()
            goMain()
        }
    }

    private suspend fun syncPushTokenIfPossible() {
        runCatching {
            PushTokenManager.syncCurrentToken(this@ForcePasswordChangeActivity, session)
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun showError(message: String) {
        binding.tvPasswordChangeError.text = message
        binding.tvPasswordChangeError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.btnUpdatePassword.isClickable = !loading
        binding.etNewPassword.isEnabled = !loading
        binding.etConfirmPassword.isEnabled = !loading
        binding.btnToggleNewPassword.isEnabled = !loading
        binding.btnToggleConfirmPassword.isEnabled = !loading
        binding.tvUpdatePassword.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.skeletonUpdatePassword.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) SkeletonUtils.startSkeletonPulse(binding.skeletonUpdatePassword)
        else SkeletonUtils.stopSkeletonPulse(binding.skeletonUpdatePassword)
    }

    override fun onBackPressed() {
        // Force-change flow blocks navigation until the password is updated.
    }
}

/**
 * Holds the password that was just verified by Employee ID login long enough
 * to complete the mandatory password change. It is never persisted or placed
 * in an Intent, and is erased as soon as the handoff succeeds or is abandoned.
 */
internal object PendingPasswordChangeCredential {
    @Volatile
    private var value: String? = null

    fun set(password: String) {
        value = password
    }

    fun peek(): String? = value

    fun clear() {
        value = null
    }
}
