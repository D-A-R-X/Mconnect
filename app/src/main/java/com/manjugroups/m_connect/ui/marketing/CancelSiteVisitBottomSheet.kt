package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CancelSiteVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.launch

class CancelSiteVisitBottomSheet : BottomSheetDialogFragment() {

    private val api by lazy { GeoTrackApi.create() }
    private val session by lazy { SessionManager(requireContext()) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_cancel_site_visit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val reason = view.findViewById<EditText>(R.id.etCancelSvReason)
        val error = view.findViewById<TextView>(R.id.tvCancelSvError)
        val confirm = view.findViewById<TextView>(R.id.btnConfirmCancelSv)

        view.findViewById<View>(R.id.btnKeepSiteVisit).setOnClickListener { dismiss() }
        confirm.setOnClickListener {
            confirm.isEnabled = false
            confirm.text = "Cancelling..."
            error.visibility = View.GONE
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val response = api.cancelSiteVisit(
                        session.bearerToken,
                        CancelSiteVisitRequest(
                            id = requireArguments().getString(ARG_SITE_VISIT_ID).orEmpty(),
                            reason = reason.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        ),
                    )
                    if (!response.success) {
                        throw IllegalStateException(response.error ?: "Unable to cancel site visit")
                    }
                    setFragmentResult(RESULT_KEY, bundleOf(KEY_CANCELLED to true))
                    Toast.makeText(requireContext(), "Site visit cancelled", Toast.LENGTH_LONG).show()
                    dismiss()
                } catch (exception: Exception) {
                    error.text = exception.message ?: "Unable to cancel site visit"
                    error.visibility = View.VISIBLE
                    confirm.isEnabled = true
                    confirm.text = "Cancel visit"
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "site_visit_cancelled"
        const val KEY_CANCELLED = "cancelled"
        private const val ARG_SITE_VISIT_ID = "arg_site_visit_id"

        fun newInstance(siteVisitId: String) = CancelSiteVisitBottomSheet().apply {
            arguments = bundleOf(ARG_SITE_VISIT_ID to siteVisitId)
        }
    }
}
