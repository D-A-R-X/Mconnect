package com.manjugroups.m_connect.ui.notifications

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentNotificationsBinding
import com.manjugroups.m_connect.databinding.ItemNotificationBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.NotificationData
import com.manjugroups.m_connect.network.IdRequest
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.hr.AttendanceHistoryFragment
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
import com.manjugroups.m_connect.ui.library.loans.LoansFragment
import com.manjugroups.m_connect.ui.marketing.bookings.BookingsFragment
import com.manjugroups.m_connect.ui.marketing.CpVisitsFragment
import com.manjugroups.m_connect.ui.telecaller.MyLeadsFragment
import com.manjugroups.m_connect.ui.marketing.SiteVisitsFragment
import com.manjugroups.m_connect.ui.tasks.TaskDetailFragment
import com.manjugroups.m_connect.ui.tasks.TasksFragment
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeParseException

enum class NotificationCategory {
    CHATS, HR, TASKS, OPERATIONS, GENERAL
}

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private var allNotifications: List<NotificationData> = emptyList()
    private var activeFilter: NotificationCategory? = null
    private lateinit var notificationAdapter: NotificationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { navigateUp() }
        binding.btnMarkAllRead.setOnClickListener { markAllRead() }

        // Apply green gradient shader to "Mark all read" button
        applyGradientToTextView(binding.btnMarkAllRead, "#1BCA0B", "#3D9D02")

        // Setup RecyclerView
        notificationAdapter = NotificationAdapter { openNotification(it) }
        binding.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotifications.adapter = notificationAdapter

        // Apply dynamic window insets for bottom navigation bar height to offset bottom list fade
        ViewCompat.setOnApplyWindowInsetsListener(binding.rvNotifications) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Update RecyclerView bottom padding
            binding.rvNotifications.setPadding(
                binding.rvNotifications.paddingLeft,
                binding.rvNotifications.paddingTop,
                binding.rvNotifications.paddingRight,
                sys.bottom + (50 * resources.displayMetrics.density).toInt()
            )
            // Update bottom fade overlay height and padding
            val fadeParams = binding.bottomFadeOverlay.layoutParams
            fadeParams.height = sys.bottom + (40 * resources.displayMetrics.density).toInt()
            binding.bottomFadeOverlay.layoutParams = fadeParams
            binding.bottomFadeOverlay.setPadding(0, 0, 0, sys.bottom)
            insets
        }

        // Setup filter chips
        setupFilterChips()

        binding.notificationsRefresh.setupPullToRefresh { loadNotifications() }

        loadNotifications()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(android.graphics.Color.WHITE, true, fullBleed = false)
        }
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private fun setupFilterChips() {
        binding.chipAll.setOnClickListener {
            selectFilter(null)
        }
        binding.chipChats.setOnClickListener {
            selectFilter(NotificationCategory.CHATS)
        }
        binding.chipHr.setOnClickListener {
            selectFilter(NotificationCategory.HR)
        }
        binding.chipTasks.setOnClickListener {
            selectFilter(NotificationCategory.TASKS)
        }
        binding.chipOps.setOnClickListener {
            selectFilter(NotificationCategory.OPERATIONS)
        }
        updateFilterChips(null)
    }

    private fun selectFilter(category: NotificationCategory?) {
        activeFilter = category
        updateFilterChips(category)
        updateEmptyStateCopy(category)
        applyNotificationsFilter()
    }

    private fun updateEmptyStateCopy(category: NotificationCategory?) {
        val (title, description) = when (category) {
            NotificationCategory.CHATS -> 
                "No Chat Notifications" to "Mentions, direct messages, and group updates will show up here."
            NotificationCategory.HR -> 
                "No HR & Approvals" to "Leave requests, permissions, and reviewer updates will show up here."
            NotificationCategory.TASKS -> 
                "No Task Notifications" to "Daily task assignments, updates, and reminders will show up here."
            NotificationCategory.OPERATIONS -> 
                "No Operations Notifications" to "Site visits, client place visits, booking workflows, and loans appear here."
            else -> 
                "No notifications yet" to "Approvals, chat updates, and reminders will show up here."
        }
        binding.emptyState.setTitle(title)
        binding.emptyState.setDescription(description)
    }

    private fun applyNotificationsFilter() {
        val filtered = if (activeFilter == null) {
            allNotifications
        } else {
            allNotifications.filter { getNotificationCategory(it.referenceType, it.title, it.message) == activeFilter }
        }
        notificationAdapter.submitList(filtered)

        updateEmptyStateCopy(activeFilter)
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvNotifications.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateFilterChips(activeCategory: NotificationCategory?) {
        val context = context ?: return

        val chips = listOf(
            Triple(binding.chipAll, binding.chipAllLabel, binding.chipAllBadge to null),
            Triple(binding.chipChats, binding.chipChatsLabel, binding.chipChatsBadge to NotificationCategory.CHATS),
            Triple(binding.chipHr, binding.chipHrLabel, binding.chipHrBadge to NotificationCategory.HR),
            Triple(binding.chipTasks, binding.chipTasksLabel, binding.chipTasksBadge to NotificationCategory.TASKS),
            Triple(binding.chipOps, binding.chipOpsLabel, binding.chipOpsBadge to NotificationCategory.OPERATIONS)
        )

        val isDarkMode = (context.resources.configuration.uiMode and 
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        for ((chipLayout, labelTv, badgePair) in chips) {
            val (badgeTv, category) = badgePair
            val isActive = category == activeCategory
            if (isActive) {
                chipLayout.setBackgroundResource(R.drawable.bg_chat_filter_active)
                labelTv.setTextColor(android.graphics.Color.WHITE)
                badgeTv.setBackgroundResource(R.drawable.bg_chat_tab_badge_white)
                badgeTv.setTextColor(android.graphics.Color.WHITE)
            } else {
                chipLayout.setBackgroundResource(R.drawable.bg_chat_filter_inactive)
                val labelColor = if (isDarkMode) {
                    android.graphics.Color.parseColor("#9BA0A5")
                } else {
                    android.graphics.Color.parseColor("#475467")
                }
                labelTv.setTextColor(labelColor)
                badgeTv.setBackgroundResource(R.drawable.bg_chat_tab_badge_blue)
                badgeTv.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    private fun updateBadgeCounts(notifications: List<NotificationData>) {
        val chatsUnread = notifications.count { !it.read && getNotificationCategory(it.referenceType, it.title, it.message) == NotificationCategory.CHATS }
        val hrUnread = notifications.count { !it.read && getNotificationCategory(it.referenceType, it.title, it.message) == NotificationCategory.HR }
        val tasksUnread = notifications.count { !it.read && getNotificationCategory(it.referenceType, it.title, it.message) == NotificationCategory.TASKS }
        val opsUnread = notifications.count { !it.read && getNotificationCategory(it.referenceType, it.title, it.message) == NotificationCategory.OPERATIONS }
        val totalUnread = notifications.count { !it.read }

        updateBadge(binding.chipAllBadge, totalUnread)
        updateBadge(binding.chipChatsBadge, chatsUnread)
        updateBadge(binding.chipHrBadge, hrUnread)
        updateBadge(binding.chipTasksBadge, tasksUnread)
        updateBadge(binding.chipOpsBadge, opsUnread)
    }

    private fun updateBadge(badgeTv: TextView, count: Int) {
        if (count > 0) {
            badgeTv.text = count.toString()
            badgeTv.visibility = View.VISIBLE
        } else {
            badgeTv.visibility = View.GONE
        }
    }

    private fun loadNotifications() {
        binding.skeletonContainer.visibility = View.VISIBLE
        binding.rvNotifications.visibility = View.GONE
        binding.emptyState.visibility = View.GONE
        SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.getNotifications(session.bearerToken)
            }.onSuccess { response ->
                allNotifications = response.notifications
                renderNotifications()
            }.onFailure {
                toast("Unable to load notifications")
                allNotifications = emptyList()
                renderNotifications()
            }
        }
    }

    private fun renderNotifications() {
        SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
        binding.skeletonContainer.visibility = View.GONE
        binding.notificationsRefresh.dismissRefresh()

        val unreadCount = allNotifications.count { !it.read }
        binding.tvUnreadSummary.text = when (unreadCount) {
            0 -> "You're all caught up"
            1 -> "1 unread notification"
            else -> "$unreadCount unread notifications"
        }
        binding.btnMarkAllRead.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE

        // Update badge counts on all filter chips
        updateBadgeCounts(allNotifications)

        // Submit the filtered list
        applyNotificationsFilter()
    }

    private fun markAllRead() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.markAllNotificationsRead(session.bearerToken)
            }.onSuccess {
                loadNotifications()
            }.onFailure {
                toast("Unable to mark all as read")
            }
        }
    }

    private fun openNotification(notification: NotificationData) {
        viewLifecycleOwner.lifecycleScope.launch {
            notification.id?.let { id ->
                runCatching {
                    api.markNotificationRead(session.bearerToken, IdRequest(id))
                }
            }

            // Map each backend referenceType to the mobile destination
            val fragment = when (notification.referenceType) {
                "leave" -> LeavesFragment.newInstance(
                    mode = if (notification.type.orEmpty().contains("approval-needed")) {
                        LeavesFragment.MODE_APPROVAL
                    } else {
                        LeavesFragment.MODE_HISTORY
                    },
                    entityId = notification.referenceId
                )
                "permission" -> PermissionsFragment.newInstance(
                    mode = if (notification.type.orEmpty().contains("approval-needed")) {
                        PermissionsFragment.MODE_APPROVAL
                    } else {
                        PermissionsFragment.MODE_HISTORY
                    },
                    entityId = notification.referenceId
                )
                "channel" -> notification.referenceId?.let {
                    ChatMessagesFragment.forChannel(
                        id = it,
                        name = notification.title ?: "Channel"
                    )
                }
                "conversation" -> notification.referenceId?.let {
                    ChatMessagesFragment.forConversation(
                        id = it,
                        name = notification.title ?: "Chat"
                    )
                }
                // The standalone Attendance Approvals screen was removed. Open
                // My Attendance instead — it shows the user's own records and,
                // for reviewers, the approval tabs are gated inside it by IAM.
                "staff-attendance" -> AttendanceHistoryFragment()
                "site-visit" -> SiteVisitsFragment()
                "clientPlaceVisit" -> CpVisitsFragment()
                "booking", "booking_cancellation" -> BookingsFragment.newInstance()
                "telecallerLeads" -> MyLeadsFragment.newInstance()
                "loan", "loan-skip-request" -> LoansFragment()
                // Land procurement notifications point at a landProperty — open
                // the Land Inspection screen (the related page) rather than the
                // project-task detail, which would 500 on the wrong id type.
                "landProperty", "landInspection", "land-inspection" ->
                    com.manjugroups.m_connect.ui.library.land.LandInspectionFragment()
                "dailyTask" -> notification.referenceId?.let { id ->
                    TaskDetailFragment.newInstance(id)
                } ?: TasksFragment()
                else -> null
            }

            // IAM gate: a notification can point at a screen the recipient no
            // longer has access to (permissions changed, wrong-audience push,
            // etc.). Rather than navigate into a screen they can't use, surface
            // a clear message. Admins and un-gated targets (chat) pass through.
            val required = requiredPermissionsFor(notification.referenceType)
            val hasAccess = required == null || required.any { session.hasPermission(it) }
            when {
                fragment != null && hasAccess -> {
                    parentFragmentManager.beginTransaction()
                        .applySmoothTransitions()
                        .replace(R.id.fragmentContainer, fragment)
                        .addToBackStack(null)
                        .commit()
                }
                fragment != null -> {
                    Toast.makeText(
                        requireContext(),
                        "You don't have access to this page.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadNotifications()
                }
                else -> {
                    Toast.makeText(
                        requireContext(),
                        "Open the related screen from the menu to view this update.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    loadNotifications()
                }
            }
        }
    }

    /**
     * IAM permission(s) required to open the screen a notification points at.
     * Any one grants access (viewer OR approver, etc.); null = un-gated (chat,
     * or an unmapped type that already falls through to the menu hint). Keys
     * mirror the App Library / web gating and SessionManager.hasPermission
     * already treats admins as allowed.
     */
    private fun requiredPermissionsFor(referenceType: String?): List<String>? = when (referenceType) {
        "leave" -> listOf("leaves.view", "leaves.viewAll", "leaves.approve")
        "permission" -> listOf("permissions.view", "permissions.viewAll", "permissions.approve")
        // My Attendance is a personal screen everyone can open; approval tabs
        // inside it are gated separately, so don't block the notification here.
        "staff-attendance" -> null
        "site-visit" -> listOf("marketing.siteVisits.view")
        "clientPlaceVisit" -> listOf("marketing.cpVisits.view")
        "booking", "booking_cancellation" -> listOf("marketing.bookings.view", "marketing.bookings.create")
        "telecallerLeads" -> listOf(
            "telecaller.externalLeads.viewOwn", "telecaller.externalLeads.viewAll",
        )
        "loan", "loan-skip-request" -> listOf("loans.view", "loans.manage", "loans.approve")
        "dailyTask" -> listOf("tasks.view", "tasks.viewAll", "tasks.create")
        "landProperty", "landInspection", "land-inspection" ->
            listOf("land.view", "land.inspect", "land.inspection.view")
        else -> null // chat (channel / conversation) + unknowns: no IAM gate
    }

    private fun applyGradientToTextView(textView: TextView, startColor: String, endColor: String) {
        textView.post {
            val paint = textView.paint
            val width = paint.measureText(textView.text.toString())
            if (width > 0) {
                textView.paint.shader = android.graphics.LinearGradient(
                    0f, 0f, width, 0f,
                    intArrayOf(
                        android.graphics.Color.parseColor(startColor),
                        android.graphics.Color.parseColor(endColor)
                    ),
                    null,
                    android.graphics.Shader.TileMode.CLAMP
                )
                textView.invalidate()
            }
        }
    }

    private fun toast(message: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onDestroyView()
        _binding = null
    }
}

class BadgeColors(
    val bgColor: Int,
    val iconColor: Int,
    val iconResId: Int?
)

fun getNotificationCategory(referenceType: String?, title: String?, message: String?): NotificationCategory {
    if (!referenceType.isNullOrBlank()) {
        val cat = when (referenceType) {
            "channel", "conversation" -> NotificationCategory.CHATS
            "leave", "permission", "staff-attendance" -> NotificationCategory.HR
            "dailyTask" -> NotificationCategory.TASKS
            "site-visit", "clientPlaceVisit", "booking", "booking_cancellation", "telecallerLeads", "loan", "loan-skip-request" -> NotificationCategory.OPERATIONS
            else -> null
        }
        if (cat != null) return cat
    }

    // Fallback: search keywords in title and message
    val textToSearch = "${title.orEmpty()} ${message.orEmpty()}".lowercase()
    val titleLower = title.orEmpty().lowercase()
    return when {
        // Evaluate OPERATIONS first to prevent generic "staff" message body text from stealing GeoTrack/tamper alerts
        textToSearch.contains("visit") || textToSearch.contains("geotrack") || 
                textToSearch.contains("tamper") || textToSearch.contains("booking") || 
                textToSearch.contains("lead") || textToSearch.contains("loan") ||
                textToSearch.contains("site") || textToSearch.contains("gps") ||
                textToSearch.contains("client") || textToSearch.contains("location") -> NotificationCategory.OPERATIONS

        textToSearch.contains("chat") || textToSearch.contains("message") || 
                textToSearch.contains("channel") || textToSearch.contains("conversation") ||
                textToSearch.contains("dm") -> NotificationCategory.CHATS
                
        textToSearch.contains("task") || textToSearch.contains("todo") || 
                textToSearch.contains("daily") -> NotificationCategory.TASKS

        textToSearch.contains("leave") || textToSearch.contains("permission") || 
                textToSearch.contains("attendance") || textToSearch.contains("wfh") || 
                textToSearch.contains("work-from-home") || textToSearch.contains("hiring") ||
                textToSearch.contains("approval") || textToSearch.contains("hr") ||
                textToSearch.contains("check-in") || textToSearch.contains("check-out") ||
                titleLower.contains("staff") -> NotificationCategory.HR
                
        else -> NotificationCategory.GENERAL
    }
}

fun getCategoryBadgeColors(
    context: android.content.Context,
    category: NotificationCategory,
    isDarkMode: Boolean
): BadgeColors {
    return when (category) {
        NotificationCategory.CHATS -> {
            val bg = if (isDarkMode) android.graphics.Color.parseColor("#162A45") else android.graphics.Color.parseColor("#EBF5FF")
            val icon = if (isDarkMode) android.graphics.Color.parseColor("#6EA3F2") else android.graphics.Color.parseColor("#0B61CA")
            BadgeColors(bg, icon, R.drawable.ic_tab_chat)
        }
        NotificationCategory.HR -> {
            val bg = if (isDarkMode) android.graphics.Color.parseColor("#2B1D38") else android.graphics.Color.parseColor("#F3EBF9")
            val icon = if (isDarkMode) android.graphics.Color.parseColor("#B283E2") else android.graphics.Color.parseColor("#7C3AED")
            BadgeColors(bg, icon, R.drawable.ic_calendar_blank)
        }
        NotificationCategory.TASKS -> {
            val bg = if (isDarkMode) android.graphics.Color.parseColor("#132B1C") else android.graphics.Color.parseColor("#E6F5EC")
            val icon = if (isDarkMode) android.graphics.Color.parseColor("#5AD88B") else android.graphics.Color.parseColor("#16A34A")
            BadgeColors(bg, icon, R.drawable.ic_check_circle)
        }
        NotificationCategory.OPERATIONS -> {
            val bg = if (isDarkMode) android.graphics.Color.parseColor("#382310") else android.graphics.Color.parseColor("#FEF3E6")
            val icon = if (isDarkMode) android.graphics.Color.parseColor("#F4B56C") else android.graphics.Color.parseColor("#D97706")
            BadgeColors(bg, icon, R.drawable.ic_pin_outline)
        }
        NotificationCategory.GENERAL -> {
            val bg = if (isDarkMode) android.graphics.Color.parseColor("#2A2B2D") else android.graphics.Color.parseColor("#F1F3F5")
            val icon = if (isDarkMode) android.graphics.Color.parseColor("#9BA0A5") else android.graphics.Color.parseColor("#5F6368")
            BadgeColors(bg, icon, R.drawable.ic_bell)
        }
    }
}

class NotificationAdapter(
    private val onNotificationClick: (NotificationData) -> Unit
) : ListAdapter<NotificationData, NotificationAdapter.NotificationViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val binding = ItemNotificationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NotificationViewHolder(binding, onNotificationClick)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class NotificationViewHolder(
        private val binding: ItemNotificationBinding,
        private val onClick: (NotificationData) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: NotificationData) {
            val context = binding.root.context
            val title = item.title?.ifBlank { null } ?: "Notification"
            val message = item.message?.ifBlank { null } ?: "Open to view more details"
            val iconLetter = item.title?.trim()?.firstOrNull()?.uppercase() ?: "N"

            binding.tvTitle.text = title
            binding.tvMessage.text = message
            binding.tvTime.text = formatRelativeTime(item)

            val category = getNotificationCategory(item.referenceType, item.title, item.message)

            val isDarkMode = (context.resources.configuration.uiMode and 
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
                    android.content.res.Configuration.UI_MODE_NIGHT_YES

            val badgeColors = getCategoryBadgeColors(context, category, isDarkMode)

            // Unread badge styling
            if (item.read) {
                binding.tvUnread.visibility = View.GONE
                binding.tvTitle.setTypeface(null, Typeface.NORMAL)
                
                val typedValueFore = android.util.TypedValue()
                context.theme.resolveAttribute(R.attr.colorForegroundPrimary, typedValueFore, true)
                binding.tvTitle.setTextColor(typedValueFore.data)

                val typedValue = android.util.TypedValue()
                context.theme.resolveAttribute(R.attr.colorSurfaceSecondary, typedValue, true)
                binding.notificationRoot.backgroundTintList = ColorStateList.valueOf(typedValue.data)
            } else {
                binding.tvUnread.visibility = View.VISIBLE
                binding.tvTitle.setTypeface(null, Typeface.BOLD)
                
                val typedValueFore = android.util.TypedValue()
                context.theme.resolveAttribute(R.attr.colorForegroundPrimary, typedValueFore, true)
                binding.tvTitle.setTextColor(typedValueFore.data)

                // Always show green dot for unread notifications
                binding.tvUnread.setBackgroundResource(R.drawable.bg_dot_green)

                val unreadBgColor = if (isDarkMode) {
                    android.graphics.Color.parseColor("#1E2D4A")
                } else {
                    android.graphics.Color.parseColor("#F5F9FF")
                }
                binding.notificationRoot.backgroundTintList = ColorStateList.valueOf(unreadBgColor)
            }

            // Set single-circle badge colors (soft background, vibrant icon/letter)
            binding.iconContainer.backgroundTintList = ColorStateList.valueOf(badgeColors.bgColor)

            if (badgeColors.iconResId != null) {
                binding.tvIconLetter.visibility = View.GONE
                binding.ivCategoryIcon.visibility = View.VISIBLE
                binding.ivCategoryIcon.setImageResource(badgeColors.iconResId)
                binding.ivCategoryIcon.imageTintList = ColorStateList.valueOf(badgeColors.iconColor)
            } else {
                binding.ivCategoryIcon.visibility = View.GONE
                binding.tvIconLetter.visibility = View.VISIBLE
                binding.tvIconLetter.text = iconLetter
                binding.tvIconLetter.setTextColor(badgeColors.iconColor)
            }

            binding.notificationRoot.setOnClickListener {
                onClick(item)
            }
        }

        private fun formatRelativeTime(notification: NotificationData): CharSequence {
            val millis = notification.creationTime?.toLong()
                ?: notification.createdAt?.let(::parseIsoMillis)
                ?: return ""
            return DateUtils.getRelativeTimeSpanString(
                millis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
        }

        private fun parseIsoMillis(value: String): Long? {
            return try {
                Instant.parse(value).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<NotificationData>() {
            override fun areItemsTheSame(oldItem: NotificationData, newItem: NotificationData): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: NotificationData, newItem: NotificationData): Boolean {
                return oldItem == newItem
            }
        }
    }
}
