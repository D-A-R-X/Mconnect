package com.manjugroups.m_connect.ui.fleet

import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateCabRequestBody
import com.manjugroups.m_connect.ui.common.MapPinDropBottomSheet
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * "New Cab Request", the web dialog's fields: purpose, pickup, drop, date
 * (required); time, passengers, notes (optional). Pickup and drop can be
 * typed or picked on the map. The reporting officer is notified to approve.
 * Laid out with the Schedule Site Visit form's components.
 */
class NewCabRequestBottomSheet : BottomSheetDialogFragment() {

    private val session by lazy { SessionManager(requireContext()) }
    private val api by lazy { ApiService.create() }

    private lateinit var etPurpose: EditText
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var etDate: EditText
    private lateinit var etTime: EditText
    private lateinit var etPassengers: EditText
    private lateinit var etNotes: EditText
    private lateinit var btnSubmit: MaterialButton

    private var date: String? = null
    private var time: String? = null
    private var submitting = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener { shown ->
            (shown as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.let { sheet ->
                    sheet.setBackgroundColor(Color.TRANSPARENT)
                    sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                    BottomSheetBehavior.from(sheet).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                        peekHeight = resources.displayMetrics.heightPixels
                    }
                }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.bottom_sheet_new_cab_request, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etPurpose = view.findViewById(R.id.etCabPurpose)
        etFrom = view.findViewById(R.id.etCabFrom)
        etTo = view.findViewById(R.id.etCabTo)
        etDate = view.findViewById(R.id.etCabDate)
        etTime = view.findViewById(R.id.etCabTime)
        etPassengers = view.findViewById(R.id.etCabPassengers)
        etNotes = view.findViewById(R.id.etCabNotes)
        btnSubmit = view.findViewById(R.id.btnCabSubmit)

        view.findViewById<View>(R.id.btnCabFromPin).setOnClickListener { pickOnMap(etFrom, "cab_request_from_pin") }
        view.findViewById<View>(R.id.btnCabToPin).setOnClickListener { pickOnMap(etTo, "cab_request_to_pin") }
        etDate.setOnClickListener { pickDate() }
        etTime.setOnClickListener { pickTime() }
        view.findViewById<View>(R.id.btnCabCancel).setOnClickListener { dismiss() }
        btnSubmit.setOnClickListener { submit() }
    }

    private fun submit() {
        if (submitting) return
        val purpose = etPurpose.text.toString().trim()
        val from = etFrom.text.toString().trim()
        val to = etTo.text.toString().trim()
        val day = date
        if (purpose.isEmpty() || from.isEmpty() || to.isEmpty() || day == null) {
            Toast.makeText(requireContext(), "Purpose, from/to location and date are required", Toast.LENGTH_SHORT).show()
            return
        }
        val passengers = etPassengers.text.toString().trim().toIntOrNull()?.takeIf { it >= 1 }
        submitting = true
        btnSubmit.isEnabled = false
        btnSubmit.text = "Submitting..."
        viewLifecycleOwner.lifecycleScope.launch {
            val error = try {
                val resp = api.createCabRequest(
                    session.bearerToken,
                    CreateCabRequestBody(
                        purpose = purpose,
                        fromLocation = from,
                        toLocation = to,
                        requestedDate = day,
                        requestedTime = time,
                        passengerCount = passengers,
                        notes = etNotes.text.toString().trim().takeIf { it.isNotEmpty() },
                    ),
                )
                if (resp.success) null else resp.error ?: "Couldn't submit the request"
            } catch (e: Exception) {
                CabRequestsFragment.serverMessage(e) ?: e.message ?: "Couldn't submit the request"
            }
            submitting = false
            if (!isAdded) return@launch
            btnSubmit.isEnabled = true
            btnSubmit.text = "Submit request"
            if (error != null) {
                Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(requireContext(), "Cab request submitted successfully", Toast.LENGTH_SHORT).show()
            setFragmentResult(RESULT_KEY, bundleOf("created" to true))
            dismiss()
        }
    }

    /** Fills [target] with the address of a pin dropped on the map. */
    private fun pickOnMap(target: EditText, tag: String) {
        val sheet = MapPinDropBottomSheet.newInstance()
        sheet.setListener { result ->
            if (result.address.isNotBlank()) target.setText(result.address)
        }
        sheet.showOnce(parentFragmentManager, tag)
    }

    /** The app's calendar sheet, one date, today onwards. */
    private fun pickDate() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        val current = date ?: today
        setFragmentResultListener(RESULT_KEY_DATE) { _, bundle ->
            val picked = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            date = picked
            etDate.setText(CabRequestsFragment.formatDate(picked))
        }
        CalendarRangePickerSheet.newInstance(
            title = "Trip Date",
            subtitle = "Select the date you need the cab",
            initialFrom = current,
            initialTo = current,
            minDate = today,
            resultKey = RESULT_KEY_DATE,
            singleSelect = true,
        ).showOnce(parentFragmentManager, "cab_request_calendar")
    }

    private fun pickTime() {
        val c = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, h, min ->
            val stored = String.format(Locale.US, "%02d:%02d", h, min)
            time = stored
            etTime.setText(displayTime(stored))
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
    }

    private fun displayTime(value: String): String = runCatching {
        val parsed = SimpleDateFormat("HH:mm", Locale.US).parse(value)!!
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(parsed)
    }.getOrDefault(value)

    companion object {
        const val RESULT_KEY = "new_cab_request_result"
        private const val RESULT_KEY_DATE = "new_cab_request_date"
    }
}
