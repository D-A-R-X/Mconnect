package com.manjugroups.m_connect.ui.notifications

import android.os.Bundle
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentNotificationsBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.NotificationData
import com.manjugroups.m_connect.network.IdRequest
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.hr.AttendanceReviewFragment
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

class NotificationsFragment : Fragment() {

    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

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

    private fun loadNotifications() {
        SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.getNotifications(session.bearerToken)
            }.onSuccess { response ->
                renderNotifications(response.notifications)
            }.onFailure {
                toast("Unable to load notifications")
                renderNotifications(emptyList())
            }
        }
    }

    private fun renderNotifications(notifications: List<NotificationData>) {
        SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
        binding.notificationsRefresh.dismissRefresh()
        binding.notificationList.removeAllViews()

        val unreadCount = notifications.count { !it.read }
        binding.tvUnreadSummary.text = when (unreadCount) {
            0 -> "You're all caught up"
            1 -> "1 unread notification"
            else -> "$unreadCount unread notifications"
        }
        binding.btnMarkAllRead.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (notifications.isEmpty()) View.VISIBLE else View.GONE

        notifications.forEachIndexed { index, notification ->
            val item = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_notification, binding.notificationList, false)

            val title = notification.title?.ifBlank { null } ?: "Notification"
            val message = notification.message?.ifBlank { null } ?: "Open to view more details"
            val iconLetter = notification.title?.trim()?.firstOrNull()?.uppercase() ?: "N"

            item.findViewById<TextView>(R.id.tvIconLetter).text = iconLetter
            item.findViewById<TextView>(R.id.tvTitle).text = title
            item.findViewById<TextView>(R.id.tvMessage).text = message
            item.findViewById<TextView>(R.id.tvTime).text = formatRelativeTime(notification)
            item.findViewById<View>(R.id.tvUnread).visibility =
                if (notification.read) View.GONE else View.VISIBLE

            val iconContainer = item.findViewById<View>(R.id.iconContainer)
            val palette = when (index % 4) {
                0 -> R.drawable.bg_chat_avatar
                1 -> R.drawable.bg_chat_avatar_success
                2 -> R.drawable.bg_chat_avatar_warning
                else -> R.drawable.bg_chat_empty_avatar
            }
            iconContainer.setBackgroundResource(palette)

            item.findViewById<LinearLayout>(R.id.notificationRoot).setOnClickListener {
                openNotification(notification)
            }

            binding.notificationList.addView(item)
        }
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
            // that surfaces that entity. Keep types matching what the
            // Convex notifications writers emit (search the backend for
            // `referenceType:` to see the full enum). Unknown types fall
            // through to the else branch and refresh the list — that
            // way new server-side notification kinds don't crash old
            // clients, they just no-op visually.
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
                // HR Attendance Review Blocked + the broader staff-attendance
                // family (escalations, reviewer reminders, reviewed/approved
                // notifications). All currently land on the HR review
                // screen — the user picks the row they care about there.
                // Was the exact symptom the screenshot showed: tapping
                // "HR Attendance Review Blocked" did nothing because this
                // type was missing from the routing table.
                "staff-attendance" -> AttendanceReviewFragment.newInstance()
                // Marketing chains. Each has a list-fragment landing —
                // the operator drills into the specific row from there.
                // (Detail-by-id factories exist for bookings + tasks; the
                // SV/CP/lead list screens don't have a cheap byId factory
                // yet, so we land on the list and the row is one tap away.)
                "site-visit" -> SiteVisitsFragment()
                "clientPlaceVisit" -> CpVisitsFragment()
                "booking", "booking_cancellation" -> BookingsFragment.newInstance()
                "telecallerLeads" -> MyLeadsFragment.newInstance()
                // Loans, including loan-skip-request approvals.
                "loan", "loan-skip-request" -> LoansFragment()
                // Daily tasks — open the specific task when the
                // notification carries its id, otherwise the list.
                "dailyTask" -> notification.referenceId?.let { id ->
                    TaskDetailFragment.newInstance(id)
                } ?: TasksFragment()
                else -> null
            }

            if (fragment != null) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            } else {
                // Unknown referenceType — keep behaviour identical to
                // before: refresh so the row at least shows up as read
                // and the operator can re-evaluate. Surface a small hint
                // so they know the tap registered.
                Toast.makeText(
                    requireContext(),
                    "Open the related screen from the menu to view this update.",
                    Toast.LENGTH_SHORT,
                ).show()
                loadNotifications()
            }
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
