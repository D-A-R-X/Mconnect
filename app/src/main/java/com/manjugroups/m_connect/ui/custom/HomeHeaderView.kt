package com.manjugroups.m_connect.ui.custom

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.ViewHomeHeaderBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import com.manjugroups.m_connect.ui.notifications.NotificationsFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HomeHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewHomeHeaderBinding = ViewHomeHeaderBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    private val session = SessionManager(context)
    private val api = ApiService.create()
    private val bannerFloatAnimators = mutableListOf<Animator>()
    private var viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isFleetBannerMode = false
    private var basePaddingTop: Int? = null

    private var onProfileClickListener: (() -> Unit)? = null
    private var onBellClickListener: (() -> Unit)? = null
    private var onViewSummaryClickListener: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.HomeHeaderView, 0, 0)
            val showProfile = typedArray.getBoolean(R.styleable.HomeHeaderView_hh_show_profile_row, true)
            val showBanner = typedArray.getBoolean(R.styleable.HomeHeaderView_hh_show_banner_card, true)
            val title = typedArray.getString(R.styleable.HomeHeaderView_hh_banner_title)
            val subtitle = typedArray.getString(R.styleable.HomeHeaderView_hh_banner_subtitle)
            val btnText = typedArray.getString(R.styleable.HomeHeaderView_hh_banner_button_text)
            val cardBg = typedArray.getResourceId(R.styleable.HomeHeaderView_hh_banner_card_background, -1)
            val illustration = typedArray.getResourceId(R.styleable.HomeHeaderView_hh_banner_illustration, -1)

            binding.homeProfileRow.visibility = if (showProfile) View.VISIBLE else View.GONE
            binding.cardWorkSummary.visibility = if (showBanner) View.VISIBLE else View.GONE
            if (title != null) binding.tvSummaryTitle.text = title
            if (subtitle != null) binding.tvSummarySubtitle.text = subtitle
            if (btnText != null) binding.tvBtnViewSummary.text = btnText

            if (cardBg != -1) {
                binding.cardWorkSummary.setBackgroundResource(cardBg)
                val params = binding.cardWorkSummary.layoutParams as? MarginLayoutParams
                if (params != null) {
                    val density = resources.displayMetrics.density
                    params.marginStart = (20 * density).toInt()
                    params.marginEnd = (20 * density).toInt()
                    binding.cardWorkSummary.layoutParams = params
                }
            }
            if (illustration != -1) {
                binding.ivCustomBannerIllustration.visibility = View.VISIBLE
                binding.ivCustomBannerIllustration.setImageResource(illustration)
                binding.bannerIllustrationContainer.visibility = View.GONE
            }

            typedArray.recycle()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (viewScope.coroutineContext[Job]?.isCancelled == true) {
            viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
        stopFloatingAnimation()
    }

    fun setProfileRowVisible(visible: Boolean) {
        binding.homeProfileRow.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setBannerCardVisible(visible: Boolean) {
        binding.cardWorkSummary.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setBannerTitle(title: String) {
        binding.tvSummaryTitle.text = title
    }

    fun setBannerSubtitle(subtitle: String) {
        binding.tvSummarySubtitle.text = subtitle
    }

    fun setBannerButtonText(text: String) {
        binding.tvBtnViewSummary.text = text
    }

    fun setFleetBannerMode() {
        isFleetBannerMode = true
        binding.bannerTextContent.visibility = View.GONE
        binding.bannerIllustrationContainer.visibility = View.GONE
        binding.ivCustomBannerIllustration.visibility = View.GONE
        binding.layoutFleetBanner.visibility = View.VISIBLE
    }

    /**
     * Drives the fleet banner's 3-dot indicator so it stays in lock-step with
     * the Pending / Assigned / Completed tab selection below the banner
     * (0 = Pending, 1 = Assigned, 2 = Completed). The active dot is white, the
     * rest grey.
     */
    fun setActiveFleetDot(index: Int) {
        val dots = listOf(binding.fleetDot0, binding.fleetDot1, binding.fleetDot2)
        dots.forEachIndexed { i, dot ->
            dot.setBackgroundResource(
                if (i == index) R.drawable.bg_dot_white else R.drawable.bg_dot_grey
            )
        }
    }

    fun setOnProfileClickListener(listener: () -> Unit) {
        onProfileClickListener = listener
    }

    fun setOnBellClickListener(listener: () -> Unit) {
        onBellClickListener = listener
    }

    fun setOnViewSummaryClickListener(listener: () -> Unit) {
        onViewSummaryClickListener = listener
    }

    fun setup(
        fragment: Fragment? = null,
        onDesignationChanged: (() -> Unit)? = null,
        onViewSummaryClick: (() -> Unit)? = null
    ) {
        // Apply status bar padding inset to the profile row
        if (basePaddingTop == null) {
            basePaddingTop = binding.homeProfileRow.paddingTop
        }
        val initialPaddingTop = basePaddingTop!!
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.homeHeaderContainer) { _, insets ->
            val topInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            binding.homeProfileRow.setPadding(
                binding.homeProfileRow.paddingStart,
                initialPaddingTop + topInset,
                binding.homeProfileRow.paddingEnd,
                binding.homeProfileRow.paddingBottom
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.homeHeaderContainer)

        // Set up click listeners for profile and notifications
        binding.btnHomeProfile.setOnClickListener {
            onProfileClickListener?.invoke() ?: run {
                val resolvedFragment = fragment ?: getFragment(this)
                resolvedFragment?.parentFragmentManager?.beginTransaction()
                    ?.replace(R.id.fragmentContainer, ProfileFragment())
                    ?.addToBackStack(null)
                    ?.commit() ?: run {
                        getFragmentActivity(context)?.supportFragmentManager?.beginTransaction()
                            ?.replace(R.id.fragmentContainer, ProfileFragment())
                            ?.addToBackStack(null)
                            ?.commit()
                    }
            }
        }

        binding.btnHomeBell.setOnClickListener {
            onBellClickListener?.invoke() ?: run {
                val resolvedFragment = fragment ?: getFragment(this)
                resolvedFragment?.parentFragmentManager?.beginTransaction()
                    ?.replace(R.id.fragmentContainer, NotificationsFragment())
                    ?.addToBackStack(null)
                    ?.commit() ?: run {
                        getFragmentActivity(context)?.supportFragmentManager?.beginTransaction()
                            ?.replace(R.id.fragmentContainer, NotificationsFragment())
                            ?.addToBackStack(null)
                            ?.commit()
                    }
            }
        }

        if (onViewSummaryClick != null) {
            onViewSummaryClickListener = onViewSummaryClick
        }
        binding.btnViewSummary.setOnClickListener {
            onViewSummaryClickListener?.invoke()
        }
        binding.btnFleetViewSummary.setOnClickListener {
            onViewSummaryClickListener?.invoke()
        }

        // Render basic data from session
        val rawName = (session.userName ?: "User").ifBlank { "User" }
        val name = rawName.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
        binding.tvHeaderName.text = name
        binding.tvAvatarInitial.text = name.first().uppercase().toString()
        binding.tvHeaderRole.text = when {
            session.designation
                ?.trim()
                ?.equals("External Fleet", ignoreCase = true) == true -> "Agency"
            session.isAdmin -> "Administrator"
            else -> "Staff"
        }

        applyAvatarPhoto(session.userPhotoUrl)
        // Skip the staff-detail refresh for external-fleet agency principals
        // — their session.staffId points at a travelAgencies row, not a real
        // staff record, so the call would fail and could blank the role.
        val isExternalFleet = session.designation
            ?.trim()
            ?.equals("External Fleet", ignoreCase = true) == true
        if (!isExternalFleet) {
            loadHeaderDesignation(onDesignationChanged)
        }
    }

    private fun getFragment(view: View): Fragment? {
        return try {
            androidx.fragment.app.FragmentManager.findFragment(view)
        } catch (_: Exception) {
            null
        }
    }

    private fun getFragmentActivity(context: Context): androidx.fragment.app.FragmentActivity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is androidx.fragment.app.FragmentActivity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }

    fun setBellBadgeCount(count: Int) {
        binding.tvBellBadge.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.tvBellBadge.text = if (count > 99) "99+" else count.toString()
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

    private fun loadHeaderDesignation(onDesignationChanged: (() -> Unit)?) {
        val staffId = session.staffId?.takeIf { it.isNotBlank() } ?: return
        viewScope.launch {
            try {
                val resp = api.getStaffDetail(session.bearerToken, staffId)
                val staff = resp.staff ?: return@launch
                val role = listOfNotNull(
                    staff.designation?.takeIf { it.isNotBlank() },
                    staff.department?.takeIf { it.isNotBlank() },
                ).joinToString(" • ")
                if (role.isNotBlank()) {
                    binding.tvHeaderRole.text = role
                }
                staff.designation?.takeIf { it.isNotBlank() }?.let {
                    val previous = session.isDriverMode
                    session.designation = it
                    if (previous != session.isDriverMode) {
                        onDesignationChanged?.invoke()
                    }
                }
                staff.photo?.takeIf { it.isNotBlank() }?.let { photo ->
                    session.userPhotoUrl = photo
                    applyAvatarPhoto(photo)
                }
            } catch (_: Exception) {
                // Ignore API call failure on soft header info
            }
        }
    }

    fun playEntryAnimation() {
        if (isFleetBannerMode) {
            playFleetEntryAnimation()
            return
        }
        // Cancel any in-flight property animations on banner pieces.
        val views = listOf(
            binding.homeProfileRow,
            binding.tvSummaryTitle, binding.tvSummarySubtitle, binding.btnViewSummary,
            binding.ivBannerMobile, binding.ivBannerProgress, binding.ivBannerSuitcase,
            binding.ivBannerGlitter
        )
        views.forEach { it.animate().cancel() }
        stopFloatingAnimation()

        // Reset transforms so the entry animation is deterministic on replays.
        views.forEach {
            it.translationY = 0f
            it.translationX = 0f
            it.alpha = 1f
            it.scaleX = 1f
            it.scaleY = 1f
        }

        val density = resources.displayMetrics.density
        val slideLeftPx = -30f * density   // text slides in from the left (-30dp)

        val easeOut = DecelerateInterpolator(2f)
        val emphasized = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        // 1. Profile row fade/slide down
        binding.homeProfileRow.alpha = 0f
        binding.homeProfileRow.translationY = -16f * density
        binding.homeProfileRow.animate()
            .alpha(1f).translationY(0f)
            .setDuration(360L)
            .setInterpolator(easeOut)
            .start()

        // 2. Banner text slides in from the left
        animateInFromLeft(binding.tvSummaryTitle, slideLeftPx, 120L, 380L, emphasized)
        animateInFromLeft(binding.tvSummarySubtitle, slideLeftPx, 200L, 380L, emphasized)
        animateInFromLeft(binding.btnViewSummary, slideLeftPx, 300L, 380L, emphasized)

        // 3. Right-side illustrations stagger in
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
        postDelayed({
            startFloatingAnimation()
        }, lastDelay + 480L)
    }

    private fun playFleetEntryAnimation() {
        val views = listOf(
            binding.homeProfileRow,
            binding.tvFleetTitle, binding.tvFleetSubtitle, binding.btnFleetViewSummary,
            binding.ivFleetVan, binding.fleetCardsContainer
        )
        views.forEach { it.animate().cancel() }
        stopFloatingAnimation()

        // Reset transforms
        views.forEach {
            it.translationY = 0f
            it.translationX = 0f
            it.alpha = 1f
            it.scaleX = 1f
            it.scaleY = 1f
        }

        val density = resources.displayMetrics.density
        val slideLeftPx = -30f * density

        val easeOut = DecelerateInterpolator(2f)
        val emphasized = PathInterpolator(0.4f, 0f, 0.2f, 1f)

        // 1. Profile row fade/slide down
        binding.homeProfileRow.alpha = 0f
        binding.homeProfileRow.translationY = -16f * density
        binding.homeProfileRow.animate()
            .alpha(1f).translationY(0f)
            .setDuration(360L)
            .setInterpolator(easeOut)
            .start()

        // 2. Fleet text slides in from left
        animateInFromLeft(binding.tvFleetTitle, slideLeftPx, 120L, 380L, emphasized)
        animateInFromLeft(binding.tvFleetSubtitle, slideLeftPx, 200L, 380L, emphasized)
        animateInFromLeft(binding.btnFleetViewSummary, slideLeftPx, 300L, 380L, emphasized)

        // 3. Composite illustration slides up and fades in
        binding.ivFleetVan.alpha = 0f
        binding.ivFleetVan.translationY = 20f * density
        binding.ivFleetVan.scaleX = 0.93f
        binding.ivFleetVan.scaleY = 0.93f
        binding.ivFleetVan.animate()
            .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(180L)
            .setDuration(500L)
            .setInterpolator(easeOut)
            .start()

        // 4. Cards container slide in from right
        binding.fleetCardsContainer.alpha = 0f
        binding.fleetCardsContainer.translationX = 32f * density
        binding.fleetCardsContainer.scaleX = 0.95f
        binding.fleetCardsContainer.scaleY = 0.95f
        binding.fleetCardsContainer.animate()
            .alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(300L)
            .setDuration(450L)
            .setInterpolator(easeOut)
            .start()

        postDelayed({
            startFloatingAnimation()
        }, 720L)
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

    fun startFloatingAnimation() {
        stopFloatingAnimation()
        val density = resources.displayMetrics.density
        if (isFleetBannerMode) {
            val animator = ObjectAnimator.ofFloat(
                binding.ivFleetVan, View.TRANSLATION_Y, 0f, -8f * density
            ).apply {
                duration = 2800L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
            animator.start()
            bannerFloatAnimators.add(animator)
            return
        }
        val floats = listOf(
            Triple(binding.ivBannerMobile, -6f, 750L),
            Triple(binding.ivBannerProgress, 5f, 500L),
            Triple(binding.ivBannerSuitcase, -5f, 1500L),
            Triple(binding.ivBannerGlitter, 4f, 1000L)
        )
        floats.forEach { (view, amplitudeDp, startDelay) ->
            val animator = ObjectAnimator.ofFloat(
                view, View.TRANSLATION_Y, 0f, amplitudeDp * density
            ).apply {
                duration = 2800L
                this.startDelay = startDelay
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }
            animator.start()
            bannerFloatAnimators.add(animator)
        }
    }

    fun stopFloatingAnimation() {
        bannerFloatAnimators.forEach { it.cancel() }
        bannerFloatAnimators.clear()
    }

    fun getHeaderBinding() = binding
}
