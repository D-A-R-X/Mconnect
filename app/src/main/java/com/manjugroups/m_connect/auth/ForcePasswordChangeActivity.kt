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

        when {
            newPassword.length < 8 -> showError("New password must be at least 8 characters")
            newPassword != confirmPassword -> showError("New password and confirmation do not match")
            else -> changePassword(newPassword)
        }
    }

    private fun changePassword(newPassword: String) {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                api.changeOwnPassword(
                    token = session.bearerToken,
                    body = ChangeOwnPasswordRequest(
                        currentPassword = null,
                        newPassword = newPassword
                    )
                )
            }.onSuccess { response ->
                if (!response.success) {
                    showError(response.error ?: "Failed to change password")
                    setLoading(false)
                    return@onSuccess
                }
                session.mustChangePassword = false
                requestNotificationAccessThenContinue()
            }.onFailure {
                showError(it.message ?: "Network error")
                setLoading(false)
            }
        }
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
