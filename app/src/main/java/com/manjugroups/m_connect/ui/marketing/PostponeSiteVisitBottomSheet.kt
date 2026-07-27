package com.manjugroups.m_connect.ui.marketing

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
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
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.PostponeSiteVisitRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PostponeSiteVisitBottomSheet : BottomSheetDialogFragment() {

    private val api by lazy { GeoTrackApi.create() }
    private val session by lazy { SessionManager(requireContext()) }
    private var apiDate: String? = null
    private var apiTime: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.bottom_sheet_postpone_site_visit, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val dateValue = view.findViewById<TextView>(R.id.tvPostponeSvDate)
        val timeValue = view.findViewById<TextView>(R.id.tvPostponeSvTime)
        val reason = view.findViewById<EditText>(R.id.etPostponeSvReason)
        val error = view.findViewById<TextView>(R.id.tvPostponeSvError)
        val confirm = view.findViewById<TextView>(R.id.btnConfirmPostponeSv)

        view.findViewById<View>(R.id.rowPostponeSvDate).setOnClickListener {
            val now = Calendar.getInstance()
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val selected = Calendar.getInstance().apply { set(year, month, day) }
                    apiDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selected.time)
                    dateValue.text = SimpleDateFormat("dd MMM yyyy", Locale.US).format(selected.time)
                    error.visibility = View.GONE
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH),
            ).apply {
                datePicker.minDate = System.currentTimeMillis()
                show()
            }
        }
        view.findViewById<View>(R.id.rowPostponeSvTime).setOnClickListener {
            val now = Calendar.getInstance()
            TimePickerDialog(
                requireContext(),
                { _, hour, minute ->
                    apiTime = String.format(Locale.US, "%02d:%02d", hour, minute)
                    val selected = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                    }
                    timeValue.text = SimpleDateFormat("hh:mm a", Locale.US).format(selected.time)
                },
                now.get(Calendar.HOUR_OF_DAY),
                now.get(Calendar.MINUTE),
                false,
            ).show()
        }
        view.findViewById<View>(R.id.btnCancelPostponeSv).setOnClickListener { dismiss() }
        confirm.setOnClickListener {
            val scheduledDate = apiDate
            if (scheduledDate == null) {
                error.text = "Select the new site visit date"
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            confirm.isEnabled = false
            confirm.text = "Saving..."
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val response = api.postponeSiteVisit(
                        session.bearerToken,
                        PostponeSiteVisitRequest(
                            id = requireArguments().getString(ARG_SITE_VISIT_ID).orEmpty(),
                            scheduledDate = scheduledDate,
                            scheduledTime = apiTime,
                            reason = reason.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                        ),
                    )
                    if (!response.success) {
                        throw IllegalStateException(response.error ?: "Unable to postpone site visit")
                    }
                    setFragmentResult(
                        RESULT_KEY,
                        bundleOf(KEY_SCHEDULED_DATE to scheduledDate),
                    )
                    Toast.makeText(
                        requireContext(),
                        "Site visit postponed to ${dateValue.text}",
                        Toast.LENGTH_LONG,
                    ).show()
                    dismiss()
                } catch (exception: Exception) {
                    error.text = exception.message ?: "Unable to postpone site visit"
                    error.visibility = View.VISIBLE
                    confirm.isEnabled = true
                    confirm.text = "Postpone SV"
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "site_visit_postponed"
        const val KEY_SCHEDULED_DATE = "scheduled_date"
        private const val ARG_SITE_VISIT_ID = "arg_site_visit_id"

        fun newInstance(siteVisitId: String) = PostponeSiteVisitBottomSheet().apply {
            arguments = bundleOf(ARG_SITE_VISIT_ID to siteVisitId)
        }
    }
}
