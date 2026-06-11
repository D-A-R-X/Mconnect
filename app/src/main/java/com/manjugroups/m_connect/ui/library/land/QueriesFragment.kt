package com.manjugroups.m_connect.ui.library.land

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentQueriesBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.QueryListItem
import com.manjugroups.m_connect.network.QueryUpdateRequest
import com.manjugroups.m_connect.ui.common.applyShrinkableBlueHeaderBackground
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setBottomCornerRadius
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Land Procurement > Queries screen.
 *
 * Mirrors the Inspection screen's chrome (sticky blue header + hero, search
 * pill, calendar date-filter, All/Pending/Completed chips, card list). Each
 * card is a document-verification query: a title (e.g. "Sale Deed, Patta"),
 * the request detail, a date, a status pill (Pending/Confirmed) and a
 * verification remark with an Add/Edit Remark CTA.
 *
 * Data is currently seeded from the design pending a real backend endpoint
 * (same staging approach the Inspection list started from). Adding a remark
 * confirms the query locally so the Pending → Confirmed flow is demonstrable.
 */
class QueriesFragment : Fragment() {

    private var _binding: FragmentQueriesBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api by lazy { ApiService.create() }

    companion object {
        private const val RESULT_KEY_FILTER = "queries_filter_calendar"
    }

    private enum class Filter(val label: String) { ALL("All"), PENDING("Pending"), COMPLETED("Completed") }

    private data class QueryRow(
        val propertyId: String,
        val queryIndex: Int,
        val number: String,
        val title: String,
        val detail: String,
        val date: String,
        val rawDate: String?,   // yyyy-MM-dd for the date filter
        var confirmed: Boolean,
        var remark: String,
    )

    private val items: MutableList<QueryRow> = mutableListOf()
    private var activeFilter: Filter = Filter.ALL
    private var searchQuery: String = ""
    private var dateFilter: String? = null
    private var hasLoadedOnce: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentQueriesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnQueriesBack.setOnClickListener { navigateUp() }
        applyStatusBarInset()

        binding.segmentedTabs.setTabs(
            listOf("All", "Pending", "Completed"),
            activeFilter.ordinal
        ) { position ->
            val filter = Filter.values()[position]
            if (activeFilter != filter) {
                activeFilter = filter
                renderList()
            }
        }

        binding.etQueriesSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                renderList()
            }
        })

        binding.btnQueriesDateFilter.setOnClickListener { openDatePicker() }
        binding.btnQueriesDateFilterClear.setOnClickListener {
            dateFilter = null
            updateDateFilterChip()
            renderList()
        }

        binding.queriesRefresh.setupPullToRefresh { loadQueries() }

        setupQueriesScrollAnimation()

        loadQueries()
    }

    /**
     * "White card slides up over the blue header" scroll effect — the
     * same two-component model used on Home / Attendance / App Library /
     * Inspection. The blue header stays anchored; the panel below
     * (which overlaps the header by -20dp in XML and carries the
     * rounded-top grey bg) translates up as the user scrolls until it
     * fully covers the blue. Its top corners interpolate 24dp → 0dp in
     * step so the curve flattens at full overlay.
     */
    private fun setupQueriesScrollAnimation() {
        val density = binding.root.resources.displayMetrics.density
        val maxPanelRadiusPx = 24f * density
        // Header stays a flat-bottomed solid blue rectangle.
        val headerBg = binding.queriesHeader.applyShrinkableBlueHeaderBackground()
        headerBg.setBottomCornerRadius(0f)

        // Grey card — rounded TOP corners + grey bg applied to
        // `queriesWhitePanel`, which lives INSIDE the NestedScrollView.
        // It IS the scrolling content, so it moves up at exactly 1×
        // rate (no parallax, no shrinking, no separate translation).
        // The ancestors set clipChildren=false in XML so the rounded
        // top edge can draw OUTSIDE the panel and over the blue header.
        val whiteCardBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.parseColor("#F1F3F8"))
            cornerRadii = floatArrayOf(
                maxPanelRadiusPx, maxPanelRadiusPx, // top-left
                maxPanelRadiusPx, maxPanelRadiusPx, // top-right
                0f, 0f,                              // bottom-right
                0f, 0f,                              // bottom-left
            )
        }
        binding.queriesWhitePanel.background = whiteCardBg
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(
                android.graphics.Color.parseColor("#0B61CA"),
                darkStatusIcons = false,
                fullBleed = true,
            )
        }
        // Refresh on return so remarks/resolutions made on web show up.
        if (::session.isInitialized) loadQueries()
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /**
     * Load verification queries from `/api/land/queries/my` — the same
     * landLegalClearance.verificationQueries the web Legal tab edits.
     */
    private fun loadQueries() {
        if (!session.isLoggedIn) {
            items.clear()
            hasLoadedOnce = true
            renderList()
            binding.queriesRefresh.dismissRefresh()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listMyQueries(session.bearerToken)
                if (resp.success) {
                    items.clear()
                    resp.items.forEachIndexed { i, it -> items.add(it.toRow(i)) }
                } else {
                    showError(resp.error ?: "Failed to load queries")
                }
            } catch (err: Exception) {
                showError(err.message ?: "Network error")
            } finally {
                hasLoadedOnce = true
                renderList()
                binding.queriesRefresh.dismissRefresh()
            }
        }
    }

    private fun showError(message: String) {
        if (_binding == null) return
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun QueryListItem.toRow(position: Int): QueryRow {
        val raw = createdOn?.takeIf { it.isNotBlank() }?.substring(0, minOf(10, createdOn.length))
        return QueryRow(
            propertyId = propertyId,
            queryIndex = queryIndex,
            number = "Q${position + 1}",
            title = query?.takeIf { it.isNotBlank() } ?: "Query",
            detail = referenceNo?.takeIf { it.isNotBlank() } ?: "—",
            date = formatDate(raw),
            rawDate = raw,
            confirmed = resolved,
            remark = remarks.orEmpty(),
        )
    }

    private fun openDatePicker() {
        val initial = dateFilter ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        setFragmentResultListener(RESULT_KEY_FILTER) { _, bundle ->
            val date = bundle.getString(CalendarRangePickerSheet.KEY_FROM) ?: return@setFragmentResultListener
            dateFilter = date
            updateDateFilterChip()
            renderList()
        }
        CalendarRangePickerSheet.newInstance(
            title = "Date Filter",
            subtitle = "Select Date Filter",
            initialFrom = initial,
            initialTo = initial,
            resultKey = RESULT_KEY_FILTER,
        ).show(parentFragmentManager, "queries_filter_calendar")
    }

    private fun updateDateFilterChip() {
        val active = dateFilter
        if (active == null) {
            binding.queriesDateFilterChip.visibility = View.GONE
            binding.btnQueriesDateFilter.setBackgroundResource(R.drawable.bg_inspection_search)
            binding.ivQueriesDateFilterDefault.visibility = View.VISIBLE
            binding.queriesDateFilterActive.visibility = View.GONE
            return
        }
        binding.queriesDateFilterChip.visibility = View.VISIBLE
        binding.tvQueriesDateFilterLabel.text = "Date · ${formatDate(active)}"
        binding.btnQueriesDateFilter.setBackgroundResource(R.drawable.bg_inspection_date_active)
        binding.ivQueriesDateFilterDefault.visibility = View.GONE
        binding.queriesDateFilterActive.visibility = View.VISIBLE
        runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(active)!!
            binding.tvQueriesDateFilterDay.text = SimpleDateFormat("d", Locale.ENGLISH).format(parsed)
            binding.tvQueriesDateFilterMonth.text = SimpleDateFormat("MMM", Locale.ENGLISH).format(parsed)
        }
    }

    private fun formatDate(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        return runCatching {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val formatter = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
            formatter.format(parser.parse(raw)!!)
        }.getOrDefault(raw)
    }


    private fun renderList() {
        val list = binding.queriesLogList
        list.removeAllViews()
        val dateFilterValue = dateFilter
        val filtered = items.filter { row ->
            val matchesFilter = when (activeFilter) {
                Filter.ALL -> true
                Filter.PENDING -> !row.confirmed
                Filter.COMPLETED -> row.confirmed
            }
            val matchesSearch = searchQuery.isBlank() ||
                row.title.contains(searchQuery, ignoreCase = true) ||
                row.detail.contains(searchQuery, ignoreCase = true)
            val matchesDate = dateFilterValue == null || row.rawDate == dateFilterValue
            matchesFilter && matchesSearch && matchesDate
        }
        val inflater = LayoutInflater.from(requireContext())
        filtered.forEach { row ->
            val card = inflater.inflate(R.layout.item_query_log, list, false)
            card.findViewById<TextView>(R.id.tvQueryTitle).text = row.title
            card.findViewById<TextView>(R.id.tvQueryDetail).text = row.detail
            card.findViewById<TextView>(R.id.tvQueryDate).text = row.date

            val statusPill = card.findViewById<TextView>(R.id.tvQueryStatus)
            if (row.confirmed) {
                statusPill.text = "Completed"
                statusPill.setBackgroundResource(R.drawable.bg_inspection_status_completed)
                statusPill.setTextColor(android.graphics.Color.parseColor("#065F46"))
            } else {
                statusPill.text = "PENDING"
                statusPill.setBackgroundResource(R.drawable.bg_query_status_pending)
                statusPill.setTextColor(android.graphics.Color.parseColor("#0B61CA"))
            }

            card.setOnClickListener { openDocumentsNeededSheet(row) }
            list.addView(card)
        }
        renderEmptyState(filtered.isEmpty())
    }

    private fun renderEmptyState(empty: Boolean) {
        binding.queriesEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty) return
        when {
            !hasLoadedOnce -> {
                binding.tvQueriesEmptyTitle.text = "Loading queries…"
                binding.tvQueriesEmptySubtitle.text = "Hang on while we fetch your queries."
            }
            items.isEmpty() -> {
                binding.tvQueriesEmptyTitle.text = "No queries yet"
                binding.tvQueriesEmptySubtitle.text =
                    "Document verification queries for your properties will appear here."
            }
            else -> {
                binding.tvQueriesEmptyTitle.text = "No matches"
                binding.tvQueriesEmptySubtitle.text =
                    "Try clearing the date filter, the status chip, or the search box."
            }
        }
    }

    /**
     * Open the Documents Needed (Additional) bottom sheet. The list of
     * documents is derived from the query's title — split by `,` / `;` so
     * a query of "Sale Deed, Patta" surfaces as two requested document
     * rows. The Completed CTA closes the sheet and marks the query
     * resolved via the same updateQuery endpoint the Add Remark sheet
     * used to call (so the web Legal tab still reflects the state).
     */
    private fun openDocumentsNeededSheet(row: QueryRow) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.sheet_documents_needed, null)
        dialog.setContentView(view)

        val container = view.findViewById<LinearLayout>(R.id.documentsContainer)
        val docs = parseRequestedDocuments(row.title)
        val itemInflater = LayoutInflater.from(requireContext())
        docs.forEach { doc ->
            val item = itemInflater.inflate(R.layout.item_document_needed, container, false)
            item.findViewById<TextView>(R.id.tvDocName).text = doc.name
            val badge = item.findViewById<TextView>(R.id.tvDocBadge)
            badge.text = if (doc.required) "Required" else "If Applicable"
            if (doc.required) {
                badge.setBackgroundResource(R.drawable.bg_query_status_pending)
                badge.setTextColor(android.graphics.Color.parseColor("#0B61CA"))
            } else {
                badge.setBackgroundResource(R.drawable.bg_doc_applicable)
                badge.setTextColor(android.graphics.Color.parseColor("#9333EA"))
            }
            container.addView(item)
        }

        view.findViewById<View>(R.id.btnDocumentsCompleted).setOnClickListener {
            dialog.dismiss()
            if (!row.confirmed) markResolved(row)
        }
        dialog.show()
    }

    private data class RequestedDoc(val name: String, val required: Boolean)

    /**
     * Turn the query title (e.g. "Sale Deed, Patta") into the list of
     * documents the inspector must collect. Each comma-separated entry is
     * surfaced as a Required document; an empty title falls back to the
     * raw text so the sheet never renders with zero rows.
     */
    private fun parseRequestedDocuments(title: String): List<RequestedDoc> {
        val parts = title.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) {
            return listOf(RequestedDoc(title.ifBlank { "Document" }, required = true))
        }
        return parts.map { RequestedDoc(it, required = true) }
    }

    /**
     * Mark the query resolved on the server. Mirrors the previous Add
     * Remark "save" path but keeps the existing remark text intact — the
     * new design's Completed button signals "documents collected", not a
     * remark edit.
     */
    private fun markResolved(row: QueryRow) {
        if (!session.isLoggedIn) return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.updateQuery(
                    session.bearerToken,
                    QueryUpdateRequest(
                        propertyId = row.propertyId,
                        queryIndex = row.queryIndex,
                        remarks = row.remark.ifBlank { null },
                        resolved = true,
                    ),
                )
                if (resp.success) {
                    android.widget.Toast.makeText(
                        requireContext(), "Marked completed",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    loadQueries()
                } else {
                    showError(resp.error ?: "Failed to mark completed")
                }
            } catch (err: Exception) {
                showError(err.message ?: "Network error")
            }
        }
    }

    private fun applyStatusBarInset() {
        val basePaddingTop = binding.queriesHeader.paddingTop
        val gapPx = (4 * binding.root.resources.displayMetrics.density).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.queriesHeader) { v, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars(),
            ).top
            v.setPadding(v.paddingLeft, basePaddingTop + topInset + gapPx, v.paddingRight, v.paddingBottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.queriesHeader)
    }
}
