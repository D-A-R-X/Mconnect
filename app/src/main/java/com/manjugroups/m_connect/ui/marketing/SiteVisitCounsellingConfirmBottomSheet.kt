package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import android.os.Bundle
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
        val canStartCounselling =
            requireArguments().getBoolean(ARG_CAN_START_COUNSELLING, false)

        view.findViewById<TextView>(R.id.tvCounsellingClientName).text =
            clientName.ifBlank { "Client name unavailable" }
        view.findViewById<TextView>(R.id.tvCounsellingProject).text =
            projectName.ifBlank { "Project unavailable" }
        view.findViewById<TextView>(R.id.tvCounsellingBdo).text =
            bdoName.ifBlank { "Not assigned" }
        view.findViewById<TextView>(R.id.tvCounsellingIncharge).text =
            inchargeName.ifBlank { "Not assigned" }
        view.findViewById<TextView>(R.id.tvCounsellingSchedule).apply {
            text = scheduledAt
            visibility = if (scheduledAt.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.tvCounsellingAccessNote).apply {
            text = if (canStartCounselling) {
                "Confirm only when counselling is actually starting."
            } else {
                "Visit details only. The assigned BDO, Site Incharge, administrator, or authorised staff can start counselling."
            }
        }
        view.findViewById<View>(R.id.btnCancelCounselling).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btnStartCounselling).apply {
            visibility = if (canStartCounselling) View.VISIBLE else View.GONE
            setOnClickListener {
                if (!canStartCounselling) return@setOnClickListener
                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(KEY_SITE_VISIT_ID to siteVisitId),
                )
                dismiss()
            }
        }
    }

    companion object {
        const val RESULT_KEY = "site_visit_counselling_confirmed"
        const val KEY_SITE_VISIT_ID = "site_visit_id"

        private const val ARG_SITE_VISIT_ID = "arg_site_visit_id"
        private const val ARG_PROJECT_NAME = "arg_project_name"
        private const val ARG_CLIENT_NAME = "arg_client_name"
        private const val ARG_BDO_NAME = "arg_bdo_name"
        private const val ARG_INCHARGE_NAME = "arg_incharge_name"
        private const val ARG_SCHEDULED_AT = "arg_scheduled_at"
        private const val ARG_CAN_START_COUNSELLING = "arg_can_start_counselling"

        fun newInstance(
            siteVisitId: String,
            projectName: String?,
            clientName: String?,
            bdoName: String?,
            inchargeName: String?,
            scheduledAt: String?,
            canStartCounselling: Boolean,
        ) = SiteVisitCounsellingConfirmBottomSheet().apply {
            arguments = bundleOf(
                ARG_SITE_VISIT_ID to siteVisitId,
                ARG_PROJECT_NAME to projectName,
                ARG_CLIENT_NAME to clientName,
                ARG_BDO_NAME to bdoName,
                ARG_INCHARGE_NAME to inchargeName,
                ARG_SCHEDULED_AT to scheduledAt,
                ARG_CAN_START_COUNSELLING to canStartCounselling,
            )
        }
    }
}
