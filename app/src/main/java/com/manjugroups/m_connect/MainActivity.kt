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

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_HR = 1
        const val TAB_CHAT = 2
        const val TAB_LIBRARY = 3

        // Cap on task-nudge carousel cards so the page dots stay readable.
        private const val MAX_NUDGE_CARDS = 5

        // After a dismissal, the task cards auto-reopen this much later.
        private const val NUDGE_REOPEN_MS = 15 * 60 * 1000L

        // Auto-carousel: glide to the next card this often while the
        // overlay is open and untouched.
        private const val NUDGE_AUTO_ADVANCE_MS = 4000L

        // Minimum gap between full task-queue downloads for the nudge banner;
        // backstack churn inside this window reuses the last result.
        private const val TASKS_BANNER_TTL_MS = 15_000L

        private const val KEY_CURRENT_TAB = "current_tab"
        private const val KEY_NUDGE_DISMISSED_AT = "task_nudge_dismissed_at"
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
    // Modal task-nudge overlay: dim/blur backdrop + swipeable card carousel.
    private lateinit var taskNudgeOverlay: FrameLayout
    private lateinit var taskNudgePager: androidx.recyclerview.widget.RecyclerView
    private val taskNudgeAdapter = TaskNudgeAdapter(this)
    // Collapsed nudge tab tucked behind the nav pill — reopens the overlay.
    private lateinit var navTasksPeek: android.view.View
    private lateinit var tvNavTasksPeek: android.widget.TextView
    // Auto-carousel plumbing: snap helper locates the settled page, the
    // runnable glides to the next one, and touch pauses the rotation.
    private val taskNudgeSnapHelper = androidx.recyclerview.widget.PagerSnapHelper()
    private var taskNudgeAutoAdvance: Runnable? = null
    // Backdrop tap / back press closes the nudge; it auto-reopens after
    // NUDGE_REOPEN_MS (elapsedRealtime so clock changes can't skew it).
    private var taskNudgeDismissedAt = 0L
    private var taskNudgeReopenJob: kotlinx.coroutines.Job? = null
    // Last open-task count, so hide() knows whether to show the peek tab.
    private var taskNudgePendingCount = 0
    // "Today" (yyyy-MM-dd) captured on the last task refresh — used by the
    // card binder to colour overdue/today deadlines.
    private var taskNudgeToday: String = ""
    private val taskNudgeBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideTaskNudgeOverlay(markDismissed = true)
        }
    }
    // Newest open task — the LIFO "top of stack" the Complete chip routes to.
    private var topPendingTask: com.manjugroups.m_connect.network.DailyTaskData? = null
    // Last time refreshTasksBanner actually hit the network (throttle clock).
    private var lastTasksBannerFetchAt = 0L
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

        // Pending-tasks nudge docked on the nav — count of incomplete My
        // Tasks; the Complete chip routes the newest task to where it's DONE
        // (its mobile screen, or a "use the web app" prompt for web-only tasks).
        taskNudgeOverlay = findViewById(R.id.taskNudgeOverlay)
        taskNudgePager = findViewById(R.id.taskNudgePager)
        taskNudgePager.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this, androidx.recyclerview.widget.RecyclerView.HORIZONTAL, false
        )
        // One card per swipe, mockup-style.
        taskNudgeSnapHelper.attachToRecyclerView(taskNudgePager)
        taskNudgePager.adapter = taskNudgeAdapter
        // Auto-carousel: pause the rotation the moment the user grabs the
        // cards; once any scroll (theirs or ours) settles, arm the next hop.
        taskNudgePager.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(
                rv: androidx.recyclerview.widget.RecyclerView,
                newState: Int,
            ) {
                when (newState) {
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING ->
                        cancelTaskNudgeAutoAdvance()
                    androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE ->
                        scheduleTaskNudgeAutoAdvance()
                }
            }
        })
        // Tapping the dimmed backdrop (or pressing back) closes the nudge;
        // the cards themselves consume their own taps.
        findViewById<android.view.View>(R.id.taskNudgeScrim).setOnClickListener {
            hideTaskNudgeOverlay(markDismissed = true)
        }
        onBackPressedDispatcher.addCallback(this, taskNudgeBackCallback)
        // Collapsed nudge tab behind the nav pill — reopens the cards.
        navTasksPeek = findViewById(R.id.navTasksPeek)
        tvNavTasksPeek = findViewById(R.id.tvNavTasksPeek)

        // Premium breathing/pulsing animation on the red border to draw user attention to pending tasks
        (navTasksPeek.background as? android.graphics.drawable.GradientDrawable)?.let { bg ->
            val strokeAnim = android.animation.ValueAnimator.ofObject(
                android.animation.ArgbEvaluator(),
                Color.parseColor("#FECDCA"), // Soft warning red
                Color.parseColor("#D92D20")  // Urgent alert red
            ).apply {
                duration = 1400 // 1.4 seconds per pulse wave
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { animator ->
                    val color = animator.animatedValue as Int
                    val fraction = animator.animatedFraction
                    // Breathe stroke thickness from 1.0dp to 2.2dp dynamically to keep it subtle yet notice-worthy
                    val width = (1.0f + fraction * 1.2f) * resources.displayMetrics.density
                    bg.setStroke(width.toInt(), color)
                }
            }
            // Start the infinite breathing animation
            strokeAnim.start()
        }

        // Custom touch listener that supports both tap and swipe up to expand cards smoothly
        val density = resources.displayMetrics.density
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var startY = 0f
        var startX = 0f
        var isDragging = false
        val maxDragDistance = 250f * density // Total vertical drag distance to open
        val initialPagerY = 250f * density // Pager initial downward translation when starting drag

        navTasksPeek.setOnTouchListener(object : android.view.View.OnTouchListener {
            @android.annotation.SuppressLint("ClickableViewAccessibility")
            override fun onTouch(v: android.view.View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        startX = event.rawX
                        isDragging = false
                        taskNudgeOverlay.animate().cancel()
                        taskNudgePager.animate().cancel()
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        val dx = event.rawX - startX
                        if (!isDragging && dy < -touchSlop && Math.abs(dy) > Math.abs(dx)) {
                            isDragging = true
                            taskNudgeBackCallback.isEnabled = true
                            if (taskNudgeOverlay.visibility != android.view.View.VISIBLE) {
                                taskNudgeOverlay.visibility = android.view.View.VISIBLE
                                taskNudgeOverlay.alpha = 0f
                                taskNudgePager.scrollToPosition(taskNudgeAdapter.startPosition())
                                taskNudgePager.translationY = initialPagerY
                                taskNudgePager.scaleX = 0.94f
                                taskNudgePager.scaleY = 0.94f
                                taskNudgePager.alpha = 0f
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val blur = android.graphics.RenderEffect.createBlurEffect(
                                    22f, 22f, android.graphics.Shader.TileMode.CLAMP
                                )
                                fragmentContainer.setRenderEffect(blur)
                                tabBarContainer.setRenderEffect(blur)
                                if (::bottomNavFadeOverlay.isInitialized) bottomNavFadeOverlay.setRenderEffect(blur)
                            }
                            navTasksPeek.visibility = android.view.View.GONE
                        }
                        if (isDragging) {
                            val progress = Math.min(1.0f, Math.max(0f, -dy / maxDragDistance))
                            taskNudgeOverlay.alpha = progress
                            taskNudgePager.alpha = progress
                            taskNudgePager.scaleX = 0.94f + (0.06f * progress)
                            taskNudgePager.scaleY = 0.94f + (0.06f * progress)
                            taskNudgePager.translationY = initialPagerY * (1.0f - progress)
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val dy = event.rawY - startY
                        if (isDragging) {
                            if (-dy > maxDragDistance * 0.3f) {
                                // Snap fully open with overshoot
                                taskNudgeOverlay.animate().alpha(1f).setDuration(250).start()
                                taskNudgePager.animate()
                                    .translationY(0f)
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(350)
                                    .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                                    .withEndAction {
                                        scheduleTaskNudgeAutoAdvance()
                                        fragmentContainer.importantForAccessibility =
                                            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                        tabBarContainer.importantForAccessibility =
                                            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                        ViewCompat.setAccessibilityPaneTitle(taskNudgeOverlay, "Pending tasks")
                                    }
                                    .start()
                            } else {
                                // Animate back to hidden
                                taskNudgeOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                                    taskNudgeOverlay.visibility = android.view.View.GONE
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        fragmentContainer.setRenderEffect(null)
                                        tabBarContainer.setRenderEffect(null)
                                        if (::bottomNavFadeOverlay.isInitialized) bottomNavFadeOverlay.setRenderEffect(null)
                                    }
                                    updateNavTasksPeekVisibility()
                                    taskNudgeBackCallback.isEnabled = false
                                }.start()
                                taskNudgePager.animate()
                                    .translationY(initialPagerY)
                                    .alpha(0f)
                                    .scaleX(0.94f)
                                    .scaleY(0.94f)
                                    .setDuration(200)
                                    .start()
                            }
                        } else {
                            // Simple click/tap action
                            showTaskNudgeOverlay()
                        }
                        return true
                    }
                }
                return false
            }
        })
        // Tasks complete server-side when their underlying work is done
        // (attendance reviewed, CP/SV visited, ...), so re-check the count on
        // every navigation — the badge disappears as soon as the stack clears.
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
        // Keep the nudge's dismissal cool-off across recreation (rotation,
        // dark-mode toggle, process death) so the modal doesn't instantly
        // reappear after being dismissed. elapsedRealtime resets on reboot —
        // a restored stamp from a previous boot would be in the "future", so
        // treat that as never-dismissed.
        taskNudgeDismissedAt = savedInstanceState?.getLong(KEY_NUDGE_DISMISSED_AT, 0L) ?: 0L
        if (taskNudgeDismissedAt > android.os.SystemClock.elapsedRealtime()) {
            taskNudgeDismissedAt = 0L
        }

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
        supportFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(R.id.fragmentContainer, com.manjugroups.m_connect.ui.tasks.TaskManagerFragment())
            .addToBackStack(null)
            .commit()
    }

    fun refreshTasksBanner(force: Boolean = false) {
        if (!session.isLoggedIn) {
            com.manjugroups.m_connect.notifications.TasksNotification.clear(this)
            return
        }
        // This fires on every backstack change AND activity resume, each call
        // re-downloading the full task queue (hundreds of rows for admins) in
        // parallel with any fetch the Task Manager screen itself is doing.
        // Throttle background refreshes; task-completion paths pass force=true
        // and a pending notification-tap route must always resolve.
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && !openTasksOnNextRefresh &&
            now - lastTasksBannerFetchAt < TASKS_BANNER_TTL_MS
        ) {
            // Even when the fetch is throttled, navigation still needs the
            // collapsed tab rescoped — it must vanish off the Home root.
            updateNavTasksPeekVisibility()
            return
        }
        lastTasksBannerFetchAt = now
        lifecycleScope.launch {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            val open = runCatching {
                api.getTaskManagerTasks(session.bearerToken, today)
                    .tasks.filter { it.status == "pending" || it.status == "in-progress" }
            }.getOrNull() ?: return@launch // network blip — keep current state
            if (isFinishing || isDestroyed) return@launch

            // LIFO — newest open task is the top of the stack the chip routes to.
            topPendingTask = open.maxByOrNull { it.creationTime ?: 0.0 }
            val pending = open.size
            // Tasks whose deadline is today or already past — the ones to nudge.
            val dueSoon = open.count { t ->
                val d = t.deadline?.trim().orEmpty()
                d.isNotEmpty() && d <= today
            }

            // System-pane notification — the companion to this banner. Shows
            // the same count and clears itself when nothing is pending.
            com.manjugroups.m_connect.notifications.TasksNotification.update(
                this@MainActivity,
                pending,
                dueSoon,
                topPendingTask?.let { it.title ?: it.taskName },
            )

            // A notification tap (EXTRA_OPEN_TASKS) opens the Task Manager once
            // this refresh confirms there's still something pending. Skip the
            // overlay logic for this pass: the fragment commit is async, so
            // backStackEntryCount is still 0 here and the show-gate below
            // would draw the modal on top of the Task Manager.
            if (openTasksOnNextRefresh) {
                openTasksOnNextRefresh = false
                if (pending > 0) {
                    hideTaskNudgeOverlay(markDismissed = false)
                    openTaskManager()
                    return@launch
                }
            }

            if (pending > 0) {
                taskNudgeToday = today
                taskNudgePendingCount = pending
                // Newest-first carousel, capped so the page dots stay sane.
                val cards = open
                    .sortedByDescending { it.creationTime ?: 0.0 }
                    .take(MAX_NUDGE_CARDS)
                taskNudgeAdapter.submit(cards, pending, dueSoon)
                // Collapsed tab mirrors the live count while the overlay is
                // closed, so pending work stays visible on every root tab.
                tvNavTasksPeek.text = when {
                    dueSoon > 0 -> "$pending pending · $dueSoon due"
                    else -> "$pending pending"
                }
                updateNavTasksPeekVisibility()
                val onHomeRoot =
                    currentTab == TAB_HOME && supportFragmentManager.backStackEntryCount == 0
                val reopenDue = taskNudgeDismissedAt == 0L ||
                    android.os.SystemClock.elapsedRealtime() - taskNudgeDismissedAt >= NUDGE_REOPEN_MS
                when {
                    onHomeRoot && reopenDue -> showTaskNudgeOverlay()
                    supportFragmentManager.backStackEntryCount > 0 ->
                        hideTaskNudgeOverlay(markDismissed = false)
                }
            } else {
                taskNudgePendingCount = 0
                updateNavTasksPeekVisibility()
                hideTaskNudgeOverlay(markDismissed = false)
            }
        }
    }

    /** The collapsed pending-tasks tab lives on every ROOT tab (Home,
     *  Attendance, Chat, Apps) but never on pushed screens — a chat thread
     *  or a form must stay clean. */
    private fun updateNavTasksPeekVisibility() {
        if (!::navTasksPeek.isInitialized) return
        val show = taskNudgePendingCount > 0 &&
            taskNudgeOverlay.visibility != android.view.View.VISIBLE &&
            supportFragmentManager.backStackEntryCount == 0 &&
            isBottomNavVisible
        navTasksPeek.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showTaskNudgeOverlay() {
        taskNudgeBackCallback.isEnabled = true
        // Cancel any in-flight hide animation FIRST — ViewPropertyAnimator
        // skips withEndAction on cancel, so a pending "set GONE + clear blur"
        // can't land after this show.
        taskNudgeOverlay.animate().cancel()
        // Blur the page + nav behind the scrim on Android 12+; older
        // devices just get the dim. Idempotent, so safe on re-show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = {
                android.graphics.RenderEffect.createBlurEffect(
                    22f, 22f, android.graphics.Shader.TileMode.CLAMP
                )
            }
            fragmentContainer.setRenderEffect(blur())
            tabBarContainer.setRenderEffect(blur())
            if (::bottomNavFadeOverlay.isInitialized) bottomNavFadeOverlay.setRenderEffect(blur())
        }
        if (taskNudgeOverlay.visibility != android.view.View.VISIBLE) {
            taskNudgeOverlay.alpha = 0f
            taskNudgeOverlay.visibility = android.view.View.VISIBLE
            // Open on the newest task, parked mid-way through the wrapped
            // positions so cards peek in from BOTH sides and the user can
            // swipe either direction immediately.
            taskNudgePager.scrollToPosition(taskNudgeAdapter.startPosition())
            // Entrance: the cards glide up with an ease-out settle (fast
            // start, feather-soft landing) while the scrim fades in.
            taskNudgePager.translationY = 64f * resources.displayMetrics.density
            taskNudgePager.alpha = 0f
            taskNudgePager.scaleX = 0.94f
            taskNudgePager.scaleY = 0.94f
            taskNudgePager.animate()
                .translationY(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(450)
                .setInterpolator(android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f))
                .start()
        }
        taskNudgeOverlay.animate().alpha(1f).setDuration(240).start()
        // Kick off the auto-carousel once the cards are up.
        scheduleTaskNudgeAutoAdvance()
        // The collapsed tab is redundant while the cards are up.
        navTasksPeek.visibility = android.view.View.GONE
        // Modal for TalkBack too: the page + nav behind the scrim must not
        // stay reachable while the cards are up.
        fragmentContainer.importantForAccessibility =
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        tabBarContainer.importantForAccessibility =
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ViewCompat.setAccessibilityPaneTitle(taskNudgeOverlay, "Pending tasks")
    }

    /** Glide the carousel to the next card after a short dwell. */
    private fun scheduleTaskNudgeAutoAdvance() {
        cancelTaskNudgeAutoAdvance()
        if (taskNudgeOverlay.visibility != android.view.View.VISIBLE) return
        if (taskNudgeAdapter.itemCount <= 1) return
        val r = Runnable {
            taskNudgeAutoAdvance = null
            if (taskNudgeOverlay.visibility != android.view.View.VISIBLE) return@Runnable
            val lm = taskNudgePager.layoutManager
                as? androidx.recyclerview.widget.LinearLayoutManager ?: return@Runnable
            val snapped = taskNudgeSnapHelper.findSnapView(lm)
                ?.let { lm.getPosition(it) } ?: return@Runnable
            // Slower-than-default glide so the hop feels silky rather than
            // snappy; the wrapped adapter makes "next" endless.
            val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(this) {
                override fun calculateSpeedPerPixel(
                    displayMetrics: android.util.DisplayMetrics,
                ): Float = 55f / displayMetrics.densityDpi
            }
            scroller.targetPosition = snapped + 1
            lm.startSmoothScroll(scroller)
            // The IDLE callback re-arms the next hop once this one settles.
        }
        taskNudgeAutoAdvance = r
        taskNudgePager.postDelayed(r, NUDGE_AUTO_ADVANCE_MS)
    }

    private fun cancelTaskNudgeAutoAdvance() {
        taskNudgeAutoAdvance?.let(taskNudgePager::removeCallbacks)
        taskNudgeAutoAdvance = null
    }

    private fun hideTaskNudgeOverlay(markDismissed: Boolean) {
        cancelTaskNudgeAutoAdvance()
        if (markDismissed) {
            taskNudgeDismissedAt = android.os.SystemClock.elapsedRealtime()
            // Auto-reopen after the cool-off. refreshTasksBanner re-checks
            // every gate (tasks still pending, on Home root) at fire time.
            taskNudgeReopenJob?.cancel()
            taskNudgeReopenJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(NUDGE_REOPEN_MS)
                refreshTasksBanner()
            }
        }
        taskNudgeBackCallback.isEnabled = false
        // Fall back to the collapsed tab so pending work stays discoverable
        // (Home root only).
        updateNavTasksPeekVisibility()
        if (taskNudgeOverlay.visibility != android.view.View.VISIBLE) return
        // Restore accessibility reach immediately (not in the end action —
        // a cancelled animation would skip it).
        fragmentContainer.importantForAccessibility =
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        tabBarContainer.importantForAccessibility =
            android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        taskNudgeOverlay.animate().cancel()
        taskNudgeOverlay.animate().alpha(0f).setDuration(150).withEndAction {
            taskNudgeOverlay.visibility = android.view.View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                fragmentContainer.setRenderEffect(null)
                tabBarContainer.setRenderEffect(null)
                if (::bottomNavFadeOverlay.isInitialized) bottomNavFadeOverlay.setRenderEffect(null)
            }
        }.start()
    }

    /** Fill one task-nudge carousel card with a task's details. */
    private fun bindNudgeCard(
        h: TaskNudgeHolder,
        t: com.manjugroups.m_connect.network.DailyTaskData,
        position: Int,
        count: Int,
        pending: Int,
        dueSoon: Int,
    ) {
        h.count.text = when {
            dueSoon > 0 -> "$pending pending · $dueSoon due"
            else -> "$pending pending"
        }
        h.title.text = (t.title ?: t.taskName ?: t.label ?: "Pending task").trim()

        // ── Per-card color theme ──────────────────────────────────────────
        // Cycle through 4 premium palettes so each card in the carousel
        // is visually distinct when swiping.
        data class NudgeTheme(
            val accentDark: Int,    // header label, icon tint, View Details text
            val accentLight: Int,   // icon chip bg, status pill bg
            val statusInProgressBg: Int,
            val statusInProgressText: Int,
            val statusPendingBg: Int,
            val statusPendingText: Int,
            val ctaRes: Int,        // CTA button drawable
            val glowRes: Int,       // bottom glow drawable
            val dotColor: Int,      // active page dot
        )

        val themes = arrayOf(
            // 0 — Purple / Indigo
            NudgeTheme(
                accentDark = Color.parseColor("#6941C6"),
                accentLight = Color.parseColor("#F4EBFF"),
                statusInProgressBg = Color.parseColor("#F4EBFF"),
                statusInProgressText = Color.parseColor("#6941C6"),
                statusPendingBg = Color.parseColor("#FFFAEB"),
                statusPendingText = Color.parseColor("#B54708"),
                ctaRes = R.drawable.bg_task_nudge_cta_purple,
                glowRes = R.drawable.bg_task_nudge_bottom_glow_purple,
                dotColor = Color.parseColor("#7F56D9"),
            ),
            // 1 — Amber / Orange
            NudgeTheme(
                accentDark = Color.parseColor("#B54708"),
                accentLight = Color.parseColor("#FFF6ED"),
                statusInProgressBg = Color.parseColor("#FFF6ED"),
                statusInProgressText = Color.parseColor("#B54708"),
                statusPendingBg = Color.parseColor("#FEF3F2"),
                statusPendingText = Color.parseColor("#B42318"),
                ctaRes = R.drawable.bg_task_nudge_cta_amber,
                glowRes = R.drawable.bg_task_nudge_bottom_glow_amber,
                dotColor = Color.parseColor("#D97706"),
            ),
            // 2 — Ocean Blue
            NudgeTheme(
                accentDark = Color.parseColor("#1D4ED8"),
                accentLight = Color.parseColor("#EFF6FF"),
                statusInProgressBg = Color.parseColor("#EFF6FF"),
                statusInProgressText = Color.parseColor("#1D4ED8"),
                statusPendingBg = Color.parseColor("#FFF7ED"),
                statusPendingText = Color.parseColor("#C2410C"),
                ctaRes = R.drawable.bg_task_nudge_cta_blue,
                glowRes = R.drawable.bg_task_nudge_bottom_glow_blue,
                dotColor = Color.parseColor("#2563EB"),
            ),
            // 3 — Emerald / Teal
            NudgeTheme(
                accentDark = Color.parseColor("#047857"),
                accentLight = Color.parseColor("#ECFDF5"),
                statusInProgressBg = Color.parseColor("#ECFDF5"),
                statusInProgressText = Color.parseColor("#047857"),
                statusPendingBg = Color.parseColor("#FFFBEB"),
                statusPendingText = Color.parseColor("#A16207"),
                ctaRes = R.drawable.bg_task_nudge_cta_emerald,
                glowRes = R.drawable.bg_task_nudge_bottom_glow_emerald,
                dotColor = Color.parseColor("#059669"),
            ),
        )

        val theme = themes[position % themes.size]

        // Apply accent color to header
        h.tvHeaderLabel.setTextColor(theme.accentDark)
        h.ivHeaderIcon.setColorFilter(theme.accentDark)
        h.chipHeaderIcon.backgroundTintList =
            android.content.res.ColorStateList.valueOf(theme.accentLight)

        // Apply to due-date row icons
        h.ivDueIcon.setColorFilter(theme.accentDark)
        h.chipDueIcon.backgroundTintList =
            android.content.res.ColorStateList.valueOf(theme.accentLight)

        // Apply to description row icons
        h.ivDescIcon.setColorFilter(theme.accentDark)
        h.chipDescIcon.backgroundTintList =
            android.content.res.ColorStateList.valueOf(theme.accentLight)

        // View Details pill
        (h.details as? TextView)?.setTextColor(theme.accentDark)
        h.details.backgroundTintList =
            android.content.res.ColorStateList.valueOf(theme.accentLight)

        // CTA button and bottom glow
        h.complete.setBackgroundResource(theme.ctaRes)
        h.bottomGlow.setBackgroundResource(theme.glowRes)

        // ── End color theme ───────────────────────────────────────────────

        // Category/module can arrive raw ("site_visits") — humanize it.
        val moduleLabel = (t.module ?: t.taskCategory)
            ?.takeIf { it.isNotBlank() }
            ?.split('_', '-', ' ')
            ?.filter { it.isNotBlank() }
            ?.joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
        val meta = listOfNotNull(
            moduleLabel,
            t.assignedByName?.takeIf { it.isNotBlank() }?.let { "By $it" },
        ).joinToString("  ·  ")
        h.meta.text = meta
        h.meta.visibility =
            if (meta.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        val inProgress = t.status?.equals("in-progress", ignoreCase = true) == true
        h.status.text = if (inProgress) "In Progress" else "Pending"
        h.status.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (inProgress) theme.statusInProgressBg else theme.statusPendingBg
        )
        h.status.setTextColor(
            if (inProgress) theme.statusInProgressText else theme.statusPendingText
        )

        // Deadline is yyyy-MM-dd; overdue/today render red so the urgency
        // gradient at the card base has a matching signal in the copy.
        val d = t.deadline?.trim().orEmpty()
        val pretty = runCatching {
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)!!
            )
        }.getOrDefault(d)
        when {
            d.isEmpty() -> {
                h.due.text = "No deadline"
                h.due.setTextColor(Color.parseColor("#667085"))
            }
            d < taskNudgeToday -> {
                h.due.text = "Overdue · $pretty"
                h.due.setTextColor(Color.parseColor("#D92D20"))
            }
            d == taskNudgeToday -> {
                h.due.text = "Due Today"
                h.due.setTextColor(Color.parseColor("#D92D20"))
            }
            else -> {
                h.due.text = pretty
                h.due.setTextColor(Color.parseColor("#101828"))
            }
        }

        val desc = t.description?.trim().orEmpty()
        h.desc.text = desc
        h.descRow.visibility =
            if (desc.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE

        // Card itself + View Details + Complete all close the nudge and open the Task
        // Manager (tasks complete server-side when their work is done).
        val openManager = android.view.View.OnClickListener {
            hideTaskNudgeOverlay(markDismissed = true)
            openTaskManager()
        }
        h.itemView.setOnClickListener(openManager)
        h.details.setOnClickListener(openManager)
        h.complete.setOnClickListener(openManager)

        // Page dots (bound per card so the incoming page is always correct).
        h.dots.removeAllViews()
        repeat(count) { i ->
            val active = i == position
            h.dots.addView(android.view.View(this).apply {
                setBackgroundResource(
                    if (active) R.drawable.bg_task_nudge_dot_on
                    else R.drawable.bg_task_nudge_dot_off
                )
                if (active) {
                    backgroundTintList =
                        android.content.res.ColorStateList.valueOf(theme.dotColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(if (active) 18 else 7), dpToPx(7)
                ).apply { marginStart = dpToPx(3); marginEnd = dpToPx(3) }
            })
        }
        h.dots.visibility =
            if (count > 1) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun dpToPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private class TaskNudgeHolder(v: android.view.View) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        val count: TextView = v.findViewById(R.id.tvNudgeCount)
        val title: TextView = v.findViewById(R.id.tvNudgeTitle)
        val meta: TextView = v.findViewById(R.id.tvNudgeMeta)
        val status: TextView = v.findViewById(R.id.tvNudgeStatus)
        val due: TextView = v.findViewById(R.id.tvNudgeDue)
        val descRow: android.view.View = v.findViewById(R.id.rowNudgeDesc)
        val desc: TextView = v.findViewById(R.id.tvNudgeDesc)
        val details: android.view.View = v.findViewById(R.id.btnNudgeDetails)
        val complete: android.view.View = v.findViewById(R.id.btnNudgeComplete)
        val dots: LinearLayout = v.findViewById(R.id.nudgeDots)
        // Tintable elements for per-card color theming
        val chipHeaderIcon: android.view.View = v.findViewById(R.id.chipHeaderIcon)
        val ivHeaderIcon: ImageView = v.findViewById(R.id.ivHeaderIcon)
        val tvHeaderLabel: TextView = v.findViewById(R.id.tvHeaderLabel)
        val chipDueIcon: android.view.View = v.findViewById(R.id.chipDueIcon)
        val ivDueIcon: ImageView = v.findViewById(R.id.ivDueIcon)
        val chipDescIcon: android.view.View = v.findViewById(R.id.chipDescIcon)
        val ivDescIcon: ImageView = v.findViewById(R.id.ivDescIcon)
        val bottomGlow: android.view.View = v.findViewById(R.id.bottomGlowContainer)
    }

    private class TaskNudgeAdapter(private val host: MainActivity) :
        androidx.recyclerview.widget.RecyclerView.Adapter<TaskNudgeHolder>() {

        companion object {
            // Wrap factor for the circular carousel: positions map onto the
            // real cards modulo items.size, so neighbours peek in from both
            // sides and the user can swipe either direction immediately.
            private const val WRAP_FACTOR = 400
        }

        private var items: List<com.manjugroups.m_connect.network.DailyTaskData> = emptyList()
        private var pendingCount = 0
        private var dueSoonCount = 0

        @Suppress("NotifyDataSetChanged")
        fun submit(
            list: List<com.manjugroups.m_connect.network.DailyTaskData>,
            pending: Int,
            dueSoon: Int,
        ) {
            items = list
            pendingCount = pending
            dueSoonCount = dueSoon
            notifyDataSetChanged()
        }

        /** Middle wrapped position that maps to the newest task (card 0). */
        fun startPosition(): Int {
            if (items.size <= 1) return 0
            val mid = itemCount / 2
            return mid - (mid % items.size)
        }

        override fun onCreateViewHolder(
            parent: android.view.ViewGroup,
            viewType: Int,
        ): TaskNudgeHolder = TaskNudgeHolder(
            android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_nudge_card, parent, false)
        )

        override fun getItemCount(): Int =
            if (items.size <= 1) items.size else items.size * WRAP_FACTOR

        override fun onBindViewHolder(holder: TaskNudgeHolder, position: Int) {
            val real = position % items.size
            host.bindNudgeCard(
                holder, items[real], real, items.size, pendingCount, dueSoonCount
            )
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
        val targetTag = tabTag(index)
        val existingTarget = supportFragmentManager.findFragmentByTag(targetTag)
        if (currentTab == index && existingTarget?.isVisible == true && supportFragmentManager.backStackEntryCount == 0) {
            return
        }

        // Leaving Home (e.g. a tracking-notification deep link straight to
        // HR) must drop the task-nudge modal synchronously — the backstack
        // listener doesn't fire on root tab switches and the async refresh
        // can fail, which would leave the blur + back-callback stuck over
        // the destination tab.
        if (::taskNudgeOverlay.isInitialized && index != TAB_HOME) {
            hideTaskNudgeOverlay(markDismissed = false)
        }

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        currentTab = index
        updateTabUi(index)
        applyTopBarForTab(index)
        setTabBarVisible(true)
        // Switching tabs while the task carousel is up counts as dismissing
        // it — otherwise its "visible" state keeps the collapsed tab hidden
        // on the new tab (and back on Home) until the next forced refresh.
        if (taskNudgeOverlay.visibility == android.view.View.VISIBLE) {
            hideTaskNudgeOverlay(markDismissed = true)
        }
        updateNavTasksPeekVisibility()

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab)
        outState.putLong(KEY_NUDGE_DISMISSED_AT, taskNudgeDismissedAt)
    }
}
