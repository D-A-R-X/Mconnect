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
            setBackgroundColor(Color.parseColor("#F7F8FA"))
            setPadding(dp(16), dp(14), dp(16), dp(28))
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

        root.addView(TextView(ctx).apply {
            text = displayDate(log.date); textSize = 19f
            setTextColor(Color.parseColor("#101828")); typeface = Typeface.DEFAULT_BOLD
        })
        log.projectName?.takeIf { it.isNotBlank() }?.let {
            root.addView(TextView(ctx).apply {
                text = it; textSize = 12f; setTextColor(Color.parseColor("#0B61CA"))
                setPadding(dp(10), dp(4), dp(10), dp(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat(); setColor(Color.parseColor("#EAF2FE"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            })
        }
        val chips = listOfNotNull(
            log.weather?.takeIf { it.isNotBlank() }?.let { "${weatherEmoji(it) ?: ""} ${it.replaceFirstChar(Char::uppercase)}".trim() },
            log.siteConditions?.takeIf { it.isNotBlank() }?.replaceFirstChar(Char::uppercase),
        ).joinToString("   ·   ")
        if (chips.isNotEmpty()) root.addView(TextView(ctx).apply {
            text = chips; textSize = 12f; setTextColor(Color.parseColor("#667085")); setPadding(0, dp(8), 0, 0)
        })

        // ── Details card (work / labour / materials / equipment / issues / safety) ──
        val details = card(ctx)
        var firstSection = true
        fun addSection(title: String, body: String?) {
            if (body.isNullOrBlank()) return
            if (!firstSection) details.addView(cardDivider(ctx))
            firstSection = false
            details.addView(label(ctx, title))
            details.addView(bodyText(ctx, body))
        }
        addSection("Work Done", log.workSummary)
        val labour = buildString {
            log.labourCount?.let { append("$it labourers") }
            log.labourHours?.let { if (isNotEmpty()) append(" · "); append("${trimNum(it)} hrs") }
        }
        addSection("Labour", labour.ifBlank { null })
        log.materialsUsed?.takeIf { it.isNotEmpty() }?.let { mats ->
            addSection("Materials Used", mats.joinToString("\n") { m ->
                "•  ${m.name} — ${trimNum(m.quantity)}${m.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
            })
        }
        log.equipmentUsed?.takeIf { it.isNotEmpty() }?.let { eq ->
            addSection("Equipment Used", eq.joinToString("\n") { e -> "•  ${e.name} — ${trimNum(e.hours)} hrs" })
        }
        addSection("Issues Encountered", log.issuesEncountered?.takeIf { it.isNotBlank() })
        addSection("Safety Observations", log.safetyObservations?.takeIf { it.isNotBlank() })
        if (details.childCount > 0) root.addView(details)

        // ── Sign-off card ──
        val signoff = listOfNotNull(
            log.supervisorName?.takeIf { it.isNotBlank() }?.let { "Supervisor: $it" },
            log.createdBy?.takeIf { it.isNotBlank() }?.let { "Created by: $it" },
        ).joinToString("  ·  ")
        if (signoff.isNotEmpty()) {
            val sc = card(ctx)
            sc.addView(label(ctx, "Sign-off"))
            sc.addView(bodyText(ctx, signoff))
            root.addView(sc)
        }

        // ── Photos & Videos card ──
        val atts = log.attachments.orEmpty().filter { it.storageId.isNotBlank() }
        val media = card(ctx)
        media.addView(label(ctx, "Photos & Videos"))
        if (atts.isNotEmpty()) media.addView(buildAttachmentStrip(ctx, atts))
        else media.addView(buildMediaEmptyState(ctx))
        root.addView(media)
        return scroll
    }

    /** White rounded card container on the grey page. */
    private fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.parseColor("#EDEFF3"))
        }
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
    }

    private fun cardDivider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(Color.parseColor("#F2F4F7"))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
        ).apply { topMargin = dp(13); bottomMargin = dp(13) }
    }

    private fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text.uppercase(Locale.getDefault()); textSize = 11f
        setTextColor(Color.parseColor("#98A2B3")); typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
        setPadding(0, 0, 0, dp(5))
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
            addView(TextView(ctx).apply {
                text = "🖼️"; textSize = 22f; gravity = Gravity.CENTER
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
            if (a.type == "video") frame.addView(TextView(ctx).apply {
                text = "▶"; textSize = 24f; setTextColor(Color.WHITE)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                )
            })
            frame.setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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

    private fun weatherEmoji(w: String?): String? = when (w?.lowercase(Locale.US)) {
        "sunny" -> "☀️"
        "cloudy" -> "☁️"
        "rainy" -> "🌧️"
        "windy" -> "💨"
        "stormy" -> "⛈️"
        else -> null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_JSON = "entry_json"
        fun newInstance(json: String) = DailyLogDetailBottomSheet().apply {
            arguments = bundleOf(ARG_JSON to json)
        }
    }
}
