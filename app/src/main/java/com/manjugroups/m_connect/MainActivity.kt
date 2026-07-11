package com.manjugroups.m_connect

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.core.view.ViewCompat
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.auth.ForcePasswordChangeActivity
import com.manjugroups.m_connect.auth.LoginActivity
import com.manjugroups.m_connect.auth.OnboardingPrefs
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.auth.WelcomeActivity
import com.manjugroups.m_connect.geotrack.GeoTrackBootstrapSync
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TrackingBootstrapData
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.notifications.WorkflowNotificationRoute
import com.manjugroups.m_connect.update.InAppUpdateManager
import com.manjugroups.m_connect.ui.chat.ChatListFragment
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment
import com.manjugroups.m_connect.ui.home.HomeFragment
import com.manjugroups.m_connect.ui.hr.HrDashboardFragment
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
import com.manjugroups.m_connect.ui.library.AppLibraryFragment
import com.manjugroups.m_connect.geotrack.TrackingCheckWorker
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import kotlinx.coroutines.launch
import kotlinx.coroutines.async

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_HR = 1
        const val TAB_CHAT = 2
        const val TAB_LIBRARY = 3

        private const val KEY_CURRENT_TAB = "current_tab"
        private const val TAG_HOME = "root_tab_home"
        private const val TAG_HR = "root_tab_hr"
        private const val TAG_CHAT = "root_tab_chat"
        private const val TAG_LIBRARY = "root_tab_library"
        private const val TRACKING_RESUME_SYNC_THROTTLE_MS = 30_000L
    }

    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val geoApi = GeoTrackApi.create()
    // Google Play in-app updates. Initialized in onCreate only once we know the
    // user stays in the shell (past the login / force-password redirects).
    private var inAppUpdateManager: InAppUpdateManager? = null
    private var currentTab = 0
    private var cachedTopInset = 0
    private var statusBarFullBleed = false
    private var lastTrackingResumeSyncMs = 0L
    private var isBottomNavVisible = true
    // Periodic IAM polling job — runs while the activity is in the
    // foreground so a permission flip on the web reaches gated UI
    // (App Library tiles, HR review buttons, etc.) within ~20s even
    // when the user is just staring at the screen. Cancelled in
    // onPause so we don't hammer the API in the background.
    private var iamPollJob: kotlinx.coroutines.Job? = null
    private val IAM_POLL_INTERVAL_MS = 20_000L

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            lifecycleScope.launch {
                runCatching {
                    PushTokenManager.syncCurrentToken(this@MainActivity, session)
                }
            }
        }
    }

    private data class TabConfig(
        val tab: FrameLayout,
        val icon: ImageView,
        val indicator: View,
        val text: TextView,
        val activeIconRes: Int,
        val inactiveIconRes: Int
    )
    private lateinit var tabs: List<TabConfig>
    private lateinit var tabBarContainer: FrameLayout
    private lateinit var floatingPeakContainer: android.view.View
    private lateinit var peekingPillsLayout: android.view.View
    private lateinit var expandedCarouselLayout: android.view.View
    private lateinit var vpPendingCards: androidx.viewpager2.widget.ViewPager2
    private lateinit var carouselIndicators: android.widget.LinearLayout
    private lateinit var tvCountTasks: android.widget.TextView
    private lateinit var tvCountVisits: android.widget.TextView
    private lateinit var tvCountFollowUps: android.widget.TextView
    private lateinit var pillTasks: android.view.View
    private lateinit var pillVisits: android.view.View
    private lateinit var pillFollowUps: android.view.View

    private val pendingCardItems = mutableListOf<PendingCardItem>()
    private lateinit var carouselAdapter: PendingCardsAdapter
    // Newest open task — the LIFO "top of stack" the Complete chip routes to.
    private var topPendingTask: com.manjugroups.m_connect.network.DailyTaskData? = null
    // Set when the pending-tasks notification is tapped — routes to the top
    // task once the next banner refresh has loaded it.
    private var openTasksOnNextRefresh = false
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var mainRoot: LinearLayout
    private lateinit var statusBarBackground: View
    private lateinit var bottomNavFadeOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        session = SessionManager(this)
        if (!session.isLoggedIn) {
            // Existing/onboarded users go to Login; only a genuine first run
            // (onboarding never completed) sees the Welcome carousel.
            val onboarded = OnboardingPrefs(this).onboardingCompleted
            startActivity(
                Intent(this, if (onboarded) LoginActivity::class.java else WelcomeActivity::class.java)
            )
            finish()
            return
        }
        if (session.mustChangePassword) {
            startActivity(Intent(this, ForcePasswordChangeActivity::class.java))
            finish()
            return
        }

        // Google Play in-app updates — checked on every cold start. High-priority
        // releases force an immediate (blocking) update; everything else downloads
        // flexibly in the background and prompts to restart. Constructed here,
        // during onCreate (before STARTED), so its activity-result launcher is
        // registered validly. No-ops on dev/sideload builds.
        inAppUpdateManager = InAppUpdateManager(this).also { it.start() }

        // Surface the POST_NOTIFICATIONS system prompt on Android 13+ so
        // the user gets push notifications for chats / tasks / approvals.
        // Guarded on the persisted `notificationPermissionPrompted` flag
        // so a user who already declined isn't pestered every cold start
        // — they can still re-enable from system settings or the in-app
        // toggle. The launcher's grant callback re-runs token sync so
        // the device becomes reachable the moment permission is granted.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PushTokenManager.hasNotificationPermission(this) &&
            !session.notificationPermissionPrompted
        ) {
            session.notificationPermissionPrompted = true
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Listen for any API call returning 401 — when that happens
        // the saved session token is no longer valid (expired, revoked,
        // or minted against a different Convex deployment than the
        // current build is pointing at). Clear local state + bounce to
        // login so the user can re-authenticate. Without this, a 401
        // would silently fail every screen and leave the app in a
        // "everything's empty / errored" stuck state.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.manjugroups.m_connect.auth.SessionInvalidationBus
                    .signals.collect {
                        if (!session.isLoggedIn) return@collect
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "Session expired. Please sign in again.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        session.clearSession()
                        startActivity(
                            Intent(
                                this@MainActivity,
                                LoginActivity::class.java,
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                            },
                        )
                        finish()
                    }
            }
        }

        TrackingCheckWorker.enqueue(this)
        setContentView(R.layout.activity_main)
        mainRoot = findViewById(R.id.mainRoot)
        statusBarBackground = findViewById(R.id.statusBarBackground)
        fragmentContainer = findViewById(R.id.fragmentContainer)
        tabBarContainer = findViewById(R.id.tabBarContainer)
        bottomNavFadeOverlay = findViewById(R.id.bottomNavFadeOverlay)

        // Initialize new Floating Peak Pills and Card Carousel
        floatingPeakContainer = findViewById(R.id.floatingPeakContainer)
        peekingPillsLayout = findViewById(R.id.peekingPillsLayout)
        expandedCarouselLayout = findViewById(R.id.expandedCarouselLayout)
        vpPendingCards = findViewById(R.id.vpPendingCards)
        carouselIndicators = findViewById(R.id.carouselIndicators)
        tvCountTasks = findViewById(R.id.tvCountTasks)
        tvCountVisits = findViewById(R.id.tvCountVisits)
        tvCountFollowUps = findViewById(R.id.tvCountFollowUps)
        pillTasks = findViewById(R.id.pillTasks)
        pillVisits = findViewById(R.id.pillVisits)
        pillFollowUps = findViewById(R.id.pillFollowUps)

        carouselAdapter = PendingCardsAdapter()
        vpPendingCards.adapter = carouselAdapter

        // Configure ViewPager2 side page peeking
        vpPendingCards.clipToPadding = false
        vpPendingCards.clipChildren = false
        vpPendingCards.offscreenPageLimit = 3

        val pageMarginPx = (10 * resources.displayMetrics.density).toInt()
        val offsetPx = (40 * resources.displayMetrics.density).toInt()
        vpPendingCards.setPadding(offsetPx, 0, offsetPx, 0)

        vpPendingCards.setPageTransformer { page, position ->
            val offset = position * -(2 * offsetPx + pageMarginPx)
            page.translationX = offset

            val absPos = Math.abs(position)
            page.scaleY = 0.92f + (1f - 0.92f) * (1f - absPos).coerceIn(0f, 1f)
            page.scaleX = 0.92f + (1f - 0.92f) * (1f - absPos).coerceIn(0f, 1f)
            page.alpha = 0.5f + (1f - 0.5f) * (1f - absPos).coerceIn(0f, 1f)
        }

        vpPendingCards.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(true, position)
            }
        })

        pillTasks.setOnClickListener { expandCarousel(CardType.TASK) }
        pillVisits.setOnClickListener { expandCarousel(CardType.VISIT) }
        pillFollowUps.setOnClickListener { expandCarousel(CardType.FOLLOW_UP) }

        // Setup collapse trigger when clicked on Home tab icon or outside
        supportFragmentManager.addOnBackStackChangedListener { refreshTasksBanner() }

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#F1F3F8")))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = true

        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            if (sys.top > 0) {
                cachedTopInset = sys.top
            }
            if (!statusBarFullBleed && cachedTopInset > 0) {
                statusBarBackground.layoutParams = statusBarBackground.layoutParams.apply {
                    height = cachedTopInset
                }
            }
            // When the floating tab bar is visible it already absorbs `sys.bottom`,
            // so the fragment only needs the *additional* IME height. When the tab
            // bar is hidden (chat thread, trip nav) the fragment owns the full
            // bottom inset, so we apply `max(ime.bottom, sys.bottom)` — otherwise
            // the keyboard overlaps the toolbar by exactly the nav-bar height.
            val tabBarShowing = ::tabBarContainer.isInitialized &&
                tabBarContainer.visibility == android.view.View.VISIBLE
            // adjustResize handles IME. The tab bar absorbs sys.bottom when
            // visible. When the tab bar is hidden, each detail fragment is
            // responsible for its own bottom inset — chat needs the input bar
            // flush to the screen edge, while scroll-based screens (loans,
            // contact info, etc.) apply paddingBottom themselves.
            val fragmentBottomInset = 0
            fragmentContainer.updatePadding(top = 0, bottom = fragmentBottomInset)
            val baseBottomPx = (8 * resources.displayMetrics.density).toInt()
            mainRoot.updatePadding(bottom = 0)
            tabBarContainer.updatePadding(left = sys.left, right = sys.right, bottom = sys.bottom + baseBottomPx)
            insets
        }
        ViewCompat.requestApplyInsets(mainRoot)

        // Same outline icon for active + inactive — only the tint changes,
        // matching the design where the shape stays constant and color flips
        // between bright green (#1BCA0B) and soft gray (#D0D5DD).
        tabs = listOf(
            TabConfig(
                findViewById(R.id.tabHome),
                findViewById(R.id.tabHomeIcon),
                findViewById(R.id.tabHomeIndicator),
                findViewById(R.id.tabHomeText),
                R.drawable.ic_nav_home,
                R.drawable.ic_nav_home
            ),
            TabConfig(
                findViewById(R.id.tabHr),
                findViewById(R.id.tabHrIcon),
                findViewById(R.id.tabHrIndicator),
                findViewById(R.id.tabHrText),
                R.drawable.ic_nav_attendance,
                R.drawable.ic_nav_attendance
            ),
            TabConfig(
                findViewById(R.id.tabChat),
                findViewById(R.id.tabChatIcon),
                findViewById(R.id.tabChatIndicator),
                findViewById(R.id.tabChatText),
                R.drawable.ic_nav_chat,
                R.drawable.ic_nav_chat
            ),
            TabConfig(
                findViewById(R.id.tabProfile),
                findViewById(R.id.tabProfileIcon),
                findViewById(R.id.tabProfileIndicator),
                findViewById(R.id.tabProfileText),
                R.drawable.ic_nav_apps,
                R.drawable.ic_nav_apps
            )
        )

        // Initialize all tabs with inactive icon variant.
        tabs.forEach { it.icon.setImageResource(it.inactiveIconRes) }

        // Tab click listeners
        tabs.forEachIndexed { index, config ->
            config.tab.setOnClickListener { selectTab(index) }
        }

        // Defensive: keep the floating nav restricted to root tabs.
        // When a child fragment is pushed onto the back stack, hide the bar;
        // when the back stack drains, re-apply the active tab's chrome so
        // header/tab state never bleeds in from the popped fragment.
        supportFragmentManager.addOnBackStackChangedListener {
            val onRoot = supportFragmentManager.backStackEntryCount == 0
            setTabBarVisible(onRoot)
            if (onRoot) applyTopBarForTab(currentTab)
        }

        currentTab = normalizeTab(savedInstanceState?.getInt(KEY_CURRENT_TAB, TAB_HOME) ?: TAB_HOME)

        // External-fleet agency principals (designation = "External Fleet")
        // live in a single-screen portal: the Admin Fleet container, with its
        // own bottom nav (Trips / Vehicles / Driver / Settings). Skip the
        // normal MainActivity tab bar entirely so they never see Home / HR /
        // Chat / Profile, which assume a real staff record they don't have.
        if (isExternalFleetPrincipal()) {
            setTabBarVisible(false)
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(
                        R.id.fragmentContainer,
                        com.manjugroups.m_connect.ui.library.AdminFleetContainerFragment(),
                        "external_fleet_root",
                    )
                    .commit()
            }
            lifecycleScope.launch { refreshSessionContext() }
            return
        }

        if (savedInstanceState == null) {
            selectTab(TAB_HOME)
            handleWorkflowNotificationIntent(intent)
            handleTasksNotificationIntent(intent)
            handleTrackingNotificationIntent(intent)
        } else {
            updateTabUi(currentTab)
            applyTopBarForTab(currentTab)
        }

        lifecycleScope.launch {
            refreshSessionContext()
        }
    }

    /**
     * On every foreground transition, re-fetch the tracking bootstrap and
     * attendance state. This is what auto-starts GeoTrack after a biometric
     * (or any external) punch-in: the punch happens off-device, the app comes
     * back to the foreground, and the next sync sees `firstPunchIn` set on the
     * server and `bootstrap.shouldTrack == true`, so the service starts.
     *
     * Throttled to one sync per 30 s to avoid hammering the API when the user
     * bounces between tabs or returns from a quick external app.
     */
    /**
     * Refresh the red pending-tasks nudge on the nav. Shows the number of
     * incomplete My Tasks (attendance review, CP, SV, ... — same source as
     * the web Task Manager); hides itself the moment everything is done.
     * Safe to call from fragments after completing a task.
     */
    /** Open the mobile Task Manager screen (banner Complete + notification tap). */
    fun openTaskManager() {
        // Immediately hide the carousel to prevent ViewPager2 internal
        // RecyclerView state conflicts during the fragment transaction.
        if (::floatingPeakContainer.isInitialized) {
            floatingPeakContainer.visibility = View.GONE
            expandedCarouselLayout.visibility = View.GONE
            peekingPillsLayout.visibility = View.GONE
        }
        // Post to avoid crashing when called from within ViewPager2's
        // RecyclerView click dispatch (adapter mutation during layout).
        fragmentContainer.post {
            if (!isFinishing && !isDestroyed) {
                supportFragmentManager.beginTransaction()
                    .applySmoothTransitions()
                    .replace(R.id.fragmentContainer, com.manjugroups.m_connect.ui.tasks.TaskManagerFragment())
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            }
        }
    }

    fun refreshTasksBanner() {
        if (!session.isLoggedIn) {
            com.manjugroups.m_connect.notifications.TasksNotification.clear(this)
            return
        }
        lifecycleScope.launch {
            try {
                val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                val token = session.bearerToken

                val (tasks, visits, cpVisits) = kotlinx.coroutines.coroutineScope {
                    val tasksDeferred = async {
                        runCatching {
                            api.getTaskManagerTasks(token, todayStr).tasks
                                .filter { it.status == "pending" || it.status == "in-progress" }
                        }.getOrDefault(emptyList())
                    }
                    val visitsDeferred = async {
                        runCatching {
                            geoApi.getTodayVisits(token, todayStr).data
                                ?.filter { it.status != "completed" && it.status != "cancelled" }
                        }.getOrNull() ?: emptyList()
                    }
                    val cpDeferred = async {
                        runCatching {
                            geoApi.getMyMarketingCpVisits(token, fromDate = null, toDate = null).visits
                                .filter { it.status != "completed" && it.status != "cancelled" }
                        }.getOrDefault(emptyList())
                    }
                    Triple(tasksDeferred.await(), visitsDeferred.await(), cpDeferred.await())
                }

                if (isFinishing || isDestroyed) return@launch

                pendingCardItems.clear()

                tasks.forEach { task ->
                    pendingCardItems.add(
                        PendingCardItem(
                            id = task.id,
                            type = CardType.TASK,
                            title = task.title ?: task.taskName ?: "Task Pending",
                            subtitle = task.description ?: "General task description",
                            timeLabel = "Deadline: ${task.deadline ?: "Today"}"
                        )
                    )
                }

                visits.forEach { visit ->
                    pendingCardItems.add(
                        PendingCardItem(
                            id = visit.id,
                            type = CardType.VISIT,
                            title = visit.leadName ?: visit.placeName ?: "Site Visit",
                            subtitle = visit.placeAddress ?: "Visit Location",
                            timeLabel = "Scheduled: ${visit.scheduledStartTime ?: "Today"}",
                            distanceLabel = "12.4 km away"
                        )
                    )
                }

                cpVisits.forEach { cp ->
                    pendingCardItems.add(
                        PendingCardItem(
                            id = cp.id ?: "",
                            type = CardType.FOLLOW_UP,
                            title = cp.clientPlaceId ?: "Client Met",
                            subtitle = cp.cpType ?: "Client Follow-up",
                            timeLabel = "Time: ${cp.scheduledTime ?: "Today"}"
                        )
                    )
                }

                // Update text badge counts — guard against uninitialized views
                if (::tvCountTasks.isInitialized) tvCountTasks.text = tasks.size.toString()
                if (::tvCountVisits.isInitialized) tvCountVisits.text = visits.size.toString()
                if (::tvCountFollowUps.isInitialized) tvCountFollowUps.text = cpVisits.size.toString()

                if (::carouselAdapter.isInitialized) {
                    carouselAdapter.submitList(pendingCardItems.toList())
                }

                // Update system notification for tasks
                topPendingTask = tasks.maxByOrNull { it.creationTime ?: 0.0 }
                val pending = tasks.size
                val dueSoon = tasks.count { t ->
                    val d = t.deadline?.trim().orEmpty()
                    d.isNotEmpty() && d <= todayStr
                }

                com.manjugroups.m_connect.notifications.TasksNotification.update(
                    this@MainActivity,
                    pending,
                    dueSoon,
                    topPendingTask?.let { it.title ?: it.taskName },
                )

                // Visibility logic — only touch views when initialized
                if (::floatingPeakContainer.isInitialized) {
                    if (pendingCardItems.isNotEmpty() && currentTab == TAB_HOME) {
                        floatingPeakContainer.visibility = View.VISIBLE
                        if (expandedCarouselLayout.visibility != View.VISIBLE) {
                            peekingPillsLayout.visibility = View.VISIBLE
                        }
                    } else {
                        floatingPeakContainer.visibility = View.GONE
                        expandedCarouselLayout.visibility = View.GONE
                        peekingPillsLayout.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error refreshing peak cards: ${e.message}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!session.isLoggedIn) return
        refreshTasksBanner()
        // Re-assert the background permissions gate every time we come
        // forward. Dialog is no-op when both checks already pass and
        // self-dismisses when the user returns from Settings having
        // toggled the missing one ON. Scoped to staff who actually need
        // background tracking — office staff aren't force-prompted.
        maybeShowBackgroundPermissionsGate()
        // Finish a downloaded flexible update / resume a stalled immediate one.
        inAppUpdateManager?.onResume()
        // Kick a periodic IAM poll while the app is in the foreground.
        // Forces a refresh every IAM_POLL_INTERVAL_MS (currently 20s)
        // regardless of throttle, so a permission change on the web
        // lands within one interval even if the user is sitting on the
        // App Library staring at the tiles. Job is cancelled in
        // onPause; only one job runs at a time.
        startIamPolling()
        val now = System.currentTimeMillis()
        if (now - lastTrackingResumeSyncMs < TRACKING_RESUME_SYNC_THROTTLE_MS) return
        lastTrackingResumeSyncMs = now
        lifecycleScope.launch {
            runCatching { syncTrackingBootstrap() }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop polling while the app isn't visible — no point burning
        // network + battery for UI nobody can see. onResume restarts.
        iamPollJob?.cancel()
        iamPollJob = null
    }

    override fun onDestroy() {
        // Release the in-app-update install listener so we don't leak the
        // activity through Play's callback registry.
        inAppUpdateManager?.destroy()
        inAppUpdateManager = null
        super.onDestroy()
    }


    private fun startIamPolling() {
        if (iamPollJob?.isActive == true) return
        iamPollJob = lifecycleScope.launch {
            // First refresh fires immediately so the foreground
            // transition itself feels responsive; then drops into a
            // steady IAM_POLL_INTERVAL_MS cadence. Each tick uses
            // `force = true` so it bypasses the bus's 5s throttle —
            // the throttle is there to protect against burst
            // refreshes, not against scheduled polling.
            while (true) {
                runCatching {
                    com.manjugroups.m_connect.auth.IamUpdateBus.refresh(
                        session, force = true,
                    )
                }
                kotlinx.coroutines.delay(IAM_POLL_INTERVAL_MS)
            }
        }
    }

    private fun maybeShowBackgroundPermissionsGate() {
        if (!session.geoTrackingEnabled) return
        // Foregrounding is the most reliable moment to reconcile the ongoing
        // red permission alert with reality: clear it the instant every
        // tracking permission is present, (re)post it while any is missing —
        // even outside the tracking window when the service isn't running.
        com.manjugroups.m_connect.notifications.PermissionAlertNotification.update(
            this,
            com.manjugroups.m_connect.geotrack.BackgroundPermissionsGateDialog
                .missingPermissionKeys(this),
        )
        com.manjugroups.m_connect.geotrack.BackgroundPermissionsGateDialog
            .showIfNeeded(supportFragmentManager, this)
    }

    fun openTab(index: Int) {
        selectTab(index)
    }

    fun setTabBarVisible(visible: Boolean) {
        if (!::tabBarContainer.isInitialized) return
        val target = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        if (tabBarContainer.visibility == target) {
            if (visible) {
                tabBarContainer.translationY = 0f
                tabBarContainer.alpha = 1f
                if (::bottomNavFadeOverlay.isInitialized) {
                    bottomNavFadeOverlay.translationY = 0f
                    bottomNavFadeOverlay.alpha = 1f
                }
                isBottomNavVisible = true
            }
            return
        }
        tabBarContainer.visibility = target
        if (::bottomNavFadeOverlay.isInitialized) {
            bottomNavFadeOverlay.visibility = target
        }
        if (visible) {
            tabBarContainer.translationY = 0f
            tabBarContainer.alpha = 1f
            if (::bottomNavFadeOverlay.isInitialized) {
                bottomNavFadeOverlay.translationY = 0f
                bottomNavFadeOverlay.alpha = 1f
            }
            isBottomNavVisible = true
        }
        // Re-dispatch insets so fragmentContainer.bottom padding flips between
        // "tab bar absorbs nav-bar" and "fragment owns full bottom inset".
        if (::mainRoot.isInitialized) {
            ViewCompat.requestApplyInsets(mainRoot)
        }
    }

    fun setBottomNavScrollState(visible: Boolean) {
        if (!::tabBarContainer.isInitialized) return
        if (tabBarContainer.visibility != android.view.View.VISIBLE && visible) return
        if (isBottomNavVisible == visible) return
        isBottomNavVisible = visible

        val translationDistance = 150f * resources.displayMetrics.density
        val translationY = if (visible) 0f else translationDistance
        val alpha = if (visible) 1f else 0f

        tabBarContainer.animate()
            .translationY(translationY)
            .alpha(alpha)
            .setDuration(400)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()

        if (::bottomNavFadeOverlay.isInitialized) {
            bottomNavFadeOverlay.animate()
                .translationY(translationY)
                .alpha(alpha)
                .setDuration(400)
                .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .start()
        }
    }

    /**
     * Lets transient overlays (e.g. CompleteCpVisitBottomSheet) snapshot the
     * tab-bar state before hiding it, so they can restore *exactly* what
     * was there on dismiss — root tabs leave the bar visible, secondary
     * screens leave it hidden. Without this they'd have to assume one or
     * the other and would re-show the bar on screens that intentionally
     * hide it.
     */
    fun isTabBarVisible(): Boolean {
        if (!::tabBarContainer.isInitialized) return false
        return tabBarContainer.visibility == android.view.View.VISIBLE
    }

    fun setTopBarAppearance(backgroundColor: Int, darkStatusIcons: Boolean, fullBleed: Boolean = false) {
        if (!::statusBarBackground.isInitialized) return
        val wasFullBleed = statusBarFullBleed
        statusBarFullBleed = fullBleed
        if (fullBleed) {
            statusBarBackground.layoutParams = statusBarBackground.layoutParams.apply { height = 0 }
            window.statusBarColor = Color.TRANSPARENT
        } else {
            statusBarBackground.setBackgroundColor(backgroundColor)
            statusBarBackground.layoutParams = statusBarBackground.layoutParams.apply {
                height = cachedTopInset
            }
            window.statusBarColor = backgroundColor
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = darkStatusIcons
        
        if (wasFullBleed != fullBleed || (fullBleed == false && statusBarBackground.layoutParams.height == 0)) {
            ViewCompat.requestApplyInsets(mainRoot)
        }
    }

    private fun selectTab(index: Int) {
        if (currentTab == index && supportFragmentManager.backStackEntryCount == 0) {
            if (index == TAB_HOME && ::expandedCarouselLayout.isInitialized) {
                if (expandedCarouselLayout.visibility == View.VISIBLE) {
                    collapseCarousel()
                } else if (pendingCardItems.isNotEmpty()) {
                    expandCarousel(CardType.TASK)
                }
            }
            return
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        currentTab = index
        updateTabUi(index)
        applyTopBarForTab(index)
        setTabBarVisible(true)

        // Show/hide floatingPeakContainer based on tab
        if (::floatingPeakContainer.isInitialized) {
            if (index == TAB_HOME && pendingCardItems.isNotEmpty()) {
                floatingPeakContainer.visibility = View.VISIBLE
                peekingPillsLayout.visibility = View.VISIBLE
                expandedCarouselLayout.visibility = View.GONE
            } else {
                floatingPeakContainer.visibility = View.GONE
                expandedCarouselLayout.visibility = View.GONE
            }
        }

        val targetTag = tabTag(index)
        val existingTarget = supportFragmentManager.findFragmentByTag(targetTag)
        val fragment = existingTarget ?: createRootFragment(index)
        val transaction = supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)

        rootTabTags().forEach { tag ->
            supportFragmentManager.findFragmentByTag(tag)?.let(transaction::hide)
        }

        if (existingTarget == null) {
            transaction.add(R.id.fragmentContainer, fragment, targetTag)
        } else {
            transaction.show(existingTarget)
        }

        transaction.commit()
    }

    private fun applyTopBarForTab(index: Int) {
        when (index) {
            TAB_HOME -> {
                setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
                if (::bottomNavFadeOverlay.isInitialized) {
                    bottomNavFadeOverlay.setBackgroundResource(R.drawable.bg_bottom_nav_fade_grey)
                }
            }
            TAB_HR -> {
                setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
                if (::bottomNavFadeOverlay.isInitialized) {
                    bottomNavFadeOverlay.setBackgroundResource(R.drawable.bg_bottom_nav_fade_grey)
                }
            }
            TAB_LIBRARY -> {
                setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
                if (::bottomNavFadeOverlay.isInitialized) {
                    bottomNavFadeOverlay.setBackgroundResource(R.drawable.bg_bottom_nav_fade_grey)
                }
            }
            else -> {
                setTopBarAppearance(Color.parseColor("#FEFEFE"), true, fullBleed = false)
                if (::bottomNavFadeOverlay.isInitialized) {
                    bottomNavFadeOverlay.setBackgroundResource(R.drawable.bg_bottom_nav_fade_white)
                }
            }
        }
    }

    private fun animateIcon(index: Int, imageView: ImageView) {
        imageView.animate().cancel()
        imageView.rotation = 0f
        imageView.translationY = 0f
        imageView.scaleX = 1f
        imageView.scaleY = 1f

        // Smooth vertical jump-and-settle animation (lifts by -14px and pops scale, then settles)
        imageView.animate()
            .translationY(-14f)
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(160)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                imageView.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                    .start()
            }
            .start()
    }

    private fun updateTabUi(index: Int) {
        val activeColor = Color.parseColor("#1BCA0B")
        val inactiveColor = Color.parseColor("#D0D5DD")

        tabs.forEachIndexed { i, config ->
            val isActive = i == index
            config.tab.background = null
            config.icon.setImageResource(
                if (isActive) config.activeIconRes else config.inactiveIconRes
            )
            val tint = if (isActive) activeColor else inactiveColor
            config.icon.imageTintList = ColorStateList.valueOf(tint)
            config.icon.alpha = 1f
            config.indicator.visibility = View.GONE
            config.text.setTextColor(tint)
            
            if (isActive) {
                animateIcon(i, config.icon)
                try {
                    config.tab.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                } catch (e: Exception) {
                    // Fail-safe
                }
            }
        }
    }

    private fun createRootFragment(index: Int): Fragment = when (index) {
        TAB_HOME -> HomeFragment()
        TAB_HR -> HrDashboardFragment()
        TAB_CHAT -> ChatListFragment()
        TAB_LIBRARY -> AppLibraryFragment()
        else -> HomeFragment()
    }

    private fun tabTag(index: Int): String = when (index) {
        TAB_HOME -> TAG_HOME
        TAB_HR -> TAG_HR
        TAB_CHAT -> TAG_CHAT
        TAB_LIBRARY -> TAG_LIBRARY
        else -> TAG_HOME
    }

    private fun rootTabTags(): List<String> = listOf(TAG_HOME, TAG_HR, TAG_CHAT, TAG_LIBRARY)

    private fun normalizeTab(index: Int): Int = when (index) {
        TAB_HOME, TAB_HR, TAB_CHAT, TAB_LIBRARY -> index
        else -> TAB_HOME
    }

    /**
     * True when the logged-in principal is an external fleet agency, as
     * surfaced by the auth response (`designation = "External Fleet"`). These
     * users get the Admin Fleet single-screen portal instead of the normal
     * staff tab shell.
     */
    private fun isExternalFleetPrincipal(): Boolean =
        session.designation?.trim()?.equals("External Fleet", ignoreCase = true) == true

    private suspend fun refreshSessionContext() {
        runCatching {
            api.getMyIamPermissions(session.bearerToken)
        }.onSuccess { iam ->
            val fresh = iam.permissions.toSet()
            session.iamPermissions = fresh
            session.isAdmin = iam.isAdmin
            // Wake any IAM-gated subscriber that mounted AFTER this
            // initial fetch (e.g. AppLibraryFragment opened seconds
            // later). Without this, the bus's first emit only fires
            // on the next throttled poll, which can be 30s+ away.
            com.manjugroups.m_connect.auth.IamUpdateBus.notifyFetched(fresh)
        }

        runCatching {
            PushTokenManager.syncCurrentToken(this@MainActivity, session)
        }

        runCatching {
            syncTrackingBootstrap()
        }
    }

    private suspend fun syncTrackingBootstrap() {
        GeoTrackBootstrapSync.sync(this, allowPromptConsent = true, api = geoApi)
    }

    private fun applyTrackingBootstrap(bootstrap: TrackingBootstrapData?, attendanceActive: Boolean) {
        GeoTrackBootstrapSync.apply(this, bootstrap, allowPromptConsent = attendanceActive && !isFinishing)

        // First-time users only learn they're tracked after the bootstrap
        // flips geoTrackingEnabled on. Re-evaluate the gate here so the
        // dialog appears immediately on the first login, not only after
        // the next foreground cycle.
        maybeShowBackgroundPermissionsGate()
    }

    private fun handleWorkflowNotificationIntent(sourceIntent: Intent?) {
        val route = WorkflowNotificationRoute.fromIntent(sourceIntent) ?: return

        when (route.targetTab) {
            WorkflowNotificationRoute.TAB_HR -> {
                selectTab(TAB_HR)
                val fragment = when (route.targetScreen) {
                    WorkflowNotificationRoute.SCREEN_LEAVES -> LeavesFragment.newInstance(
                        mode = route.targetMode,
                        entityId = route.entityId
                    )
                    WorkflowNotificationRoute.SCREEN_PERMISSIONS -> PermissionsFragment.newInstance(
                        mode = route.targetMode,
                        entityId = route.entityId
                    )
                    else -> null
                }

                fragment?.let {
                    // Notification intents can arrive after onSaveInstanceState;
                    // a plain commit() would throw IllegalStateException.
                    supportFragmentManager.beginTransaction()
                        .applySmoothTransitions()
                        .replace(R.id.fragmentContainer, it)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
            }
            WorkflowNotificationRoute.TAB_CHAT -> {
                selectTab(TAB_CHAT)
                val fragment = when (route.targetScreen) {
                    WorkflowNotificationRoute.SCREEN_CHAT_CHANNEL -> {
                        route.channelId?.let { channelId ->
                            ChatMessagesFragment.forChannel(
                                id = channelId,
                                name = route.targetTitle ?: "Channel"
                            )
                        }
                    }
                    WorkflowNotificationRoute.SCREEN_CHAT_CONVERSATION -> {
                        route.conversationId?.let { conversationId ->
                            ChatMessagesFragment.forConversation(
                                id = conversationId,
                                name = route.targetTitle ?: "Chat"
                            )
                        }
                    }
                    else -> null
                }

                fragment?.let {
                    supportFragmentManager.beginTransaction()
                        .applySmoothTransitions()
                        .replace(R.id.fragmentContainer, it)
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
            }
            else -> selectTab(TAB_HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWorkflowNotificationIntent(intent)
        handleTasksNotificationIntent(intent)
        handleTrackingNotificationIntent(intent)
    }

    /**
     * Taps on the GeoTrack notifications land where the staff can ACT:
     *  - permission alert → the background-permissions gate sheet (or the
     *    app's system settings page when the gate has nothing left to fix —
     *    e.g. only Physical activity is missing, which the gate doesn't cover)
     *  - tracking notification during a field activity → On Duty ends on the
     *    HR dashboard; CP / SV / Fleet trips end from Home's today-trips list.
     */
    private fun handleTrackingNotificationIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(
                com.manjugroups.m_connect.notifications.PermissionAlertNotification.EXTRA_FIX_PERMISSIONS,
                false,
            )
        ) {
            intent.removeExtra(
                com.manjugroups.m_connect.notifications.PermissionAlertNotification.EXTRA_FIX_PERMISSIONS,
            )
            if (!com.manjugroups.m_connect.geotrack.BackgroundPermissionsGateDialog.allGranted(this)) {
                com.manjugroups.m_connect.geotrack.BackgroundPermissionsGateDialog
                    .showIfNeeded(supportFragmentManager, this)
            } else {
                runCatching {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", packageName, null),
                        ),
                    )
                }
            }
            return
        }
        when (intent.getStringExtra(
            com.manjugroups.m_connect.geotrack.service.TrackingNotification.EXTRA_OPEN_ACTIVITY_KIND,
        )) {
            "onduty" -> selectTab(TAB_HR)
            "cp", "sv", "fleet" -> selectTab(TAB_HOME)
        }
    }

    /** Tap on the pending-tasks notification → route the newest task. */
    private fun handleTasksNotificationIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(
                com.manjugroups.m_connect.notifications.TasksNotification.EXTRA_OPEN_TASKS,
                false,
            ) == true
        ) {
            openTasksOnNextRefresh = true
            refreshTasksBanner()
        }
    }

    private fun expandCarousel(initialPageType: CardType) {
        if (!::expandedCarouselLayout.isInitialized) return
        peekingPillsLayout.visibility = View.GONE
        expandedCarouselLayout.visibility = View.VISIBLE

        val index = pendingCardItems.indexOfFirst { it.type == initialPageType }
        if (index >= 0 && index < pendingCardItems.size) {
            vpPendingCards.setCurrentItem(index, false)
        } else if (pendingCardItems.isNotEmpty()) {
            vpPendingCards.setCurrentItem(0, false)
        }

        expandedCarouselLayout.translationY = 150f
        expandedCarouselLayout.scaleX = 0.9f
        expandedCarouselLayout.scaleY = 0.9f
        expandedCarouselLayout.alpha = 0f

        expandedCarouselLayout.animate()
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()

        updateDots(true, if (index >= 0) index else 0)
    }

    private fun collapseCarousel() {
        if (!::expandedCarouselLayout.isInitialized || expandedCarouselLayout.visibility != View.VISIBLE) return

        expandedCarouselLayout.animate()
            .translationY(150f)
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                if (isFinishing || isDestroyed) return@withEndAction
                expandedCarouselLayout.visibility = View.GONE
                if (pendingCardItems.isNotEmpty() && currentTab == TAB_HOME
                    && ::peekingPillsLayout.isInitialized) {
                    peekingPillsLayout.visibility = View.VISIBLE
                    peekingPillsLayout.translationY = -60f
                    peekingPillsLayout.alpha = 0f
                    peekingPillsLayout.animate()
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(300)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
            .start()
    }

    private fun updateDots(animated: Boolean, selectedIndex: Int) {
        if (!::carouselIndicators.isInitialized) return
        carouselIndicators.removeAllViews()
        val count = pendingCardItems.size
        if (count <= 1) return

        for (i in 0 until count) {
            val dot = View(this)
            val size = (8 * resources.displayMetrics.density).toInt()
            val params = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = (4 * resources.displayMetrics.density).toInt()
                rightMargin = (4 * resources.displayMetrics.density).toInt()
            }
            dot.layoutParams = params
            if (i == selectedIndex) {
                val activeBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#7F56D9"))
                }
                dot.background = activeBg
            } else {
                val inactiveBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#D0D5DD"))
                }
                dot.background = inactiveBg
            }
            carouselIndicators.addView(dot)
        }
    }

    private inner class PendingCardsAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PendingCardsAdapter.CardViewHolder>() {
        private var items: List<PendingCardItem> = emptyList()

        fun submitList(newItems: List<PendingCardItem>) {
            items = newItems.toList()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pending_carousel_card, parent, false)
            return CardViewHolder(view)
        }

        override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class CardViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            private val cardContainer: View = view.findViewById(R.id.cardContainer)
            private val ivHeaderIcon: ImageView = view.findViewById(R.id.ivHeaderIcon)
            private val tvHeaderType: TextView = view.findViewById(R.id.tvHeaderType)
            private val tvCardTitle: TextView = view.findViewById(R.id.tvCardTitle)
            private val tvCardSubtitle: TextView = view.findViewById(R.id.tvCardSubtitle)
            private val tvTimeInfo: TextView = view.findViewById(R.id.tvTimeInfo)
            private val tvDistanceInfo: TextView = view.findViewById(R.id.tvDistanceInfo)
            private val layoutDistanceInfo: View = view.findViewById(R.id.layoutDistanceInfo)
            private val ivCardIllustration: ImageView = view.findViewById(R.id.ivCardIllustration)

            fun bind(item: PendingCardItem) {
                tvCardTitle.text = item.title
                tvCardSubtitle.text = item.subtitle
                tvTimeInfo.text = item.timeLabel

                if (item.distanceLabel != null) {
                    layoutDistanceInfo.visibility = View.VISIBLE
                    tvDistanceInfo.text = item.distanceLabel
                } else {
                    layoutDistanceInfo.visibility = View.GONE
                }

                when (item.type) {
                    CardType.TASK -> {
                        cardContainer.setBackgroundResource(R.drawable.bg_carousel_card_task)
                        ivHeaderIcon.setImageResource(R.drawable.ic_nav_attendance)
                        ivHeaderIcon.setColorFilter(Color.parseColor("#7F56D9"))
                        tvHeaderType.text = "Task Pending"
                        tvHeaderType.setTextColor(Color.parseColor("#7F56D9"))
                        ivCardIllustration.setImageResource(R.drawable.ic_ill_task)

                        itemView.setOnClickListener {
                            openTaskManager()
                        }
                    }
                    CardType.VISIT -> {
                        cardContainer.setBackgroundResource(R.drawable.bg_carousel_card_visit)
                        ivHeaderIcon.setImageResource(R.drawable.ic_map_expand)
                        ivHeaderIcon.setColorFilter(Color.parseColor("#D97706"))
                        tvHeaderType.text = "Visit Pending"
                        tvHeaderType.setTextColor(Color.parseColor("#D97706"))
                        ivCardIllustration.setImageResource(R.drawable.ic_ill_visit)

                        itemView.setOnClickListener {
                            collapseCarousel()
                        }
                    }
                    CardType.FOLLOW_UP -> {
                        cardContainer.setBackgroundResource(R.drawable.bg_carousel_card_followup)
                        ivHeaderIcon.setImageResource(R.drawable.ic_nav_chat)
                        ivHeaderIcon.setColorFilter(Color.parseColor("#0D9488"))
                        tvHeaderType.text = "Follow-up Pending"
                        tvHeaderType.setTextColor(Color.parseColor("#0D9488"))
                        ivCardIllustration.setImageResource(R.drawable.ic_ill_followup)

                        itemView.setOnClickListener {
                            collapseCarousel()
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab)
    }
}

enum class CardType {
    TASK, VISIT, FOLLOW_UP
}

data class PendingCardItem(
    val id: String,
    val type: CardType,
    val title: String,
    val subtitle: String,
    val timeLabel: String,
    val distanceLabel: String? = null
)
