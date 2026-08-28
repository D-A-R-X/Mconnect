package com.manjugroups.m_connect.ui.home

import android.app.Dialog
import android.os.Bundle
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
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.launch

/**
 * Arrival OTP entry sheet.
 *
 * Caller is responsible for invoking `requestArrivalOtp` once before showing
 * the sheet (so the SMS goes out and we know the masked phone).
 * The sheet handles `verifyArrivalOtp`, then signals
 * the host fragment via `setFragmentResult` on success so the host can
 * complete the visit.
 */
class ArrivalOtpBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    private lateinit var boxes: List<EditText>
    private var errorText: TextView? = null
    private var verifyBtn: View? = null
    private var subtitleText: TextView? = null
    private var cpVisitId: String? = null
    private var gmRequestInFlight: Boolean = false

    private var visitId: String = ""
    private var lat: Double? = null
    private var lng: Double? = null
    // The arrival photo's storage id — populated upstream in
    // TripNavigationFragment.uploadArrivalPhotoThenAskOtp before this
    // sheet is shown, then forwarded with the OTP verify request so
    // the backend can link the photo to the fieldVisit row at the
    // moment arrival is confirmed (not at trip completion).
    private var arrivalPhotoStorageId: String? = null

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
        arrivalPhotoStorageId = args.getString(ARG_ARRIVAL_PHOTO_STORAGE_ID)
        cpVisitId = args.getString(ARG_CP_VISIT_ID)
        val phoneMasked = args.getString(ARG_PHONE_MASKED)

        boxes = listOf(
            view.findViewById(R.id.arrivalOtpBox1),
            view.findViewById(R.id.arrivalOtpBox2),
            view.findViewById(R.id.arrivalOtpBox3),
            view.findViewById(R.id.arrivalOtpBox4),
        )
        errorText = view.findViewById(R.id.tvArrivalOtpError)
        verifyBtn = view.findViewById(R.id.btnArrivalOtpVerify)
        subtitleText = view.findViewById(R.id.tvArrivalOtpSubtitle)

        // Figma 314:10209 — keep the static body copy as the subtitle. Phone
        // mask stays visible without adding a timeout state: the OTP remains
        // active until it is verified or the visit closes.
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

        // "Client not sharing the OTP? Request GM" — only offered when we know
        // which CP visit this is; without it there is nothing to ask about.
        val requestGmBtn = view.findViewById<TextView>(R.id.btnArrivalOtpRequestGm)
        if (cpVisitId.isNullOrBlank()) {
            requestGmBtn?.visibility = View.GONE
        } else {
            requestGmBtn?.visibility = View.VISIBLE
            requestGmBtn?.setOnClickListener { promptGmRequest() }
        }

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
                    ArrivalOtpVerifyBody(
                        visitId = visitId,
                        otp = entered,
                        lat = lat,
                        lng = lng,
                        arrivalPhotoStorageId = arrivalPhotoStorageId,
                    ),
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

    private fun showError(message: String) {
        errorText?.text = message
        errorText?.visibility = View.VISIBLE
    }


    /**
     * Ask for an optional remark, then send the request to the GM.
     *
     * The remark is genuinely optional — a staff member standing in front of
     * an uncooperative client should not be made to write an essay before
     * they can get help.
     */
    private fun promptGmRequest() {
        if (gmRequestInFlight) return
        val cpId = cpVisitId?.takeIf { it.isNotBlank() } ?: return
        val ctx = context ?: return

        val input = android.widget.EditText(ctx).apply {
            hint = "Remark (optional) — e.g. client refused to share the code"
            setPadding(48, 32, 48, 32)
            maxLines = 3
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Request GM for OTP")
            .setMessage(
                "Your GM will get a chat message with the client, your location, " +
                    "the distance from the client's place and the OTP.",
            )
            .setView(input)
            .setPositiveButton("Send request") { _, _ ->
                sendGmRequest(cpId, input.text?.toString()?.trim().orEmpty())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendGmRequest(cpVisitId: String, remark: String) {
        gmRequestInFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.requestCpOtpAssist(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.CpOtpAssistRequest(
                        clientPlaceVisitId = cpVisitId,
                        lat = lat,
                        lng = lng,
                        remark = remark.takeIf { it.isNotEmpty() },
                    ),
                )
                if (!isAdded) return@launch
                if (resp.success) {
                    Toast.makeText(
                        requireContext(),
                        "Sent to ${resp.gmName ?: "your manager"}. They can read the OTP back to you.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Couldn't send the request",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        e.message ?: "Couldn't send the request",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                gmRequestInFlight = false
            }
        }
    }

    companion object {
        const val RESULT_KEY = "arrival_otp_result"
        const val KEY_OTP = "otp"
        private const val ARG_VISIT_ID = "arg_visit_id"
        private const val ARG_PHONE_MASKED = "arg_phone_masked"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LNG = "arg_lng"
        private const val ARG_ARRIVAL_PHOTO_STORAGE_ID = "arg_arrival_photo_storage_id"
        private const val ARG_CP_VISIT_ID = "arg_cp_visit_id"

        fun newInstance(
            visitId: String,
            /** The CP visit this arrival belongs to — needed to ask the GM
             *  for help when the client won't share the code. */
            cpVisitId: String? = null,
            phoneMasked: String?,
            lat: Double?,
            lng: Double?,
            arrivalPhotoStorageId: String? = null,
        ): ArrivalOtpBottomSheet = ArrivalOtpBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_VISIT_ID, visitId)
                if (cpVisitId != null) putString(ARG_CP_VISIT_ID, cpVisitId)
                if (phoneMasked != null) putString(ARG_PHONE_MASKED, phoneMasked)
                if (lat != null) putDouble(ARG_LAT, lat)
                if (lng != null) putDouble(ARG_LNG, lng)
                if (!arrivalPhotoStorageId.isNullOrBlank()) {
                    putString(ARG_ARRIVAL_PHOTO_STORAGE_ID, arrivalPhotoStorageId)
                }
            }
        }
    }
}
