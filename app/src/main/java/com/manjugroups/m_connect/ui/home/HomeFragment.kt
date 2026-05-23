package com.manjugroups.m_connect.ui.home

import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentHomeBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AssignedPlace
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.notifications.NotificationsFragment
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import com.manjugroups.m_connect.ui.common.SkeletonUtils
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

    private var pendingEntryAnimation = true
    private val bannerFloatAnimators = mutableListOf<android.animation.ObjectAnimator>()

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

        applyStatusBarInset()
        setupHeader()
        setupActions()
        collectState()
        collectEvents()
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        loadUnreadNotifications()
        startBannerAnimation()
    }

    private fun applyStatusBarInset() {
        val basePaddingTop = binding.homeProfileRow.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.homeHeaderContainer) { _, insets ->
            val b = _binding ?: return@setOnApplyWindowInsetsListener insets
            val topInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            b.homeProfileRow.setPadding(
                b.homeProfileRow.paddingStart,
                basePaddingTop + topInset,
                b.homeProfileRow.paddingEnd,
                b.homeProfileRow.paddingBottom
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.homeHeaderContainer)
    }

    private fun startBannerAnimation() {
        val anim = binding.ivBannerAnimation.drawable as? android.graphics.drawable.AnimationDrawable
        anim?.start()
    }

    override fun onResume() {
        super.onResume()
        // Defensive: restore tab bar in case a child fragment hid it.
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
            Color.parseColor("#0B61CA"),
            false,
            fullBleed = true
        )
        loadUnreadNotifications()
        // Refresh attendance and visits — covers biometric punches and returning from trips.
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
        // Pull the staff record so a profile photo updated from web/iOS
        // appears here too. ProfilePhotos.resolve rebuilds the serve URL
        // from the current BASE_URL on every render, so cached photos
        // never stick to an old domain.
        applyAvatarPhoto(session.userPhotoUrl)
        loadHeaderDesignation()
        // Replay the stagger when returning to the Home tab (either from a child
        // fragment via back, or after pop-back from another tab via show/hide).
        if (_binding != null && binding.homeContent.visibility == View.VISIBLE) {
            binding.homeContent.post { playHomeEntryAnimation() }
        }
        startBannerAnimation()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            stopBannerFloatingAnimation()
        } else if (_binding != null &&
            binding.homeContent.visibility == View.VISIBLE) {
            binding.homeContent.post { playHomeEntryAnimation() }
        }
    }

    private fun playHomeEntryAnimation() {
        if (_binding == null) return

        // Cancel any in-flight property animations on banner pieces.
        val views = listOf(
            binding.homeProfileRow, binding.whiteContentArea,
            binding.tvSummaryTitle, binding.tvSummarySubtitle, binding.btnViewSummary,
            binding.ivBannerMobile, binding.ivBannerProgress, binding.ivBannerSuitcase,
            binding.ivBannerGlitter
        )
        views.forEach { it.animate().cancel() }
        stopBannerFloatingAnimation()

        // Reset transforms so the entry animation is deterministic on replays.
        views.forEach {
            it.translationY = 0f
            it.translationX = 0f
            it.alpha = 1f
            it.scaleX = 1f
            it.scaleY = 1f
        }

        val ctx = binding.root.context
        val density = ctx.resources.displayMetrics.density
        val slideLeftPx = -30f * density   // text slides in from the left (-30dp)

        val easeOut = android.view.animation.DecelerateInterpolator(2f)
        val emphasized = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

        // 1. Profile row fade/slide down (Frame 1 → Frame 2)
        binding.homeProfileRow.alpha = 0f
        binding.homeProfileRow.translationY = -16f * density
        binding.homeProfileRow.animate()
            .alpha(1f).translationY(0f)
            .setDuration(360L)
            .setInterpolator(easeOut)
            .start()

        // 2. White curtain descends to reveal the banner (Frame 1 → Frame 3 reveal)
        // Starts immediately so the banner is exposed by the time pieces enter their final spots.
        binding.whiteContentArea.animate().cancel()
        binding.whiteContentArea.translationY = -150f * density
        binding.whiteContentArea.animate()
            .translationY(0f)
            .setDuration(720L)
            .setInterpolator(easeOut)
            .start()

        // 3. Banner text slides in from the left while the curtain is descending
        animateInFromLeft(binding.tvSummaryTitle, slideLeftPx, 120L, 380L, emphasized)
        animateInFromLeft(binding.tvSummarySubtitle, slideLeftPx, 200L, 380L, emphasized)
        animateInFromLeft(binding.btnViewSummary, slideLeftPx, 300L, 380L, emphasized)

        // 4. Right-side illustrations stagger in (start earlier so they're settled by ~720ms when curtain lands)
        val rightSide = listOf(
            binding.ivBannerGlitter to 160L,   // back layer first
            binding.ivBannerMobile to 200L,
            binding.ivBannerProgress to 280L,
            binding.ivBannerSuitcase to 360L
        )
        var lastDelay = 0L
        rightSide.forEach { (v, delay) ->
            v.alpha = 0f
            v.translationX = 32f * density
            v.translationY = 12f * density
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.animate()
                .alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay)
                .setDuration(420L)
                .setInterpolator(easeOut)
                .start()
            if (delay > lastDelay) lastDelay = delay
        }

        // Kick off the continuous floating loop once the cluster has landed.
        binding.root.postDelayed({
            if (_binding != null) startBannerFloatingAnimation()
        }, lastDelay + 480L)
    }

    private fun animateInFromLeft(
        v: View,
        startX: Float,
        delay: Long,
        duration: Long,
        interpolator: android.view.animation.Interpolator
    ) {
        v.alpha = 0f
        v.translationX = startX
        v.animate()
            .alpha(1f).translationX(0f)
            .setStartDelay(delay)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
    }

    private fun startBannerFloatingAnimation() {
        if (_binding == null) return
        stopBannerFloatingAnimation()
        val density = binding.root.context.resources.displayMetrics.density
        // (view, amplitudeDp, delayMs) — mirrors the reference's y bobbing keyframes
        // and per-element delays. Mobile, progress, and suitcase each bob with 
        // a different rhythm.
        val floats = listOf(
            Triple(binding.ivBannerMobile, -6f, 750L),
            Triple(binding.ivBannerProgress, 5f, 500L),
            Triple(binding.ivBannerSuitcase, -5f, 1500L),
            Triple(binding.ivBannerGlitter, 4f, 1000L)
        )
        floats.forEach { (view, amplitudeDp, startDelay) ->
            val animator = android.animation.ObjectAnimator.ofFloat(
                view, View.TRANSLATION_Y, 0f, amplitudeDp * density
            ).apply {
                duration = 2800L
                this.startDelay = startDelay
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            }
            animator.start()
            bannerFloatAnimators.add(animator)
        }
    }

    private fun stopBannerFloatingAnimation() {
        bannerFloatAnimators.forEach { it.cancel() }
        bannerFloatAnimators.clear()
    }

    private fun setupHeader() {
        val rawName = (session.userName ?: "User").ifBlank { "User" }
        val name = rawName.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
        binding.tvHeaderName.text = name
        binding.tvAvatarInitial.text = name.first().uppercase()
        applyAvatarPhoto(session.userPhotoUrl)
        binding.tvHeaderRole.text =
            if (session.isAdmin) "Administrator" else "Staff"
        loadHeaderDesignation()
    }

    private fun applyAvatarPhoto(url: String?) {
        val resolved = ProfilePhotos.resolve(url)
        if (resolved == null) {
            binding.ivHomeAvatar.setImageDrawable(null)
            binding.tvAvatarInitial.visibility = View.VISIBLE
            return
        }
        binding.tvAvatarInitial.visibility = View.GONE
        binding.ivHomeAvatar.load(resolved) {
            crossfade(true)
            transformations(CircleCropTransformation())
        }
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
                staff.photo?.takeIf { it.isNotBlank() }?.let { photo ->
                    session.userPhotoUrl = photo
                    applyAvatarPhoto(photo)
                }
            } catch (_: Exception) {
                // Keep the fallback; not worth a toast on a soft header field.
            }
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
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    when (state) {
                        is HomeUiState.Loading -> {
                            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
                            binding.homeContent.visibility = View.GONE
                        }

                        is HomeUiState.Loaded -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                            binding.homeContent.visibility = View.VISIBLE
                            renderSummary()
                            if (!viewModel.isVisitsLoading.value) {
                                renderVisitCard(state)
                            }
                            if (pendingEntryAnimation) {
                                pendingEntryAnimation = false
                                binding.homeContent.post { playHomeEntryAnimation() }
                            }
                        }

                        is HomeUiState.Error -> {
                            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                            binding.homeContent.visibility = View.VISIBLE
                            binding.tvSummarySubtitle.text = "Today task & presence activity"
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

    private fun renderSummary() {
        // As per the provided frames, the banner text is static: "Plan, Visit & Achieve"
        // But we can update the subtitle or other elements if needed.
        // For now, keeping it consistent with the image.
    }

    private fun renderVisitCard(state: HomeUiState.Loaded) {
        // Home shows today's visits only.
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
            // Match Frame 4 text exactly
            binding.tvVisitEmptyTitle.text = "No Trips Available"
            binding.tvVisitEmptySubtitle.text = "It looks like you don't have any meetings scheduled at the moment.\nThis space will be updated as new meetings are added!"
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
        val lead = itemView.findViewById<TextView>(R.id.tvVisitItemLead)
        val avatar = itemView.findViewById<TextView>(R.id.tvVisitItemAvatar)
        val staffName = itemView.findViewById<TextView>(R.id.tvVisitItemStaffName)
        val staffRole = itemView.findViewById<TextView>(R.id.tvVisitItemStaffRole)
        val statusPill = itemView.findViewById<LinearLayout>(R.id.visitItemStatusPill)
        val statusText = itemView.findViewById<TextView>(R.id.tvVisitItemStatus)
        val distance = itemView.findViewById<TextView>(R.id.tvVisitItemDistance)
        val eta = itemView.findViewById<TextView>(R.id.tvVisitItemEta)

        val clientName = visit.placeName ?: visit.leadName ?: "Scheduled Visit"
        // Client name lives in the header (avatar + staffName) only — the
        // body's left cell now shows the visit Type ("Direct CP" / "SV
        // confirmation CP" / etc.) instead of repeating the client name.
        bindTripCardHeader(avatar, staffName, staffRole, clientName)
        time.text = formatVisitTimeOrDate(visit)
        distance.text = if (visit.placeLat != null && visit.placeLng != null) "Open route" else "Not mapped"

        val isCpVisit = visit.clientPlaceVisitId != null
        // Surface the visit category so the field staff can tell at a
        // glance which lane this row belongs to before tapping in.
        // "sv_cum_cp" rows open into the locked Reject/Confirm sheet on
        // the trip nav; "direct_cp" rows open the full outcome flow.
        val categoryLabel = when (visit.visitCategory) {
            "sv_cum_cp" -> "SV confirmation CP"
            "direct_cp" -> "Direct CP"
            "site_visit" -> "Site Visit"
            else -> if (isCpVisit) "CP visit" else "Visit"
        }
        // Bind category into the body's Type cell. The standalone
        // tvVisitItemLead badge below the grid is no longer needed.
        title.text = categoryLabel
        lead.visibility = View.GONE

        val status = visit.status.lowercase(Locale.getDefault())
        val isCompleted = status in setOf("completed", "complete", "done", "closed")
        val needsCpDetails = isCpVisit && status == "arrived" && visit.cpVisit?.outcome.isNullOrBlank()
        val isInProgress = status in setOf(
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
        )

        when {
            needsCpDetails -> {
                statusText.text = "Reaching"
                statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_progress)
                statusText.setTextColor(android.graphics.Color.parseColor("#B54708"))
                action.text = "Complete Trip"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_ready)
                action.setTextColor(android.graphics.Color.WHITE)
                actionIcon.visibility = View.VISIBLE
                eta.text = "Within ${visit.reachingRadiusMeters ?: 500}m"
            }
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

        if (isCompleted) {
            // Completed visits open a read-only summary instead of the trip flow.
            val openDetail: (View) -> Unit = { openCompletedVisitDetail(visit) }
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(openDetail)
            actionBtn.isClickable = true
            actionBtn.setOnClickListener(openDetail)
        } else {
            val openNav: (View) -> Unit = { openTripNavigationForVisit(visit) }
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(openNav)
            actionBtn.isClickable = true
            actionBtn.setOnClickListener(openNav)
        }

        applyItemSpacing(itemView, index, total)
        return itemView
    }

    private fun createAssignedPlaceItem(place: AssignedPlace, index: Int, total: Int): View {
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

        bindTripCardHeader(avatar, staffName, staffRole, place.name)
        // Place name is already shown in the header — body Type cell calls
        // out the row kind ("Assigned place") instead of repeating it.
        title.text = "Assigned place"
        time.text = "Available Today"
        distance.text = if (place.lat != null && place.lng != null) "Open route" else "Not mapped"
        eta.text = "After start"
        statusText.text = "Ready"
        statusPill.background = requireContext().getDrawable(R.drawable.bg_home_trip_status_ready)
        statusText.setTextColor(android.graphics.Color.parseColor("#169B2F"))
        action.text = "Start Trip"
        actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_trip_action_ready)
        action.setTextColor(android.graphics.Color.WHITE)
        actionIcon.visibility = View.VISIBLE

        val openNav: (View) -> Unit = { openTripNavigationForPlace(place) }
        itemView.isClickable = true
        itemView.isFocusable = true
        itemView.setOnClickListener(openNav)
        actionBtn.isClickable = true
        actionBtn.setOnClickListener(openNav)

        applyItemSpacing(itemView, index, total)
        return itemView
    }

    private fun bindTripCardHeader(
        avatar: TextView,
        nameView: TextView,
        roleView: TextView,
        clientName: String,
    ) {
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
            visitCategory = visit.visitCategory,
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openCompletedVisitDetail(visit: TodayVisit) {
        val fragment = com.manjugroups.m_connect.ui.marketing
            .CompletedVisitDetailFragment.forVisit(visit)
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
            // Try ISO 8601 first, then plain date
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            iso.isLenient = false
            iso.parse(scheduledDate.substringBefore(".").substringBefore("Z"))
        }.getOrNull() ?: runCatching {
            val plain = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            plain.isLenient = false
            plain.parse(scheduledDate.take(10))
        }.getOrNull() ?: return "Today"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed)
    }

    private fun formatTimeValue(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null

        // ISO 8601 datetime: extract time after the 'T' separator before any regex
        // (word-boundary \b fails between 'T' and a digit, and timezone '+HH:MM' can false-match)
        val isoMatch = Regex("""^\d{4}-\d{2}-\d{2}T(\d{2}):(\d{2})""").find(value)
        if (isoMatch != null) {
            val hour24 = isoMatch.groupValues[1].toIntOrNull() ?: return null
            val minute = isoMatch.groupValues[2]
            val hour12 = when {
                hour24 == 0 -> 12
                hour24 > 12 -> hour24 - 12
                else -> hour24
            }
            val suffix = if (hour24 < 12) "AM" else "PM"
            return String.format(Locale.getDefault(), "%02d:%s %s", hour12, minute, suffix)
        }

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
        SkeletonUtils.stopAll()
        stopBannerFloatingAnimation()
        super.onDestroyView()
        _binding = null
    }
}
