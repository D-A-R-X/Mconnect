package com.manjugroups.m_connect.ui.home

import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentHomeBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AssignedPlace
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.notifications.NotificationsFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: HomeViewModel by viewModels()
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val visitEmptySubtitle =
        "It looks like you don’t have any meetings scheduled at the moment. " +
            "This space will be updated as new meetings are added!"

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
        collectEvents()
        viewModel.loadHomeData(session.bearerToken)
        loadUnreadNotifications()
    }

    override fun onResume() {
        super.onResume()
        // Defensive: restore tab bar in case a child fragment hid it.
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        loadUnreadNotifications()
        // Refresh visit list — covers returning from TripNavigationFragment
        viewModel.loadTodayVisits(session.bearerToken)
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
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ProfileFragment())
                .addToBackStack(null)
                .commit()
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
                            if (!viewModel.isVisitsLoading.value) {
                                renderVisitCard(state)
                            }
                        }

                        is HomeUiState.Error -> {
                            binding.homeLoading.visibility = View.GONE
                            binding.homeContent.visibility = View.VISIBLE
                            binding.tvSummarySubtitle.text = "Today task & presence activity"
                            binding.tvVisitCountBadge.visibility = View.GONE
                            binding.visitListContent.visibility = View.GONE
                            binding.visitEmptyContent.visibility = View.VISIBLE
                            binding.tvVisitEmptyTitle.text = "No Visits Available"
                            binding.tvVisitEmptySubtitle.text = visitEmptySubtitle
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isVisitsLoading.collect { loading ->
                    setVisitSkeletonVisible(loading)
                    if (!loading) {
                        // Re-render once loading finishes so the real cards appear.
                        (viewModel.uiState.value as? HomeUiState.Loaded)?.let(::renderVisitCard)
                    }
                }
            }
        }
    }

    private var visitSkeletonAnimating = false

    private fun setVisitSkeletonVisible(visible: Boolean) {
        val skeleton = binding.visitSkeletonContainer
        if (visible) {
            binding.visitListContent.visibility = View.GONE
            binding.visitEmptyContent.visibility = View.GONE
            skeleton.visibility = View.VISIBLE
            if (!visitSkeletonAnimating) {
                val pulse = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(), R.anim.skeleton_pulse
                )
                forEachLeafBlock(skeleton) { it.startAnimation(pulse) }
                visitSkeletonAnimating = true
            }
        } else {
            if (visitSkeletonAnimating) {
                forEachLeafBlock(skeleton) { it.clearAnimation() }
                visitSkeletonAnimating = false
            }
            skeleton.visibility = View.GONE
        }
    }

    private fun forEachLeafBlock(group: ViewGroup, action: (View) -> Unit) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ViewGroup) forEachLeafBlock(child, action)
            else action(child)
        }
    }

    private fun collectEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.punchEvent.collect { event ->
                    val message = when (event) {
                        is PunchEvent.Success -> event.message
                        is PunchEvent.Error -> event.message
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
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
        val places = state.assignedPlaces
        val displayCount = if (visits.isNotEmpty()) visits.size else places.size

        if (displayCount > 0) {
            binding.tvVisitCountBadge.visibility = View.VISIBLE
            binding.tvVisitCountBadge.text = displayCount.toString()
        } else {
            binding.tvVisitCountBadge.visibility = View.GONE
        }

        if (visits.isEmpty() && places.isEmpty()) {
            binding.visitListContent.visibility = View.GONE
            binding.visitEmptyContent.visibility = View.VISIBLE
            binding.tvVisitEmptyTitle.text = "No Visits Available"
            binding.tvVisitEmptySubtitle.text = visitEmptySubtitle
            return
        }

        binding.visitListContent.visibility = View.VISIBLE
        binding.visitEmptyContent.visibility = View.GONE
        binding.visitListContent.removeAllViews()

        if (visits.isNotEmpty()) {
            visits.forEachIndexed { index, visit ->
                val itemView = createVisitItem(visit, index, visits.size)
                binding.visitListContent.addView(itemView)
            }
            return
        }

        places.forEachIndexed { index, place ->
            val itemView = createAssignedPlaceItem(place, index, places.size)
            binding.visitListContent.addView(itemView)
        }
    }

    private fun createVisitItem(visit: TodayVisit, index: Int, total: Int): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, binding.visitListContent, false)
        val title = itemView.findViewById<TextView>(R.id.tvVisitItemTitle)
        val time = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        val action = itemView.findViewById<TextView>(R.id.btnVisitItemAction)

        title.text = visit.placeName ?: "Scheduled Visit"
        time.text = formatVisitTimeOrDate(visit)

        val status = visit.status.lowercase(Locale.getDefault())
        val isCompleted = status in setOf("completed", "complete", "done", "closed")
        val isInProgress = status in setOf(
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
        )

        when {
            isInProgress -> {
                action.text = "In progress"
                action.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action_progress)
                action.setTextColor(android.graphics.Color.parseColor("#B54708"))
            }
            isCompleted -> {
                action.text = "Completed"
                action.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action_disabled)
                action.setTextColor(android.graphics.Color.parseColor("#475467"))
            }
            else -> {
                action.text = "Start Trip"
                action.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action)
                action.setTextColor(android.graphics.Color.WHITE)
            }
        }

        // Card is the navigation target for both scheduled and in-progress
        // visits. Pill is informational: clickable only for scheduled (where it
        // reads "Start Trip"); a status badge otherwise.
        val canOpen = !isCompleted
        if (canOpen) {
            val openNav: (View) -> Unit = { openTripNavigationForVisit(visit) }
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(openNav)
            if (isInProgress) {
                action.isClickable = false
                action.setOnClickListener(null)
            } else {
                action.isClickable = true
                action.setOnClickListener(openNav)
            }
        } else {
            itemView.isClickable = false
            itemView.isFocusable = false
            itemView.setOnClickListener(null)
            action.isClickable = false
            action.setOnClickListener(null)
        }

        applyItemSpacing(itemView, index, total)
        return itemView
    }

    private fun createAssignedPlaceItem(place: AssignedPlace, index: Int, total: Int): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, binding.visitListContent, false)
        val title = itemView.findViewById<TextView>(R.id.tvVisitItemTitle)
        val time = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        val action = itemView.findViewById<TextView>(R.id.btnVisitItemAction)

        title.text = place.name
        time.text = "Available Today"
        action.text = "Start Trip"
        action.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action)
        action.setTextColor(android.graphics.Color.WHITE)

        val openNav: (View) -> Unit = { openTripNavigationForPlace(place) }
        itemView.isClickable = true
        itemView.isFocusable = true
        itemView.setOnClickListener(openNav)
        action.isClickable = true
        action.setOnClickListener(openNav)

        applyItemSpacing(itemView, index, total)
        return itemView
    }

    private fun openTripNavigationForVisit(visit: TodayVisit) {
        val fragment = TripNavigationFragment.forVisit(
            visitId = visit.id,
            placeName = visit.placeName,
            placeAddress = visit.placeAddress,
            destLat = visit.placeLat,
            destLng = visit.placeLng
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openTripNavigationForPlace(place: AssignedPlace) {
        val fragment = TripNavigationFragment.forPlace(
            placeId = place.id,
            placeName = place.name,
            placeAddress = place.address,
            destLat = place.lat,
            destLng = place.lng
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun applyItemSpacing(itemView: View, index: Int, total: Int) {
        val params = itemView.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        params.bottomMargin = if (index == total - 1) 0 else dpToPx(10)
        itemView.layoutParams = params
    }

    private fun formatVisitTimeOrDate(visit: TodayVisit): String {
        val startRaw = visit.scheduledStartTime
        val endRaw = visit.scheduledEndTime
        val start = startRaw?.let { formatTimeValue(it) }
        val end = endRaw?.let { formatTimeValue(it) }

        if (!start.isNullOrBlank() && !end.isNullOrBlank()) return "$start - $end"
        if (!start.isNullOrBlank()) return start
        if (!end.isNullOrBlank()) return end

        // Fallback: if scheduledDate contains a datetime, show time; else show date.
        val embeddedTime = visit.scheduledDate.let { formatTimeValue(it) }
        if (!embeddedTime.isNullOrBlank()) return embeddedTime

        return formatVisitDate(visit.scheduledDate)
    }

    private fun formatVisitDate(scheduledDate: String?): String {
        if (scheduledDate.isNullOrBlank()) return "Today"
        val parsed = runCatching {
            val input = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            input.parse(scheduledDate)
        }.getOrNull() ?: return "Today"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed)
    }

    private fun formatTimeValue(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null

        val amPmMatch = Regex("(?i)\\b(\\d{1,2}:\\d{2})(?::\\d{2})?\\s*(AM|PM)\\b").find(value)
        if (amPmMatch != null) {
            return "${amPmMatch.groupValues[1]} ${amPmMatch.groupValues[2].uppercase(Locale.getDefault())}"
        }

        val h24Match = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)(?::[0-5]\\d)?\\b").find(value)
        if (h24Match != null) {
            val hour24 = h24Match.groupValues[1].toIntOrNull() ?: return null
            val minute = h24Match.groupValues[2]
            val hour12 = when {
                hour24 == 0 -> 12
                hour24 > 12 -> hour24 - 12
                else -> hour24
            }
            val suffix = if (hour24 < 12) "AM" else "PM"
            return String.format(Locale.getDefault(), "%02d:%s %s", hour12, minute, suffix)
        }

        return null
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
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
