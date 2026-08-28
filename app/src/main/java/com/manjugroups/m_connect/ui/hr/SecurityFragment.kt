package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentSecurityBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * HR > Security — the mobile counterpart of the web staff Security tab.
 *
 * Three actions over one staff directory:
 *  - Device Reset   clears the device lock so the next sign-in binds a new
 *                   phone (for a staff who changed handset)
 *  - Staff Login    ends the app sessions but KEEPS the lock (for a lost or
 *                   handed-over phone)
 *  - Password Reset sets a new password / manages expiry exemption
 *
 * Tabs rather than three separate screens: the list, the search and the filters
 * are identical in all three, only the action differs. Each tab is gated by its
 * own permission, because `staff.resetDeviceBinding` and `staff.password` are
 * separate rights and a user may hold one without the other — a tab they cannot
 * use is not shown at all rather than failing on tap.
 */
class SecurityFragment : Fragment() {

    private var _binding: FragmentSecurityBinding? = null
    private val binding get() = _binding!!

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    // Loaded pages, appended as the user scrolls. Never the whole directory at
    // once — an org with hundreds of staff made a single call slow enough that
    // the screen sat blank and read as stuck.
    private val loaded = mutableListOf<StaffData>()
    private var cursor: String? = null
    private var isDone = false
    private var isLoading = false
    private var loadFailed = false

    private var searchQuery: String = ""
    private var searchJob: kotlinx.coroutines.Job? = null
    /** Search is served by the server so it reaches staff not yet paged in. */
    private var isSearching = false

    private var designationFilter: String? = null
    private var departmentFilter: String? = null

    private enum class Action { DEVICE_RESET, STAFF_LOGIN, PASSWORD_RESET }

    private data class Tab(
        val action: Action,
        val label: String,
        val hint: String,
        val permission: String,
    )

    private val allTabs = listOf(
        Tab(
            Action.DEVICE_RESET,
            "Device Reset",
            "Clears the device lock so the next sign-in binds a new phone, and signs the current phone out.",
            "staff.resetDeviceBinding",
        ),
        Tab(
            Action.STAFF_LOGIN,
            "Staff Login",
            "Shows the phone an account is locked to, and signs it out of the app without freeing the lock.",
            "staff.resetDeviceBinding",
        ),
        Tab(
            Action.PASSWORD_RESET,
            "Password Reset",
            "Sets a new password and manages the password-expiry exemption.",
            "staff.password",
        ),
    )

    /** Only the tabs this user may actually act on. */
    private val tabs: List<Tab> by lazy {
        allTabs.filter { session.hasPermission(it.permission) }
    }

    private var selectedTab: Action = Action.DEVICE_RESET

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSecurityBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnSecurityBack.setOnClickListener { navigateUp() }
        selectedTab = tabs.firstOrNull()?.action ?: Action.DEVICE_RESET

        buildTabs()
        setupSearch()
        binding.btnSecurityFilter.setOnClickListener { openFilters() }
        binding.securityRefresh.setupPullToRefresh {
            if (isSearching) runSearch(searchQuery) else resetAndLoad()
        }
        binding.btnSecurityRetry.setOnClickListener {
            if (isSearching) runSearch(searchQuery) else resetAndLoad()
        }

        // Scroll-based paging: pull the next page once the user is within a
        // screen of the bottom, so rows are already there when they arrive.
        binding.securityScroll.viewTreeObserver.addOnScrollChangedListener {
            if (_binding == null) return@addOnScrollChangedListener
            val scroll = binding.securityScroll
            val child = scroll.getChildAt(0) ?: return@addOnScrollChangedListener
            val remaining = child.height - (scroll.height + scroll.scrollY)
            if (remaining < scroll.height) loadNextPage()
        }

        // A staff action taken in the sheet can change what the list shows.
        parentFragmentManager.setFragmentResultListener(
            StaffSecurityBottomSheet.RESULT_KEY, viewLifecycleOwner,
        ) { _, _ -> render() }
        childFragmentManager.setFragmentResultListener(
            StaffSecurityBottomSheet.RESULT_KEY, viewLifecycleOwner,
        ) { _, _ -> render() }

        resetAndLoad()
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

    override fun onDestroyView() {
        searchJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    // ---------- data ----------

    private fun resetAndLoad() {
        loaded.clear()
        cursor = null
        isDone = false
        loadFailed = false
        render()
        loadNextPage(first = true)
    }

    /**
     * Pulls one page. Called for the first page and again from the scroll
     * listener as the user nears the bottom.
     *
     * Guarded by [isLoading] so a fast scroll cannot fire several overlapping
     * requests for the same cursor and append the same page twice.
     */
    private fun loadNextPage(first: Boolean = false) {
        if (isLoading || isDone || isSearching) return
        if (!first && cursor == null) return
        isLoading = true
        renderLoadingState()

        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                api.getStaffPaginated(
                    token = session.bearerToken,
                    numItems = PAGE_SIZE,
                    cursor = cursor,
                )
            }.getOrNull()

            if (!isAdded || _binding == null) return@launch
            isLoading = false
            binding.securityRefresh.dismissRefresh()

            if (resp?.success != true) {
                // Keep the pages already on screen; only a FIRST-page failure
                // has nothing to show, and that gets an explicit retry rather
                // than a blank rectangle.
                loadFailed = loaded.isEmpty()
                render()
                return@launch
            }

            loaded.addAll(resp.page.filter { !it.id.isNullOrBlank() })
            cursor = resp.continueCursor
            isDone = resp.isDone || resp.continueCursor == null
            loadFailed = false
            render()

            // With a filter on, one page may contain few (or no) matches. Keep
            // pulling so the user is not left staring at an empty list while
            // hundreds of unloaded staff would have matched.
            if (hasActiveFilters() && !isDone && visibleStaff().size < PAGE_SIZE) {
                loadNextPage()
            }
        }
    }

    /**
     * Server-side search, so a name is found even when their page has not been
     * scrolled to yet. Debounced — a keystroke per request would hammer the
     * backend and the results would race.
     */
    private fun runSearch(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            isSearching = false
            resetAndLoad()
            return
        }
        isSearching = true
        loadFailed = false
        isLoading = true
        renderLoadingState()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            val resp = runCatching { api.searchStaff(session.bearerToken, q) }.getOrNull()
            if (!isAdded || _binding == null) return@launch
            isLoading = false
            if (resp?.success == true) {
                loaded.clear()
                loaded.addAll(resp.staff.filter { !it.id.isNullOrBlank() })
                // A search result set is complete in itself — no paging.
                isDone = true
                cursor = null
            } else {
                loadFailed = loaded.isEmpty()
            }
            render()
        }
    }

    private fun hasActiveFilters() =
        designationFilter != null || departmentFilter != null

    private fun visibleStaff(): List<StaffData> = loaded
        .filter { s ->
            val matchesDesignation =
                designationFilter == null || s.designation.equals(designationFilter, true)
            val matchesDepartment =
                departmentFilter == null || s.department.equals(departmentFilter, true)
            matchesDesignation && matchesDepartment
        }
        // Active first, then by name, so whoever most likely needs an action is
        // at the top. Sorted at render time because pages arrive incrementally.
        .sortedWith(
            compareBy<StaffData> { if (it.status == "active") 0 else 1 }
                .thenBy { it.name?.lowercase(Locale.US) ?: "" },
        )

    // ---------- rendering ----------

    /** Skeleton only while the FIRST page is in flight; a spinner for later pages. */
    private fun renderLoadingState() {
        if (_binding == null) return
        val firstLoad = isLoading && loaded.isEmpty()
        binding.securitySkeleton.visibility = if (firstLoad) View.VISIBLE else View.GONE
        if (firstLoad && binding.securitySkeleton.childCount == 0) {
            repeat(6) { binding.securitySkeleton.addView(skeletonRow()) }
        }
        binding.securityLoadingMore.visibility =
            if (isLoading && loaded.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun render() {
        if (_binding == null) return
        renderLoadingState()

        val rows = visibleStaff()
        binding.tvSecurityCount.text = when {
            isSearching -> "${rows.size} found"
            isDone -> "${rows.size} staff"
            // Honest while paging: more exist that have not been pulled yet.
            else -> "${rows.size} loaded"
        }
        binding.tvSecurityTabHint.text =
            tabs.firstOrNull { it.action == selectedTab }?.hint.orEmpty()
        renderChips()

        binding.securityList.removeAllViews()
        rows.forEach { binding.securityList.addView(staffRow(it)) }
        binding.securityList.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE

        // Nothing at all AND the first load failed -> an explicit retry, never a
        // blank screen the user has to guess about.
        val showError = loadFailed && rows.isEmpty()
        binding.securityError.visibility = if (showError) View.VISIBLE else View.GONE
        binding.tvSecurityErrorText.text =
            "Couldn't load the staff directory. Check your connection and try again."

        val showEmpty = !showError && rows.isEmpty() && !isLoading
        binding.securityEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (showEmpty) {
            val narrowed = searchQuery.isNotBlank() || hasActiveFilters()
            binding.tvSecurityEmptyTitle.text =
                if (narrowed) "No matches" else "No staff found"
            binding.tvSecurityEmptySubtitle.text = if (narrowed) {
                "Try a different search term or clear the filters."
            } else {
                "The staff directory came back empty."
            }
        }
    }

    /** A grey card the size of a real row, so the wait looks like loading. */
    private fun skeletonRow(): View = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(72),
        ).apply { bottomMargin = dp(10) }
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#E9EDF3"))
        }
    }

    private fun staffRow(staff: StaffData): View {
        val row = layoutInflater.inflate(R.layout.item_staff, binding.securityList, false)
        val name = staff.name?.takeIf { it.isNotBlank() } ?: "Unnamed staff"
        row.findViewById<TextView>(R.id.tvStaffInitials).text = initials(name)
        row.findViewById<TextView>(R.id.tvStaffName).text = name
        row.findViewById<TextView>(R.id.tvStaffRole).text =
            listOfNotNull(
                staff.designation?.takeIf { it.isNotBlank() },
                staff.department?.takeIf { it.isNotBlank() },
            ).joinToString(" • ").ifBlank { staff.role.orEmpty() }
        row.findViewById<TextView>(R.id.tvStaffPhone).text =
            listOfNotNull(
                staff.employeeId?.takeIf { it.isNotBlank() },
                staff.phone?.takeIf { it.isNotBlank() },
            ).joinToString(" • ")
        row.findViewById<TextView>(R.id.tvStaffStatus)?.text = staff.status.orEmpty()
        row.findViewById<View>(R.id.tvStaffGeoTrackHealth)?.visibility = View.GONE

        // Acting on your OWN account from here would lock you out mid-task, and
        // the server refuses it anyway — so it is not offered.
        val isSelf = staff.id != null && staff.id == session.staffId
        row.alpha = if (isSelf) 0.5f else 1f
        row.isClickable = !isSelf
        row.setOnClickListener {
            if (isSelf) {
                Toast.makeText(
                    requireContext(),
                    "You can't run security actions on your own account",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            // The tab decides which action the sheet offers, so a tab is a real
            // choice rather than a label.
            StaffSecurityBottomSheet.show(
                fm = childFragmentManager,
                staffId = staff.id!!,
                staffName = name,
                focus = when (selectedTab) {
                    Action.DEVICE_RESET -> StaffSecurityBottomSheet.FOCUS_DEVICE_RESET
                    Action.STAFF_LOGIN -> StaffSecurityBottomSheet.FOCUS_STAFF_LOGIN
                    Action.PASSWORD_RESET -> StaffSecurityBottomSheet.FOCUS_PASSWORD
                },
            )
        }
        return row
    }

    private fun initials(name: String): String =
        name.trim().split(" ").filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }

    // ---------- tabs ----------

    private fun buildTabs() {
        binding.securityTabs.removeAllViews()
        if (tabs.size <= 1) {
            // One action available — the strip would be a label, not a choice.
            binding.securityTabs.visibility = View.GONE
            return
        }
        binding.securityTabs.visibility = View.VISIBLE
        tabs.forEach { tab ->
            val active = tab.action == selectedTab
            binding.securityTabs.addView(
                TextView(requireContext()).apply {
                    text = tab.label
                    textSize = 13f
                    setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#475467"))
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(20).toFloat()
                        setColor(Color.parseColor(if (active) "#0B61CA" else "#F2F4F7"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(8) }
                    setOnClickListener {
                        selectedTab = tab.action
                        buildTabs()
                        render()
                    }
                },
            )
        }
    }

    // ---------- search + filters ----------

    private fun setupSearch() {
        binding.etSecuritySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val next = s?.toString().orEmpty()
                if (next == searchQuery) return
                searchQuery = next
                runSearch(next)
            }
        })
    }

    private fun openFilters() {
        SecurityFilterSheet.show(
            fm = childFragmentManager,
            // Options come from the pages loaded so far. They grow as the user
            // scrolls, which is honest: offering a designation from a page that
            // was never fetched would filter to an empty list.
            designations = loaded.mapNotNull { it.designation?.takeIf { d -> d.isNotBlank() } }
                .distinct().sorted(),
            departments = loaded.mapNotNull { it.department?.takeIf { d -> d.isNotBlank() } }
                .distinct().sorted(),
            selectedDesignation = designationFilter,
            selectedDepartment = departmentFilter,
        ) { designation, department ->
            designationFilter = designation
            departmentFilter = department
            render()
        }
    }

    private fun renderChips() {
        val active = listOfNotNull(
            designationFilter?.let { "Designation: $it" to { designationFilter = null } },
            departmentFilter?.let { "Department: $it" to { departmentFilter = null } },
        )
        binding.securityFilterDot.visibility =
            if (active.isEmpty()) View.GONE else View.VISIBLE
        binding.securityChipsScroll.visibility =
            if (active.isEmpty()) View.GONE else View.VISIBLE
        binding.securityChips.removeAllViews()
        active.forEach { (label, clear) ->
            binding.securityChips.addView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(6), dp(10), dp(6))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor("#EAF4FF"))
                        setStroke(dp(1), Color.parseColor("#B2DDFF"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(8) }
                    addView(TextView(context).apply {
                        text = label
                        textSize = 12f
                        setTextColor(Color.parseColor("#0B61CA"))
                    })
                    addView(ImageView(context).apply {
                        setImageResource(R.drawable.ic_filter_close)
                        layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                            marginStart = dp(6)
                        }
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#0B61CA"))
                    })
                    setOnClickListener {
                        clear()
                        render()
                    }
                },
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_SIZE = 25
        /** Long enough that typing a name is one request, not eight. */
        private const val SEARCH_DEBOUNCE_MS = 300L

        /** Whether this user can reach Security at all. */
        fun isAvailable(session: SessionManager): Boolean =
            session.hasPermission("staff.resetDeviceBinding") ||
                session.hasPermission("staff.password")

        fun newInstance() = SecurityFragment()
    }
}
