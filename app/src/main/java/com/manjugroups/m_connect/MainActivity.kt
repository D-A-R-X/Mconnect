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
import kotlinx.coroutines.launch

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

        if (savedInstanceState == null) {
            selectTab(TAB_HOME)
            handleWorkflowNotificationIntent(intent)
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
    override fun onResume() {
        super.onResume()
        if (!session.isLoggedIn) return
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

        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        currentTab = index
        updateTabUi(index)
        applyTopBarForTab(index)
        setTabBarVisible(true)

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

    private fun updateTabUi(index: Int) {
        // Matches the design tokens — bright green for the active tab, light cool gray
        // for inactive ones. Same outline icon in both states, only the tint changes.
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
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, it)
                        .addToBackStack(null)
                        .commit()
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
                        .replace(R.id.fragmentContainer, it)
                        .addToBackStack(null)
                        .commit()
                }
            }
            else -> selectTab(TAB_HOME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWorkflowNotificationIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab)
    }
}
