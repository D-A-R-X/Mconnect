package com.manjugroups.m_connect.ui.library.land

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLandInspectionBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.InspectionListItem
import com.manjugroups.m_connect.ui.common.applyShrinkableBlueHeaderBackground
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setBottomCornerRadius
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Land Procurement > Inspection screen.
 *
 * Top of page: sticky blue header with hero illustration on the right,
 * then a search bar + calendar button, then a horizontally-scrollable
 * status filter chip row, then the card list.
 *
 * Items are fetched live from `/api/land/inspections/my`, which returns
 * every land property where the calling staff is in `inspectionAssignedTo`
 * or `inspectionAssignedToList`. Each row arrives with a server-derived
 * status (not_started/in_progress/completed) so the chips can filter
 * locally without an extra round trip per card.
 */
class LandInspectionFragment : Fragment() {

    private var _binding: FragmentLandInspectionBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api by lazy { ApiService.create() }

    private enum class Status(val label: String, val key: String) {
        IN_PROGRESS("In Progress", "in_progress"),
        COMPLETED("Completed", "completed"),
        NOT_STARTED("Not Started", "not_started");

        companion object {
            fun fromKey(key: String?): Status =
                values().firstOrNull { it.key == (key ?: "") } ?: NOT_STARTED
        }
    }

    private data class InspectionRow(
        val propertyId: String,
        val title: String,
        val phone: String,
        val areaLabel: String,
        val date: String,
        val rawInspectionDate: String?,  // yyyy-MM-dd for date filter compare
        val place: String,
        val status: Status,
    )

    private var allItems: List<InspectionRow> = emptyList()
    private var activeFilter: Status? = null   // null = "All"
    private var searchQuery: String = ""
    private var dateFilter: String? = null  // yyyy-MM-dd, null = no filter
    private var hasLoadedOnce: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLandInspectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        session = SessionManager(requireContext())

        binding.btnInspectionBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        applyStatusBarInset()
        setupInspectionScrollAnimation()

        // Filter chips — null means "All", anything else narrows to that status.
        binding.chipFilterAll.setOnClickListener { setFilter(null) }
        binding.chipFilterProgress.setOnClickListener { setFilter(Status.IN_PROGRESS) }
        binding.chipFilterCompleted.setOnClickListener { setFilter(Status.COMPLETED) }
        binding.chipFilterNotStarted.setOnClickListener { setFilter(Status.NOT_STARTED) }

        binding.etInspectionSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                renderList()
            }
        })

        binding.btnInspectionDateFilter.setOnClickListener { openDatePicker() }
        binding.btnInspectionDateFilterClear.setOnClickListener {
            dateFilter = null
            updateDateFilterChip()
            renderList()
        }

        binding.inspectionRefresh.setupPullToRefresh { loadInspections() }

        loadInspections()
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
        // Refresh on return — the user may have come back from saving the
        // inspection form, in which case the row's status flipped server-side.
        if (::session.isInitialized) loadInspections()
    }

    /**
     * Fetch the current inspector's assigned land properties from
     * `/api/land/inspections/my` and re-render. The spinner is dismissed
     * regardless of success/failure so the user is never stuck on a
     * "loading" state after a network error.
     */
    private fun loadInspections() {
        if (!session.isLoggedIn) {
            allItems = emptyList()
            hasLoadedOnce = true
            renderList()
            binding.inspectionRefresh.dismissRefresh()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listMyInspections(session.bearerToken)
                if (resp.success) {
                    allItems = resp.items.map { it.toRow() }
                } else {
                    showError(resp.error ?: "Failed to load inspections")
                }
            } catch (err: Exception) {
                showError(err.message ?: "Network error")
            } finally {
                hasLoadedOnce = true
                renderList()
                binding.inspectionRefresh.dismissRefresh()
            }
        }
    }

    private fun showError(message: String) {
        if (_binding == null) return
        android.widget.Toast.makeText(requireContext(), message, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Open a system DatePickerDialog defaulting to either the currently
     * filtered date or today. The picked date narrows the visible list
     * to properties whose `inspectionDate` matches exactly (yyyy-MM-dd
     * comparison — the server stores it as a date string).
     */
    private fun openDatePicker() {
        val cal = Calendar.getInstance()
        dateFilter?.let { iso ->
            try {
                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
                if (parsed != null) cal.time = parsed
            } catch (_: Exception) { /* default to today */ }
        }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                dateFilter = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                updateDateFilterChip()
                renderList()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun updateDateFilterChip() {
        val active = dateFilter
        if (active == null) {
            binding.inspectionDateFilterChip.visibility = View.GONE
            return
        }
        binding.inspectionDateFilterChip.visibility = View.VISIBLE
        binding.tvInspectionDateFilterLabel.text = "Date · ${formatInspectionDate(active)}"
    }

    private fun InspectionListItem.toRow(): InspectionRow {
        val areaText = totalArea?.let { area ->
            val unit = areaUnit?.replaceFirstChar { c -> c.uppercase() } ?: "Acres"
            "${"%.2f".format(area)} $unit"
        } ?: "—"
        val place = listOfNotNull(
            locality?.takeIf { it.isNotBlank() } ?: city?.takeIf { it.isNotBlank() }
                ?: village?.takeIf { it.isNotBlank() },
            district?.takeIf { it.isNotBlank() } ?: taluk?.takeIf { it.isNotBlank() },
        ).joinToString(", ").ifBlank { fullAddress.orEmpty() }
        return InspectionRow(
            propertyId = propertyId,
            title = referenceNo ?: "LP-${propertyId.take(8)}",
            phone = referrerContact.orEmpty(),
            areaLabel = areaText,
            date = formatInspectionDate(inspectionDate),
            rawInspectionDate = inspectionDate?.takeIf { it.isNotBlank() },
            place = place,
            status = Status.fromKey(derivedInspectionStatus),
        )
    }

    private fun formatInspectionDate(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val formatter = SimpleDateFormat("d MMM yyyy", Locale.ENGLISH)
            formatter.format(parser.parse(raw)!!)
        } catch (_: Exception) {
            raw
        }
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
     * Push the blue header content down by the system status-bar height +
     * a small 4dp gap, so the back arrow / title don't collide with the
     * notch on full-bleed devices. Mirrors HomeFragment.applyStatusBarInset.
     */
    private fun applyStatusBarInset() {
        val basePaddingTop = binding.inspectionHeader.paddingTop
        val gapPx = (4 * binding.root.resources.displayMetrics.density).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.inspectionHeader) { v, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()
            ).top
            v.setPadding(
                v.paddingLeft,
                basePaddingTop + topInset + gapPx,
                v.paddingRight,
                v.paddingBottom,
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.inspectionHeader)
    }

    /**
     * "Panel slides up over fixed header" scroll effect.
     *  - The blue header stays anchored at full size — no shrink, no
     *    fade, no movement.
     *  - The rounded-top white panel translates UP as the user scrolls;
     *    once the user has scrolled past the header height, the panel
     *    has fully overlaid the header and is hidden behind nothing.
     *  - The panel's top-corner radius interpolates 24dp → 0dp during
     *    the overlay so the curve flattens into a sharp rectangle once
     *    the panel is fully on top of the header.
     */
    private fun setupInspectionScrollAnimation() {
        val density = binding.root.resources.displayMetrics.density
        val maxPanelRadiusPx = 24f * density
        // Header stays a flat-bottomed solid blue rectangle.
        val headerBg = binding.inspectionHeader.applyShrinkableBlueHeaderBackground()
        headerBg.setBottomCornerRadius(0f)

        // Mutable panel background so the TOP corners can sharpen as it
        // slides up over the header.
        val panelBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.parseColor("#F1F3F8"))
            cornerRadii = floatArrayOf(
                maxPanelRadiusPx, maxPanelRadiusPx,
                maxPanelRadiusPx, maxPanelRadiusPx,
                0f, 0f, 0f, 0f,
            )
        }
        binding.inspectionRefresh.background = panelBg

        // Need the header height to know how far the panel must slide
        // before the blue is fully covered. wrap_content → measure post.
        binding.inspectionHeader.post {
            val b = _binding ?: return@post
            val overlayDistancePx = b.inspectionHeader.height.toFloat()
            // Extend the panel past the column bottom by headerHeight so
            // its visible bottom keeps reaching the screen edge once
            // translated up — removes the grey strip that was visible
            // below the last card at end-of-scroll.
            (b.inspectionRefresh.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = -overlayDistancePx.toInt()
                b.inspectionRefresh.layoutParams = this
            }
            b.inspectionScroll.setOnScrollChangeListener(
                androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                    val view = _binding ?: return@OnScrollChangeListener
                    // translationY clamps to -overlayDistancePx so once
                    // the header is fully covered, further scroll just
                    // scrolls the panel content normally (no more lift).
                    val translateY = -scrollY.toFloat().coerceAtMost(overlayDistancePx)
                    view.inspectionRefresh.translationY = translateY

                    val progress = (-translateY / overlayDistancePx).coerceIn(0f, 1f)
                    val r = (1f - progress) * maxPanelRadiusPx
                    panelBg.cornerRadii = floatArrayOf(
                        r, r,   // top-left
                        r, r,   // top-right
                        0f, 0f, // bottom-right
                        0f, 0f, // bottom-left
                    )
                }
            )
        }
    }

    private fun setFilter(status: Status?) {
        activeFilter = status
        styleChip(binding.chipFilterAll, status == null)
        styleChip(binding.chipFilterProgress, status == Status.IN_PROGRESS)
        styleChip(binding.chipFilterCompleted, status == Status.COMPLETED)
        styleChip(binding.chipFilterNotStarted, status == Status.NOT_STARTED)
        renderList()
    }

    private fun styleChip(chip: TextView, active: Boolean) {
        val ctx = context ?: return
        if (active) {
            chip.setBackgroundResource(R.drawable.bg_inspection_chip_active)
            chip.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
            chip.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(ctx, R.font.inter_semibold)
        } else {
            chip.setBackgroundResource(R.drawable.bg_inspection_chip_inactive)
            chip.setTextColor(android.graphics.Color.parseColor("#6B7280"))
            chip.typeface = androidx.core.content.res.ResourcesCompat
                .getFont(ctx, R.font.inter_medium)
        }
    }

    private fun renderList() {
        val list = binding.inspectionLogList
        list.removeAllViews()
        val dateFilterValue = dateFilter
        val filtered = allItems.filter { item ->
            val matchesStatus = activeFilter == null || item.status == activeFilter
            val matchesSearch = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.place.contains(searchQuery, ignoreCase = true)
            val matchesDate = dateFilterValue == null ||
                item.rawInspectionDate == dateFilterValue
            matchesStatus && matchesSearch && matchesDate
        }
        val inflater = LayoutInflater.from(requireContext())
        filtered.forEach { item ->
            val card = inflater.inflate(R.layout.item_land_log, list, false)
            card.findViewById<TextView>(R.id.tvLandLogTitle).text = item.title
            card.findViewById<TextView>(R.id.tvLandLogPhone).text = item.phone
            card.findViewById<TextView>(R.id.tvLandLogAreaDate).text =
                "${item.areaLabel} • ${item.date}"
            card.findViewById<TextView>(R.id.tvLandLogPlace).text = item.place
            val statusPill = card.findViewById<TextView>(R.id.tvLandLogStatus)
            statusPill.text = item.status.label
            applyStatusStyle(statusPill, item.status)
            // Tapping a card opens the Site Inspection form sheet,
            // carrying the row's property id so the sheet can prefill from
            // any existing report and write back to the right record.
            card.setOnClickListener {
                SiteInspectionBottomSheet
                    .newInstance(item.propertyId, item.title)
                    .show(parentFragmentManager, "site_inspection")
            }
            list.addView(card)
        }
        renderEmptyState(filtered.isEmpty())
    }

    /**
     * Decide what (if anything) to show when the card list is empty.
     * Three distinct cases — each needs a different message so the user
     * understands whether to wait, change a filter, or contact an admin.
     */
    private fun renderEmptyState(listEmpty: Boolean) {
        val emptyView = binding.inspectionEmptyState
        if (!listEmpty) {
            emptyView.visibility = View.GONE
            return
        }
        emptyView.visibility = View.VISIBLE
        when {
            !hasLoadedOnce -> {
                binding.tvInspectionEmptyTitle.text = "Loading inspections…"
                binding.tvInspectionEmptySubtitle.text = "Hang on while we fetch your assignments."
            }
            allItems.isEmpty() -> {
                binding.tvInspectionEmptyTitle.text = "No inspections assigned"
                binding.tvInspectionEmptySubtitle.text =
                    "When an admin assigns you to a land property on the web, it will appear here."
            }
            else -> {
                binding.tvInspectionEmptyTitle.text = "No matches"
                binding.tvInspectionEmptySubtitle.text =
                    "Try clearing the date filter, the status chip, or the search box."
            }
        }
    }

    private fun applyStatusStyle(pill: TextView, status: Status) {
        when (status) {
            Status.IN_PROGRESS -> {
                pill.setBackgroundResource(R.drawable.bg_inspection_status_progress)
                pill.setTextColor(android.graphics.Color.parseColor("#92400E"))
            }
            Status.COMPLETED -> {
                pill.setBackgroundResource(R.drawable.bg_inspection_status_completed)
                pill.setTextColor(android.graphics.Color.parseColor("#065F46"))
            }
            Status.NOT_STARTED -> {
                pill.setBackgroundResource(R.drawable.bg_inspection_status_notstarted)
                pill.setTextColor(android.graphics.Color.parseColor("#991B1B"))
            }
        }
    }
}
