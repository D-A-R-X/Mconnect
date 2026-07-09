package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.databinding.BottomSheetReviewAttendanceRequestBinding
import com.manjugroups.m_connect.network.AttendanceApprovalRecord
import java.text.SimpleDateFormat
import java.util.*

class ReviewAttendanceRequestBottomSheet : BottomSheetDialogFragment() {

    interface OnActionClickListener {
        fun onApprove(recordId: String, status: String)
        fun onReject(recordId: String)
    }

    private var _binding: BottomSheetReviewAttendanceRequestBinding? = null
    private val binding get() = _binding!!
    private var record: AttendanceApprovalRecord? = null
    private var listener: OnActionClickListener? = null

    companion object {
        fun newInstance(record: AttendanceApprovalRecord, listener: OnActionClickListener): ReviewAttendanceRequestBottomSheet {
            val sheet = ReviewAttendanceRequestBottomSheet()
            sheet.record = record
            sheet.listener = listener
            return sheet
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { di ->
            val sheet = (di as? BottomSheetDialog)
                ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
                it.outlineProvider = null

                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.75).toInt()
                behavior.skipCollapsed = false
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetReviewAttendanceRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val rec = record ?: return
        
        // Header & Name
        binding.tvStaffName.text = rec.staffName?.trim().orEmpty()
        binding.tvRequestDate.text = formatDateLabel(rec.date) ?: rec.date.orEmpty()

        // Recorded (actual) punches — "--" when the day has none, never a
        // fake placeholder time.
        binding.tvRecordedIn.text = formatTime(rec.punchInTime) ?: "--"
        binding.tvRecordedOut.text = formatTime(rec.punchOutTime) ?: "--"

        // Source & Submission Date
        binding.tvSourceBadge.text = rec.source?.ifBlank { "mobile" } ?: "mobile"
        binding.tvSubmittedAt.text = "Submitted on ${rec.date.orEmpty()}"

        // Correction requests carry the times the employee asked for plus
        // their reason — show the "Requested Correction" block for those.
        // Plain review rows keep the rejection-reason input instead.
        val requestedIn = formatTime(rec.requestedPunchIn)
        val requestedOut = formatTime(rec.requestedPunchOut)
        // A real time-correction request carries requested punch times. A plain
        // remark only carries a reason — for those, drop the "Requested
        // Correction" punch fields (they'd just read "--") and show the note
        // under a "Remark" heading instead.
        val hasRealCorrection = requestedIn != null || requestedOut != null
        val hasRemark = !rec.requestReason.isNullOrBlank()
        if (hasRealCorrection || hasRemark) {
            binding.layoutCorrectionUI.visibility = View.VISIBLE
            binding.layoutRejectionUI.visibility = View.GONE

            binding.tvCorrectionTitle.text = if (hasRealCorrection) "Requested Correction" else "Remark"
            binding.layoutCorrectionTimes.visibility = if (hasRealCorrection) View.VISIBLE else View.GONE
            binding.tvApprovedIn.text = requestedIn ?: "--"
            binding.tvApprovedOut.text = requestedOut ?: "--"
            binding.tvCorrectionReason.text =
                rec.requestReason?.trim().takeUnless { it.isNullOrBlank() } ?: "—"
        } else {
            binding.layoutCorrectionUI.visibility = View.GONE
            binding.layoutRejectionUI.visibility = View.VISIBLE

            binding.etRejectionReason.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val len = s?.length ?: 0
                    binding.tvReasonCharCount.text = "$len/200"
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        // Close button
        binding.btnSheetClose.setOnClickListener { dismiss() }

        // Row 1 buttons
        binding.btnSheetCancel.setOnClickListener { dismiss() }
        
        binding.btnSheetReject.setOnClickListener {
            listener?.onReject(rec.id.orEmpty())
            dismiss()
        }
        
        binding.btnSheetTimeCorrection.setOnClickListener {
            Toast.makeText(requireContext(), "Time Correction clicked", Toast.LENGTH_SHORT).show()
            dismiss()
        }

        // Row 2 buttons
        binding.btnSheetAbsent.setOnClickListener {
            listener?.onApprove(rec.id.orEmpty(), "absent")
            dismiss()
        }
        
        binding.btnSheetPresent.setOnClickListener {
            listener?.onApprove(rec.id.orEmpty(), "present")
            dismiss()
        }
    }

    private fun formatTime(isoString: String?): String? {
        if (isoString.isNullOrBlank()) return null
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
            SimpleDateFormat("HH:mm:ss", Locale.US),
            SimpleDateFormat("HH:mm", Locale.US)
        )
        for (fmt in formats) {
            val date = runCatching { fmt.parse(isoString) }.getOrNull()
            if (date != null) {
                return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date).uppercase()
            }
        }
        return null
    }

    private fun formatDateLabel(dateString: String?): String? {
        if (dateString.isNullOrBlank()) return null
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US),
            SimpleDateFormat("d MMM yyyy", Locale.US)
        )
        for (fmt in formats) {
            val date = runCatching { fmt.parse(dateString) }.getOrNull()
            if (date != null) {
                return SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(date)
            }
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
