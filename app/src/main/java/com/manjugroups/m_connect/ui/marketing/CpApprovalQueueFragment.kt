package com.manjugroups.m_connect.ui.marketing

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.ui.common.navigateUp
import androidx.lifecycle.lifecycleScope
import coil.load
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CpApprovalActionRequest
import com.manjugroups.m_connect.network.CpApprovalItem
import com.manjugroups.m_connect.network.CpApprovalRejectRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * GM review queue for OUT-OF-GEOFENCE CP completions held for approval. Lists
 * the completions awaiting THIS GM (server-scoped by the resolved approver),
 * each with the client, the field staff, the place + how far out of geofence,
 * the recorded outcome, and the arrival photo. Approve → the visit completes;
 * Reject (with a remark) → the visit reopens for the same staff.
 */
class CpApprovalQueueFragment : Fragment() {

    private val api = GeoTrackApi.create()
    private lateinit var session: SessionManager

    private lateinit var container: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView

    // Guards against a double-tap firing two approve/reject calls for one item.
    private var submitting = false

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).roundToInt()

    private fun font(res: Int): Typeface? =
        runCatching { ResourcesCompat.getFont(requireContext(), res) }.getOrNull()

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        session = SessionManager(requireContext())
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(24))
        }

        root.addView(TextView(requireContext()).apply {
            text = "CP completions to approve"
            textSize = 18f
            setTextColor(Color.parseColor("#101828"))
            includeFontPadding = false
            typeface = font(R.font.inter_semibold) ?: typeface
        })
        root.addView(TextView(requireContext()).apply {
            text = "Out-of-geofence completions waiting on your approval."
            textSize = 13f
            setTextColor(Color.parseColor("#667085"))
            includeFontPadding = false
            typeface = font(R.font.inter_regular) ?: typeface
            setPadding(0, dp(4), 0, dp(16))
        })

        progress = ProgressBar(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        }
        emptyView = TextView(requireContext()).apply {
            text = "Nothing to approve right now."
            textSize = 14f
            setTextColor(Color.parseColor("#667085"))
            gravity = Gravity.CENTER
            typeface = font(R.font.inter_regular) ?: typeface
            setPadding(0, dp(24), 0, dp(24))
            visibility = View.GONE
        }
        container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(progress)
        root.addView(emptyView)
        root.addView(container)

        // A full page, not a sheet: an approver reviews photos, distances and
        // remarks here, which a half-height sheet made cramped.
        val page = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        page.addView(buildTopBar())
        page.addView(
            ScrollView(requireContext()).apply {
                isFillViewport = true
                addView(root)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f }
            },
        )
        return page
    }

    /** Back bar matching the other pushed detail screens. */
    private fun buildTopBar(): View {
        val bar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        bar.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_arrow_left)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#0B61CA"),
            )
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setPadding(dp(8))
            isClickable = true
            isFocusable = true
            contentDescription = "Back"
            setOnClickListener { navigateUp() }
        })
        bar.addView(TextView(requireContext()).apply {
            text = "CP Approvals"
            textSize = 17f
            setTextColor(Color.parseColor("#101828"))
            includeFontPadding = false
            typeface = font(R.font.inter_semibold) ?: typeface
            setPadding(dp(4), 0, 0, 0)
        })
        return bar
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let {
            it.setTopBarAppearance(Color.WHITE, true)
            it.setTabBarVisible(false)
        }
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        container.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { api.getPendingCpApprovals(session.bearerToken) }
            if (!isAdded) return@launch
            progress.visibility = View.GONE
            result.onSuccess { resp ->
                // A failed response used to be swallowed into an empty list, so a
                // network/route/auth error looked identical to "nothing pending" —
                // the GM saw "Nothing to approve" and reasonably concluded approval
                // was broken. Now a real failure shows a retry instead.
                if (!resp.success && !resp.error.isNullOrBlank()) {
                    showMessage("Couldn't load approvals: ${resp.error}", retry = true)
                    return@onSuccess
                }
                if (resp.items.isEmpty()) {
                    showMessage("Nothing to approve right now.", retry = false)
                    return@onSuccess
                }
                resp.items.forEach { container.addView(buildCard(it)) }
            }.onFailure {
                showMessage(
                    "Couldn't load approvals. Check your connection and tap to retry.",
                    retry = true,
                )
            }
        }
    }

    private fun showMessage(msg: String, retry: Boolean) {
        emptyView.text = msg
        emptyView.visibility = View.VISIBLE
        emptyView.isClickable = retry
        emptyView.setOnClickListener(if (retry) View.OnClickListener { load() } else null)
    }

    private fun buildCard(item: CpApprovalItem): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14))
            background = roundedBg("#FFFFFF", "#E4E7EC")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }

        card.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(requireContext()).apply {
                    text = item.clientName ?: "Client"
                    textSize = 16f
                    setTextColor(Color.parseColor("#101828"))
                    includeFontPadding = false
                    typeface = font(R.font.inter_semibold) ?: typeface
                })
                addView(TextView(requireContext()).apply {
                    text = item.staffName ?: "Field staff"
                    textSize = 12f
                    setTextColor(Color.parseColor("#667085"))
                    includeFontPadding = false
                    typeface = font(R.font.inter_regular) ?: typeface
                    setPadding(0, dp(3), dp(8), 0)
                })
            })
            addView(TextView(requireContext()).apply {
                text = "Pending review"
                textSize = 11f
                setTextColor(Color.parseColor("#B54708"))
                includeFontPadding = false
                typeface = font(R.font.inter_semibold) ?: typeface
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = roundedBg("#FFFAEB", "#FEDF89", 14)
            })
        })

        val visitFacts = listOf(
            "Date & time" to scheduledDateTime(item),
            "Start time" to formatEpoch(item.startedAt),
            "End time" to formatEpoch(item.completedAt ?: item.requestedAt),
            "CP type" to friendlyCpType(item.cpType),
        )
        card.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            visitFacts.chunked(2).forEachIndexed { rowIndex, rowFacts ->
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowFacts.forEachIndexed { columnIndex, fact ->
                        addView(factTile(fact.first, fact.second).also {
                            it.layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ).apply {
                                if (columnIndex == 0) rightMargin = dp(8)
                            }
                        })
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (rowIndex > 0) topMargin = dp(8)
                    }
                })
            }
        })

        val distance = item.distanceMeters?.let { m ->
            val label = if (m >= 1000.0) {
                String.format(java.util.Locale.US, "%.1f km", m / 1000.0)
            } else {
                "${m.roundToInt()} m"
            }
            "$label out of geofence"
        }
        val place = listOfNotNull(item.placeName, distance).joinToString(" · ")
        val evidence = listOfNotNull(
            place.takeIf { it.isNotBlank() }?.let { "Location" to it },
            item.outcome?.takeIf { it.isNotBlank() }
                ?.let { "Outcome" to friendlyCpType(it) },
            item.staffRemark?.takeIf { it.isNotBlank() }
                ?.let { "Staff reason" to it },
        )
        if (evidence.isNotEmpty()) {
            card.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = roundedBg("#F8FAFC", "#EAECF0", 10)
                evidence.forEach { (label, value) ->
                    addView(TextView(requireContext()).apply {
                        text = label
                        textSize = 11f
                        setTextColor(Color.parseColor("#667085"))
                        includeFontPadding = false
                        typeface = font(R.font.inter_medium) ?: typeface
                        setPadding(0, dp(3), 0, 0)
                    })
                    addView(TextView(requireContext()).apply {
                        text = value
                        textSize = 12f
                        setTextColor(
                            Color.parseColor(if (label == "Location") "#B54708" else "#101828"),
                        )
                        includeFontPadding = false
                        typeface = font(R.font.inter_regular) ?: typeface
                        setPadding(0, dp(2), 0, dp(3))
                    })
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            })
        }

        item.photoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            card.addView(ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply {
                    topMargin = dp(10)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBg("#F2F4F7", "#E4E7EC", 8)
                clipToOutline = true
                load(url)
            })
        }

        card.addView(actionButton("View travelled path", "#0B61CA", "#EFF6FF") {
            CpApprovalTripDetailDialog.newInstance(item)
                .show(parentFragmentManager, CpApprovalTripDetailDialog.TAG)
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        })

        // Approve / Reject row
        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(actionButton("Reject", "#B42318", "#FEE4E2") {
            promptReject(item)
        }.also {
            (it.layoutParams as LinearLayout.LayoutParams).apply {
                weight = 1f; rightMargin = dp(8)
            }
        })
        actions.addView(actionButton("Approve", "#FFFFFF", "#169B2F") {
            approve(item)
        }.also {
            (it.layoutParams as LinearLayout.LayoutParams).weight = 1f
        })
        card.addView(actions)
        return card
    }

    private fun friendlyCpType(value: String?): String = value
        ?.takeIf { it.isNotBlank() }
        ?.replace('_', ' ')
        ?.split(' ')
        ?.joinToString(" ") { word ->
            word.lowercase(Locale.US).replaceFirstChar { it.titlecase(Locale.US) }
        }
        ?: "Not recorded"

    private fun scheduledDateTime(item: CpApprovalItem): String {
        val date = item.scheduledDate?.takeIf { it.isNotBlank() } ?: "Not recorded"
        val time = item.scheduledTime?.takeIf { it.isNotBlank() }?.let(::formatClock)
        return listOfNotNull(date, time).joinToString(" · ")
    }

    private fun formatClock(value: String): String {
        val parsed = listOf("HH:mm", "HH:mm:ss").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(value) }.getOrNull()
        } ?: return value
        return SimpleDateFormat("h:mm a", Locale.US).format(parsed)
    }

    private fun formatEpoch(value: Double?): String {
        if (value == null || value <= 0.0) return "Not recorded"
        return SimpleDateFormat("dd MMM yyyy, h:mm a", Locale.US)
            .format(Date(value.toLong()))
    }

    private fun actionButton(
        label: String,
        fg: String,
        bg: String,
        onClick: () -> Unit,
    ): TextView = TextView(requireContext()).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.parseColor(fg))
        includeFontPadding = false
        typeface = font(R.font.inter_semibold) ?: typeface
        setPadding(dp(12), dp(13), dp(12), dp(13))
        background = roundedBg(bg, bg)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun factTile(label: String, value: String) =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = roundedBg("#F8FAFC", "#EAECF0", 10)
            addView(TextView(requireContext()).apply {
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#667085"))
                includeFontPadding = false
                typeface = font(R.font.inter_medium) ?: typeface
            })
            addView(TextView(requireContext()).apply {
                text = value
                textSize = 12f
                setTextColor(Color.parseColor("#101828"))
                includeFontPadding = false
                typeface = font(R.font.inter_semibold) ?: typeface
                setPadding(0, dp(3), 0, 0)
            })
        }

    private fun approve(item: CpApprovalItem) {
        if (submitting) return
        submitting = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                api.approveCpCompletion(
                    session.bearerToken,
                    CpApprovalActionRequest(id = item.id),
                )
            }.getOrNull()
            if (!isAdded) { submitting = false; return@launch }
            submitting = false
            if (resp?.success == true) {
                Toast.makeText(requireContext(), "Approved", Toast.LENGTH_SHORT).show()
                notifyChanged()
                load()
            } else {
                Toast.makeText(
                    requireContext(),
                    resp?.error ?: "Could not approve",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun promptReject(item: CpApprovalItem) {
        val input = EditText(requireContext()).apply {
            hint = "Reason for rejecting"
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.parseColor("#101828"))
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            includeFontPadding = false
            typeface = font(R.font.inter_regular) ?: typeface
            background = ResourcesCompat.getDrawable(
                resources, R.drawable.bg_outcome_field_pill, null,
            )
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val wrap = FrameLayout(requireContext()).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Reject & reassign")
            .setMessage("This reopens the visit for ${item.staffName ?: "the staff"} with your remark.")
            .setView(wrap)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reject") { _, _ ->
                val remark = input.text?.toString()?.trim().orEmpty()
                if (remark.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "A remark is required",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                reject(item, remark)
            }
            .show()
    }

    private fun reject(item: CpApprovalItem, remark: String) {
        if (submitting) return
        submitting = true
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                api.rejectCpCompletion(
                    session.bearerToken,
                    CpApprovalRejectRequest(id = item.id, remark = remark),
                )
            }.getOrNull()
            if (!isAdded) { submitting = false; return@launch }
            submitting = false
            if (resp?.success == true) {
                Toast.makeText(
                    requireContext(),
                    "Rejected & reassigned",
                    Toast.LENGTH_SHORT,
                ).show()
                notifyChanged()
                load()
            } else {
                Toast.makeText(
                    requireContext(),
                    resp?.error ?: "Could not reject",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    // Tells the host (CpVisitsFragment) an approval changed so it can refresh its
    // banner count + list. Fired after each action and on dismiss.
    private fun notifyChanged() {
        runCatching {
            parentFragmentManager.setFragmentResult(RESULT_KEY, android.os.Bundle.EMPTY)
        }
    }

    override fun onDestroyView() {
        // Tell the opener an approval may have changed, the same way the sheet
        // did on dismiss, so the CP list and the library count refresh.
        notifyChanged()
        super.onDestroyView()
    }

    private fun roundedBg(fill: String, stroke: String, radiusDp: Int = 12) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
        }

    companion object {
        // Emitted to the hosting FragmentManager whenever an approval changes or
        // the page is left, so the CP Visits banner + list refresh.
        const val RESULT_KEY = "cp_approval_changed"

        fun newInstance(): CpApprovalQueueFragment = CpApprovalQueueFragment()
    }
}
