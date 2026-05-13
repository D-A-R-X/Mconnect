package com.manjugroups.m_connect.ui.home

import android.widget.ImageView
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

    private var hasAnimatedBanner = false
    private var isBannerCollapsed = false
    private var bannerMeasuredHeight = 0

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
        setupScroll()
        collectState()
        collectEvents()
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        loadUnreadNotifications()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        loadUnreadNotifications()
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        
        // Re-trigger animation expansion on swipe back
        if (!hasAnimatedBanner) {
            animateBannerExpansion()
        }
    }

    override fun onPause() {
        super.onPause()
        // Reset state so it re-animates next time
        hasAnimatedBanner = false
        isBannerCollapsed = false
        if (_binding != null) {
            binding.bannerExpandable.visibility = View.INVISIBLE
            binding.bannerExpandable.alpha = 0f
            binding.bannerExpandable.layoutParams.height = 0
        }
    }

    private fun setupHeader() {
        val rawName = (session.userName ?: "User").ifBlank { "User" }
        val name = rawName.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
        binding.tvHeaderName.text = name
        binding.tvAvatarInitial.text = name.first().uppercase()
        binding.tvHeaderRole.text = if (session.isAdmin) "Administrator" else "Staff"
        loadHeaderDesignation()
    }

    private fun loadHeaderDesignation() {
        val staffId = session.staffId?.takeIf { it.isNotBlank() } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaffDetail(session.bearerToken, staffId)
                val staff = resp.staff ?: return@launch
                if (_binding == null) return@launch
                val role = listOfNotNull(
                    staff.designation?.takeIf { it.isNotBlank() },
                    staff.department?.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
                if (role.isNotBlank()) binding.tvHeaderRole.text = role
            } catch (_: Exception) { }
        }
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
        binding.btnViewSummary.setOnClickListener {
            Toast.makeText(requireContext(), "Opening Work Summary...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupScroll() {
        binding.homeContent.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > 50 && !isBannerCollapsed && bannerMeasuredHeight > 0) {
                collapseBanner()
            } else if (scrollY < 10 && isBannerCollapsed) {
                expandBanner()
            }
        })
    }

    private fun collapseBanner() {
        if (!isBannerCollapsed) {
            isBannerCollapsed = true
            val animator = android.animation.ValueAnimator.ofInt(binding.bannerExpandable.height, 0)
            animator.addUpdateListener { valueAnimator ->
                if (_binding == null) return@addUpdateListener
                val value = valueAnimator.animatedValue as Int
                val params = binding.bannerExpandable.layoutParams
                params.height = value
                binding.bannerExpandable.layoutParams = params
            }
            animator.duration = 300
            animator.start()
            binding.bannerExpandable.animate().alpha(0f).setDuration(200).start()
        }
    }

    private fun expandBanner() {
        if (isBannerCollapsed) {
            isBannerCollapsed = false
            val animator = android.animation.ValueAnimator.ofInt(0, bannerMeasuredHeight)
            animator.addUpdateListener { valueAnimator ->
                if (_binding == null) return@addUpdateListener
                val value = valueAnimator.animatedValue as Int
                val params = binding.bannerExpandable.layoutParams
                params.height = value
                binding.bannerExpandable.layoutParams = params
            }
            animator.duration = 300
            animator.start()
            binding.bannerExpandable.animate().alpha(1f).setDuration(300).start()
        }
    }

    private fun animateBannerExpansion() {
        if (hasAnimatedBanner || _binding == null) return
        hasAnimatedBanner = true

        binding.homeContentWrapper.visibility = View.VISIBLE
        binding.bannerExpandable.visibility = View.VISIBLE
        binding.bannerExpandable.alpha = 0f
        
        binding.bannerExpandable.post {
            if (_binding == null) return@post
            val widthSpec = View.MeasureSpec.makeMeasureSpec(binding.bannerExpandable.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            binding.bannerExpandable.measure(widthSpec, heightSpec)
            bannerMeasuredHeight = binding.bannerExpandable.measuredHeight

            if (bannerMeasuredHeight <= 0) {
                hasAnimatedBanner = false
                return@post
            }

            val animator = android.animation.ValueAnimator.ofInt(0, bannerMeasuredHeight)
            animator.addUpdateListener { valueAnimator ->
                if (_binding == null) return@addUpdateListener
                val value = valueAnimator.animatedValue as Int
                val params = binding.bannerExpandable.layoutParams
                params.height = value
                binding.bannerExpandable.layoutParams = params
            }
            
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    _binding?.bannerExpandable?.animate()?.alpha(1f)?.setDuration(400)?.start()
                }
            })
            
            animator.duration = 700
            animator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            animator.start()
        }
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is HomeUiState.Loading -> {
                            binding.homeLoading.visibility = View.VISIBLE
                        }
                        is HomeUiState.Loaded -> {
                            binding.homeLoading.visibility = View.GONE
                            binding.homeContentWrapper.visibility = View.VISIBLE
                            binding.homeContent.visibility = View.VISIBLE
                            animateBannerExpansion()
                            if (!viewModel.isVisitsLoading.value) {
                                renderVisitCard(state)
                            }
                        }
                        is HomeUiState.Error -> {
                            binding.homeLoading.visibility = View.GONE
                            binding.homeContentWrapper.visibility = View.VISIBLE
                            binding.homeContent.visibility = View.VISIBLE
                            binding.tvVisitCountBadge.visibility = View.GONE
                            binding.visitListContent.visibility = View.GONE
                            binding.visitEmptyContent.visibility = View.VISIBLE
                            binding.tvVisitEmptyTitle.text = "No Trips Available"
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

    private fun renderVisitCard(state: HomeUiState.Loaded) {
        val visits = state.todayVisits.filter { it.status != "cancelled" }
        val displayCount = visits.size

        if (displayCount > 0) {
            binding.tvVisitCountBadge.visibility = View.VISIBLE
            binding.tvVisitCountBadge.text = displayCount.toString()
        } else {
            binding.tvVisitCountBadge.visibility = View.GONE
        }

        if (displayCount == 0) {
            binding.visitListContent.visibility = View.GONE
            binding.visitEmptyContent.visibility = View.VISIBLE
            binding.tvVisitEmptyTitle.text = "No Visits Available"
            binding.tvVisitEmptySubtitle.text = visitEmptySubtitle
            return
        }

        binding.visitListContent.visibility = View.VISIBLE
        binding.visitEmptyContent.visibility = View.GONE
        binding.visitListContent.removeAllViews()

        visits.forEachIndexed { index, visit ->
            val itemView = createVisitItem(visit, index, displayCount, state.hasOpenSession)
            binding.visitListContent.addView(itemView)
        }
    }

    private fun createVisitItem(
        visit: TodayVisit,
        index: Int,
        total: Int,
        canStartTrip: Boolean,
    ): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, binding.visitListContent, false)
        val title = itemView.findViewById<TextView>(R.id.tvVisitItemTitle)
        val time = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        val actionBtn = itemView.findViewById<LinearLayout>(R.id.btnVisitItemAction)
        val action = itemView.findViewById<TextView>(R.id.tvVisitItemActionLabel)
        val actionIcon = itemView.findViewById<ImageView>(R.id.ivVisitItemActionIcon)
        val avatar = itemView.findViewById<TextView>(R.id.tvVisitItemAvatar)
        val staffName = itemView.findViewById<TextView>(R.id.tvVisitItemStaffName)
        val staffRole = itemView.findViewById<TextView>(R.id.tvVisitItemStaffRole)
        val statusPill = itemView.findViewById<LinearLayout>(R.id.visitItemStatusPill)
        val statusText = itemView.findViewById<TextView>(R.id.tvVisitItemStatus)
        val distance = itemView.findViewById<TextView>(R.id.tvVisitItemDistance)
        val eta = itemView.findViewById<TextView>(R.id.tvVisitItemEta)

        val clientName = visit.placeName ?: visit.leadName ?: "Scheduled Visit"
        bindTripCardHeader(avatar, staffName, staffRole, clientName)
        title.text = clientName
        time.text = formatVisitTimeOrDate(visit)
        distance.text = if (visit.placeLat != null && visit.placeLng != null) "Open route" else "Not mapped"

        val status = visit.status.lowercase(Locale.getDefault())
        val isCompleted = status in setOf("completed", "complete", "done", "closed")
        val isInProgress = status in setOf("in-progress", "in_progress", "ongoing", "started", "active", "arrived")

        when {
            isInProgress -> {
                statusText.text = if (status == "arrived") "Reaching" else "Enroute"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_progress)
                statusText.setTextColor(android.graphics.Color.parseColor("#B54708"))
                action.text = if (status == "arrived") "Complete Trip" else "Enroute"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_progress)
                action.setTextColor(android.graphics.Color.parseColor("#B54708"))
                actionIcon.visibility = View.GONE
                eta.text = if (status == "arrived") "At client place" else "Tracking"
            }
            isCompleted -> {
                statusText.text = "Complete"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_done)
                statusText.setTextColor(android.graphics.Color.parseColor("#475467"))
                action.text = "Complete"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_disabled)
                action.setTextColor(android.graphics.Color.parseColor("#475467"))
                actionIcon.visibility = View.GONE
                eta.text = "Complete"
            }
            !canStartTrip -> {
                statusText.text = "Clock in"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_done)
                statusText.setTextColor(android.graphics.Color.parseColor("#475467"))
                action.text = "Clock In First"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_disabled)
                action.setTextColor(android.graphics.Color.parseColor("#475467"))
                actionIcon.visibility = View.GONE
                eta.text = "After clock in"
            }
            else -> {
                statusText.text = "Start"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_ready)
                statusText.setTextColor(android.graphics.Color.parseColor("#169B2F"))
                action.text = "Start Trip"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_ready)
                action.setTextColor(android.graphics.Color.WHITE)
                actionIcon.visibility = View.VISIBLE
                eta.text = "After start"
            }
        }

        val canOpen = !isCompleted
        if (canOpen) {
            val openNav: (View) -> Unit = { openTripNavigationForVisit(visit) }
            itemView.setOnClickListener(openNav)
            actionBtn.setOnClickListener(openNav)
        }

        applyItemSpacing(itemView, index, total)
        return itemView
    }

    private fun bindTripCardHeader(avatar: TextView, nameView: TextView, roleView: TextView, clientName: String) {
        val name = formatPersonName(clientName.ifBlank { "Client" })
        avatar.text = name.firstOrNull()?.uppercase() ?: "M"
        nameView.text = name
        roleView.visibility = View.GONE
    }

    private fun formatPersonName(rawName: String): String {
        return rawName.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
            .ifBlank { "Client" }
    }

    private fun openTripNavigationForVisit(visit: TodayVisit) {
        val fragment = TripNavigationFragment.forVisit(
            visitId = visit.id,
            placeName = visit.placeName,
            placeAddress = visit.placeAddress,
            destLat = visit.placeLat,
            destLng = visit.placeLng,
            status = visit.status,
            tripType = visit.tripType,
            clientPlaceVisitId = visit.clientPlaceVisitId,
            cpClientMet = visit.cpVisit?.clientMet,
            cpOutcome = visit.cpVisit?.outcome,
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun applyItemSpacing(itemView: View, index: Int, total: Int) {
        val params = itemView.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = if (index == total - 1) 0 else dpToPx(10)
        itemView.layoutParams = params
    }

    private fun formatVisitTimeOrDate(visit: TodayVisit): String {
        val start = visit.scheduledStartTime?.let { formatTimeValue(it) }
        val end = visit.scheduledEndTime?.let { formatTimeValue(it) }
        if (!start.isNullOrBlank() && !end.isNullOrBlank()) return "$start - $end"
        if (!start.isNullOrBlank()) return start
        if (!end.isNullOrBlank()) return end
        val embeddedTime = visit.scheduledDate.let { formatTimeValue(it) }
        if (!embeddedTime.isNullOrBlank()) return embeddedTime
        return formatVisitDate(visit.scheduledDate)
    }

    private fun formatVisitDate(scheduledDate: String?): String {
        if (scheduledDate.isNullOrBlank()) return "Today"
        val parsed = runCatching {
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            iso.parse(scheduledDate.substringBefore(".").substringBefore("Z"))
        }.getOrNull() ?: return "Today"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed)
    }

    private fun formatTimeValue(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val isoMatch = Regex("""^\d{4}-\d{2}-\d{2}T(\d{2}):(\d{2})""").find(value)
        if (isoMatch != null) {
            val hour24 = isoMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = isoMatch.groupValues[2]
            val hour12 = if (hour24 == 0) 12 else if (hour24 > 12) hour24 - 12 else hour24
            val suffix = if (hour24 < 12) "AM" else "PM"
            return String.format(Locale.getDefault(), "%02d:%s %s", hour12, minute, suffix)
        }
        return null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun loadUnreadNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.getUnreadNotificationCount(session.bearerToken) }
                .onSuccess { response ->
                    if (_binding == null) return@onSuccess
                    binding.tvBellBadge.visibility = if (response.unreadCount > 0) View.VISIBLE else View.GONE
                    binding.tvBellBadge.text = if (response.unreadCount > 99) "99+" else response.unreadCount.toString()
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        hasAnimatedBanner = false
        isBannerCollapsed = false
        bannerMeasuredHeight = 0
    }
}
