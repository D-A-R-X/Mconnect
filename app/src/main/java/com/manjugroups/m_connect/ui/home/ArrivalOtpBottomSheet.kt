package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ArrivalOtpVerifyBody
import com.manjugroups.m_connect.network.ArrivalOtpRequestBody
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.launch

/**
 * Arrival OTP entry sheet.
 *
 * Caller is responsible for invoking `requestArrivalOtp` once before showing
 * the sheet (so the SMS goes out and we know the masked phone + expiry).
 * The sheet handles `verifyArrivalOtp` and resend internally, then signals
 * the host fragment via `setFragmentResult` on success so the host can
 * complete the visit.
 */
class ArrivalOtpBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    private lateinit var boxes: List<EditText>
    private var errorText: TextView? = null
    private var resendBtn: TextView? = null
    private var verifyBtn: View? = null
    private var subtitleText: TextView? = null
    private var countdownTimer: CountDownTimer? = null
    private var resendTimer: CountDownTimer? = null

    private var visitId: String = ""
    private var lat: Double? = null
    private var lng: Double? = null
    private var resendCooldownSeconds: Int = 60

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // Resize the sheet to make room for the soft keyboard so the
        // OTP boxes + Submit button stay visible while the user types.
        // The dialog_arrival_otp layout wraps its content in a
        // NestedScrollView, so any shortfall in vertical space scrolls
        // cleanly within the sheet rather than getting clipped.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_arrival_otp, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val args = requireArguments()
        visitId = args.getString(ARG_VISIT_ID).orEmpty()
        lat = if (args.containsKey(ARG_LAT)) args.getDouble(ARG_LAT) else null
        lng = if (args.containsKey(ARG_LNG)) args.getDouble(ARG_LNG) else null
        resendCooldownSeconds = args.getInt(ARG_RESEND_COOLDOWN, 60)
        val phoneMasked = args.getString(ARG_PHONE_MASKED)
        val expiresIn = args.getInt(ARG_EXPIRES_IN, 600)

        boxes = listOf(
            view.findViewById(R.id.arrivalOtpBox1),
            view.findViewById(R.id.arrivalOtpBox2),
            view.findViewById(R.id.arrivalOtpBox3),
            view.findViewById(R.id.arrivalOtpBox4),
        )
        errorText = view.findViewById(R.id.tvArrivalOtpError)
        verifyBtn = view.findViewById(R.id.btnArrivalOtpVerify)
        resendBtn = view.findViewById(R.id.btnArrivalOtpCancel) // re-purposed
        subtitleText = view.findViewById(R.id.tvArrivalOtpSubtitle)

        // Figma 314:10209 — keep the static body copy as the subtitle. Phone
        // mask + expiry countdown are shown via the error text when relevant
        // so the design stays clean while users still get feedback.
        subtitleText?.text = buildString {
            append("Please confirm if you have seen or met the client at this location.")
            if (!phoneMasked.isNullOrBlank()) {
                append("\nSent to ").append(phoneMasked)
            }
        }

        boxes.forEachIndexed { index, edit ->
            edit.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    errorText?.visibility = View.GONE
                    if ((s?.length ?: 0) == 1 && index < boxes.lastIndex) {
                        boxes[index + 1].requestFocus()
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            edit.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    keyCode == KeyEvent.KEYCODE_DEL &&
                    edit.text.isEmpty() && index > 0
                ) {
                    boxes[index - 1].apply {
                        requestFocus()
                        setText("")
                    }
                    true
                } else false
            }
            edit.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    verifyBtn?.performClick()
                    true
                } else false
            }
        }
        boxes.first().requestFocus()

        verifyBtn?.setOnClickListener { performVerify() }
        resendBtn?.setOnClickListener { performResend() }

        // OTP expiry countdown displayed inline.
        startExpiryCountdown(expiresIn)
        // Resend button starts disabled for cooldown window.
        startResendCooldown(resendCooldownSeconds)
    }

    override fun onDestroyView() {
        countdownTimer?.cancel()
        resendTimer?.cancel()
        super.onDestroyView()
    }

    private fun performVerify() {
        val entered = boxes.joinToString("") { it.text.toString().trim() }
        if (entered.length != 4) {
            showError("Enter all 4 digits")
            return
        }
        verifyBtn?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.verifyArrivalOtp(
                    session.bearerToken,
                    ArrivalOtpVerifyBody(visitId = visitId, otp = entered, lat = lat, lng = lng)
                )
                if (resp.success) {
                    setFragmentResult(RESULT_KEY, bundleOf(KEY_OTP to entered))
                    dismissAllowingStateLoss()
                } else {
                    verifyBtn?.isEnabled = true
                    boxes.forEach { it.setText("") }
                    boxes.first().requestFocus()
                    showError(resp.error ?: "Invalid OTP")
                }
            } catch (e: Exception) {
                verifyBtn?.isEnabled = true
                showError("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun performResend() {
        if (lat == null || lng == null) {
            showError("Location unavailable; cannot resend")
            return
        }
        resendBtn?.isClickable = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.requestArrivalOtp(
                    session.bearerToken,
                    ArrivalOtpRequestBody(visitId = visitId, lat = lat!!, lng = lng!!)
                )
                if (resp.success) {
                    Toast.makeText(
                        requireContext(),
                        "OTP resent" + (resp.contactPhoneMasked?.let { " to $it" } ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                    boxes.forEach { it.setText("") }
                    boxes.first().requestFocus()
                    startExpiryCountdown(resp.otpExpiresInSeconds ?: 600)
                    startResendCooldown(resp.resendCooldownSeconds ?: 60)
                } else {
                    resendBtn?.isClickable = true
                    showError(resp.error ?: "Failed to resend OTP")
                }
            } catch (e: Exception) {
                resendBtn?.isClickable = true
                showError("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun startExpiryCountdown(seconds: Int) {
        // Don't redraw the figma subtitle every second. Only surface a message
        // when the OTP actually expires — that's when the user needs to act.
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer((seconds * 1000L).coerceAtLeast(1000L), 1000L) {
            override fun onTick(msLeft: Long) = Unit
            override fun onFinish() {
                showError("OTP expired. Tap Resend to get a new one.")
            }
        }.start()
    }

    private fun startResendCooldown(seconds: Int) {
        resendTimer?.cancel()
        resendBtn?.isClickable = false
        val total = seconds.coerceAtLeast(0)
        resendTimer = object : CountDownTimer(total * 1000L + 100L, 1000L) {
            override fun onTick(msLeft: Long) {
                val s = (msLeft / 1000).toInt()
                resendBtn?.text = "Resend available in ${s}s"
            }
            override fun onFinish() {
                resendBtn?.text = "Haven't received the verification code? Resend it."
                resendBtn?.isClickable = true
            }
        }.start()
    }

    private fun showError(message: String) {
        errorText?.text = message
        errorText?.visibility = View.VISIBLE
    }

    companion object {
        const val RESULT_KEY = "arrival_otp_result"
        const val KEY_OTP = "otp"
        private const val ARG_VISIT_ID = "arg_visit_id"
        private const val ARG_PHONE_MASKED = "arg_phone_masked"
        private const val ARG_EXPIRES_IN = "arg_expires_in"
        private const val ARG_RESEND_COOLDOWN = "arg_resend_cooldown"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LNG = "arg_lng"

        fun newInstance(
            visitId: String,
            phoneMasked: String?,
            expiresInSeconds: Int,
            resendCooldownSeconds: Int,
            lat: Double?,
            lng: Double?,
        ): ArrivalOtpBottomSheet = ArrivalOtpBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_VISIT_ID, visitId)
                if (phoneMasked != null) putString(ARG_PHONE_MASKED, phoneMasked)
                putInt(ARG_EXPIRES_IN, expiresInSeconds)
                putInt(ARG_RESEND_COOLDOWN, resendCooldownSeconds)
                if (lat != null) putDouble(ARG_LAT, lat)
                if (lng != null) putDouble(ARG_LNG, lng)
            }
        }
    }
}
