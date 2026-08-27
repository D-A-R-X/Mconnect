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
import com.manjugroups.m_connect.ui.common.AvatarUtils.loadUserAvatar
import java.text.SimpleDateFormat
import java.util.*

class ReviewAttendanceRequestBottomSheet : BottomSheetDialogFragment() {

    interface OnActionClickListener {
        fun onApprove(recordId: String, status: String)
        fun onReject(recordId: String)
        /**
         * Direct punch-time correction. Times are ISO instants already built
         * from the row's own date, so the caller just forwards them.
         */
        fun onCorrectTimes(
            recordId: String,
            correctedPunchIn: String?,
            correctedPunchOut: String?,
            reason: String?,
        )
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
        
        // Header & Name. The avatar was never bound, so the sheet always showed
        // the bare placeholder drawable even though the record carries a
        // resolved photo URL (the list cards behind it render it fine).
        binding.tvStaffName.text = rec.staffName?.trim().orEmpty()
        binding.ivUserAvatar.loadUserAvatar(rec.staffPhotoUrl, rec.staffName)
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
        
        // Time Correction was a stub that toasted "Time Correction clicked"
        // and closed — the web has had this action for a long time and mobile
        // reviewers (GM / AVP) had no way to fix a wrong punch. Hidden unless
        // the viewer actually holds the permission, so nobody is offered an
        // action the server will refuse.
        val session = com.manjugroups.m_connect.auth.SessionManager(requireContext())
        val mayCorrect = session.hasPermission("attendance.correctPunchTimes")
        binding.btnSheetTimeCorrection.visibility = if (mayCorrect) View.VISIBLE else View.GONE
        binding.btnSheetTimeCorrection.setOnClickListener {
            promptTimeCorrection(rec)
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

    /**
     * Ask for the corrected punch-in / punch-out, then hand them to the host.
     *
     * Each picker is optional: correcting only the punch-out is a normal case
     * (someone forgot to clock out), so an untouched field is left null rather
     * than being re-sent as its current value.
     */
    private fun promptTimeCorrection(rec: AttendanceApprovalRecord) {
        val ctx = requireContext()
        val date = rec.date?.trim().orEmpty()
        if (date.isEmpty()) {
            Toast.makeText(ctx, "This row has no date to correct against.", Toast.LENGTH_SHORT).show()
            return
        }
        var newIn: String? = null
        var newOut: String? = null

        fun askReasonAndSubmit() {
            if (newIn == null && newOut == null) {
                Toast.makeText(ctx, "Pick at least one time to correct.", Toast.LENGTH_SHORT).show()
                return
            }
            val input = android.widget.EditText(ctx).apply {
                hint = "Reason for the correction"
                setPadding(48, 32, 48, 32)
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Correct punch times")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    listener?.onCorrectTimes(
                        rec.id.orEmpty(),
                        newIn,
                        newOut,
                        input.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    )
                    dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun pick(title: String, existing: String?, onPicked: (String) -> Unit, next: () -> Unit) {
            val cal = java.util.Calendar.getInstance()
            parseInstant(existing)?.let { cal.time = it }
            val dialog = android.app.TimePickerDialog(
                ctx,
                { _, hour, minute ->
                    onPicked(isoAt(date, hour, minute))
                    next()
                },
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                false,
            )
            dialog.setTitle(title)
            // Skipping a picker must not abandon the flow — it just leaves that
            // side uncorrected and moves on to the next step.
            dialog.setButton(android.app.TimePickerDialog.BUTTON_NEGATIVE, "Skip") { _, _ -> next() }
            dialog.show()
        }

        pick("Corrected punch-in", rec.punchInTime, { newIn = it }) {
            pick("Corrected punch-out", rec.punchOutTime, { newOut = it }) {
                askReasonAndSubmit()
            }
        }
    }

    /** Local wall-clock time on [date] as a UTC ISO instant — the same shape
     *  the web builds with `new Date(`${date}T${time}`).toISOString()`. */
    private fun isoAt(date: String, hour: Int, minute: Int): String {
        val local = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val parsed = local.parse("$date %02d:%02d".format(hour, minute))
            ?: return date
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(parsed)
    }

    private fun parseInstant(iso: String?): java.util.Date? {
        if (iso.isNullOrBlank()) return null
        return listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        ).firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(iso)
            }.getOrNull()
        }
    }

}
