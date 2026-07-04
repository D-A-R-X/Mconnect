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
        binding.tvStaffName.text = rec.staffName?.trim().orEmpty().ifBlank { "Elaine" }
        binding.tvRequestDate.text = formatDateLabel(rec.date) ?: (rec.date ?: "Thu, 27 Sept 2024")
        
        // Times
        val inTime = formatTime(rec.punchInTime) ?: "09:00 AM"
        val outTime = formatTime(rec.punchOutTime) ?: "05:00 PM"
        binding.tvRecordedIn.text = inTime
        binding.tvRecordedOut.text = outTime

        // Source & Submission Date
        binding.tvSourceBadge.text = rec.source?.ifBlank { "Biometric" } ?: "Biometric"
        val displayDate = rec.date ?: "27/09/2024"
        binding.tvSubmittedAt.text = "Submitted on $displayDate, 06:15 PM"

        // Toggle UI blocks based on Record ID
        // req_1 corresponds to first dummy, req_2 (or others) to second dummy
        if (rec.id == "req_1" || rec.id == "app_1") {
            // First dummy: Correction UI
            binding.layoutCorrectionUI.visibility = View.VISIBLE
            binding.layoutRejectionUI.visibility = View.GONE
            
            binding.tvApprovedIn.text = inTime
            binding.tvApprovedOut.text = outTime
            binding.tvCorrectionReason.text = "Worked full day at site"
        } else {
            // Second dummy (and all others): Rejection Reason Input UI
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
