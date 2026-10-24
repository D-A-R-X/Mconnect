package com.manjugroups.m_connect.ui.fleet

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateCabRequestBody
import com.manjugroups.m_connect.ui.common.MapPinDropBottomSheet
import com.manjugroups.m_connect.ui.common.showOnce
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * "New Cab Request", the web dialog's fields: purpose, pickup, drop, date
 * (required); time, passengers, notes (optional). Pickup and drop can be
 * typed or picked on the map. The reporting officer is notified to approve.
 */
class NewCabRequestBottomSheet : BottomSheetDialogFragment() {

    private val session by lazy { SessionManager(requireContext()) }
    private val api by lazy { ApiService.create() }

    private lateinit var etPurpose: EditText
    private lateinit var etFrom: EditText
    private lateinit var etTo: EditText
    private lateinit var tvDate: TextView
    private lateinit var tvTime: TextView
    private lateinit var etPassengers: EditText
    private lateinit var etNotes: EditText
    private lateinit var btnSubmit: TextView

    private var date: String? = null
    private var time: String? = null
    private var submitting = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        form.addView(TextView(ctx).apply {
            text = "New Cab Request"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#101828"))
        })
        form.addView(TextView(ctx).apply {
            text = "Fill in the trip details. Your reporting officer will be notified to approve."
            textSize = 13f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(4), 0, dp(8))
        })

        etPurpose = field(form, "Purpose *", "Site visit, client meeting...")
        etFrom = locationField(form, "From location (pickup) *", "Pickup address")
        etTo = locationField(form, "To location (drop) *", "Drop address")

        tvDate = picker(form, "Date *", "Select date") { pickDate() }
        tvTime = picker(form, "Time", "Select time (optional)") { pickTime() }

        etPassengers = field(form, "Passengers", "1").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        etNotes = field(form, "Notes", "Any additional info...").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(18), 0, 0)
        }
        buttons.addView(TextView(ctx).apply {
            text = "Cancel"
            textSize = 14f
            setTextColor(Color.parseColor("#344054"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded("#FFFFFF", "#D0D5DD")
            setOnClickListener { dismiss() }
        })
        btnSubmit = TextView(ctx).apply {
            text = "Submit request"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded("#9B5CF6", "#9B5CF6")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(10) }
            setOnClickListener { submit() }
        }
        buttons.addView(btnSubmit)
        form.addView(buttons)

        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(form)
        }
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
        btnSubmit.alpha = 0.6f
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
            btnSubmit.alpha = 1f
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

    private fun pickDate() {
        val c = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            date = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            tvDate.text = CabRequestsFragment.formatDate(date)
            tvDate.setTextColor(Color.parseColor("#101828"))
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun pickTime() {
        val c = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, h, min ->
            time = String.format(Locale.US, "%02d:%02d", h, min)
            tvTime.text = time
            tvTime.setTextColor(Color.parseColor("#101828"))
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
    }

    // ── form helpers ────────────────────────────────────────────────────────

    private fun label(parent: LinearLayout, text: String) {
        parent.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#344054"))
            setPadding(0, dp(12), 0, dp(6))
        })
    }

    private fun field(parent: LinearLayout, title: String, hint: String): EditText {
        label(parent, title)
        val et = EditText(requireContext()).apply {
            this.hint = hint
            textSize = 14f
            setTextColor(Color.parseColor("#101828"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded("#FFFFFF", "#D0D5DD")
        }
        parent.addView(et, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return et
    }

    /** A text field with a map button that fills it from a dropped pin. */
    private fun locationField(parent: LinearLayout, title: String, hint: String): EditText {
        label(parent, title)
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded("#FFFFFF", "#D0D5DD")
        }
        val et = EditText(requireContext()).apply {
            this.hint = hint
            textSize = 14f
            background = null
            setTextColor(Color.parseColor("#101828"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(14), dp(12), dp(8), dp(12))
        }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(requireContext()).apply {
            text = "📍 Map"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B61CA"))
            setPadding(dp(10), dp(10), dp(12), dp(10))
            setOnClickListener {
                val sheet = MapPinDropBottomSheet.newInstance()
                sheet.setListener { result ->
                    if (result.address.isNotBlank()) et.setText(result.address)
                }
                sheet.showOnce(parentFragmentManager, "cab_request_map_pin")
            }
        })
        parent.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return et
    }

    private fun picker(parent: LinearLayout, title: String, hint: String, onClick: () -> Unit): TextView {
        label(parent, title)
        val tv = TextView(requireContext()).apply {
            text = hint
            textSize = 14f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded("#FFFFFF", "#D0D5DD")
            setOnClickListener { onClick() }
        }
        parent.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return tv
    }

    private fun rounded(fill: String, stroke: String) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(Color.parseColor(fill))
        setStroke(dp(1), Color.parseColor(stroke))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RESULT_KEY = "new_cab_request_result"
    }
}
