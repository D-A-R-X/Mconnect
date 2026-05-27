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
import com.manjugroups.m_connect.ui.hr.LeavesFragment
import com.manjugroups.m_connect.ui.hr.PermissionsFragment
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

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
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
                else -> null
            }

            if (fragment != null) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, fragment)
                    .addToBackStack(null)
                    .commit()
            } else {
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
