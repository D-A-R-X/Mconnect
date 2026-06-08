package com.manjugroups.m_connect.auth

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ActivityLoginBinding
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager
    private val viewModel: AuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // Edge-to-edge: keyboard insets aren't auto-applied. Push the form
        // sheet above the keyboard manually so the focused field stays visible.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = maxOf(ime.bottom, sys.bottom))
            insets
        }

        session = SessionManager(this)

        if (session.isLoggedIn) {
            goToNext()
            return
        }

        setupListeners()
        collectState()
    }

    private fun setupListeners() {
        binding.etPhone.setOnFocusChangeListener { _, hasFocus ->
            binding.phoneInputContainer.setBackgroundResource(
                if (hasFocus) R.drawable.bg_auth_input_focused else R.drawable.bg_auth_input
            )
        }

        binding.tvForgotPassword.setOnClickListener {
            Toast.makeText(this, "Forgot password is not enabled yet", Toast.LENGTH_SHORT).show()
        }

        binding.btnEmployeeOption.setOnClickListener {
            startActivity(Intent(this, EmployeePasswordLoginActivity::class.java))
        }

        binding.btnSendOtp.setOnClickListener {
            val phone = normalizePhone(binding.etPhone.text.toString())

            binding.tvPhoneError.visibility = View.GONE
            binding.phoneInputContainer.setBackgroundResource(R.drawable.bg_auth_input)

            when {
                phone.isEmpty() -> showPhoneError(getString(R.string.phone_required))
                !isValidIndianMobile(phone) -> showPhoneError(getString(R.string.phone_invalid))
                else -> viewModel.sendOtp(phone)
            }
        }
    }

    private fun showPhoneError(message: String) {
        binding.tvPhoneError.text = message
        binding.tvPhoneError.visibility = View.VISIBLE
        binding.phoneInputContainer.setBackgroundResource(R.drawable.bg_auth_input_error)
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is AuthUiState.Loading -> {
                            binding.tvSendOtp.visibility = View.INVISIBLE
                            binding.skeletonSendOtp.visibility = View.VISIBLE
                            SkeletonUtils.startSkeletonPulse(binding.skeletonSendOtp)
                            binding.btnSendOtp.isClickable = false
                        }
                        is AuthUiState.OtpSent -> {
                            resetButton()
                            val phone = normalizePhone(binding.etPhone.text.toString())
                            startActivity(Intent(this@LoginActivity, OtpActivity::class.java).apply {
                                putExtra(OtpActivity.EXTRA_PHONE, phone)
                            })
                            viewModel.resetState()
                        }
                        is AuthUiState.Error -> {
                            resetButton()
                            val msg = state.message
                            // Suppress dev-mode "OTP sent" confirmation that the backend
                            // sometimes returns in the error channel.
                            if (!msg.contains("otp sent", ignoreCase = true)) {
                                Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                            viewModel.resetState()
                        }
                        else -> resetButton()
                    }
                }
            }
        }
    }

    private fun resetButton() {
        binding.tvSendOtp.visibility = View.VISIBLE
        SkeletonUtils.stopSkeletonPulse(binding.skeletonSendOtp)
        binding.skeletonSendOtp.visibility = View.GONE
        binding.btnSendOtp.isClickable = true
    }

    private fun goToNext() {
        val next = if (session.mustChangePassword) {
            Intent(this, ForcePasswordChangeActivity::class.java)
        } else {
            Intent(this, MainActivity::class.java)
        }
        startActivity(next)
        finish()
    }

    private fun normalizePhone(input: String): String {
        val digits = input.filter(Char::isDigit)
        return if (digits.length == 12 && digits.startsWith("91")) {
            digits.substring(2)
        } else {
            digits
        }
    }

    private fun isValidIndianMobile(phone: String): Boolean {
        return phone.length == 10 && phone.firstOrNull() in '6'..'9'
    }
}
