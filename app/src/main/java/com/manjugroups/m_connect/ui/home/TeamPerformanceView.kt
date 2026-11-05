package com.manjugroups.m_connect.ui.home

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.CockpitMetric
import com.manjugroups.m_connect.network.CockpitPerformance
import com.manjugroups.m_connect.network.TelecallerCoreClient
import com.manjugroups.m_connect.ui.common.LocalCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Team Performance on the mobile dashboard: the web Insights "Executive
 * cockpit" cards (Calls, CP completed, SV completed, Bookings, Collections,
 * Registrations) for the viewer's permitted team, over a chosen range.
 * Scoping happens in Telecaller Core from the viewer's identity, exactly as on
 * the web. The last answer per range is cached so the card paints instantly.
 */
class TeamPerformanceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private enum class Range(val label: String) { TODAY("Today"), WEEK("Last 7 days"), MONTH("This month"), DAYS30("Last 30 days") }

    private data class Metric(val key: String, val label: String, val money: Boolean = false)

    private val metrics = listOf(
        Metric("calls", "Calls"),
        Metric("cp", "CP completed"),
        Metric("sv", "SV completed"),
        Metric("booking", "Bookings"),
        Metric("collection", "Collections", money = true),
        Metric("registration", "Registrations"),
    )

    private val subtitle: TextView
    private val chips: LinearLayout
    private val grid: GridLayout
    private val error: TextView
    private val cards = mutableMapOf<String, View>()

    private var range = Range.WEEK
    private var bearer: (() -> String)? = null
    private var staffKey: String = ""
    private var owner: LifecycleOwner? = null
    private var loadJob: Job? = null
    private val gson = Gson()

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_team_perf_section)
        val pad = dp(16)
        setPadding(pad, dp(14), pad, pad)
        LayoutInflater.from(context).inflate(R.layout.view_team_performance, this, true)
        subtitle = findViewById(R.id.tvTpSubtitle)
        chips = findViewById(R.id.tpRangeChips)
        grid = findViewById(R.id.tpGrid)
        error = findViewById(R.id.tvTpError)
        findViewById<View>(R.id.btnTpRefresh).setOnClickListener { load(force = true) }
        error.setOnClickListener { load(force = true) }
        buildChips()
        buildCards()
        renderSubtitle(null)
    }

    /** Wire once from the host fragment, then call [load]. */
    fun bind(owner: LifecycleOwner, staffId: String?, bearerToken: () -> String) {
        this.owner = owner
        this.staffKey = staffId.orEmpty()
        this.bearer = bearerToken
    }

    fun load(force: Boolean = false) {
        val lifecycleOwner = owner ?: return
        val token = bearer?.invoke()?.takeIf { it.isNotBlank() } ?: return
        val (from, to) = rangeDates(range)
        val key = "team_performance_${staffKey}_${range.name}"
        loadJob?.cancel()
        loadJob = lifecycleOwner.lifecycleScope.launch {
            if (!force) {
                val cached = withContext(Dispatchers.IO) {
                    runCatching { LocalCache.get(context, key, CockpitPerformance::class.java) }.getOrNull()
                }
                if (cached != null) render(cached) else renderLoading()
            } else {
                renderLoading()
            }
            val result = runCatching {
                val data = TelecallerCoreClient.call(
                    token, "reports", "cockpitPerformance",
                    mapOf("dateFrom" to from, "dateTo" to to, "refresh" to force),
                )
                gson.fromJson(data, CockpitPerformance::class.java)
            }
            result.onSuccess { perf ->
                error.visibility = GONE
                render(perf)
                withContext(Dispatchers.IO) {
                    runCatching { LocalCache.put(context, key, perf, System.currentTimeMillis()) }
                }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                error.text = "Couldn't load team performance. Tap to retry."
                error.visibility = VISIBLE
                if (cards.values.all { it.findViewById<TextView>(R.id.tvTpValue).text == "—" }) {
                    metrics.forEach { setCaption(it.key, "Unavailable") }
                }
            }
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun render(perf: CockpitPerformance) {
        renderSubtitle(perf)
        val noData = perf.availableFrom != null && perf.dateTo != null && perf.dateTo < perf.availableFrom
        metrics.forEach { metric ->
            val card = cards[metric.key] ?: return@forEach
            val value = card.findViewById<TextView>(R.id.tvTpValue)
            val progress = card.findViewById<ProgressBar>(R.id.pbTpProgress)
            val m = perf.metrics[metric.key] ?: CockpitMetric()
            if (noData) {
                value.text = "—"
                progress.progress = 0
                setCaption(metric.key, "No recorded performance")
                return@forEach
            }
            value.text = valueText(m, metric.money)
            progress.progress = m.percentage.coerceIn(0.0, 100.0).toInt()
            val sqft = perf.metrics["${metric.key}Sqft"]
            val caption = buildString {
                append(if (m.target > 0) "${trim(m.percentage)}% achieved" else "No count target set")
                if (sqft != null && sqft.target > 0) {
                    append("\nArea: ${number(sqft.actual)} / ${number(sqft.target)} sq.ft")
                }
            }
            setCaption(metric.key, caption)
        }
    }

    private fun renderLoading() {
        metrics.forEach { metric ->
            cards[metric.key]?.let { card ->
                card.findViewById<TextView>(R.id.tvTpValue).text = "—"
                card.findViewById<ProgressBar>(R.id.pbTpProgress).progress = 0
            }
            setCaption(metric.key, "Loading…")
        }
    }

    private fun renderSubtitle(perf: CockpitPerformance?) {
        val (from, to) = perf?.dateFrom?.let { it to (perf.dateTo ?: it) } ?: rangeDates(range)
        val span = if (from == to) prettyDate(from) else "${prettyDate(from)} – ${prettyDate(to)}"
        subtitle.text = "Targets and achievements · $span · Your permitted team"
    }

    private fun setCaption(key: String, text: String) {
        cards[key]?.findViewById<TextView>(R.id.tvTpCaption)?.text = text
    }

    /** "33,431 / No target", the target part smaller and muted (as on web). */
    private fun valueText(m: CockpitMetric, money: Boolean): CharSequence {
        val actual = if (money) rupees(m.actual) else number(m.actual)
        val target = if (m.target > 0) (if (money) rupees(m.target) else number(m.target)) else "No target"
        val builder = SpannableStringBuilder(actual)
        val start = builder.length
        builder.append(" / $target")
        builder.setSpan(ForegroundColorSpan(Color.parseColor("#667085")), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(AbsoluteSizeSpan(13, true), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private fun buildChips() {
        chips.removeAllViews()
        Range.values().forEachIndexed { index, r ->
            val chip = TextView(context).apply {
                text = r.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.inter_semibold)
                setBackgroundResource(R.drawable.bg_team_perf_chip)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isSelected = r == range
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#5B21B6"))
                setOnClickListener {
                    if (range == r) return@setOnClickListener
                    range = r
                    for (i in 0 until chips.childCount) {
                        val c = chips.getChildAt(i) as TextView
                        c.isSelected = i == r.ordinal
                        c.setTextColor(if (c.isSelected) Color.WHITE else Color.parseColor("#5B21B6"))
                    }
                    renderSubtitle(null)
                    load()
                }
            }
            chips.addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) marginStart = dp(8)
            })
        }
    }

    private fun buildCards() {
        // Two columns on phones, three on wide screens (tablets, landscape).
        val widthDp = resources.configuration.screenWidthDp
        val columns = if (widthDp >= 600) 3 else 2
        grid.columnCount = columns
        grid.removeAllViews()
        val inflater = LayoutInflater.from(context)
        val gap = dp(10)
        metrics.forEachIndexed { index, metric ->
            val card = inflater.inflate(R.layout.item_team_performance_metric, grid, false)
            card.findViewById<TextView>(R.id.tvTpLabel).text = metric.label
            card.findViewById<TextView>(R.id.tvTpValue).text = "—"
            val column = index % columns
            card.layoutParams = GridLayout.LayoutParams(
                GridLayout.spec(index / columns),
                GridLayout.spec(column, 1f),
            ).apply {
                width = 0
                if (column > 0) marginStart = gap
                if (index >= columns) topMargin = gap
            }
            cards[metric.key] = card
            grid.addView(card)
        }
    }

    // ── Formatting ──────────────────────────────────────────────────────────

    private fun rangeDates(r: Range): Pair<String, String> {
        val ist = TimeZone.getTimeZone("Asia/Kolkata")
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = ist }
        val end = Calendar.getInstance(ist)
        val start = (end.clone() as Calendar)
        when (r) {
            Range.TODAY -> Unit
            Range.WEEK -> start.add(Calendar.DAY_OF_MONTH, -6)
            Range.DAYS30 -> start.add(Calendar.DAY_OF_MONTH, -29)
            Range.MONTH -> start.set(Calendar.DAY_OF_MONTH, 1)
        }
        return fmt.format(start.time) to fmt.format(end.time)
    }

    private fun prettyDate(iso: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
        SimpleDateFormat("d MMM", Locale.ENGLISH).format(parsed)
    }.getOrDefault(iso)

    private fun number(value: Double): String =
        NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }.format(value)

    private fun trim(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.1f", value)

    /** Compact INR like the web: ₹71L, ₹2.4Cr, ₹85K. */
    private fun rupees(value: Double): String = when {
        value >= 1_00_00_000 -> "₹${String.format(Locale.US, "%.1f", value / 1_00_00_000).removeSuffix(".0")}Cr"
        value >= 1_00_000 -> "₹${Math.round(value / 1_00_000)}L"
        value >= 1_000 -> "₹${Math.round(value / 1_000)}K"
        else -> "₹${number(value)}"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
