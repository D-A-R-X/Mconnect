package com.manjugroups.m_connect.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentHomeBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.notifications.NotificationsFragment
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        setupHeader()
        setupActions()
        collectState()
        viewModel.loadHomeData(session.bearerToken)
        loadUnreadNotifications()
    }

    override fun onResume() {
        super.onResume()
        loadUnreadNotifications()
    }

    private fun setupHeader() {
        val rawName = (session.userName ?: "User").ifBlank { "User" }
        val name = rawName.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
        binding.tvHeaderName.text = name
        binding.tvHeaderRole.text = "Junior Full Stack Developer"
        binding.tvAvatarInitial.text = name.first().uppercase()
    }

    private fun setupActions() {
        binding.btnHomeProfile.setOnClickListener {
            (activity as? MainActivity)?.openTab(MainActivity.TAB_PROFILE)
        }
        binding.btnHomeMessages.setOnClickListener {
            (activity as? MainActivity)?.openTab(MainActivity.TAB_CHAT)
        }
        binding.btnHomeBell.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, NotificationsFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is HomeUiState.Loading -> {
                            binding.homeLoading.visibility = View.VISIBLE
                            binding.homeContent.visibility = View.GONE
                        }

                        is HomeUiState.Loaded -> {
                            binding.homeLoading.visibility = View.GONE
                            binding.homeContent.visibility = View.VISIBLE
                            renderSummary(state)
                            renderVisitCard(state)
                        }

                        is HomeUiState.Error -> {
                            binding.homeLoading.visibility = View.GONE
                            binding.homeContent.visibility = View.VISIBLE
                            binding.tvSummarySubtitle.text = "Today task & presence activity"
                            binding.tvVisitEmptyTitle.text = "No Visits Available"
                            binding.tvVisitEmptySubtitle.text =
                                "It looks like you don't have any meetings scheduled at the moment. " +
                                    "This space will be updated as new meetings are added!"
                        }
                    }
                }
            }
        }
    }

    private fun renderSummary(state: HomeUiState.Loaded) {
        val totalTasks = state.todayVisits.count { it.status != "cancelled" } + state.assignedPlaces.size
        val presenceState = when {
            state.hasOpenSession -> "active presence"
            state.totalMinutes > 0 -> "presence activity"
            else -> "presence activity"
        }
        binding.tvSummarySubtitle.text =
            if (totalTasks > 0) "Today $totalTasks task(s) & $presenceState"
            else "Today task & presence activity"
    }

    private fun renderVisitCard(state: HomeUiState.Loaded) {
        val visits = state.todayVisits.filter { it.status != "cancelled" }
        if (visits.isEmpty()) {
            binding.tvVisitEmptyTitle.text = "No Visits Available"
            binding.tvVisitEmptySubtitle.text =
                "It looks like you don't have any meetings scheduled at the moment. " +
                    "This space will be updated as new meetings are added!"
            return
        }

        val active = visits.count { it.status == "in-progress" }
        binding.tvVisitEmptyTitle.text = "${visits.size} Visit${if (visits.size > 1) "s" else ""} Planned"
        binding.tvVisitEmptySubtitle.text =
            if (active > 0) "$active in progress right now."
            else "You're all set for today's visit schedule."
    }

    private fun loadUnreadNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.getUnreadNotificationCount(session.bearerToken)
            }.onSuccess { response ->
                if (_binding == null) return@onSuccess
                val unreadCount = response.unreadCount
                binding.tvBellBadge.visibility = if (unreadCount > 0) View.VISIBLE else View.GONE
                binding.tvBellBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
