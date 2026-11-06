package com.manjugroups.m_connect.ui.hr

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Approving attendance with a value other than the shift-based system result
 * needs a reason (web parity: the web prompts for one). The server says so with
 * "Approval differs from the shift-based system result (absent). Enter an
 * override reason before approving." (HTTP 422 OVERRIDE_REASON_REQUIRED on new
 * servers, HTTP 500 on older ones). Approving used to end there with
 * "Error: HTTP 500".
 */
object ApprovalOverrideReason {

    private val systemResult = Regex("""shift-based system result \(([a-z-]+)\)""")

    /** The system result named in an override error, or null when [message] is another error. */
    fun systemResultIn(message: String?): String? =
        message?.let { systemResult.find(it)?.groupValues?.get(1) }

    fun label(value: String): String = when (value) {
        "half-day" -> "Half-day"
        "weekoff" -> "Week off"
        else -> value.replaceFirstChar { it.uppercase() }
    }

    /** Asks for the reason; [onReason] gets the trimmed text (never blank). */
    fun prompt(context: Context, chosen: String, system: String, onReason: (String) -> Unit) {
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val input = EditText(context).apply {
            hint = "Reason for approving as ${label(chosen)}"
            minLines = 3
            filters = arrayOf(InputFilter.LengthFilter(500))
        }
        val frame = FrameLayout(context).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Reason needed")
            .setMessage(
                "The shift-based result for this day is ${label(system)}. " +
                    "Add a reason to approve it as ${label(chosen)}.",
            )
            .setView(frame)
            .setPositiveButton("Approve", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = input.text?.toString()?.trim().orEmpty()
                if (reason.isEmpty()) {
                    input.error = "Enter a reason"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onReason(reason)
            }
        }
        dialog.show()
    }
}
