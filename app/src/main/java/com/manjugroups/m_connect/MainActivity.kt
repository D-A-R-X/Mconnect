package com.manjugroups.m_connect

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.auth.WelcomeActivity
import com.manjugroups.m_connect.geotrack.GeoTrackConsentActivity
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TrackingBootstrapData
import com.manjugroups.m_connect.network.TrackingDeviceSyncRequest
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.notifications.WorkflowNotificationRoute
import com.manjugroups.m_connect.ui.chat.ChatListFragment
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment
import com.manjugroups.m_connect.ui.home.HomeFragment
import com.manjugroups.m_connect.ui.hr.HrDashboardFragment
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_HOME = 0
        const val TAB_HR = 1
        const val TAB_CHAT = 2
        const val TAB_PROFILE = 3

        private const val KEY_CURRENT_TAB = "current_tab"
        private const val TAG_HOME = "root_tab_home"
        private const val TAG_HR = "root_tab_hr"
        private const val TAG_CHAT = "root_tab_chat"
        private const val TAG_PROFILE = "root_tab_profile"
    }

    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val geoApi = GeoTrackApi.create()
    private var currentTab = 0

    private data class TabConfig(
        val tab: FrameLayout,
        val icon: ImageView,
        val indicator: View,
        val text: TextView,
        val iconRes: Int
    )
    private lateinit var tabs: List<TabConfig>
    private lateinit var tabBarContainer: FrameLayout
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var mainRoot: LinearLayout
    private lateinit var statusBarBackground: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        session = SessionManager(this)
        if (!session.isLoggedIn) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        mainRoot = findViewById(R.id.mainRoot)
        statusBarBackground = findViewById(R.id.statusBarBackground)
        fragmentContainer = findViewById(R.id.fragmentContainer)
        tabBarContainer = findViewById(R.id.tabBarContainer)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.parseColor("#1C2020")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false

        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarBackground.layoutParams = statusBarBackground.layoutParams.apply {
                height = sys.top
            }
            fragmentContainer.updatePadding(top = 0)
            tabBarContainer.updatePadding(left = sys.left, right = sys.right, bottom = sys.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(mainRoot)

        tabs = listOf(
            TabConfig(
                findViewById(R.id.tabHome),
                findViewById(R.id.tabHomeIcon),
                findViewById(R.id.tabHomeIndicator),
                findViewById(R.id.tabHomeText),
                R.drawable.ic_tab_home_pencil
            ),
            TabConfig(
                findViewById(R.id.tabHr),
                findViewById(R.id.tabHrIcon),
                findViewById(R.id.tabHrIndicator),
                findViewById(R.id.tabHrText),
                R.drawable.ic_tab_calendar_pencil
            ),
            TabConfig(
                findViewById(R.id.tabChat),
                findViewById(R.id.tabChatIcon),
                findViewById(R.id.tabChatIndicator),
                findViewById(R.id.tabChatText),
                R.drawable.ic_tab_note_pencil
            ),
            TabConfig(
                findViewById(R.id.tabProfile),
                findViewById(R.id.tabProfileIcon),
                findViewById(R.id.tabProfileIndicator),
                findViewById(R.id.tabProfileText),
                R.drawable.ic_tab_profile
            )
        )

        // Set icons
        tabs.forEach { it.icon.setImageResource(it.iconRes) }

        // Tab click listeners
        tabs.forEachIndexed { index, config ->
            config.tab.setOnClickListener { selectTab(index) }
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

    fun openTab(index: Int) {
        selectTab(index)
    }

    fun setTabBarVisible(visible: Boolean) {
        if (!::tabBarContainer.isInitialized) return
        tabBarContainer.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    fun setTopBarAppearance(backgroundColor: Int, darkStatusIcons: Boolean) {
        if (!::statusBarBackground.isInitialized) return
        statusBarBackground.setBackgroundColor(backgroundColor)
        window.statusBarColor = backgroundColor
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = darkStatusIcons
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
            TAB_HR -> setTopBarAppearance(Color.parseColor("#7155FF"), false)
            TAB_PROFILE -> setTopBarAppearance(Color.parseColor("#7155FF"), false)
            else -> setTopBarAppearance(Color.parseColor("#FEFEFE"), true)
        }
    }

    private fun updateTabUi(index: Int) {
        val iconColor = Color.WHITE
        val mutedAlpha = 0.86f

        tabs.forEachIndexed { i, config ->
            val isActive = i == index
            config.tab.background = null
            config.icon.imageTintList = ColorStateList.valueOf(iconColor)
            config.icon.alpha = if (isActive) 1f else mutedAlpha
            config.indicator.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            config.text.setTextColor(Color.WHITE)
        }
    }

    private fun createRootFragment(index: Int): Fragment = when (index) {
        TAB_HOME -> HomeFragment()
        TAB_HR -> HrDashboardFragment()
        TAB_CHAT -> ChatListFragment()
        TAB_PROFILE -> ProfileFragment()
        else -> HomeFragment()
    }

    private fun tabTag(index: Int): String = when (index) {
        TAB_HOME -> TAG_HOME
        TAB_HR -> TAG_HR
        TAB_CHAT -> TAG_CHAT
        TAB_PROFILE -> TAG_PROFILE
        else -> TAG_HOME
    }

    private fun rootTabTags(): List<String> = listOf(TAG_HOME, TAG_HR, TAG_CHAT, TAG_PROFILE)

    private fun normalizeTab(index: Int): Int = when (index) {
        TAB_HOME, TAB_HR, TAB_CHAT, TAB_PROFILE -> index
        else -> TAB_HOME
    }

    private suspend fun refreshSessionContext() {
        runCatching {
            api.getMyIamPermissions(session.bearerToken)
        }.onSuccess { iam ->
            session.iamPermissions = iam.permissions.toSet()
            session.isAdmin = iam.isAdmin
        }

        runCatching {
            PushTokenManager.syncCurrentToken(this@MainActivity, session)
        }

        runCatching {
            syncTrackingBootstrap()
        }
    }

    private suspend fun syncTrackingBootstrap() {
        val deviceId = session.trackingDeviceId
        val deviceSync = geoApi.syncTrackingDevice(
            session.bearerToken,
            TrackingDeviceSyncRequest(
                deviceId = deviceId,
                appVersion = BuildConfig.VERSION_NAME,
                pushToken = session.pushToken,
                notificationPermission = PushTokenManager.hasNotificationPermission(this),
                fineLocationPermission = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
                backgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else true,
                activityRecognitionPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
                } else true,
                batteryOptimizationIgnored = (getSystemService(POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(packageName),
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
            )
        )
        val bootstrap = deviceSync.bootstrap ?: geoApi.getTrackingBootstrap(session.bearerToken, deviceId).data
        applyTrackingBootstrap(bootstrap)
    }

    private fun applyTrackingBootstrap(bootstrap: TrackingBootstrapData?) {
        session.geoTrackingEnabled = bootstrap?.assignment?.attendance != null || bootstrap?.assignment?.siteVisit != null
        session.geoConsentGiven = bootstrap?.consent?.status == "granted"
        session.geoConsentDeclined = bootstrap?.consent?.status == "declined" || bootstrap?.consent?.status == "revoked"
        session.activeTrackingSessionId = bootstrap?.activeSession?.id
        session.shouldTrackNow = bootstrap?.shouldTrack == true

        if (bootstrap?.shouldPromptConsent == true && !isFinishing) {
            startActivity(Intent(this, GeoTrackConsentActivity::class.java))
            return
        }

        val canStartTracking = bootstrap?.shouldTrack == true &&
            !bootstrap.activeSession?.id.isNullOrBlank() &&
            GeoTrackService.hasRequiredLocationPermissions(this)

        if (canStartTracking) {
            GeoTrackService.start(this)
        } else {
            GeoTrackService.stop(this)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
