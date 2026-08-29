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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentSecurityBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.network.StaffPaginatedResponse
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.common.AvatarUtils.loadUserAvatar
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import com.manjugroups.m_connect.network.ActiveStaffLogin
import com.manjugroups.m_connect.network.StaffLoginSession
import com.manjugroups.m_connect.network.StaffIdRequest
import com.manjugroups.m_connect.network.ActiveStaffSession
import com.manjugroups.m_connect.network.LogoutStaffDeviceRequest
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
    private var loadMoreFailed = false
    private var loadFailed = false
    private var pageRetryJob: Job? = null
    private var dataLoadJob: Job? = null
    private var pageRetryRound = 0
    private lateinit var securityAdapter: SecurityAdapter

    private var searchQuery: String = ""
    private var searchJob: kotlinx.coroutines.Job? = null
    /** Search is served by the server so it reaches staff not yet paged in. */
    private var isSearching = false

    private var designationFilter: String? = null
    private var departmentFilter: String? = null

    // Staff Login shows a different dataset: who currently HAS a session, not
    // the staff directory. Loaded once (the endpoint returns the whole set) and
    // filtered client-side like the other tabs.
    private var logins: List<com.manjugroups.m_connect.network.ActiveStaffLogin> = emptyList()
    private var loginsLoaded = false

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
            "Shows active web and mobile sessions. Open a staff member to review or end individual device sessions.",
            "settings.staffLogin.view",
        ),
        Tab(
            Action.PASSWORD_RESET,
            "Password Reset",
            "Sets a new password and manages the password-expiry exemption.",
            "staff.password",
        ),
    )

    /**
     * Only the tabs this user may actually act on.
     *
     * Deliberately NOT hasPermission(): that is `isAdmin || key`, and mobile's
     * isAdmin flag is set for many non-admin roles — the phantom-key leak
     * documented on SessionManager.canViewVpDashboard. A super-admin is granted
     * explicitly instead, and everyone else needs the real IAM key.
     */
    private val tabs: List<Tab> by lazy {
        allTabs.filter { holdsSecurityKey(session, it.permission) }
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
        securityAdapter = SecurityAdapter()
        binding.securityList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = securityAdapter
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy >= 0) maybeLoadNextPage()
                }
            })
        }
        binding.btnSecurityFilter.setOnClickListener { openFilters() }
        binding.securityRefresh.setupPullToRefresh {
            if (isSearching) runSearch(searchQuery) else resetAndLoad()
        }
        binding.btnSecurityRetry.setOnClickListener {
            if (isSearching) runSearch(searchQuery) else resetAndLoad()
        }
        binding.btnSecurityLoadMoreRetry.setOnClickListener {
            pageRetryJob?.cancel()
            pageRetryRound = 0
            loadNextPage(first = loaded.isEmpty())
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
        dataLoadJob?.cancel()
        pageRetryJob?.cancel()
        pageRetryJob = null
        _binding = null
        super.onDestroyView()
    }

    // ---------- data ----------

    private fun resetAndLoad() {
        dataLoadJob?.cancel()
        dataLoadJob = null
        isLoading = false
        if (selectedTab == Action.STAFF_LOGIN) {
            loginsLoaded = false
            loadFailed = false
            loadLogins()
            return
        }
        loaded.clear()
        cursor = null
        isDone = false
        loadFailed = false
        loadMoreFailed = false
        pageRetryJob?.cancel()
        pageRetryRound = 0
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
        val requestedCursor = cursor
        isLoading = true
        loadMoreFailed = false
        renderLoadingState()

        dataLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val resp = getStaffPageWithRetry(requestedCursor)

            if (!isAdded || _binding == null) return@launch
            isLoading = false
            binding.securityRefresh.dismissRefresh()

            if (resp?.success != true) {
                // Keep the pages already on screen; only a FIRST-page failure
                // has nothing to show, and that gets an explicit retry rather
                // than a blank rectangle.
                loadFailed = loaded.isEmpty()
                loadMoreFailed = loaded.isNotEmpty() && !isDone
                render()
                schedulePageRetry(first = loaded.isEmpty())
                return@launch
            }

            val knownIds = loaded.mapNotNullTo(mutableSetOf()) { it.id }
            loaded.addAll(resp.page.filter { !it.id.isNullOrBlank() && knownIds.add(it.id!!) })
            val nextCursor = resp.continueCursor?.takeIf { it.isNotBlank() }
            cursor = nextCursor
            isDone = resp.isDone || nextCursor == null ||
                (!first && nextCursor == requestedCursor)
            loadFailed = false
            pageRetryJob?.cancel()
            pageRetryRound = 0
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
     * The field network can briefly lose DNS while remaining connected. The
     * same API host commonly resolves again a few hundred milliseconds later,
     * so retry this idempotent GET with the exact same cursor before exposing
     * a manual retry state. Never retry auth/client errors or cancelled jobs.
     */
    private suspend fun getStaffPageWithRetry(
        requestedCursor: String?,
    ): StaffPaginatedResponse? {
        repeat(PAGE_LOAD_ATTEMPTS) { attempt ->
            try {
                return api.getStaffPaginated(
                    token = session.bearerToken,
                    numItems = PAGE_SIZE,
                    cursor = requestedCursor,
                    // This screen needs the staff rows only; the enriched
                    // response hung the request.
                    lite = "1",
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val retryable = error is IOException ||
                    (error is HttpException && (error.code() == 408 ||
                        error.code() == 429 || error.code() >= 500))
                if (!retryable || attempt == PAGE_LOAD_ATTEMPTS - 1) return null
                delay(PAGE_RETRY_DELAYS_MS[attempt])
            }
        }
        return null
    }

    /** Keep a failed cursor alive and resume paging when DNS/network returns. */
    private fun schedulePageRetry(first: Boolean) {
        pageRetryJob?.cancel()
        val waitMs = AUTO_PAGE_RETRY_DELAYS_MS[
            pageRetryRound.coerceAtMost(AUTO_PAGE_RETRY_DELAYS_MS.lastIndex)
        ]
        pageRetryRound += 1
        pageRetryJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(waitMs)
            if (_binding == null || isDone || isSearching) return@launch
            loadNextPage(first = first)
        }
    }

    /** Pull the next page shortly before the final recycled row is visible. */
    private fun maybeLoadNextPage() {
        if (_binding == null || selectedTab == Action.STAFF_LOGIN || loadMoreFailed) return
        val manager = binding.securityList.layoutManager as? LinearLayoutManager ?: return
        val lastVisible = manager.findLastVisibleItemPosition()
        if (securityAdapter.itemCount == 0 ||
            lastVisible >= securityAdapter.itemCount - PAGE_PREFETCH_ROWS
        ) {
            loadNextPage()
        }
    }

    /**
     * Server-side search, so a name is found even when their page has not been
     * scrolled to yet. Debounced — a keystroke per request would hammer the
     * backend and the results would race.
     */
    private fun runSearch(query: String) {
        searchJob?.cancel()
        dataLoadJob?.cancel()
        dataLoadJob = null
        isLoading = false
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
            // lite: this screen needs the staff rows only. The enriched
            // response costs hundreds of extra reads and timed out.
            val resp = runCatching {
                api.searchStaff(session.bearerToken, q, lite = "1")
            }.getOrNull()
            if (!isAdded || _binding == null) return@launch
            isLoading = false
            if (resp?.success == true) {
                loaded.clear()
                loaded.addAll(resp.staff.filter {
                    !it.id.isNullOrBlank() && matchesSecuritySearch(it, q)
                })
                // A search result set is complete in itself — no paging.
                isDone = true
                cursor = null
            } else {
                loadFailed = loaded.isEmpty()
            }
            render()
        }
    }

    private fun matchesSecuritySearch(staff: StaffData, query: String): Boolean {
        val q = query.trim().lowercase(Locale.US)
        if (q.isBlank()) return true
        val digitsOnly = q.all(Char::isDigit)
        if (digitsOnly && q.length <= 6) {
            return staff.employeeId.orEmpty()
                .filter(Char::isLetterOrDigit)
                .lowercase(Locale.US)
                .contains(q)
        }
        val queryDigits = q.filter(Char::isDigit)
        return staff.name.orEmpty().lowercase(Locale.US).contains(q) ||
            staff.employeeId.orEmpty().lowercase(Locale.US).contains(q) ||
            (queryDigits.isNotEmpty() &&
                staff.phone.orEmpty().filter(Char::isDigit).contains(queryDigits))
    }

    /**
     * Live sessions for the Staff Login tab.
     *
     * A DIFFERENT dataset from the other two tabs: who currently has a session,
     * not the staff directory. A staff can be bound to a phone and not logged
     * in, or logged in on web only — showing the device binding here (as this
     * tab used to) answered the wrong question.
     *
     * The endpoint returns the whole set in one response, exactly as the web
     * table does, so there is no paging — the list is filtered client-side like
     * the other tabs.
     */
    private fun loadLogins() {
        if (isLoading) return
        isLoading = true
        renderLoadingState()
        dataLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                api.getActiveStaffLogins(session.bearerToken)
            }.getOrNull()
            if (!isAdded || _binding == null) return@launch
            isLoading = false
            binding.securityRefresh.dismissRefresh()
            if (resp?.success == true) {
                logins = resp.rows.filter { !it.staffId.isNullOrBlank() }
                loginsLoaded = true
                loadFailed = false
            } else {
                loadFailed = logins.isEmpty()
            }
            render()
        }
    }

    private fun visibleLogins(): List<ActiveStaffLogin> {
        val q = searchQuery.trim().lowercase(Locale.US)
        return logins.filter { r ->
            val matchesQuery = q.isEmpty() || listOfNotNull(
                r.name, r.employeeId, r.phone, r.designation, r.department,
            ).any { it.lowercase(Locale.US).contains(q) }
            val matchesDesignation =
                designationFilter == null || r.designation.equals(designationFilter, true)
            val matchesDepartment =
                departmentFilter == null || r.department.equals(departmentFilter, true)
            matchesQuery && matchesDesignation && matchesDepartment
        }.sortedBy { it.name?.lowercase(Locale.US) ?: "" }
    }

    /** One session row: web and mobile state side by side, as on the web table. */
    private fun loginRow(r: ActiveStaffLogin): View {
        val name = r.name?.takeIf { it.isNotBlank() } ?: "Unnamed staff"
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EAECF0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        card.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    marginEnd = dp(10)
                }
                contentDescription = "$name profile photo"
                loadUserAvatar(ProfilePhotos.resolve(r.photo), name)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                addView(TextView(context).apply {
                    text = name
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#101828"))
                })
                addView(TextView(context).apply {
                    text = listOfNotNull(
                        r.employeeId?.takeIf { it.isNotBlank() },
                        r.designation?.takeIf { it.isNotBlank() },
                        r.department?.takeIf { it.isNotBlank() },
                    ).joinToString(" - ")
                    textSize = 12f
                    setTextColor(Color.parseColor("#667085"))
                    setPadding(0, dp(2), 0, 0)
                })
            })
        })

        card.isClickable = true
        card.isFocusable = true
        card.setOnClickListener {
            r.staffId?.let { showLoggedInDevices(it, name) }
        }
        card.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(10))
        })

        card.addView(sessionLine("Web", r.webSession))
        card.addView(sessionLine("Mobile", r.mobileSession))

        card.addView(TextView(requireContext()).apply {
            text = if (r.deviceCount == 1) "1 device" else "${r.deviceCount} devices"
            textSize = 11f
            setTextColor(Color.parseColor("#98A2B3"))
            setPadding(0, dp(8), 0, 0)
        })

        // Opens the same grouped device list as web. Keep the row-level action
        // explicit for accessibility while allowing the whole card to open it.
        card.addView(TextView(requireContext()).apply {
            text = "View logged-in devices"
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#0B61CA"))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#EFF8FF"))
                setStroke(dp(1), Color.parseColor("#B2DDFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            isClickable = true
            setOnClickListener { r.staffId?.let { id -> showLoggedInDevices(id, name) } }
        })
        return card
    }

    /** "Web  Logged in  since 28 Aug, 12:21 pm", or a muted "Not logged in". */
    private fun sessionLine(label: String, info: StaffLoginSession?): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            val active = info?.createdAt != null
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(Color.parseColor("#667085"))
                layoutParams = LinearLayout.LayoutParams(
                    dp(56), LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(TextView(context).apply {
                text = if (active) "Logged in" else "Not logged in"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor(if (active) "#067647" else "#98A2B3"))
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(Color.parseColor(if (active) "#ECFDF3" else "#F2F4F7"))
                }
            })
            if (active) {
                addView(TextView(context).apply {
                    text = "since " + SimpleDateFormat("d MMM, h:mm a", Locale.US)
                        .format(Date(info!!.createdAt!!.toLong()))
                    textSize = 11f
                    setTextColor(Color.parseColor("#667085"))
                    setPadding(dp(8), 0, 0, 0)
                })
            }
        }

    private fun showLoggedInDevices(staffId: String, name: String) {
        val canLogout = holdsSecurityKey(session, "settings.staffLogin.create")
        val list = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(TextView(context).apply {
                text = "Loading devices..."
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#667085"))
                setPadding(dp(12), dp(28), dp(12), dp(28))
            })
        }
        val scroll = ScrollView(requireContext()).apply {
            addView(list)
        }
        val builder = AlertDialog.Builder(requireContext())
            .setTitle("Logged-in devices")
            .setMessage(
                "$name is signed in on the devices below. " +
                    if (canLogout) "Sign out one device or all devices." else "You have view-only access.",
            )
            .setView(scroll)
            .setNegativeButton("Close", null)
        if (canLogout) {
            builder.setPositiveButton("Logout all devices", null)
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(Color.parseColor("#D92D20"))
                setOnClickListener {
                    dialog.dismiss()
                    confirmLogout(staffId, name)
                }
            }
        }
        dialog.show()

        viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching {
                api.getActiveStaffSessions(session.bearerToken, staffId)
            }.getOrNull()
            if (!isAdded || !dialog.isShowing) return@launch
            list.removeAllViews()
            if (response?.success != true) {
                list.addView(sessionSheetMessage(response?.error ?: "Couldn't load active devices"))
                return@launch
            }
            if (response.sessions.isEmpty()) {
                list.addView(sessionSheetMessage("No active devices."))
                return@launch
            }
            response.sessions.forEach { activeSession ->
                list.addView(sessionDeviceCard(activeSession, staffId, name, dialog, canLogout))
            }
        }
    }

    private fun sessionSheetMessage(message: String) = TextView(requireContext()).apply {
        text = message
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#667085"))
        setPadding(dp(12), dp(28), dp(12), dp(28))
    }

    private fun sessionDeviceCard(
        activeSession: ActiveStaffSession,
        staffId: String,
        staffName: String,
        dialog: AlertDialog,
        canLogout: Boolean,
    ): View {
        val label = describeSessionDevice(activeSession)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#EAECF0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            addView(TextView(context).apply {
                val kind = if (activeSession.deviceType == "mobile") "Mobile" else "Web"
                text = "$kind  $label" + if (activeSession.isCurrent) "  This device" else ""
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.parseColor("#101828"))
            })
            addView(TextView(context).apply {
                val signedIn = activeSession.createdAt?.let {
                    SimpleDateFormat("d MMM, h:mm a", Locale.US).format(Date(it.toLong()))
                } ?: "Unknown"
                text = "Signed in $signedIn" + activeSession.ip.takeIf { it.isNotBlank() }
                    .let { ip -> if (ip == null) "" else " · $ip" }
                textSize = 11f
                setTextColor(Color.parseColor("#667085"))
                setPadding(0, dp(4), 0, 0)
            })
            if (canLogout) {
                addView(TextView(context).apply {
                    text = "Logout this device"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#B42318"))
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(Color.parseColor("#FEF3F2"))
                        setStroke(dp(1), Color.parseColor("#FDA29B"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
                    setOnClickListener {
                        isEnabled = false
                        text = "Signing out..."
                        viewLifecycleOwner.lifecycleScope.launch {
                            val response = runCatching {
                                api.logoutStaffDevice(
                                    session.bearerToken,
                                    LogoutStaffDeviceRequest(staffId, activeSession.sessionIds),
                                )
                            }.getOrNull()
                            if (!isAdded) return@launch
                            dialog.dismiss()
                            Toast.makeText(
                                requireContext(),
                                if (response?.success == true) "$staffName signed out of $label"
                                else response?.error ?: "Couldn't sign out device",
                                Toast.LENGTH_LONG,
                            ).show()
                            loginsLoaded = false
                            loadLogins()
                            if (response?.success != true) showLoggedInDevices(staffId, staffName)
                        }
                    }
                })
            }
        }
    }

    private fun describeSessionDevice(activeSession: ActiveStaffSession): String {
        if (activeSession.deviceType == "mobile") {
            val model = activeSession.model.ifBlank { activeSession.device }
            val platform = activeSession.os.ifBlank { "Android" }
            return if (model.isBlank()) "Mobile app on $platform" else "$model · $platform"
        }
        val browser = activeSession.browser.ifBlank { "Web browser" }
        return if (activeSession.os.isBlank()) browser else "$browser on ${activeSession.os}"
    }

    private fun confirmLogout(staffId: String, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Log out everywhere?")
            .setMessage(
                "Ends " + name + "'s web AND mobile sessions. They will need to sign in again.",
            )
            .setPositiveButton("Log out") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val resp = runCatching {
                        api.logoutStaffEverywhere(
                            session.bearerToken,
                            StaffIdRequest(staffId),
                        )
                    }.getOrNull()
                    if (!isAdded) return@launch
                    Toast.makeText(
                        requireContext(),
                        if (resp?.success == true) "Signed out everywhere"
                        else resp?.error ?: "Couldn't sign them out",
                        Toast.LENGTH_LONG,
                    ).show()
                    // Re-read rather than guessing the new state.
                    loginsLoaded = false
                    loadLogins()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        val firstLoad = isLoading && if (selectedTab == Action.STAFF_LOGIN) {
            logins.isEmpty()
        } else {
            loaded.isEmpty()
        }
        binding.securitySkeleton.visibility = if (firstLoad) View.VISIBLE else View.GONE
        if (firstLoad && binding.securitySkeleton.childCount == 0) {
            repeat(6) { binding.securitySkeleton.addView(skeletonRow()) }
        }
        binding.securityLoadingMore.visibility =
            if (isLoading && loaded.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnSecurityLoadMoreRetry.visibility =
            if (loadMoreFailed && !isLoading) View.VISIBLE else View.GONE
    }

    private fun render() {
        if (_binding == null) return
        renderLoadingState()

        val staffRows = visibleStaff()
        val loginRows = visibleLogins()
        binding.tvSecurityCount.text = when {
            selectedTab == Action.STAFF_LOGIN -> if (searchQuery.isBlank()) {
                "${loginRows.size} active"
            } else {
                "${loginRows.size} found"
            }
            isSearching -> "${staffRows.size} found"
            isDone -> "${staffRows.size} staff"
            // Honest while paging: more exist that have not been pulled yet.
            else -> "${staffRows.size} loaded"
        }
        binding.tvSecurityTabHint.text =
            tabs.firstOrNull { it.action == selectedTab }?.hint.orEmpty()
        renderChips()

        securityAdapter.submitList(
            if (selectedTab == Action.STAFF_LOGIN) {
                loginRows.map(SecurityListRow::Login)
            } else {
                staffRows.map(SecurityListRow::Staff)
            },
        )
        val hasRows = if (selectedTab == Action.STAFF_LOGIN) loginRows.isNotEmpty()
        else staffRows.isNotEmpty()
        binding.securityList.visibility = if (hasRows) View.VISIBLE else View.GONE

        // Nothing at all AND the first load failed -> an explicit retry, never a
        // blank screen the user has to guess about.
        val showError = loadFailed && !hasRows
        binding.securityError.visibility = if (showError) View.VISIBLE else View.GONE
        binding.tvSecurityErrorText.text = if (selectedTab == Action.STAFF_LOGIN) {
            "Couldn't load active sessions. Check your connection and try again."
        } else {
            "Couldn't load the staff directory. Check your connection and try again."
        }

        val showEmpty = !showError && !hasRows && !isLoading
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
        val initials = row.findViewById<TextView>(R.id.tvStaffInitials)
        val avatar = row.findViewById<ImageView>(R.id.ivStaffAvatar)
        initials.visibility = View.GONE
        avatar.visibility = View.VISIBLE
        avatar.loadUserAvatar(ProfilePhotos.resolve(staff.photo), name)
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
                        searchJob?.cancel()
                        dataLoadJob?.cancel()
                        dataLoadJob = null
                        isLoading = false
                        isSearching = false
                        selectedTab = tab.action
                        buildTabs()
                        if (tab.action == Action.STAFF_LOGIN && !loginsLoaded) {
                            loadLogins()
                        } else if (tab.action != Action.STAFF_LOGIN && loaded.isEmpty()) {
                            resetAndLoad()
                        }
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
                if (selectedTab == Action.STAFF_LOGIN) render() else runSearch(next)
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

    private sealed interface SecurityListRow {
        val stableId: String

        data class Staff(val value: StaffData) : SecurityListRow {
            override val stableId = "staff:${value.id}"
        }

        data class Login(val value: ActiveStaffLogin) : SecurityListRow {
            override val stableId = "login:${value.staffId}"
        }
    }

    /** Keeps only visible cards alive while preserving the existing row actions. */
    private inner class SecurityAdapter :
        ListAdapter<SecurityListRow, SecurityAdapter.RowHolder>(ROW_DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val container = android.widget.FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            return RowHolder(container)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.container.removeAllViews()
            val view = when (val row = getItem(position)) {
                is SecurityListRow.Staff -> staffRow(row.value)
                is SecurityListRow.Login -> loginRow(row.value)
            }
            holder.container.addView(view)
        }

        inner class RowHolder(val container: android.widget.FrameLayout) :
            RecyclerView.ViewHolder(container)
    }

    companion object {
        private val ROW_DIFF = object : DiffUtil.ItemCallback<SecurityListRow>() {
            override fun areItemsTheSame(
                oldItem: SecurityListRow,
                newItem: SecurityListRow,
            ) = oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(
                oldItem: SecurityListRow,
                newItem: SecurityListRow,
            ) = oldItem == newItem
        }

        private const val PAGE_SIZE = 25
        private const val PAGE_PREFETCH_ROWS = 8
        private const val PAGE_LOAD_ATTEMPTS = 3
        private val PAGE_RETRY_DELAYS_MS = longArrayOf(300L, 900L)
        private val AUTO_PAGE_RETRY_DELAYS_MS = longArrayOf(4_000L, 10_000L, 20_000L, 30_000L)
        /** Long enough that typing a name is one request, not eight. */
        private const val SEARCH_DEBOUNCE_MS = 300L

        /**
         * Super-admins always hold Security; everyone else needs the explicit
         * IAM key. Reading the EXPLICIT permission set rather than
         * hasPermission() keeps the blanket isAdmin flag — which mobile sets
         * for many non-admin roles — from handing out device resets and
         * password changes.
         */
        private fun holdsSecurityKey(session: SessionManager, key: String): Boolean =
            (session.role ?: "").trim().equals("super-admin", ignoreCase = true) ||
                session.iamPermissions.contains(key)

        /** Whether this user can reach Security at all. */
        fun isAvailable(session: SessionManager): Boolean =
            SECURITY_KEYS.any { holdsSecurityKey(session, it) }

        private val SECURITY_KEYS = listOf(
            "staff.resetDeviceBinding",
            "staff.password",
            "settings.staffLogin.view",
        )

        fun newInstance() = SecurityFragment()
    }
}
