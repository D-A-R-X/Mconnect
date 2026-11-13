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
import com.manjugroups.m_connect.ui.common.ButtonLoader
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    // Cap extreme system font sizes; see FontScale.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.manjugroups.m_connect.util.FontScale.cap(newBase))
    }

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager
    private val viewModel: AuthViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        // Ask for the old sideloaded Mconnect to be removed before sign-in too.
        com.manjugroups.m_connect.ui.common.OldAppUninstallGate
            .showIfNeeded(supportFragmentManager, this)
        lifecycleScope.launch {
            if (com.manjugroups.m_connect.ui.common.OldAppUninstallGate.refreshPolicy(this@LoginActivity)) {
                com.manjugroups.m_connect.ui.common.OldAppUninstallGate
                    .showIfNeeded(supportFragmentManager, this@LoginActivity)
            }
        }
    }

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

    // Phone Number Hint: Google's sheet with this phone's own SIM number(s).
    // Offered once per screen, on the first tap into an empty phone field.
    private var phoneHintOffered = false
    private val phoneHintLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val number = runCatching {
            com.google.android.gms.auth.api.identity.Identity.getSignInClient(this)
                .getPhoneNumberFromIntent(result.data)
        }.getOrNull() ?: return@registerForActivityResult
        val phone = normalizePhone(number)
        if (isValidIndianMobile(phone)) {
            binding.etPhone.setText(phone)
            binding.etPhone.setSelection(phone.length)
            binding.tvPhoneError.visibility = View.GONE
        }
    }

    private fun offerPhoneNumberHint() {
        if (phoneHintOffered || binding.etPhone.text?.isNotEmpty() == true) return
        phoneHintOffered = true
        val request = com.google.android.gms.auth.api.identity.GetPhoneNumberHintIntentRequest.builder().build()
        com.google.android.gms.auth.api.identity.Identity.getSignInClient(this)
            .getPhoneNumberHintIntent(request)
            .addOnSuccessListener { pendingIntent ->
                runCatching {
                    phoneHintLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                }
            }
        // No SIM / no Play services: the field simply stays as it is.
    }

    private fun setupListeners() {
        binding.etPhone.setOnClickListener { offerPhoneNumberHint() }
        binding.etPhone.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) offerPhoneNumberHint()
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
                else -> {
                    // WhatsApp one-tap / zero-tap autofill needs this before the
                    // OTP is sent; harmless when WhatsApp isn't installed.
                    WhatsAppOtp.sendHandshake(this)
                    viewModel.sendOtp(phone, LoginDeviceInfo.capture(applicationContext))
                }
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
                            ButtonLoader.start(binding.btnSendOtp, "Sending OTP")
                        }
                        is AuthUiState.OtpSent -> {
                            resetButton()
                            openOtpEntry(state.agencyDriver)
                            viewModel.resetState()
                        }
                        is AuthUiState.Error -> {
                            resetButton()
                            val msg = state.message
                            // Defensive compatibility for older gateways that returned a
                            // successful dispatch message inside an error envelope.
                            if (otpDispatchWasAcknowledged(null, msg, null)) {
                                openOtpEntry(agencyDriver = false)
                            } else {
                                Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                            viewModel.resetState()
                        }
                        is AuthUiState.DeviceLinkedToAnotherAccount -> {
                            resetButton()
                            androidx.appcompat.app.AlertDialog.Builder(this@LoginActivity)
                                .setTitle("Device linked to another account")
                                .setMessage(state.message)
                                .setPositiveButton("OK", null)
                                .show()
                            viewModel.resetState()
                        }
                        else -> resetButton()
                    }
                }
            }
        }
    }

    private fun resetButton() {
        ButtonLoader.stop(binding.btnSendOtp)
    }

    private fun openOtpEntry(agencyDriver: Boolean) {
        val phone = normalizePhone(binding.etPhone.text.toString())
        if (!isValidIndianMobile(phone)) {
            showPhoneError(getString(R.string.phone_invalid))
            return
        }
        startActivity(Intent(this, OtpActivity::class.java).apply {
            putExtra(OtpActivity.EXTRA_PHONE, phone)
            putExtra(OtpActivity.EXTRA_AGENCY_DRIVER, agencyDriver)
        })
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
