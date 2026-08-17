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
    private val geoApi = com.manjugroups.m_connect.network.GeoTrackApi.create()
    private lateinit var otpBoxes: List<EditText>
    private var countDownTimer: CountDownTimer? = null
    private var canResend = false
    private var bootstrapJob: kotlinx.coroutines.Job? = null

    companion object {
        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_AGENCY_DRIVER = "extra_agency_driver"
    }

    private val phone: String by lazy {
        intent.getStringExtra(EXTRA_PHONE) ?: ""
    }

    // Whether the OTP was issued on the travel-desk (agency driver) path, so
    // verify is routed there. Seeded from the intent; a resend can flip it.
    private var agencyDriver: Boolean = false

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
        agencyDriver = intent.getBooleanExtra(EXTRA_AGENCY_DRIVER, false)
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
        // Attach device identity + telemetry so the backend can bind this staff
        // account to this one device (agency-driver logins skip binding).
        val deviceInfo =
            if (agencyDriver) null else LoginDeviceInfo.capture(applicationContext)
        viewModel.verifyOtp(phone, getOtp(), agencyDriver, deviceInfo)
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
                            // A resend re-runs detection; keep the verify path
                            // in sync with where the fresh OTP actually went.
                            agencyDriver = state.agencyDriver
                            resetButton()
                            clearOtp()
                            viewModel.resetState()
                        }
                        is AuthUiState.Verified -> {
                            bootstrapJob = lifecycleScope.launch {
                                bootstrapSession(state.response)
                            }
                            playSuccessAnimation {
                                lifecycleScope.launch {
                                    bootstrapJob?.join()
                                    requestNotificationAccessThenContinue()
                                }
                            }
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

    private suspend fun bootstrapSession(response: com.manjugroups.m_connect.network.VerifyOtpResponse) = kotlinx.coroutines.coroutineScope {
        session.saveSession(
            token = response.token!!,
            name = response.user?.name,
            phone = response.user?.phone ?: phone
        )
        session.staffId = response.user?.staffId
        session.employeeId = response.user?.employeeId
        session.role = response.user?.role
        session.externalFleetCanBill = response.user?.canBill == true
        session.mustChangePassword = false
        session.geoTrackingEnabled = response.user?.geoTrackingEnabled == true
        session.geoConsentGiven = false
        session.geoConsentDeclined = false
        session.shouldTrackNow = false
        session.activeTrackingSessionId = null

        // Cache designation immediately from the verify-otp payload.
        response.user?.designation?.takeIf { it.isNotBlank() }?.let {
            session.designation = it
        }
        // Department too — it's what separates a Transport driver from a
        // fleet-desk administrator, and MainActivity routes on it at startup.
        response.user?.department?.takeIf { it.isNotBlank() }?.let {
            session.department = it
        }

        // External-fleet principals (the agency and its drivers) have no
        // `staff` row, no IAM grants and no MMS fleet-driver record. Their
        // designation is synthesised by the auth payload, so probing those
        // staff-only endpoints would 401 — which the session watchdog turns
        // into a forced logout — and getStaffDetail would overwrite the
        // designation the restricted shell keys off. Skip all three.
        if (session.isExternalFleetPrincipal) return@coroutineScope

        // Run network requests in parallel
        val jobFleet = launch {
            session.fleetDriverByBackend = runCatching {
                geoApi.getMmsFleetDriverTrips(session.bearerToken)
            }.map { response ->
                response.success &&
                    response.diagnostics?.notDriver != true &&
                    response.diagnostics?.reason != "staff_not_driver"
            }.getOrDefault(false)
        }

        val jobIam = launch {
            runCatching {
                api.getMyIamPermissions(session.bearerToken)
            }.onSuccess { iam ->
                session.iamPermissions = iam.permissions.toSet()
                session.isAdmin = iam.isAdmin
                session.role = iam.role
            }
        }

        val jobStaff = launch {
            session.staffId?.takeIf { it.isNotBlank() }?.let { staffId ->
                runCatching {
                    api.getStaffDetail(session.bearerToken, staffId)
                }.onSuccess { resp ->
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

        // Wait for all fetches to complete
        jobFleet.join()
        jobIam.join()
        jobStaff.join()
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
        // An agency driver isn't staff, so the MMS registerPushDevice path 401s
        // (and the interceptor would bounce them to sign-in). Register on their
        // own travel-desk channel instead, so trip allocations can push them.
        if (session.isExternalFleetDriver) {
            runCatching {
                PushTokenManager.syncAgencyDriverToken(this@OtpActivity, session)
            }
            return
        }
        // The agency principal (not a driver) has no push channel; skip.
        if (session.isExternalFleetPrincipal) return
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

    private fun playSuccessAnimation(onComplete: () -> Unit) {
        // Hide soft keyboard immediately
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())

        // Disable interaction on all inputs and resend
        otpBoxes.forEach { box ->
            box.isEnabled = false
            box.clearFocus()
        }
        binding.btnVerify.isEnabled = false
        binding.tvResend.isEnabled = false

        val containerCenterX = binding.otpBoxesContainer.width / 2f
        val animators = otpBoxes.mapIndexed { index, box ->
            val boxCenterX = box.left + box.width / 2f
            val targetTranslationX = containerCenterX - boxCenterX
            
            // Symmetrical pairs jump delay: outer-most (0 & 5) jump first, inner-most (2 & 3) last
            val pairIndex = if (index < 3) index else 5 - index
            val startDelayMs = pairIndex * 220L
            
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 650
                startDelay = startDelayMs
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    
                    // 1. Parabolic hop path (y = 4 * height * x * (1 - x))
                    val jumpHeight = 200f // height in pixels
                    box.translationX = fraction * targetTranslationX
                    box.translationY = -4f * jumpHeight * fraction * (1f - fraction)
                    
                    // 2. Squash and stretch dynamics
                    if (fraction < 0.35f) {
                        // Takeoff: stretch vertically
                        val localT = fraction / 0.35f
                        box.scaleY = 1f + 0.35f * localT
                        box.scaleX = 1f - 0.3f * localT
                        box.alpha = 1f
                    } else if (fraction < 0.7f) {
                        // Mid-air flight: recover back to normal
                        val localT = (fraction - 0.35f) / 0.35f
                        box.scaleY = 1.35f - 0.35f * localT
                        box.scaleX = 0.7f + 0.3f * localT
                        box.alpha = 1f
                    } else {
                        // Landing: squash down and fade away to center
                        val localT = (fraction - 0.7f) / 0.3f
                        box.scaleY = 1.0f - 1.0f * localT
                        box.scaleX = 1.0f - 1.0f * localT
                        box.alpha = 1f - localT
                    }
                }
            }
        }

        // Fade out surrounding elements
        val fadeTitle = android.animation.ObjectAnimator.ofFloat(binding.tvOtpTitle, "alpha", 1f, 0f)
        val fadeSubtitle = android.animation.ObjectAnimator.ofFloat(binding.tvOtpSubtitle, "alpha", 1f, 0f)
        val fadeVerifyBtn = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(binding.btnVerify, "alpha", 1f, 0f),
                android.animation.ObjectAnimator.ofFloat(binding.btnVerify, "scaleX", 1f, 0.9f),
                android.animation.ObjectAnimator.ofFloat(binding.btnVerify, "scaleY", 1f, 0.9f)
            )
            duration = 300
        }
        val fadeTimer = android.animation.ObjectAnimator.ofFloat(binding.tvTimer, "alpha", 1f, 0f)
        val fadeResend = android.animation.ObjectAnimator.ofFloat(binding.tvResend, "alpha", 1f, 0f)
        val fadeError = android.animation.ObjectAnimator.ofFloat(binding.tvOtpError, "alpha", 1f, 0f)

        android.animation.AnimatorSet().apply {
            playTogether(animators + fadeTitle + fadeSubtitle + fadeVerifyBtn + fadeTimer + fadeResend + fadeError)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.otpBoxesContainer.visibility = View.GONE
                    binding.btnVerify.visibility = View.GONE
                    binding.tvOtpTitle.visibility = View.GONE
                    binding.tvOtpSubtitle.visibility = View.GONE
                    binding.tvTimer.visibility = View.GONE
                    binding.tvResend.visibility = View.GONE
                    binding.tvOtpError.visibility = View.GONE

                    // 1. Initial State for Pixar Bouncing Green Circle
                    binding.verifySuccessContainer.visibility = View.VISIBLE
                    binding.verifySuccessContainer.scaleX = 0.6f
                    binding.verifySuccessContainer.scaleY = 1.4f // Stretched vertically as it falls
                    binding.verifySuccessContainer.translationY = -400f // Start falling from above
                    binding.verifySuccessContainer.alpha = 1f
                    
                    binding.ivSuccessCheckmark.scaleX = 0f
                    binding.ivSuccessCheckmark.scaleY = 0f
                    binding.ivSuccessCheckmark.alpha = 0f

                    // Animator A: The Fall (translates containerY from -400f to 0f)
                    val fallAnim = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "translationY", -400f, 0f
                    ).apply {
                        duration = 320
                        interpolator = android.view.animation.AccelerateInterpolator(1.5f)
                    }

                    // Animator B: The First Ground Impact Squash (squashes flat, then recovers)
                    val squashY = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleY", 1.4f, 0.45f, 1.1f, 1.0f
                    ).apply {
                        duration = 200
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }
                    val squashX = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleX", 0.6f, 1.55f, 0.9f, 1.0f
                    ).apply {
                        duration = 200
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }

                    // Outward Shockwave Ripples (triggered on impact)
                    val ripple1 = android.animation.AnimatorSet().apply {
                        binding.rippleRing1.visibility = View.VISIBLE
                        binding.rippleRing1.scaleX = 1f
                        binding.rippleRing1.scaleY = 1f
                        binding.rippleRing1.alpha = 0.8f
                        playTogether(
                            android.animation.ObjectAnimator.ofFloat(binding.rippleRing1, "scaleX", 1f, 2.6f),
                            android.animation.ObjectAnimator.ofFloat(binding.rippleRing1, "scaleY", 1f, 2.6f),
                            android.animation.ObjectAnimator.ofFloat(binding.rippleRing1, "alpha", 0.8f, 0f)
                        )
                        duration = 600
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }
                    val ripple2 = android.animation.AnimatorSet().apply {
                        binding.rippleRing2.visibility = View.VISIBLE
                        binding.rippleRing2.scaleX = 1f
                        binding.rippleRing2.scaleY = 1f
                        binding.rippleRing2.alpha = 0.8f
                        playTogether(
                            android.animation.ObjectAnimator.ofFloat(binding.rippleRing2, "scaleX", 1f, 2.6f),
                            android.animation.ObjectAnimator.ofFloat(binding.rippleRing2, "scaleY", 1f, 2.6f),
                            android.animation.ObjectAnimator.ofFloat(binding.rippleRing2, "alpha", 0.8f, 0f)
                        )
                        startDelay = 120
                        duration = 600
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }

                    // Animator C: Rebound Jump Up (rebound to -140f, stretching vertically)
                    val jumpUp = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "translationY", 0f, -140f
                    ).apply {
                        duration = 220
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }
                    val jumpStretchY = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleY", 1.0f, 1.25f
                    ).apply {
                        duration = 220
                    }
                    val jumpStretchX = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleX", 1.0f, 0.75f
                    ).apply {
                        duration = 220
                    }

                    // Animator D: Fall Back Down to Ground
                    val fallDown = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "translationY", -140f, 0f
                    ).apply {
                        duration = 220
                        interpolator = android.view.animation.AccelerateInterpolator()
                    }
                    val jumpSettleY = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleY", 1.25f, 1.0f
                    ).apply {
                        duration = 220
                    }
                    val jumpSettleX = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleX", 0.75f, 1.0f
                    ).apply {
                        duration = 220
                    }

                    // Animator E: Checkmark Pop (starts at apex of rebound / fall down start)
                    val popCheckmark = android.animation.AnimatorSet().apply {
                        playTogether(
                            android.animation.ObjectAnimator.ofFloat(binding.ivSuccessCheckmark, "scaleX", 0f, 1.3f, 1.0f),
                            android.animation.ObjectAnimator.ofFloat(binding.ivSuccessCheckmark, "scaleY", 0f, 1.3f, 1.0f),
                            android.animation.ObjectAnimator.ofFloat(binding.ivSuccessCheckmark, "alpha", 0f, 1f)
                        )
                        duration = 350
                        interpolator = android.view.animation.OvershootInterpolator(1.8f)
                    }

                    // Animator F: Second smaller ground impact squash
                    val smallSquashY = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleY", 1.0f, 0.85f, 1.0f
                    ).apply {
                        duration = 160
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }
                    val smallSquashX = android.animation.ObjectAnimator.ofFloat(
                        binding.verifySuccessContainer, "scaleX", 1.0f, 1.15f, 1.0f
                    ).apply {
                        duration = 160
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }

                    // Sequential Orchestration of the Pixar Animation
                    val sequence = android.animation.AnimatorSet()
                    sequence.play(fallAnim)
                    
                    val squashSet = android.animation.AnimatorSet().apply {
                        playTogether(squashY, squashX, ripple1, ripple2)
                    }
                    sequence.play(squashSet).after(fallAnim)

                    val reboundSet = android.animation.AnimatorSet().apply {
                        playTogether(jumpUp, jumpStretchY, jumpStretchX)
                    }
                    sequence.play(reboundSet).after(squashSet)

                    val fallBackSet = android.animation.AnimatorSet().apply {
                        playTogether(fallDown, jumpSettleY, jumpSettleX)
                    }
                    sequence.play(fallBackSet).after(reboundSet)
                    sequence.play(popCheckmark).with(fallDown)

                    val finalSquashSet = android.animation.AnimatorSet().apply {
                        playTogether(smallSquashY, smallSquashX)
                    }
                    sequence.play(finalSquashSet).after(fallBackSet)

                    // Post-animation settles: Start Breathing idle cycle and delay session transition
                    sequence.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            // Infinite Breathing Loop (scales gently between 1.0f and 1.07f)
                            val breathAnim = android.animation.ValueAnimator.ofFloat(1.0f, 1.07f).apply {
                                duration = 1200
                                repeatCount = android.animation.ValueAnimator.INFINITE
                                repeatMode = android.animation.ValueAnimator.REVERSE
                                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                                addUpdateListener { anim ->
                                    val scale = anim.animatedValue as Float
                                    binding.verifySuccessContainer.scaleX = scale
                                    binding.verifySuccessContainer.scaleY = scale
                                }
                            }
                            breathAnim.start()

                            // Proceed to session bootstrapping after 300 milliseconds of breathing
                            binding.verifySuccessContainer.postDelayed({
                                breathAnim.cancel()
                                onComplete()
                            }, 300)
                        }
                    })
                    sequence.start()
                }
            })
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
    }
}
