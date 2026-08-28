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

    private var allStaff: List<StaffData> = emptyList()
    private var searchQuery: String = ""
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
        binding.securityRefresh.setupPullToRefresh { load() }

        // A staff action taken in the sheet can change what the list shows.
        parentFragmentManager.setFragmentResultListener(
            StaffSecurityBottomSheet.RESULT_KEY, viewLifecycleOwner,
        ) { _, _ -> render() }
        childFragmentManager.setFragmentResultListener(
            StaffSecurityBottomSheet.RESULT_KEY, viewLifecycleOwner,
        ) { _, _ -> render() }

        load()
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
        _binding = null
        super.onDestroyView()
    }

    // ---------- data ----------

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching { api.getStaff(session.bearerToken) }.getOrNull()
            if (!isAdded || _binding == null) return@launch
            binding.securityRefresh.dismissRefresh()
            if (resp?.success != true) {
                // Keep whatever is already on screen; a failed refresh must not
                // empty a directory the user is working through.
                if (allStaff.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        resp?.let { "Couldn't load staff" } ?: "Network error",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                render()
                return@launch
            }
            allStaff = resp.staff
                .filter { !it.id.isNullOrBlank() }
                // Active first, then by name, so the people most likely to need
                // an action are at the top.
                .sortedWith(
                    compareBy<StaffData> { if (it.status == "active") 0 else 1 }
                        .thenBy { it.name?.lowercase(Locale.US) ?: "" },
                )
            render()
        }
    }

    private fun visibleStaff(): List<StaffData> {
        val q = searchQuery.trim().lowercase(Locale.US)
        return allStaff.filter { s ->
            val matchesQuery = q.isEmpty() || listOfNotNull(
                s.name, s.employeeId, s.phone, s.designation, s.department,
            ).any { it.lowercase(Locale.US).contains(q) }
            val matchesDesignation =
                designationFilter == null || s.designation.equals(designationFilter, true)
            val matchesDepartment =
                departmentFilter == null || s.department.equals(departmentFilter, true)
            matchesQuery && matchesDesignation && matchesDepartment
        }
    }

    // ---------- rendering ----------

    private fun render() {
        if (_binding == null) return
        val rows = visibleStaff()
        binding.tvSecurityCount.text =
            if (rows.size == allStaff.size) "${rows.size} staff"
            else "${rows.size} of ${allStaff.size}"

        binding.tvSecurityTabHint.text =
            tabs.firstOrNull { it.action == selectedTab }?.hint.orEmpty()

        renderChips()

        binding.securityList.removeAllViews()
        rows.forEach { binding.securityList.addView(staffRow(it)) }

        val empty = rows.isEmpty()
        binding.securityEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.securityList.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            val filtered = searchQuery.isNotBlank() ||
                designationFilter != null || departmentFilter != null
            binding.tvSecurityEmptyTitle.text =
                if (filtered) "No matches" else "No staff found"
            binding.tvSecurityEmptySubtitle.text = if (filtered) {
                "Try a different search term or clear the filters."
            } else {
                "The staff directory came back empty."
            }
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
                searchQuery = s?.toString().orEmpty()
                render()
            }
        })
    }

    private fun openFilters() {
        SecurityFilterSheet.show(
            fm = childFragmentManager,
            designations = allStaff.mapNotNull { it.designation?.takeIf { d -> d.isNotBlank() } }
                .distinct().sorted(),
            departments = allStaff.mapNotNull { it.department?.takeIf { d -> d.isNotBlank() } }
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
        /** Whether this user can reach Security at all. */
        fun isAvailable(session: SessionManager): Boolean =
            session.hasPermission("staff.resetDeviceBinding") ||
                session.hasPermission("staff.password")

        fun newInstance() = SecurityFragment()
    }
}
