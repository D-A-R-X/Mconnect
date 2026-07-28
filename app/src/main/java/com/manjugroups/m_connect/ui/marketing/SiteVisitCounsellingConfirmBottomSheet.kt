package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R

class SiteVisitCounsellingConfirmBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(
        R.layout.bottom_sheet_site_visit_counselling_confirm,
        container,
        false,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val siteVisitId = requireArguments().getString(ARG_SITE_VISIT_ID).orEmpty()
        val projectName = requireArguments().getString(ARG_PROJECT_NAME).orEmpty()
        val clientName = requireArguments().getString(ARG_CLIENT_NAME).orEmpty()
        val bdoName = requireArguments().getString(ARG_BDO_NAME).orEmpty()
        val inchargeName = requireArguments().getString(ARG_INCHARGE_NAME).orEmpty()
        val scheduledAt = requireArguments().getString(ARG_SCHEDULED_AT).orEmpty()
        val mobileNumber = requireArguments().getString(ARG_MOBILE_NUMBER).orEmpty()
        val additionalVisitors =
            requireArguments().getString(ARG_ADDITIONAL_VISITORS).orEmpty()
        val foodPreferences = requireArguments().getString(ARG_FOOD_PREFERENCES).orEmpty()
        val occupation = requireArguments().getString(ARG_OCCUPATION).orEmpty()
        val leadTemperature = requireArguments().getString(ARG_LEAD_TEMPERATURE)
            ?.trim()
            ?.lowercase()
        val visitStatus = requireArguments().getString(ARG_VISIT_STATUS)
            ?.trim()
            ?.lowercase()
        val outcome = requireArguments().getString(ARG_OUTCOME)
            ?.trim()
            ?.lowercase()
        val outcomeNotes = requireArguments().getString(ARG_OUTCOME_NOTES).orEmpty()
        val canStartCounselling =
            requireArguments().getBoolean(ARG_CAN_START_COUNSELLING, false)
        val isOngoing = visitStatus == "on_counselling"
        val isCompleted = visitStatus == "completed" || !outcome.isNullOrBlank()

        view.findViewById<TextView>(R.id.tvCounsellingClientName).text =
            clientName.ifBlank { "Client name unavailable" }
        view.findViewById<TextView>(R.id.tvCounsellingProject).text =
            projectName.ifBlank { "Project unavailable" }
        view.findViewById<TextView>(R.id.tvCounsellingBdo).text =
            bdoName.ifBlank { "Not assigned" }
        view.findViewById<TextView>(R.id.tvCounsellingIncharge).text =
            inchargeName.ifBlank { "Not assigned" }
        view.findViewById<TextView>(R.id.tvCounsellingMobile).text =
            mobileNumber.ifBlank { "Not provided" }
        view.findViewById<TextView>(R.id.tvCounsellingOccupation).text =
            occupation.ifBlank { "Not provided" }
        view.findViewById<TextView>(R.id.tvCounsellingAdditionalVisitors).text =
            additionalVisitors.ifBlank { "No additional visitors listed" }
        view.findViewById<TextView>(R.id.tvCounsellingFoodPreferences).text =
            foodPreferences.ifBlank { "Not provided" }
        view.findViewById<TextView>(R.id.tvCounsellingLeadTemperature).apply {
            val badge = when (leadTemperature) {
                "hot" -> Triple("HOT LEAD", R.drawable.bg_sv_status_red, R.attr.colorError)
                "warm" -> Triple("WARM LEAD", R.drawable.bg_sv_status_orange, R.attr.colorWarning)
                "cold" -> Triple("COLD LEAD", R.drawable.bg_sv_status_blue, R.attr.colorAccentPrimary)
                else -> null
            }
            visibility = if (badge == null) View.GONE else View.VISIBLE
            badge?.let { (label, backgroundRes, colorAttr) ->
                text = label
                setBackgroundResource(backgroundRes)
                setTextColor(resolveThemeColor(colorAttr))
            }
        }
        view.findViewById<TextView>(R.id.tvCounsellingSchedule).apply {
            text = scheduledAt
            visibility = if (scheduledAt.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.tvCounsellingVisitStatus).apply {
            when {
                isCompleted -> {
                    text = "COMPLETED"
                    setBackgroundResource(R.drawable.bg_badge_success)
                    setTextColor(resolveThemeColor(R.attr.colorSuccess))
                    visibility = View.VISIBLE
                }
                isOngoing -> {
                    text = "ONGOING"
                    setBackgroundResource(R.drawable.bg_badge_info)
                    setTextColor(resolveThemeColor(R.attr.colorInfo))
                    visibility = View.VISIBLE
                }
                else -> visibility = View.GONE
            }
        }
        view.findViewById<View>(R.id.counsellingOutcomeContainer).visibility =
            if (isCompleted) View.VISIBLE else View.GONE
        view.findViewById<TextView>(R.id.tvCounsellingOutcome).text =
            outcomeLabel(outcome)
        view.findViewById<TextView>(R.id.tvCounsellingOutcomeNotes).apply {
            text = outcomeNotes
            visibility = if (outcomeNotes.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.tvCounsellingAccessNote).apply {
            text = when {
                isCompleted -> "The site visit outcome has been recorded."
                isOngoing -> "Counselling is currently in progress."
                canStartCounselling -> "Confirm only when counselling is actually starting."
                else -> "Visit details only. The assigned BDO, Site Incharge, administrator, or authorised staff can start counselling."
            }
        }
        view.findViewById<View>(R.id.counsellingActions).visibility =
            if (isOngoing || isCompleted) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.btnCancelCounselling).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnStartCounselling).apply {
            visibility =
                if (canStartCounselling && !isOngoing && !isCompleted) View.VISIBLE else View.GONE
            setOnClickListener {
                if (!canStartCounselling || isOngoing || isCompleted) {
                    return@setOnClickListener
                }
                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_SITE_VISIT_ID to siteVisitId,
                        KEY_LEAD_TEMPERATURE to leadTemperature,
                    ),
                )
                dismiss()
            }
        }
    }

    private fun resolveThemeColor(attr: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attr, value, true)
        return value.data
    }

    private fun outcomeLabel(outcome: String?): String = when (outcome) {
        "converted_to_booking" -> "Converted as Booking"
        "not_interested" -> "Client Not Interested"
        "follow_up", "postponed" -> "Follow up"
        "interested" -> "Interested"
        "other" -> "Other"
        else -> "Outcome not recorded"
    }

    companion object {
        const val RESULT_KEY = "site_visit_counselling_confirmed"
        const val KEY_SITE_VISIT_ID = "site_visit_id"
        const val KEY_LEAD_TEMPERATURE = "lead_temperature"

        private const val ARG_SITE_VISIT_ID = "arg_site_visit_id"
        private const val ARG_PROJECT_NAME = "arg_project_name"
        private const val ARG_CLIENT_NAME = "arg_client_name"
        private const val ARG_BDO_NAME = "arg_bdo_name"
        private const val ARG_INCHARGE_NAME = "arg_incharge_name"
        private const val ARG_SCHEDULED_AT = "arg_scheduled_at"
        private const val ARG_MOBILE_NUMBER = "arg_mobile_number"
        private const val ARG_ADDITIONAL_VISITORS = "arg_additional_visitors"
        private const val ARG_FOOD_PREFERENCES = "arg_food_preferences"
        private const val ARG_OCCUPATION = "arg_occupation"
        private const val ARG_LEAD_TEMPERATURE = "arg_lead_temperature"
        private const val ARG_VISIT_STATUS = "arg_visit_status"
        private const val ARG_OUTCOME = "arg_outcome"
        private const val ARG_OUTCOME_NOTES = "arg_outcome_notes"
        private const val ARG_CAN_START_COUNSELLING = "arg_can_start_counselling"

        fun newInstance(
            siteVisitId: String,
            projectName: String?,
            clientName: String?,
            bdoName: String?,
            inchargeName: String?,
            scheduledAt: String?,
            mobileNumber: String?,
            additionalVisitors: String?,
            foodPreferences: String?,
            occupation: String?,
            leadTemperature: String?,
            visitStatus: String?,
            outcome: String?,
            outcomeNotes: String?,
            canStartCounselling: Boolean,
        ) = SiteVisitCounsellingConfirmBottomSheet().apply {
            arguments = bundleOf(
                ARG_SITE_VISIT_ID to siteVisitId,
                ARG_PROJECT_NAME to projectName,
                ARG_CLIENT_NAME to clientName,
                ARG_BDO_NAME to bdoName,
                ARG_INCHARGE_NAME to inchargeName,
                ARG_SCHEDULED_AT to scheduledAt,
                ARG_MOBILE_NUMBER to mobileNumber,
                ARG_ADDITIONAL_VISITORS to additionalVisitors,
                ARG_FOOD_PREFERENCES to foodPreferences,
                ARG_OCCUPATION to occupation,
                ARG_LEAD_TEMPERATURE to leadTemperature,
                ARG_VISIT_STATUS to visitStatus,
                ARG_OUTCOME to outcome,
                ARG_OUTCOME_NOTES to outcomeNotes,
                ARG_CAN_START_COUNSELLING to canStartCounselling,
            )
        }
    }
}
