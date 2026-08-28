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
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffBoundDevice
import com.manjugroups.m_connect.network.PasswordExpiryExemptRequest
import com.manjugroups.m_connect.network.SetStaffPasswordRequest
import com.manjugroups.m_connect.network.StaffIdRequest
import com.manjugroups.m_connect.network.StaffPasswordStatus
import kotlinx.coroutines.async
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

    /**
     * Which action the caller wants. The Security screen's tabs pass this so a
     * tab is a real choice rather than a label - "Device Reset" must not also
     * offer Force Logout, or the tab means nothing. Null (the staff-detail
     * entry point) shows everything, as before.
     */
    private var focus: String? = null

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
        focus = arguments?.getString(ARG_FOCUS)

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
            setBackgroundColor(Color.WHITE)
        }

        // Drag handle — the app's other sheets have one; without it this read
        // as a page that had been dropped in rather than a sheet.
        root.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#E4E7EC"))
            }
        })

        // Avatar + name header, matching the staff rows this sheet opens from,
        // so it is obvious WHOSE access is about to change.
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        header.addView(TextView(requireContext()).apply {
            text = initials(staffName)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B61CA"))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#EAF4FF"))
            }
        })
        header.addView(
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginStart = dp(12) }
                addView(TextView(context).apply {
                    text = staffName.ifBlank { "This employee" }
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 17f
                    setTextColor(Color.parseColor("#101828"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(context).apply {
                    text = "Device & access"
                    textSize = 12f
                    setTextColor(Color.parseColor("#667085"))
                    setPadding(0, dp(2), 0, 0)
                })
            },
        )
        root.addView(header)

        progress = ProgressBar(requireContext()).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(28)
                bottomMargin = dp(28)
            }
        }
        root.addView(progress)

        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        // Scrollable: the password section plus a full device card can exceed
        // the sheet's height on a short screen.
        root.addView(
            ScrollView(requireContext()).apply {
                isVerticalScrollBarEnabled = false
                addView(container)
            },
        )
        return root
    }

    private fun initials(name: String): String =
        name.trim().split(" ").filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    /**
     * Loads the device binding and the password status.
     *
     * The two calls are INDEPENDENT and are fired together. Awaiting them one
     * after the other meant a spinner for the sum of both — measured at 9.0s +
     * 7.1s on a real device, which reads as a hung sheet rather than a slow
     * one. They now overlap, and the device card is painted the moment its own
     * response lands instead of waiting on the password call.
     */
    private fun load() {
        progress.visibility = View.VISIBLE
        container.visibility = View.GONE
        container.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val bindingDeferred = async {
                runCatching { api.getStaffSecurity(session.bearerToken, staffId) }
                    .getOrNull()
            }
            val passwordDeferred = async {
                runCatching { api.getStaffPasswordStatus(session.bearerToken, staffId) }
                    .getOrNull()
            }

            val binding = bindingDeferred.await()
            if (!isAdded || view == null) return@launch
            progress.visibility = View.GONE
            container.visibility = View.VISIBLE
            if (binding?.success != true) {
                container.addView(
                    noticeCard(
                        title = "Couldn't load device details",
                        body = binding?.error ?: "Check your connection and try again.",
                        accent = "#B42318",
                        fill = "#FEF3F2",
                        stroke = "#FDA29B",
                    ),
                )
            } else {
                renderBinding(binding.binding)
            }

            // Appended when it arrives. A separate permission (staff.password),
            // so a failure here just omits the section rather than failing the
            // whole sheet.
            val passwordStatus = passwordDeferred.await()
            if (!isAdded || view == null) return@launch
            renderPassword(passwordStatus?.takeIf { it.success }?.status)
        }
    }

    private fun renderBinding(device: StaffBoundDevice?) {
        if (device == null || !device.bound) {
            container.addView(
                noticeCard(
                    title = "No device locked",
                    body = "The next mobile sign-in will bind whichever phone is used.",
                    accent = "#667085",
                    fill = "#F9FAFB",
                    stroke = "#EAECF0",
                ),
            )
            // Sessions can still exist without a lock, so the logout action
            // stays available here.
            if (showsForceLogout) {
                container.addView(
                    pillButton("Force mobile logout", Style.DANGER) {
                        confirm(
                            "Force mobile logout?",
                            "Signs ${staffName.ifBlank { "this employee" }} out of the app on " +
                                "every phone. Web sessions are unaffected.",
                        ) { forceLogout() }
                    },
                )
            }
            return
        }

        // One card for the device rather than a flat run of label/value pairs —
        // these belong together, and the loose stack read as debug output.
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F9FAFB"))
                setStroke(dp(1), Color.parseColor("#EAECF0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }

        // Headline: the handset itself, with a live "locked" chip.
        val top = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
                addView(TextView(context).apply {
                    text = listOfNotNull(
                        device.deviceModel?.takeIf { it.isNotBlank() },
                        device.platform?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ").ifBlank { "Unknown device" }
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 15f
                    setTextColor(Color.parseColor("#101828"))
                })
                device.batteryPct?.let { pct ->
                    addView(TextView(context).apply {
                        text = "Battery ${pct.toInt()}%"
                        textSize = 12f
                        setTextColor(Color.parseColor("#667085"))
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            },
        )
        top.addView(chip("Locked", "#067647", "#ECFDF3"))
        card.addView(top)

        card.addView(cardDivider())

        // Details, two per line where they fit — a full-width row each made the
        // card twice as tall as it needed to be.
        device.deviceId?.let { card.addView(detailRow("Device ID", it, mono = true)) }
        card.addView(
            detailPair(
                "IP address", device.ip?.takeIf { it.isNotBlank() } ?: "—",
                "Bound", device.boundAt?.let { shortTime(it) } ?: "—",
            ),
        )
        device.lastSeenAt?.let { card.addView(detailRow("Last seen", formatTime(it))) }
        container.addView(card)

        if (showsDeviceReset) {
            container.addView(
                pillButton("Reset device lock", Style.WARN) {
                    confirm(
                        "Reset device lock?",
                        "Clears the lock so the next mobile sign-in binds a new phone, " +
                            "and signs the current phone out. Use this when the staff has " +
                            "changed handset.",
                    ) { resetDevice() }
                },
            )
        }
        if (showsForceLogout) {
            container.addView(
                pillButton("Force mobile logout", Style.DANGER) {
                    confirm(
                        "Force mobile logout?",
                        "Signs them out of the app but KEEPS the account locked to this " +
                            "phone. Use this for a lost or handed-over device.",
                    ) { forceLogout() }
                },
            )
        }
    }

    /**
     * Password card — the other half of the web Security tab.
     *
     * Gated by `staff.password`, which is a DIFFERENT right from the device
     * actions above, so this section is loaded separately and simply omitted
     * when the caller does not hold it (the request 403s and we render nothing
     * rather than an error).
     */
    private fun renderPassword(status: StaffPasswordStatus?) {
        if (!showsPassword) return
        if (status == null) {
            // Opened FROM the Password tab but the status did not load — say so.
            // Otherwise the sheet shows a device card and no actions at all,
            // which reads as a dead end rather than a failure.
            if (focus == FOCUS_PASSWORD) {
                container.addView(
                    noticeCard(
                        title = "Password details unavailable",
                        body = "You may not have the password permission, or the " +
                            "request failed. Pull to refresh the list and try again.",
                        accent = "#B54708",
                        fill = "#FFFAEB",
                        stroke = "#FEC84B",
                    ),
                )
            }
            return
        }
        container.addView(divider())
        container.addView(sectionTitle("Password"))

        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F9FAFB"))
                setStroke(dp(1), Color.parseColor("#EAECF0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }

        val head = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(requireContext()).apply {
            text = if (status.hasPassword) "Password is set" else "No password set"
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setTextColor(Color.parseColor("#101828"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            )
        })
        head.addView(
            if (status.hasPassword) chip("Set", "#067647", "#ECFDF3")
            else chip("Not set", "#B54708", "#FFFAEB"),
        )
        card.addView(head)

        if (status.mustChangePassword) {
            card.addView(TextView(requireContext()).apply {
                text = "Must change at next login"
                textSize = 12f
                setTextColor(Color.parseColor("#B54708"))
                setPadding(0, dp(6), 0, 0)
            })
        }
        status.passwordUpdatedAt?.let {
            card.addView(detailRow("Last changed", formatTime(it)))
        }
        if (status.passwordExpiryExempt) {
            card.addView(TextView(requireContext()).apply {
                text = "Exempt from password expiry"
                textSize = 12f
                setTextColor(Color.parseColor("#667085"))
                setPadding(0, dp(6), 0, 0)
            })
        }
        container.addView(card)

        container.addView(
            pillButton("Set a new password", Style.PRIMARY) { promptSetPassword() },
        )

        // "Don't ask to change password" — same switch the web tab has. Neutral
        // styling: it is a setting, not a destructive action.
        val exempt = status.passwordExpiryExempt
        container.addView(
            pillButton(
                if (exempt) "Re-enable password expiry" else "Exempt from password expiry",
                Style.WARN,
            ) {
                confirm(
                    if (exempt) "Re-enable expiry?" else "Exempt from expiry?",
                    if (exempt) {
                        "This staff will be asked to change their password again on the normal schedule."
                    } else {
                        "This staff will stop being asked to change their password on the normal schedule."
                    },
                ) { setExpiryExempt(!exempt) }
            },
        )
    }

    /**
     * Collects a new password.
     *
     * The value is sent straight to the server, which hashes it and applies the
     * strength policy; it is never stored or echoed back by the app. The
     * "must change at next login" default matches the web tab.
     */
    private fun promptSetPassword() {
        val ctx = requireContext()
        val wrapper = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(16), dp(22), 0)
        }

        wrapper.addView(TextView(ctx).apply {
            text = "NEW PASSWORD"
            textSize = 10f
            letterSpacing = 0.04f
            setTextColor(Color.parseColor("#98A2B3"))
            setPadding(0, 0, 0, dp(6))
        })

        // Boxed field with a show/hide toggle. A bare EditText gave no sense of
        // a form, and an admin typing a password they must then read out to the
        // staff has to be able to SEE it.
        val fieldRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#D0D5DD"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52),
            )
        }
        val input = android.widget.EditText(ctx).apply {
            hint = "Enter a new password"
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setTextColor(Color.parseColor("#101828"))
            background = null
            textSize = 15f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f,
            )
        }
        fieldRow.addView(input)
        fieldRow.addView(TextView(ctx).apply {
            text = "Show"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B61CA"))
            setPadding(dp(8), dp(6), dp(4), dp(6))
            isClickable = true
            setOnClickListener {
                val hidden = input.inputType and
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
                input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    if (hidden) {
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    } else {
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                text = if (hidden) "Hide" else "Show"
                input.setSelection(input.text?.length ?: 0)
            }
        })
        wrapper.addView(fieldRow)

        wrapper.addView(TextView(ctx).apply {
            text = "At least 8 characters, with an upper and lower case letter, " +
                "a number and a symbol."
            textSize = 11f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(8), 0, dp(2))
        })
        wrapper.addView(TextView(ctx).apply {
            text = "They will be asked to change it at their next login."
            textSize = 11f
            setTextColor(Color.parseColor("#B54708"))
            setPadding(0, dp(4), 0, 0)
        })

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Set a new password")
            .setView(wrapper)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        // Bound manually so a blank entry does not dismiss the dialog and lose
        // what was typed — setPositiveButton's own listener always closes.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(Color.parseColor("#0B61CA"))
            setOnClickListener {
                val value = input.text?.toString().orEmpty()
                if (value.isBlank()) {
                    Toast.makeText(ctx, "Enter a password", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                savePassword(value)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            ?.setTextColor(Color.parseColor("#667085"))
    }

    private fun savePassword(newPassword: String) = runAction {
        val resp = api.setStaffPassword(
            session.bearerToken,
            SetStaffPasswordRequest(
                staffId = staffId,
                newPassword = newPassword,
                mustChangePassword = true,
            ),
        )
        if (resp.success) {
            "Password updated — they must change it at next login"
        } else {
            resp.error ?: "Couldn't set the password"
        }
    }

    private fun setExpiryExempt(exempt: Boolean) = runAction {
        val resp = api.setStaffPasswordExpiryExempt(
            session.bearerToken,
            PasswordExpiryExemptRequest(staffId = staffId, exempt = exempt),
        )
        if (resp.success) {
            if (exempt) "Exempted from password expiry" else "Password expiry re-enabled"
        } else {
            resp.error ?: "Couldn't update the setting"
        }
    }

    private fun sectionTitle(text: String): View = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#101828"))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun divider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
        ).apply { topMargin = dp(16); bottomMargin = dp(8) }
        setBackgroundColor(Color.parseColor("#EAECF0"))
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

    /** Visual weight of an action. Destructive actions must not look alike. */
    private enum class Style { PRIMARY, WARN, DANGER }

    /**
     * A real, full-width pill button.
     *
     * These were bare coloured TextViews — the same mistake as the collections
     * "Not Collected" control: a destructive action that reads as a caption and
     * gives no press feedback. Same 54dp / 27dp geometry as every other footer
     * action in the app.
     */
    private fun pillButton(text: String, style: Style, onClick: () -> Unit): View {
        val (fg, fill, stroke) = when (style) {
            Style.PRIMARY -> Triple("#FFFFFF", "#0B61CA", "#0B61CA")
            Style.WARN -> Triple("#B54708", "#FFFAEB", "#FEC84B")
            Style.DANGER -> Triple("#B42318", "#FEF3F2", "#FDA29B")
        }
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(fg))
            background = GradientDrawable().apply {
                cornerRadius = dp(27).toFloat()
                setColor(Color.parseColor(fill))
                setStroke(dp(1), Color.parseColor(stroke))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54),
            ).apply { topMargin = dp(12) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** Small status pill (Locked / Password set / …). */
    private fun chip(text: String, fg: String, fill: String): View =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(fg))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor(fill))
            }
        }

    private fun noticeCard(
        title: String,
        body: String,
        accent: String,
        fill: String,
        stroke: String,
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) }
        addView(TextView(context).apply {
            text = title
            setTypeface(typeface, Typeface.BOLD)
            textSize = 14f
            setTextColor(Color.parseColor(accent))
        })
        addView(TextView(context).apply {
            text = body
            textSize = 12f
            setTextColor(Color.parseColor("#667085"))
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun cardDivider(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
        ).apply { topMargin = dp(14); bottomMargin = dp(12) }
        setBackgroundColor(Color.parseColor("#EAECF0"))
    }

    private fun detailLabel(text: String) = TextView(requireContext()).apply {
        this.text = text.uppercase(Locale.US)
        textSize = 10f
        letterSpacing = 0.04f
        setTextColor(Color.parseColor("#98A2B3"))
    }

    private fun detailValue(text: String, mono: Boolean) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#101828"))
        // A device id is a token to compare, not prose — monospace makes
        // checking it against another screen actually possible.
        if (mono) typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, dp(2), 0, 0)
    }

    private fun detailRow(label: String, value: String, mono: Boolean = false): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(detailLabel(label))
            addView(detailValue(value, mono))
        }

    /** Two short fields side by side, so the card does not run twice as tall. */
    private fun detailPair(
        leftLabel: String,
        leftValue: String,
        rightLabel: String,
        rightValue: String,
    ): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
                addView(detailLabel(leftLabel))
                addView(detailValue(leftValue, mono = false).apply { maxLines = 1 })
            },
        )
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                ).apply { marginStart = dp(12) }
                addView(detailLabel(rightLabel))
                addView(detailValue(rightValue, mono = false).apply { maxLines = 1 })
            },
        )
    }

    /** "21 Aug, 12:36 pm" — the year is noise next to a full timestamp. */
    private fun shortTime(ms: Double): String =
        SimpleDateFormat("d MMM, h:mm a", Locale.US).format(Date(ms.toLong()))

    private fun formatTime(ms: Double): String =
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(Date(ms.toLong()))

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private val showsDeviceReset get() = focus == null || focus == FOCUS_DEVICE_RESET
    private val showsForceLogout get() = focus == null || focus == FOCUS_STAFF_LOGIN
    private val showsPassword get() = focus == null || focus == FOCUS_PASSWORD

    companion object {
        const val RESULT_KEY = "staff_security_changed"
        const val FOCUS_DEVICE_RESET = "device_reset"
        const val FOCUS_STAFF_LOGIN = "staff_login"
        const val FOCUS_PASSWORD = "password"
        private const val ARG_STAFF_ID = "arg_staff_id"
        private const val ARG_STAFF_NAME = "arg_staff_name"
        private const val ARG_FOCUS = "arg_focus"

        fun show(
            fm: FragmentManager,
            staffId: String,
            staffName: String?,
            /** One of the FOCUS_* keys, or null to show every action. */
            focus: String? = null,
        ) {
            if (fm.findFragmentByTag("staff_security") != null) return
            StaffSecurityBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_STAFF_ID, staffId)
                    putString(ARG_STAFF_NAME, staffName.orEmpty())
                    focus?.let { putString(ARG_FOCUS, it) }
                }
            }.show(fm, "staff_security")
        }
    }
}
