package com.manjugroups.m_connect.ui.hr

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffBoundDevice
import com.manjugroups.m_connect.network.StaffIdRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Staff security actions on mobile — the web Security tab's Bound Mobile
 * Device card, for an HR/admin who is away from a desk.
 *
 * Two deliberately separate actions:
 *  - Reset device  — clears the lock so the next login binds a NEW phone, and
 *                    ends the old phone's sessions. For a staff who changed
 *                    handset.
 *  - Force logout  — ends the sessions but KEEPS the lock. For a lost or
 *                    handed-over phone, where the session must die without
 *                    letting any new handset claim the account.
 *
 * Both are gated server-side by `staff.resetDeviceBinding`; the caller decides
 * only whether to open this sheet.
 */
class StaffSecurityBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var staffId: String = ""
    private var staffName: String = ""
    private var inFlight = false

    private lateinit var container: LinearLayout
    private lateinit var progress: ProgressBar

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        session = SessionManager(requireContext())
        staffId = arguments?.getString(ARG_STAFF_ID).orEmpty()
        staffName = arguments?.getString(ARG_STAFF_NAME).orEmpty()

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
        }
        root.addView(TextView(requireContext()).apply {
            text = "Device & Access"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 18f
            setTextColor(Color.parseColor("#101828"))
        })
        root.addView(TextView(requireContext()).apply {
            text = staffName.ifBlank { "This employee" }
            textSize = 13f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(2), 0, dp(14))
        })

        progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        root.addView(progress)

        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(container)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        container.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val binding = runCatching {
                api.getStaffSecurity(session.bearerToken, staffId)
            }.getOrNull()
            if (!isAdded) return@launch
            progress.visibility = View.GONE
            container.visibility = View.VISIBLE
            container.removeAllViews()

            if (binding?.success != true) {
                container.addView(label(binding?.error ?: "Couldn't load device details"))
                return@launch
            }
            renderBinding(binding.binding)
        }
    }

    private fun renderBinding(device: StaffBoundDevice?) {
        if (device == null || !device.bound) {
            container.addView(label("No device is locked to this account."))
            container.addView(
                label("The next mobile sign-in will bind whichever phone is used."),
            )
            // Sessions can still exist without a lock, so the logout action
            // stays available here.
            container.addView(actionButton("Force mobile logout", "#B42318") {
                confirm(
                    "Force mobile logout?",
                    "Signs ${staffName.ifBlank { "this employee" }} out of the app on " +
                        "every phone. Web sessions are unaffected.",
                ) { forceLogout() }
            })
            return
        }

        container.addView(row("Device", listOfNotNull(
            device.deviceModel?.takeIf { it.isNotBlank() },
            device.platform?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Unknown device" }))
        device.deviceId?.let { container.addView(row("Device ID", it)) }
        device.batteryPct?.let { container.addView(row("Battery", "${it.toInt()}%")) }
        device.ip?.let { container.addView(row("IP address", it)) }
        device.boundAt?.let { container.addView(row("Bound at", formatTime(it))) }
        device.lastSeenAt?.let { container.addView(row("Last seen", formatTime(it))) }

        container.addView(actionButton("Reset device lock", "#B54708") {
            confirm(
                "Reset device lock?",
                "Clears the lock so the next mobile sign-in binds a new phone, " +
                    "and signs the current phone out. Use this when the staff has " +
                    "changed handset.",
            ) { resetDevice() }
        })
        container.addView(actionButton("Force mobile logout", "#B42318") {
            confirm(
                "Force mobile logout?",
                "Signs them out of the app but KEEPS the account locked to this " +
                    "phone. Use this for a lost or handed-over device.",
            ) { forceLogout() }
        })
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Continue") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetDevice() = runAction {
        val resp = api.resetStaffDevice(session.bearerToken, StaffIdRequest(staffId))
        if (resp.success) "Device lock cleared" else resp.error ?: "Couldn't reset the device"
    }

    private fun forceLogout() = runAction {
        val resp = api.forceStaffMobileLogout(session.bearerToken, StaffIdRequest(staffId))
        if (resp.success) {
            val n = resp.signedOut ?: 0
            if (n > 0) "Signed out of $n session(s)" else "No active mobile session"
        } else {
            resp.error ?: "Couldn't sign them out"
        }
    }

    /** Serialises the two actions so a double-tap can't fire both. */
    private fun runAction(block: suspend () -> String) {
        if (inFlight) return
        inFlight = true
        viewLifecycleOwner.lifecycleScope.launch {
            val message = try {
                block()
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            }
            inFlight = false
            if (!isAdded) return@launch
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            // Re-read rather than guessing the new state.
            load()
            parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle.EMPTY)
        }
    }

    // ── small view helpers (this sheet is built in code, like the CP queue) ──

    private fun row(label: String, value: String): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(context).apply {
                text = label.uppercase(Locale.US)
                textSize = 10f
                setTextColor(Color.parseColor("#98A2B3"))
            })
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(Color.parseColor("#101828"))
            })
        }

    private fun label(text: String): View = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#475467"))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun actionButton(text: String, colorHex: String, onClick: () -> Unit): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(colorHex))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }

    private fun formatTime(ms: Double): String =
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(Date(ms.toLong()))

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val RESULT_KEY = "staff_security_changed"
        private const val ARG_STAFF_ID = "arg_staff_id"
        private const val ARG_STAFF_NAME = "arg_staff_name"

        fun show(fm: FragmentManager, staffId: String, staffName: String?) {
            if (fm.findFragmentByTag("staff_security") != null) return
            StaffSecurityBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_STAFF_ID, staffId)
                    putString(ARG_STAFF_NAME, staffName.orEmpty())
                }
            }.show(fm, "staff_security")
        }
    }
}
