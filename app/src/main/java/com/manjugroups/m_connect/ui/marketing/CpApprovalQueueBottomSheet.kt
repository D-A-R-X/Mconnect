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
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CpApprovalActionRequest
import com.manjugroups.m_connect.network.CpApprovalItem
import com.manjugroups.m_connect.network.CpApprovalRejectRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * GM review queue for OUT-OF-GEOFENCE CP completions held for approval. Lists
 * the completions awaiting THIS GM (server-scoped by the resolved approver),
 * each with the client, the field staff, the place + how far out of geofence,
 * the recorded outcome, and the arrival photo. Approve → the visit completes;
 * Reject (with a remark) → the visit reopens for the same staff.
 */
class CpApprovalQueueBottomSheet : BottomSheetDialogFragment() {

    private val api = GeoTrackApi.create()
    private lateinit var session: SessionManager

    private lateinit var container: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var emptyView: TextView

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
            setPadding(dp(20), dp(12), dp(20), dp(24))
            background = ResourcesCompat.getDrawable(
                resources, R.drawable.bg_bottom_sheet_white_rounded, null,
            )
        }

        // Drag handle
        root.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(16)
            }
            background = ResourcesCompat.getDrawable(
                resources, R.drawable.bg_grey_rounded, null,
            )
        })

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

        return ScrollView(requireContext()).apply { addView(root) }
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
            val items = runCatching {
                api.getPendingCpApprovals(session.bearerToken).items
            }.getOrElse { emptyList() }
            if (!isAdded) return@launch
            progress.visibility = View.GONE
            if (items.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                return@launch
            }
            items.forEach { container.addView(buildCard(it)) }
        }
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

        card.addView(TextView(requireContext()).apply {
            text = item.clientName ?: "Client"
            textSize = 15f
            setTextColor(Color.parseColor("#101828"))
            includeFontPadding = false
            typeface = font(R.font.inter_semibold) ?: typeface
        })
        card.addView(TextView(requireContext()).apply {
            text = "by ${item.staffName ?: "Field staff"}"
            textSize = 12f
            setTextColor(Color.parseColor("#475467"))
            includeFontPadding = false
            typeface = font(R.font.inter_regular) ?: typeface
            setPadding(0, dp(2), 0, 0)
        })

        val distance = item.distanceMeters?.let { "${it.roundToInt()} m out of geofence" }
        val place = listOfNotNull(item.placeName, distance).joinToString(" · ")
        if (place.isNotBlank()) {
            card.addView(TextView(requireContext()).apply {
                text = place
                textSize = 12f
                setTextColor(Color.parseColor("#B54708"))
                includeFontPadding = false
                typeface = font(R.font.inter_medium) ?: typeface
                setPadding(0, dp(6), 0, 0)
            })
        }
        item.outcome?.takeIf { it.isNotBlank() }?.let { outcome ->
            card.addView(TextView(requireContext()).apply {
                text = "Outcome: ${outcome.replace('_', ' ')}"
                textSize = 12f
                setTextColor(Color.parseColor("#475467"))
                includeFontPadding = false
                typeface = font(R.font.inter_regular) ?: typeface
                setPadding(0, dp(2), 0, 0)
            })
        }
        // The staff's reason for completing away from the client location.
        item.staffRemark?.takeIf { it.isNotBlank() }?.let { remark ->
            card.addView(TextView(requireContext()).apply {
                text = "Staff reason: $remark"
                textSize = 12f
                setTextColor(Color.parseColor("#101828"))
                includeFontPadding = false
                typeface = font(R.font.inter_regular) ?: typeface
                setPadding(0, dp(4), 0, 0)
            })
        }

        item.photoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            card.addView(ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply {
                    topMargin = dp(10)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(url)
            })
        }

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

    private fun approve(item: CpApprovalItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                api.approveCpCompletion(
                    session.bearerToken,
                    CpApprovalActionRequest(id = item.id),
                )
            }.getOrNull()
            if (!isAdded) return@launch
            if (resp?.success == true) {
                Toast.makeText(requireContext(), "Approved", Toast.LENGTH_SHORT).show()
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
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                api.rejectCpCompletion(
                    session.bearerToken,
                    CpApprovalRejectRequest(id = item.id, remark = remark),
                )
            }.getOrNull()
            if (!isAdded) return@launch
            if (resp?.success == true) {
                Toast.makeText(
                    requireContext(),
                    "Rejected & reassigned",
                    Toast.LENGTH_SHORT,
                ).show()
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

    private fun roundedBg(fill: String, stroke: String) =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(1), Color.parseColor(stroke))
        }

    companion object {
        fun show(fm: FragmentManager) {
            if (fm.findFragmentByTag("cp_approval_queue") != null) return
            CpApprovalQueueBottomSheet().show(fm, "cp_approval_queue")
        }
    }
}
