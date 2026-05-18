package com.manjugroups.m_connect.auth

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
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
import com.manjugroups.m_connect.databinding.ActivityOtpBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

class OtpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOtpBinding
    private lateinit var session: SessionManager
    private val viewModel: AuthViewModel by viewModels()
    private val api = ApiService.create()
    private lateinit var otpBoxes: List<EditText>
    private var countDownTimer: CountDownTimer? = null
    private var canResend = false

    companion object {
        const val EXTRA_PHONE = "extra_phone"
    }

    private val phone: String by lazy {
        intent.getStringExtra(EXTRA_PHONE) ?: ""
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        session.notificationPermissionPrompted = true
        lifecycleScope.launch {
            syncPushTokenIfPossible()
            goToMain()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOtpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // Edge-to-edge keyboard handling: lift the OTP sheet above the keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = maxOf(ime.bottom, sys.bottom))
            insets
        }

        session = SessionManager(this)

        val formatted = if (phone.length == 10) " ${phone}" else phone
        val full = getString(R.string.otp_subtitle, formatted)
        val phoneToken = "+91$formatted"
        val span = SpannableString(full)
        val phoneStart = full.indexOf(phoneToken)
        if (phoneStart >= 0) {
            span.setSpan(
                StyleSpan(android.graphics.Typeface.BOLD),
                phoneStart, phoneStart + phoneToken.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.tvOtpSubtitle.text = span

        otpBoxes = listOf(
            binding.otpBox1, binding.otpBox2, binding.otpBox3,
            binding.otpBox4, binding.otpBox5, binding.otpBox6
        )

        setupOtpBoxes()
        setupListeners()
        collectState()
        startTimer()

        otpBoxes.forEach { it.clearFocus() }
    }

    private fun setupOtpBoxes() {
        otpBoxes.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && index < otpBoxes.lastIndex) {
                        otpBoxes[index + 1].requestFocus()
                    }
                    updateOtpBoxStyles()
                }
            })

            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                    if (editText.text.isEmpty() && index > 0) {
                        otpBoxes[index - 1].apply {
                            setText("")
                            requestFocus()
                        }
                        return@setOnKeyListener true
                    }
                }
                false
            }

            editText.setOnFocusChangeListener { _, _ ->
                updateOtpBoxStyles()
            }
        }
    }

    private fun updateOtpBoxStyles() {
        otpBoxes.forEach { box ->
            val bg = if (box.hasFocus()) R.drawable.bg_auth_otp_box_focused else R.drawable.bg_auth_otp_box
            box.setBackgroundResource(bg)
        }
    }

    private fun getOtp(): String = otpBoxes.joinToString("") { it.text.toString() }

    private fun setupListeners() {
        binding.btnVerify.setOnClickListener {
            val otp = getOtp()
            if (otp.length < 6) {
                binding.tvOtpError.text = getString(R.string.otp_incomplete)
                binding.tvOtpError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            verifyOtp()
        }

        binding.tvResend.setOnClickListener {
            if (canResend) {
                viewModel.sendOtp(phone)
                startTimer()
            }
        }
    }

    private fun verifyOtp() {
        binding.tvOtpError.visibility = View.GONE
        viewModel.verifyOtp(phone, getOtp())
    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is AuthUiState.Loading -> {
                            binding.tvVerify.visibility = View.INVISIBLE
                            binding.skeletonVerify.visibility = View.VISIBLE
                            SkeletonUtils.startSkeletonPulse(binding.skeletonVerify)
                            binding.btnVerify.isClickable = false
                        }
                        is AuthUiState.OtpSent -> {
                            resetButton()
                            clearOtp()
                            viewModel.resetState()
                        }
                        is AuthUiState.Verified -> {
                            resetButton()
                            bootstrapSession(state.response)
                            viewModel.resetState()
                        }
                        is AuthUiState.Error -> {
                            resetButton()
                            binding.tvOtpError.text = state.message
                            binding.tvOtpError.visibility = View.VISIBLE
                            clearOtp()
                            viewModel.resetState()
                        }
                        else -> resetButton()
                    }
                }
            }
        }
    }

    private fun resetButton() {
        binding.tvVerify.visibility = View.VISIBLE
        SkeletonUtils.stopSkeletonPulse(binding.skeletonVerify)
        binding.skeletonVerify.visibility = View.GONE
        binding.btnVerify.isClickable = true
    }

    private fun clearOtp() {
        otpBoxes.forEach { it.setText("") }
        otpBoxes.first().requestFocus()
    }

    private fun startTimer() {
        canResend = false
        binding.tvTimer.text = getString(R.string.resend_code)
        binding.tvResend.text = getString(R.string.resend_otp_timer, "00:30")
        binding.tvResend.alpha = 0.5f
        binding.tvResend.isClickable = false
        binding.tvResend.setTextColor(Color.parseColor("#98A2B3"))

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(30_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                binding.tvResend.text = getString(
                    R.string.resend_otp_timer,
                    String.format("00:%02d", seconds)
                )
            }

            override fun onFinish() {
                binding.tvTimer.text = getString(R.string.resend_code)
                binding.tvResend.text = getString(R.string.resend_code_prompt)
                canResend = true
                binding.tvResend.alpha = 1.0f
                binding.tvResend.isClickable = true
                binding.tvResend.setTextColor(Color.parseColor("#0B61CA"))
            }
        }.start()
    }

    private fun bootstrapSession(response: com.manjugroups.m_connect.network.VerifyOtpResponse) {
        lifecycleScope.launch {
            session.saveSession(
                token = response.token!!,
                name = response.user?.name,
                phone = response.user?.phone ?: phone
            )
            session.staffId = response.user?.staffId
            session.geoTrackingEnabled = false
            session.geoConsentGiven = false
            session.geoConsentDeclined = false
            session.shouldTrackNow = false
            session.activeTrackingSessionId = null

            runCatching {
                api.getMyIamPermissions(session.bearerToken)
            }.onSuccess { iam ->
                session.iamPermissions = iam.permissions.toSet()
                session.isAdmin = iam.isAdmin
            }

            // Cache the reporting officer so the very first leave/permission
            // apply (before the user opens Profile) can route to the right
            // approver. Best-effort — if it fails, ApplyLeaveFragment will
            // refetch on demand.
            session.staffId?.takeIf { it.isNotBlank() }?.let { staffId ->
                runCatching {
                    api.getStaffDetail(session.bearerToken, staffId)
                }.onSuccess { resp ->
                    resp.staff?.let { staff ->
                        session.reportingToId = staff.reportingTo
                        session.reportingToName = staff.reportingToName
                    }
                }
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
            goToMain()
        }
    }

    private suspend fun syncPushTokenIfPossible() {
        runCatching {
            PushTokenManager.syncCurrentToken(this@OtpActivity, session)
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
