package com.manjugroups.m_connect.ui.projects

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.widget.NestedScrollView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.DailyLogAttachment
import com.manjugroups.m_connect.network.DailyLogEntry
import com.manjugroups.m_connect.ui.common.ImagePreviewDialog
import java.text.SimpleDateFormat
import java.util.Locale

/** Read-only detail view for a daily log entry, opened from the list card. */
class DailyLogDetailBottomSheet : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                val b = BottomSheetBehavior.from(it)
                b.peekHeight = (resources.displayMetrics.heightPixels * 0.9f).toInt()
                b.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val scroll = NestedScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isFillViewport = false
            // White sheet (from bg_bottom_sheet) + the form's 20dp gutters, so
            // the read-only fields read like the New Entry form.
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(root)

        // Drag handle
        root.addView(View(ctx).apply {
            setBackgroundResource(R.drawable.bg_chat_sheet_handle)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14)
            }
        })

        val log = runCatching {
            Gson().fromJson(arguments?.getString(ARG_JSON), DailyLogEntry::class.java)
        }.getOrNull()
        if (log == null) { root.addView(bodyText(ctx, "Couldn't load this entry.")); return scroll }

        // Title = the entry date, mirroring the form's "New Entry" heading.
        root.addView(TextView(ctx).apply {
            text = displayDate(log.date); textSize = 19f
            setTextColor(Color.parseColor("#101828")); typeface = Typeface.DEFAULT_BOLD
        })

        // Read-only fields rendered with the SAME form UI (label + bordered
        // value box) the New Entry form uses, so viewing mirrors entering.
        // Only populated fields are shown.
        fun field(labelText: String, value: String?, multiline: Boolean = false) {
            val v = value?.trim().takeUnless { it.isNullOrBlank() } ?: return
            root.addView(formLabel(ctx, labelText))
            root.addView(formValue(ctx, v, multiline))
        }

        field("Project", log.projectName)
        field("Weather", log.weather?.replaceFirstChar(Char::uppercase))
        field("Site Conditions", log.siteConditions?.replaceFirstChar(Char::uppercase))
        field("Work Done", log.workSummary, multiline = true)
        val labour = buildString {
            log.labourCount?.let { append("$it labourers") }
            log.labourHours?.let { if (isNotEmpty()) append(" · "); append("${trimNum(it)} hrs") }
        }
        field("Labour", labour.ifBlank { null })
        log.materialsUsed?.takeIf { it.isNotEmpty() }?.let { mats ->
            field("Materials Used", mats.joinToString("\n") { m ->
                "•  ${m.name} — ${trimNum(m.quantity)}${m.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
            }, multiline = true)
        }
        log.equipmentUsed?.takeIf { it.isNotEmpty() }?.let { eq ->
            field("Equipment Used", eq.joinToString("\n") { e -> "•  ${e.name} — ${trimNum(e.hours)} hrs" }, multiline = true)
        }
        field("Issues Encountered", log.issuesEncountered, multiline = true)
        field("Safety Observations", log.safetyObservations, multiline = true)
        field("Supervisor", log.supervisorName)
        field("Created By", log.createdBy)

        // ── Photos & Videos ──
        root.addView(formSectionTitle(ctx, "Photos & Videos"))
        val atts = log.attachments.orEmpty().filter { it.storageId.isNotBlank() }
        if (atts.isNotEmpty()) root.addView(buildAttachmentStrip(ctx, atts))
        else root.addView(buildMediaEmptyState(ctx))
        return scroll
    }

    // ── Reusable form UI: same label + bordered value box as the New Entry form ──

    private fun formLabel(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 12f
        setTextColor(Color.parseColor("#667085"))
        runCatching { typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }
    }

    /** Bordered read-only value box, styled like the form's input fields (bg_input). */
    private fun formValue(ctx: Context, text: String, multiline: Boolean = false) = TextView(ctx).apply {
        this.text = text; textSize = 14f
        setTextColor(Color.parseColor("#101828"))
        runCatching { typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium) }
        setBackgroundResource(R.drawable.bg_input)
        setLineSpacing(dp(2).toFloat(), 1f)
        if (multiline) {
            setPadding(dp(12), dp(12), dp(12), dp(12)); gravity = Gravity.TOP; minHeight = dp(72)
        } else {
            setPadding(dp(12), dp(11), dp(12), dp(11)); gravity = Gravity.CENTER_VERTICAL; minHeight = dp(46)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
    }

    private fun formSectionTitle(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 14f
        setTextColor(Color.parseColor("#101828"))
        runCatching { typeface = ResourcesCompat.getFont(ctx, R.font.inter_semibold) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(18) }
    }

    private fun bodyText(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; textSize = 14f
        setTextColor(Color.parseColor("#344054")); setLineSpacing(dp(2).toFloat(), 1f)
    }

    /** Placeholder shown in the media holder when the log has no photos/videos. */
    private fun buildMediaEmptyState(ctx: Context): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(22), dp(16), dp(22))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#F9FAFB"))
                setStroke(dp(1), Color.parseColor("#EAECF0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_image_outline)
                setColorFilter(Color.parseColor("#CBD2DC"))
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            })
            addView(TextView(ctx).apply {
                text = "No photos or videos"; textSize = 13f
                setTextColor(Color.parseColor("#98A2B3")); gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun buildAttachmentStrip(ctx: Context, atts: List<DailyLogAttachment>): View {
        val scroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        atts.forEach { a ->
            val url = a.url?.takeIf { it.isNotBlank() }
                ?: (BuildConfig.BASE_URL + "api/storage/serve?storageId=" + a.storageId)
            val frame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(8) }
            }
            frame.addView(ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(dp(96), dp(96))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_input)
                clipToOutline = true
                load(url)
            })
            if (a.type == "video") frame.addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_home_trip_play)
                setColorFilter(Color.WHITE)
                setBackgroundResource(R.drawable.bg_home_new_action_circle)
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#66000000"))
                val p = dp(7); setPadding(p, p, p, p)
                layoutParams = FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)
            })
            frame.setOnClickListener {
                if (a.type == "video") {
                    // Video still opens in an external player (the in-app
                    // viewer only shows images); images preview in-app.
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                } else {
                    ImagePreviewDialog.show(requireContext(), url)
                }
            }
            row.addView(frame)
        }
        scroll.addView(row)
        return scroll
    }

    private fun displayDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "Daily Log"
        return runCatching {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                .format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!)
        }.getOrDefault(iso)
    }

    private fun trimNum(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_JSON = "entry_json"
        fun newInstance(json: String) = DailyLogDetailBottomSheet().apply {
            arguments = bundleOf(ARG_JSON to json)
        }
    }
}
