package com.manjugroups.m_connect.ui.hr

import android.app.Activity
import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import com.manjugroups.m_connect.ui.common.applyShrinkableBlueHeaderBackground
import com.manjugroups.m_connect.ui.common.dismissRefresh
import com.manjugroups.m_connect.ui.common.setBottomCornerRadius
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentHrDashboardBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.AttendanceRecord
import com.manjugroups.m_connect.network.HomeFenceData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Calendar

class HrDashboardFragment : Fragment() {

    private var _binding: FragmentHrDashboardBinding? = null
    private val binding get() = _binding!!
    private val flowViewModel: AttendanceFlowViewModel by activityViewModels()
    private val api = ApiService.create()
    private lateinit var session: SessionManager

    // Dates (yyyy-MM-dd) for which the user just submitted a remark/correction
    // from a day card on this screen. Drives the "Remark Submitted" badge so
    // the affordance reflects the pending request until the list reloads with
    // server-side state. Mirrors AttendanceHistoryFragment.
    private val submittedRemarkDates = mutableSetOf<String>()

    // Calendar.DAY_OF_WEEK values that are the staff's weekly-off, resolved
    // from their shift schedule. Gap days (no attendance row) that fall on one
    // of these render "Week Off" instead of defaulting to "Absent".
    private var weekoffWeekdays: Set<Int> = emptySet()
    private var pendingPunchMode: PunchMode? = null
    private var pendingPunchImageFile: File? = null
    private var pendingPunchImageUri: android.net.Uri? = null

    /**
     * Becomes true the first time the attendance card has rendered with real
     * (non-skeleton) content. Subsequent `state.isLoading=true` emissions —
     * e.g. the brief refresh after a punch — won't wipe the card to skeleton
     * again. Prevents the "whole container disappears and comes back"
     * flicker after a successful clock-in / clock-out.
     */
    private var hasShownAttendanceContentOnce = false
    private var isLaunchingCamera = false
    private var isTodayLoading = true
    private var isHistoryLoading = true
    private var wasShowingSkeleton = false

    /**
     * Becomes true once the *initial* attendance load (today + history) has
     * fully resolved at least once. After that point the full skeleton is
     * never shown again: refreshes triggered by tab switches, pull-to-refresh
     * or post-punch reloads keep the clock-in summary card on screen and just
     * update its contents in place. Without this gate every `isHistoryLoading`
     * cycle wiped `cardAttendanceSummary` to skeleton, so the whole clock-in
     * section vanished and popped back on each refresh / tab return.
     */
    private var hasCompletedFirstAttendanceLoad = false
    private var recentHistoryRecords: List<AttendanceRecord> = emptyList()
    private var liveTickerJob: Job? = null
    // Fires just after midnight to reload attendance so the clocked-out Clock
    // Out lock clears for the new day.
    private var midnightRefreshJob: Job? = null
    // Reference to the dynamic "Today" history card's hours TextView, so the
    // live ticker can update it each second. Reset whenever the history list
    // is rebuilt.
    private var todayCardHoursView: TextView? = null

    // Home geofence enforcement. The web's hr.attendance.homeBlockEnabled
    // setting blocks punch-IN from inside the staff's home radius — instead
    // of letting them tap and bounce off a server error, we mirror the
    // check client-side and disable the button preemptively.
    //   homeFence            — last snapshot pulled from /home-fence; null
    //                          until the first fetch, treated as "no block"
    //                          while loading so we never falsely disable.
    //   isInsideHomeFence    — last computed boolean; drives both the
    //                          button enable state and the subtitle.
    //   geofenceWatcherJob   — re-checks distance every few seconds while
    //                          the user is on the dashboard.
    private var homeFence: HomeFenceData? = null
    private var isInsideHomeFence: Boolean = false
    private var geofenceWatcherJob: Job? = null
    // Live diagnostic for the fence — captured each watcher tick so the
    // subtitle can surface WHY enforcement isn't firing when staff
    // expect it (policy off / no GPS / outside radius / etc.).
    private var lastFenceStatus: String? = null

    private var pendingEntryAnimation = true
    private var hasPlayedEntryAnimation = false

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraGranted && (fineGranted || coarseGranted)) {
            launchSystemCamera()
        } else {
            isLaunchingCamera = false
            Toast.makeText(
                requireContext(),
                "Camera and GPS permissions are required for punch.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val captureSelfieLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val imageUri = pendingPunchImageUri
        if (imageUri != null) {
            runCatching {
                requireContext().revokeUriPermission(
                    imageUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }

        val mode = pendingPunchMode
        val imageFile = pendingPunchImageFile
        if (mode == null || imageFile == null) {
            isLaunchingCamera = false
            return@registerForActivityResult
        }

        val success = result.resultCode == Activity.RESULT_OK
        if (!success) {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "Selfie capture cancelled.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        if (!imageFile.exists()) {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "Failed to read captured selfie.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchLocationOrNull()
            if (location == null) {
                isLaunchingCamera = false
                Toast.makeText(
                    requireContext(),
                    "Unable to fetch GPS location. Please try again in open sky.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val address = resolveAddress(location)
            isLaunchingCamera = false
            navigateToPunchDetail(
                mode = mode,
                photoPath = imageFile.absolutePath,
                latitude = location.latitude,
                longitude = location.longitude,
                address = address,
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHrDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        updateAttendanceLoadingUi()

        binding.hrScroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 10) {
                (activity as? com.manjugroups.m_connect.MainActivity)?.setBottomNavScrollState(false)
            } else if (scrollY <= 10) {
                (activity as? com.manjugroups.m_connect.MainActivity)?.setBottomNavScrollState(true)
            }
        })

        binding.btnClockInNow.setOnClickListener {
            // Button stays active visually so the user can always reach
            // for it. If they're inside their home fence, refuse the tap
            // and show the "You are at Home!" warning dialog.
            if (isInsideHomeFence) {
                HomeFenceWarningDialog.show(parentFragmentManager)
                return@setOnClickListener
            }
            parentFragmentManager.beginTransaction()
                .applySmoothTransitions()
                .replace(R.id.fragmentContainer, ClockInAreaFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnOnDutyDisabled.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "Please clock in first to start on duty.",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnOnDuty.setOnClickListener {
            if (session.isOnDuty) {
                completeOnDutyTrip()
            } else {
                OnDutyFormBottomSheet.newInstance().show(parentFragmentManager, "on_duty_form")
            }
        }

        parentFragmentManager.setFragmentResultListener(
            OnDutyFormBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(OnDutyFormBottomSheet.KEY_STARTED, false)) {
                updateOnDutyButtonUi()
                val isClockedIn = flowViewModel.uiState.value.isClockedIn
                updateHeaderTexts(isClockedIn, animateDynamicIsland = true)
            }
        }

        // Public Transport on-duty routes through the proof sheet; once the
        // travel proof is uploaded it hands the storageIds + note back here
        // and we close the trip with them attached.
        parentFragmentManager.setFragmentResultListener(
            OnDutyProofBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val ids = bundle.getStringArrayList(OnDutyProofBottomSheet.KEY_PROOF_IDS)
            val note = bundle.getString(OnDutyProofBottomSheet.KEY_PROOF_NOTE)
            finishOnDuty(proofPhotoIds = ids, proofNote = note)
        }

        binding.btnClockOut.setOnClickListener {
            // Show the SAME "Today" figure the dashboard card displays.
            // While the session is open the API's todayMinutes is 0 (it
            // only sums CLOSED sessions), so reading it directly made the
            // sheet say 00:00:00 even after hours on the clock. The card
            // instead runs a live ticker of `now - firstPunchIn`; mirror
            // that here so the confirm sheet and the card always agree.
            // Once clocked out, the ticker stops and todayMinutes holds
            // the real total, so we fall back to it. Standard workday =
            // 8h (480m); anything above lands in the overtime bucket.
            val state = flowViewModel.uiState.value
            val firstIso = state.firstPunchInIso
            val today = if (state.isClockedIn && !firstIso.isNullOrBlank()) {
                val firstMs = parseIsoMillisOrNull(firstIso)
                if (firstMs != null) {
                    ((System.currentTimeMillis() - firstMs).coerceAtLeast(0) / 60_000L).toInt()
                } else {
                    state.todayMinutes
                }
            } else {
                state.todayMinutes
            }
            val overtime = (today - 480).coerceAtLeast(0)
            ClockOutConfirmBottomSheet.newInstance(
                todayMinutes = today,
                overtimeMinutes = overtime,
            ).show(parentFragmentManager, "clock_out_confirm")
        }

        parentFragmentManager.setFragmentResultListener(
            ClockOutConfirmBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(ClockOutConfirmBottomSheet.KEY_CONFIRMED, false)) {
                beginPunchCapture(PunchMode.PUNCH_OUT)
            }
        }

        parentFragmentManager.setFragmentResultListener(
            SelfieClockInDetailFragment.RESULT_KEY_PUNCH_COMPLETED,
            viewLifecycleOwner,
        ) { _, bundle ->
            val rawMode = bundle.getString(SelfieClockInDetailFragment.KEY_MODE)
            val mode = runCatching { PunchMode.valueOf(rawMode ?: "") }.getOrNull()
            when (mode) {
                PunchMode.PUNCH_OUT -> {
                    session.clearOnDutyDetails()
                    ClockOutSuccessBottomSheet()
                        .show(parentFragmentManager, "clock_out_success")
                }
                PunchMode.PUNCH_IN -> {
                    ClockInSuccessBottomSheet().show(parentFragmentManager, "clock_in_success")
                }
                null -> Unit
            }
        }

        // Reload the attendance cards after a correction/remark request is
        // submitted from a day card's edit icon.
        parentFragmentManager.setFragmentResultListener(
            EditAttendanceBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            if (bundle.getBoolean(EditAttendanceBottomSheet.KEY_SUBMITTED, false)) {
                bundle.getString("date")?.let { submittedRemarkDates.add(it) }
                loadRecentHistoryCards()
            }
        }

        collectState()
        collectEvents()
        flowViewModel.loadTodayAttendance(session.bearerToken)
        loadRecentHistoryCards()

        // Pull-to-refresh: same two loads as the initial open. There's no
        // single completion signal across both calls, so dismiss after a
        // short delay — feels instant on cached data and honest on slow
        // networks.
        binding.hrRefresh.setupPullToRefresh {
            flowViewModel.loadTodayAttendance(session.bearerToken)
            loadRecentHistoryCards()
            binding.hrRefresh.postDelayed({ _binding?.hrRefresh?.dismissRefresh() }, 800)
        }

        // Header pieces start hidden — content pieces are wired to enter once their
        // skeleton drops in updateAttendanceLoadingUi.
        primeHeaderForEntryAnimation()
        binding.attendanceHeader.post { playHeaderEntryAnimation() }

        setupAttendanceScrollAnimation()
    }

    /**
     * Collapsing-header effect — same pattern as App Library:
     *  - subtitle + illustration fade out as the user scrolls down
     *  - the header shrinks from 172dp → 120dp (just enough to keep
     *    the title at marginTop=71dp + ~30dp content + ~16dp padding
     *    visible without the title clipping or crossing the status
     *    bar)
     *  - content cards scroll up underneath the sticky header.
     * Effect saturates within ~140dp of scroll.
     */
    /**
     * "Panel slides up over fixed header" scroll effect.
     * The blue attendance header stays fixed at full size; the scroll
     * panel translates UP over it as the user scrolls. The header's
     * rounded bottom corners flatten in step with the slide.
     */
    private fun setupAttendanceScrollAnimation() {
        val density = binding.root.resources.displayMetrics.density
        val maxPanelRadiusPx = 30f * density

        // Blue header — full rectangular background (no rounded bottom corners).
        binding.attendanceHeader.applyShrinkableBlueHeaderBackground()

        // White card — rounded TOP corners + white bg applied to
        // `hrWhitePanel`, which lives INSIDE the NestedScrollView. The
        // white card IS the scrolling content, so it moves up at
        // exactly 1× rate (no parallax, no shrinking, no separate
        // translation). The ancestors set clipChildren=false in XML so
        // the rounded top edge can draw OUTSIDE the panel and over the
        // blue header — that's how the white visually "overlays" the
        // blue as it scrolls up.
        val whiteCardBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(android.graphics.Color.WHITE)
            cornerRadii = floatArrayOf(
                maxPanelRadiusPx, maxPanelRadiusPx, // top-left
                maxPanelRadiusPx, maxPanelRadiusPx, // top-right
                0f, 0f,                              // bottom-right
                0f, 0f,                              // bottom-left
            )
        }
        binding.hrWhitePanel.background = whiteCardBg
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) {
            // Replay when returning to the Attendance tab.
            primeHeaderForEntryAnimation()
            binding.attendanceHeader.post { playHeaderEntryAnimation() }
            // Re-trigger content entry if data is already loaded.
            if (!isTodayLoading && !isHistoryLoading) {
                pendingEntryAnimation = true
                binding.root.post { playContentEntryAnimation() }
            }
        }
    }

    private fun primeHeaderForEntryAnimation() {
        if (_binding == null) return
        val density = binding.root.resources.displayMetrics.density
        binding.attendanceHeaderTextGroup.animate().cancel()
        binding.attendanceHeaderTextGroup.alpha = 0f
        binding.attendanceHeaderTextGroup.translationX = -30f * density
        binding.ivAttendanceHeaderIllustration.animate().cancel()
        binding.ivAttendanceHeaderIllustration.alpha = 0f
        binding.ivAttendanceHeaderIllustration.translationX = 30f * density
        binding.ivAttendanceHeaderIllustration.scaleX = 0.85f
        binding.ivAttendanceHeaderIllustration.scaleY = 0.85f
    }

    private fun playHeaderEntryAnimation() {
        if (_binding == null) return
        val emphasized = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
        val expoOut = android.view.animation.PathInterpolator(0.19f, 1f, 0.22f, 1f)

        binding.attendanceHeaderTextGroup.animate()
            .alpha(1f).translationX(0f)
            .setStartDelay(80L)
            .setDuration(420L)
            .setInterpolator(emphasized)
            .start()

        binding.ivAttendanceHeaderIllustration.animate()
            .alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(200L)
            .setDuration(520L)
            .setInterpolator(expoOut)
            .start()
    }

    private fun playContentEntryAnimation() {
        if (_binding == null || !pendingEntryAnimation) return
        pendingEntryAnimation = false
        val density = binding.root.resources.displayMetrics.density
        val riseTravel = 32f * density
        val easeOut = android.view.animation.DecelerateInterpolator(1.5f)

        // Cards rise from below in a stagger — same "ascending content" feel as the
        // Home curtain + Apps section reveal.
        val cards = listOfNotNull(
            binding.cardAttendanceSummary.takeIf { it.visibility == View.VISIBLE },
            binding.cardHistory1.takeIf { it.visibility == View.VISIBLE },
            binding.cardHistory2.takeIf { it.visibility == View.VISIBLE },
            binding.cardHistory3.takeIf { it.visibility == View.VISIBLE },
        )
        cards.forEachIndexed { index, card ->
            card.animate().cancel()
            card.alpha = 0f
            card.translationY = riseTravel
            card.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L + index * 90L)
                .setDuration(440L)
                .setInterpolator(easeOut)
                .start()
        }
        hasPlayedEntryAnimation = true
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(true)
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#0B61CA"), false, fullBleed = true)
        flowViewModel.loadTodayAttendance(session.bearerToken)
        loadRecentHistoryCards()
        // Refresh the geofence policy each time the dashboard becomes
        // visible (so an HR-side radius change picks up without restart),
        // then start the periodic watcher.
        loadHomeFence()
        startGeofenceWatcher()
    }

    override fun onPause() {
        super.onPause()
        stopGeofenceWatcher()
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flowViewModel.uiState.collect { state ->
                    // Only show the full skeleton on the FIRST load. Subsequent
                    // isLoading=true emissions (refresh after a punch, etc.)
                    // should update content in place — otherwise the whole
                    // attendance card vanishes briefly and the user sees a
                    // jarring blank-and-pop after Take a Break / Resume.
                    isTodayLoading = state.isLoading && !hasShownAttendanceContentOnce
                    updateAttendanceLoadingUi()
                    if (!state.isLoading) hasShownAttendanceContentOnce = true
                    binding.tvTodayHours.text = state.todayHours
                    // The old static "Today" card (cardHistory1) is permanently
                    // hidden — today's log is now injected as the FIRST item in
                    // the history list below, in the same card style as past
                    // days, tagged "Today". See bindRecentHistoryCards.
                    binding.cardHistory1.visibility = View.GONE
                    binding.tvPayPeriodLabel.text = state.payPeriodLabel
                        .ifBlank { binding.tvPayPeriodLabel.text }
                    binding.tvPayPeriodHours.text = state.payPeriodHours

                    // The top "Clocked in" box was moved down into the
                    // history strip: the "Today" log card now carries the
                    // clock-in time alongside the other day logs, so this
                    // summary box stays hidden to avoid showing the same
                    // info twice.
                    binding.todayPunchSummary.visibility = View.GONE
                    binding.btnClockInNow.isEnabled = !state.isLoading && !state.isSubmitting
                    binding.btnClockOut.isEnabled = !state.isLoading && !state.isSubmitting

                    // Mobile clock button — three states driven by
                    // clockedOutOnMobile (the person's LATEST attendance event
                    // is a *mobile* clock-out):
                    //   1. No punch today → "Clock In Now" (mobile clock-in).
                    //   2. On the clock (punched in via biometric OR mobile, and
                    //      no mobile clock-out is the latest event) → enabled
                    //      "Clock Out". Internal biometric gate punches keep it
                    //      here (a gate punch-out never clocks him out).
                    //   3. Latest event IS a mobile clock-out → disabled
                    //      "Clocked Out" (tracking stopped). A later biometric
                    //      gate punch (in OR out) becomes the latest event and
                    //      returns to state 2 — so he can clock out again
                    //      whenever he wants.
                    if (state.hasClockedInToday && !state.clockedOutOnMobile) {
                        binding.clockInButtonGroup.visibility = View.GONE
                        binding.clockedInButtonGroup.visibility = View.VISIBLE
                        binding.btnOnDuty.visibility = View.VISIBLE
                        binding.btnClockOut.isEnabled = !state.isSubmitting
                        binding.btnClockOut.text = "Clock Out"
                        binding.btnClockOut.setTextColor(Color.WHITE)
                        binding.btnClockOut.setBackgroundResource(
                            R.drawable.bg_attendance_btn_primary
                        )
                        binding.btnClockOut.alpha = 1.0f
                        binding.btnOnDuty.isEnabled = !state.isSubmitting
                        updateOnDutyButtonUi()
                        startLiveTodayTicker(state.firstPunchInIso)
                        updateHeaderTexts(true)
                    } else if (state.clockedOutOnMobile) {
                        // Clocked out on mobile → dead "Clocked Out" until a
                        // later punch. Only a mobile clock-out lands here (a
                        // biometric gate punch-out never does), so tracking
                        // stops exactly when the person ends their day here; a
                        // later gate punch flips back to the enabled Clock Out.
                        binding.clockInButtonGroup.visibility = View.GONE
                        binding.clockedInButtonGroup.visibility = View.VISIBLE
                        binding.btnOnDuty.visibility = View.GONE
                        binding.btnClockOut.isEnabled = false
                        binding.btnClockOut.text = "Clocked Out"
                        binding.btnClockOut.setTextColor(Color.WHITE)
                        binding.btnClockOut.setBackgroundResource(
                            R.drawable.bg_attendance_btn_primary
                        )
                        binding.btnClockOut.alpha = 0.5f
                        stopLiveTodayTicker()
                        updateHeaderTexts(false)
                        scheduleMidnightRefresh()
                    } else {
                        // On-duty is intentionally NOT cleared here (see the
                        // clocked-out branch above). The not-clocked-in render
                        // must never wipe a persisted on-duty session — that
                        // was the relaunch/crash data-loss bug.
                        binding.clockInButtonGroup.visibility = View.VISIBLE
                        binding.clockedInButtonGroup.visibility = View.GONE
                        binding.btnOnDutyDisabled.isEnabled = !state.isSubmitting
                        stopLiveTodayTicker()
                        // Title + subtitle + Dynamic Island handled
                        // centrally by updateHeaderTexts; reset the
                        // button drawable + hide the fence status line
                        // since the home-fence check is now enforced
                        // only in the click handler (top Toast).
                        updateHeaderTexts(false)
                        binding.btnClockInNow.setBackgroundResource(
                            R.drawable.bg_attendance_btn_primary
                        )
                        binding.btnClockInNow.alpha = 1f
                        binding.tvClockInFenceStatus.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun updateOnDutyButtonUi() {
        if (!isAdded || _binding == null) return
        if (session.isOnDuty) {
            binding.btnOnDuty.text = "Complete On Duty"
            binding.btnOnDuty.setTextColor(Color.WHITE)
            binding.btnOnDuty.setBackgroundResource(R.drawable.bg_attendance_btn_blue_gradient)
        } else {
            binding.btnOnDuty.text = "On Duty"
            binding.btnOnDuty.setTextColor(Color.parseColor("#1BCA0B"))
            binding.btnOnDuty.setBackgroundResource(R.drawable.bg_attendance_btn_outline)
        }
    }

    private fun showDynamicIslandWithAnimation(animate: Boolean) {
        if (!isAdded || _binding == null) return
        val pill = binding.layoutDynamicIslandOnduty
        if (pill.visibility == View.VISIBLE) return

        if (!animate) {
            pill.visibility = View.VISIBLE
            pill.alpha = 1f
            pill.scaleX = 1f
            pill.scaleY = 1f
            pill.translationY = 0f
            return
        }

        pill.visibility = View.VISIBLE
        pill.alpha = 0f
        pill.scaleX = 0.4f
        pill.scaleY = 0.4f
        pill.translationY = -60f

        pill.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
            .start()
    }

    private fun hideDynamicIslandWithAnimation(animate: Boolean) {
        if (!isAdded || _binding == null) return
        val pill = binding.layoutDynamicIslandOnduty
        if (pill.visibility == View.GONE) return

        if (!animate) {
            pill.visibility = View.GONE
            return
        }

        pill.animate()
            .alpha(0f)
            .scaleX(0.4f)
            .scaleY(0.4f)
            .translationY(-60f)
            .setDuration(400)
            .setInterpolator(android.view.animation.AnticipateInterpolator())
            .withEndAction {
                pill.visibility = View.GONE
            }
            .start()
    }

    private fun updateHeaderTexts(isClockedIn: Boolean, animateDynamicIsland: Boolean = false) {
        if (!isAdded || _binding == null) return
        
        // Show/hide the dynamic island pill based on On Duty status
        if (session.isOnDuty) {
            showDynamicIslandWithAnimation(animateDynamicIsland)
        } else {
            hideDynamicIslandWithAnimation(animateDynamicIsland)
        }

        if (isClockedIn) {
            binding.tvAttendanceHeaderTitle.text = "You're Clocked In"
            binding.tvAttendanceHeaderSubtitle.text = "Have a productive day ahead"
        } else {
            val hasClockedOutToday = !flowViewModel.uiState.value.isClockedIn && !flowViewModel.uiState.value.firstPunchInIso.isNullOrBlank()
            if (hasClockedOutToday) {
                binding.tvAttendanceHeaderTitle.text = "Clocked Out"
                binding.tvAttendanceHeaderSubtitle.text =
                    "You're done for the day — attendance finalizes at midnight"
            } else {
                binding.tvAttendanceHeaderTitle.text = "Let’s Clock-In!"
                binding.tvAttendanceHeaderSubtitle.text =
                    "Don’t miss your clock in schedule"
            }
        }
    }

    /**
     * While clocked-in, refresh the "Today" stat every second as
     * `now - firstPunchIn`. Falls back to the server-reported total
     * when no first-punch timestamp is available.
     */
    private fun startLiveTodayTicker(firstPunchIso: String?) {
        if (liveTickerJob?.isActive == true) return
        if (firstPunchIso.isNullOrBlank()) return
        val firstMillis = parseIsoMillisOrNull(firstPunchIso) ?: return

        liveTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && _binding != null) {
                val elapsedMs = (System.currentTimeMillis() - firstMillis).coerceAtLeast(0)
                val totalMinutes = (elapsedMs / 60_000L).toInt()
                _binding?.tvTodayHours?.text =
                    AttendanceFlowViewModel.formatMinutesForToday(totalMinutes)
                // Keep the inline "Today" history card's Total Hours live too,
                // so it agrees with the top stat instead of sitting at 00:00:00.
                todayCardHoursView?.text = formatMinutesAsPeriod(totalMinutes)
                delay(1000L)
            }
        }
    }

    private fun stopLiveTodayTicker() {
        liveTickerJob?.cancel()
        liveTickerJob = null
    }

    /**
     * When the user is clocked out for the day, the Clock Out button is
     * locked. Schedule a reload just after midnight so the day rolls over and
     * the button becomes actionable again for the new day (the finalize of
     * the previous day happens server-side). Reopening the app on a new day
     * already picks up the fresh state via onResume; this covers the case
     * where the app is left open across midnight.
     */
    private fun scheduleMidnightRefresh() {
        if (midnightRefreshJob?.isActive == true) return
        val now = Calendar.getInstance()
        val nextMidnight = (now.clone() as Calendar).apply {
            add(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 2)
            set(Calendar.MILLISECOND, 0)
        }
        val delayMs = (nextMidnight.timeInMillis - now.timeInMillis).coerceAtLeast(0L)
        midnightRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            if (_binding == null) return@launch
            flowViewModel.loadTodayAttendance(session.bearerToken)
        }
    }

    private fun parseIsoMillisOrNull(iso: String): Long? {
        // Handle both "2026-04-27T09:30:00+05:30" and "...Z" / no offset.
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
        )
        for (p in patterns) {
            try {
                return SimpleDateFormat(p, Locale.US).parse(iso)?.time
            } catch (_: Exception) {
                /* try next */
            }
        }
        return null
    }

    private fun collectEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flowViewModel.events.collect { event ->
                    when (event) {
                        is AttendanceFlowEvent.Error -> {
                            Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                        }

                        is AttendanceFlowEvent.SubmissionFailed -> {
                            Toast.makeText(
                                requireContext(),
                                "${event.message} Please retry.",
                                Toast.LENGTH_LONG,
                            ).show()
                            loadRecentHistoryCards()
                        }

                        is AttendanceFlowEvent.Success -> {
                            loadRecentHistoryCards()
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLiveTodayTicker()
        _binding = null
        pendingEntryAnimation = true
        hasPlayedEntryAnimation = false
    }


    private fun hasPunchPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && (fineGranted || coarseGranted)
    }

    private fun beginPunchCapture(mode: PunchMode) {
        if (isLaunchingCamera) return
        pendingPunchMode = mode
        if (hasPunchPermissions()) {
            launchSystemCamera()
            return
        }
        isLaunchingCamera = true
        capturePermissionLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun launchSystemCamera() {
        val imageFile = createPunchPhotoFile()
        if (imageFile == null) {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "Unable to create selfie file.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPunchImageFile = imageFile
        val imageUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            imageFile,
        )
        pendingPunchImageUri = imageUri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(
                requireContext().contentResolver,
                "PunchSelfie",
                imageUri,
            )
        }
        try {
            isLaunchingCamera = true
            captureSelfieLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "No camera app available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPunchPhotoFile(): File? {
        return try {
            val dir = File(requireContext().cacheDir, "punch_photos")
            if (!dir.exists()) dir.mkdirs()
            File.createTempFile("punch_selfie_", ".jpg", dir)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadRecentHistoryCards() {
        isHistoryLoading = true
        updateAttendanceLoadingUi()
        viewLifecycleOwner.lifecycleScope.launch {
            // Resolve weekly-off weekdays first so the gap-fill below can flag
            // off days as "Week Off". Best-effort — failure leaves the set
            // unchanged and gap days fall back to the punch-derived verdict.
            refreshWeekoffWeekdays()
            try {
                // Show the full current calendar month, not just the trailing 31 days.
                val cal = Calendar.getInstance()
                val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val toDate = ymd.format(cal.time)
                val fromDate = String.format(
                    Locale.US,
                    "%04d-%02d-01",
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH) + 1
                )
                val response = api.getMyAttendance(
                    token = session.bearerToken,
                    fromDate = fromDate,
                    toDate = toDate,
                )
                if (!response.success) {
                    bindRecentHistoryCards(emptyList())
                } else {
                    bindRecentHistoryCards(response.records)
                }
            } catch (_: Exception) {
                bindRecentHistoryCards(emptyList())
            } finally {
                isHistoryLoading = false
                updateAttendanceLoadingUi()
            }
        }
    }

    /**
     * Pull the staff's shift schedule and cache which weekdays are weekly-off
     * (isWorkDay == false). Only an explicit `false` counts — unknown/null
     * days stay work days so a real absence is never masked as "Week Off".
     */
    private suspend fun refreshWeekoffWeekdays() {
        val staffId = session.staffId
        val token = session.bearerToken
        if (staffId.isNullOrBlank() || token.isBlank()) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val resp = runCatching { api.getTodayShift(token, staffId, today) }.getOrNull() ?: return
        val schedule = resp.shift?.schedule ?: return
        val offDays = mutableSetOf<Int>()
        if (schedule.sunday?.isWorkDay == false) offDays.add(Calendar.SUNDAY)
        if (schedule.monday?.isWorkDay == false) offDays.add(Calendar.MONDAY)
        if (schedule.tuesday?.isWorkDay == false) offDays.add(Calendar.TUESDAY)
        if (schedule.wednesday?.isWorkDay == false) offDays.add(Calendar.WEDNESDAY)
        if (schedule.thursday?.isWorkDay == false) offDays.add(Calendar.THURSDAY)
        if (schedule.friday?.isWorkDay == false) offDays.add(Calendar.FRIDAY)
        if (schedule.saturday?.isWorkDay == false) offDays.add(Calendar.SATURDAY)
        weekoffWeekdays = offDays
    }

    private fun bindRecentHistoryCards(records: List<AttendanceRecord>) {
        // The loader coroutine runs on viewLifecycleOwner.lifecycleScope, but a
        // view torn down mid-request resumes the suspended call with a
        // CancellationException that the broad `catch (_: Exception)` swallows —
        // then this runs with _binding already null. Bail before touching views.
        if (_binding == null) return
        recentHistoryRecords = records
        // Fill gaps: walk every date from the start of the current
        // month to today and inject a placeholder AttendanceRecord
        // for any day the backend didn't return. The backend's
        // getMyAttendance only includes days that have a row in the
        // staffAttendance table — if a staff didn't clock in
        // yesterday, yesterday silently vanished from this list,
        // which read as "yesterday's attendance is not showing"
        // even though the real cause was "no row was ever created".
        // With this fill, every day in the period appears with
        // 00:00:00 hrs / "-- - --" so the gap is visible and
        // actionable instead of invisible.
        val byDate = records.associateBy { it.date.orEmpty() }
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val today = ymd.format(cal.time)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val filled = mutableListOf<AttendanceRecord>()
        while (true) {
            val day = ymd.format(cal.time)
            if (day > today) break
            // For a missing day, pre-mark it as a week-off when its weekday is
            // in the staff's weekly-off set so the badge reads "Week Off"
            // rather than the punch-derived "Absent". Real backend rows are
            // never overridden.
            val isWeekoffDay = weekoffWeekdays.contains(cal.get(Calendar.DAY_OF_WEEK))
            filled.add(
                byDate[day] ?: AttendanceRecord(
                    date = day,
                    status = null,
                    totalMinutes = 0,
                    approvedAttendance = if (isWeekoffDay) "weekoff" else null,
                    punchInTime = null,
                    punchOutTime = null,
                    hasOpenSession = false,
                    sessions = emptyList(),
                ),
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val sorted = filled.sortedByDescending { it.date ?: "" }
        // Static cardHistory1 is retired; today lives inline in the list.
        binding.cardHistory1.visibility = View.GONE

        val container = binding.historyListContainer
        val childCount = container.childCount
        val targetCount = sorted.size

        if (childCount > targetCount) {
            container.removeViews(targetCount, childCount - targetCount)
        }

        todayCardHoursView = null
        val liveState = flowViewModel.uiState.value
        val liveInIso = liveState.firstPunchInIso
        val liveInMs = liveInIso?.let { parseIsoMillisOrNull(it) }

        sorted.forEachIndexed { index, record ->
            val card = if (index < childCount) {
                container.getChildAt(index)
            } else {
                val newCard = LayoutInflater.from(requireContext()).inflate(
                    R.layout.item_attendance_history_card,
                    container,
                    false
                )
                container.addView(newCard)
                newCard
            }
            val dateView = card.findViewById<TextView>(R.id.tvHistoryItemDate)
            val hoursView = card.findViewById<TextView>(R.id.tvHistoryItemHours)
            val rangeView = card.findViewById<TextView>(R.id.tvHistoryItemRange)

            if (index == 0) {
                // TODAY row — tag, live clock-in, no clock-out until the day
                // ends, hours stay live (server total is 0 for an open
                // session; the ticker keeps it advancing).
                dateView.text = "Today"
                val recordInIso = record.punchInTime
                    ?: record.sessions?.firstOrNull()?.punchInTime
                val todayInIso = liveInIso?.takeIf { it.isNotBlank() } ?: recordInIso
                val todayInLabel = todayInIso?.takeIf { it.isNotBlank() }
                    ?.let(::formatIsoTime) ?: "--"
                rangeView.text = "$todayInLabel - --"
                hoursView.text =
                    if (liveState.isClockedIn && liveInMs != null) {
                        val mins = ((System.currentTimeMillis() - liveInMs)
                            .coerceAtLeast(0) / 60_000L).toInt()
                        formatMinutesAsPeriod(mins)
                    } else {
                        liveState.latestTotalHours
                            .ifBlank { formatMinutesAsPeriod(record.totalMinutes ?: 0) }
                    }
                todayCardHoursView = hoursView
            } else {
                dateView.text = formatDashboardDate(record.date)
                hoursView.text = formatMinutesAsPeriod(record.totalMinutes ?: 0)
                rangeView.text = buildPunchRange(record)
            }

            // Remark / Time Correction affordance — shown on every PAST day
            // card (punched or not). Today (index 0) is excluded: the day is
            // still running, so a remark/correction would be premature. A
            // no-punch "Absent" past day has no backend id; the backend seeds
            // an attendance row keyed by {staffId, date} when the request is
            // raised, so only a date is required here. A just-submitted day
            // swaps the pencil for the "Remark Submitted" badge until reload.
            val editBtn = card.findViewById<android.widget.ImageView>(R.id.btnHistoryItemEdit)
            val remarkBadge = card.findViewById<View>(R.id.badgeRemarkSubmitted)
            val canRemark = index > 0 && !record.date.isNullOrBlank()
            val alreadySubmitted =
                record.date?.let { submittedRemarkDates.contains(it) } == true
            when {
                !canRemark -> {
                    editBtn.visibility = View.GONE
                    remarkBadge.visibility = View.GONE
                }
                alreadySubmitted -> {
                    editBtn.visibility = View.GONE
                    remarkBadge.visibility = View.VISIBLE
                }
                else -> {
                    remarkBadge.visibility = View.GONE
                    editBtn.visibility = View.VISIBLE
                    editBtn.setOnClickListener {
                        EditAttendanceBottomSheet.newInstance(record)
                            .show(parentFragmentManager, "edit_attendance")
                    }
                }
            }

            // Present / Absent pill on every past-day card. Today's row
            // (index 0) is skipped — the day is still running so a verdict
            // would be premature; the helper also short-circuits on
            // today's date as a second guard.
            if (index > 0) {
                AttendanceStatusBadge.bind(
                    card.findViewById(R.id.tvHistoryItemStatus),
                    record,
                )
            }
        }
    }

    private fun bindHistoryCard(
        card: View,
        dateView: TextView,
        hoursView: TextView,
        rangeView: TextView,
        record: AttendanceRecord?,
    ) {
        if (record == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        dateView.text = formatDashboardDate(record.date)
        hoursView.text = formatMinutesAsPeriod(record.totalMinutes ?: 0)
        rangeView.text = buildPunchRange(record)
    }

    private fun formatDashboardDate(date: String?): String {
        val raw = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)
            SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(parsed ?: Date())
        } catch (_: Exception) {
            raw
        }
    }

    private fun formatMinutesAsPeriod(totalMinutes: Int): String {
        val safe = totalMinutes.coerceAtLeast(0)
        val hours = safe / 60
        val minutes = safe % 60
        return String.format(Locale.US, "%02d:%02d:00 hrs", hours, minutes)
    }

    private fun buildPunchRange(record: AttendanceRecord): String {
        val firstIn = record.punchInTime ?: record.sessions?.firstOrNull()?.punchInTime
        val lastOut = record.punchOutTime ?: record.sessions?.lastOrNull()?.punchOutTime
        val inLabel = firstIn?.let(::formatIsoTime) ?: "--"
        val outLabel = if (record.hasOpenSession == true) "--" else (lastOut?.let(::formatIsoTime) ?: "--")
        return "$inLabel - $outLabel"
    }

    private fun formatIsoTime(iso: String): String {
        val millis = parseIsoMillis(iso) ?: return "--"
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun parseIsoMillis(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) {
                    fmt.timeZone = TimeZone.getTimeZone("UTC")
                }
                return fmt.parse(iso)?.time
            } catch (_: Exception) {
                // try next pattern
            }
        }
        return null
    }

    private suspend fun fetchLocationOrNull(): Location? {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(requireContext())
            val token = CancellationTokenSource().token
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token).await()
                ?: client.lastLocation.await()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Application-context variant used by the background on-duty complete.
     * The fragment may already be gone by the time this runs, so it takes a
     * captured Context instead of requireContext(), and it is time-boxed so
     * a slow GPS fix can never stall the (now already-cleared) flow — it
     * just falls back to the last known location or null.
     */
    private suspend fun fetchLocationForContextOrNull(ctx: android.content.Context): Location? {
        return try {
            kotlinx.coroutines.withTimeoutOrNull(4000L) {
                val client = LocationServices.getFusedLocationProviderClient(ctx)
                val token = CancellationTokenSource().token
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token).await()
                    ?: client.lastLocation.await()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Entry point for the "Complete On Duty" tap. Public Transport trips
     * must attach travel proof first (Rapido/bus ticket, etc.), so they
     * detour through [OnDutyProofBottomSheet]; every other vehicle closes
     * straight away.
     */
    private fun completeOnDutyTrip() {
        val isPublicTransport =
            session.onDutyVehicleType.equals("Public Transport", ignoreCase = true) ||
                session.onDutyVehicleOwnership.equals("Public Vehicle", ignoreCase = true)
        if (isPublicTransport) {
            OnDutyProofBottomSheet.newInstance().show(parentFragmentManager, "on_duty_proof")
            return
        }
        finishOnDuty(proofPhotoIds = null, proofNote = null)
    }

    /**
     * Close the active OnDuty trip. Optimistic by design: the local
     * session flags + UI clear *immediately* so the button flips back the
     * instant it's tapped — the old flow blocked on a high-accuracy GPS
     * fix before doing anything, which is what made completion feel like
     * it hung for several seconds. The network complete (with a
     * best-effort, time-boxed location fix) runs on the activity scope so
     * it survives this fragment being destroyed. Local state is cleared
     * regardless of server outcome; the server falls back to the most
     * recent active on-duty trip on the next call, and the nightly
     * trip-finalize cron closes any orphan.
     */
    private fun finishOnDuty(proofPhotoIds: List<String>?, proofNote: String?) {
        val tripId = session.onDutyTripId
        val token = session.bearerToken
        val appCtx = requireContext().applicationContext

        session.clearOnDutyDetails()
        // Field activity ended → return the tracking notification to its
        // neutral shift line.
        com.manjugroups.m_connect.geotrack.service.TrackingNotification.refresh(appCtx)
        Toast.makeText(requireContext(), "On Duty completed.", Toast.LENGTH_SHORT).show()
        updateOnDutyButtonUi()
        val isClockedIn = flowViewModel.uiState.value.isClockedIn
        updateHeaderTexts(isClockedIn, animateDynamicIsland = true)

        requireActivity().lifecycleScope.launch {
            val loc = fetchLocationForContextOrNull(appCtx)
            runCatching {
                api.completeOnDutyTrip(
                    token,
                    com.manjugroups.m_connect.network.CompleteOnDutyTripRequest(
                        tripId = tripId,
                        lat = loc?.latitude,
                        lng = loc?.longitude,
                        address = null,
                        proofPhotoIds = proofPhotoIds?.takeIf { it.isNotEmpty() },
                        proofNote = proofNote?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
    }

    // ── Home geofence enforcement ──────────────────────────────────────
    //
    // Mirrors checkHomeBlock in convex/staffAttendance.ts. The endpoint
    // returns one snapshot per session (the home pin + per-staff/global
    // radius + the enabled flag); the watcher loop below periodically
    // grabs the last-known location and recomputes distance so the button
    // disable state reflects movement in/out of the fence in near-real
    // time without burning battery on high-accuracy fixes.

    private fun loadHomeFence() {
        val token = session.bearerToken
        if (token.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = try {
                api.getHomeFence(token)
            } catch (_: Exception) {
                null
            }
            if (_binding == null) return@launch
            homeFence = snapshot?.fence?.takeIf { it.enabled }
            // Fresh fetch → recompute right away so the button reacts to
            // the new policy without waiting for the next watcher tick.
            refreshGeofenceState()
        }
    }

    /** Returns true when the staff is enforceably inside their home radius. */
    private suspend fun computeInsideHomeFence(): Boolean {
        val fence = homeFence
        if (fence == null) {
            lastFenceStatus = "Home-block policy off (HR setting)"
            return false
        }
        if (!fence.enabled) {
            lastFenceStatus = "Home-block policy off"
            return false
        }
        val lat = fence.lat
        val lng = fence.lng
        if (lat == null || lng == null) {
            lastFenceStatus = "No home pin set for this staff"
            return false
        }
        val hasFine = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            lastFenceStatus = "Grant location permission to enforce home fence"
            return false
        }
        val loc = try {
            val client = LocationServices.getFusedLocationProviderClient(requireContext())
            val token = CancellationTokenSource().token
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token).await()
                ?: client.lastLocation.await()
        } catch (_: Exception) { null }
        if (loc == null) {
            lastFenceStatus = "Waiting for GPS fix…"
            return false
        }
        if (loc.latitude == 0.0 && loc.longitude == 0.0) {
            lastFenceStatus = "Waiting for GPS fix…"
            return false
        }
        val distanceMeters = haversineMeters(
            loc.latitude, loc.longitude, lat, lng,
        ).toInt()
        val inside = distanceMeters <= fence.radiusMeters
        lastFenceStatus = if (inside) {
            "Inside home fence (${distanceMeters} m ≤ ${fence.radiusMeters} m)"
        } else {
            "Outside home fence (${distanceMeters} m, allowed when ≤ ${fence.radiusMeters} m)"
        }
        return inside
    }

    /** Recompute inside-fence and update the button + subtitle. */
    private fun refreshGeofenceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            val inside = computeInsideHomeFence()
            if (_binding == null) return@launch
            if (inside == isInsideHomeFence) return@launch
            isInsideHomeFence = inside
            applyGeofenceToButton()
        }
    }

    /**
     * Drives the Clock-In button's enabled state + the header subtitle
     * from `isInsideHomeFence`. Safe to call from collectState (when the
     * flow state changes) and from refreshGeofenceState (when GPS does).
     */
    private fun applyGeofenceToButton() {
        // Intentionally no visual cue — the button stays active green,
        // header stays "Let's Clock-In!", no diagnostic banner. The
        // only enforcement is in the click listener: if isInsideHomeFence
        // is true at tap time, we refuse with a top-of-screen Toast.
        if (_binding == null) return
        binding.tvClockInFenceStatus.visibility = View.GONE
    }

    /** Brief top-of-screen Toast for the geofence refusal. */
    private fun showTopToast(message: String) {
        val act = activity ?: return
        com.manjugroups.m_connect.ui.common.TopToast.show(act, message)
    }

    private fun startGeofenceWatcher() {
        if (geofenceWatcherJob?.isActive == true) return
        geofenceWatcherJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && _binding != null) {
                refreshGeofenceState()
                // Tighter than the original 15 s so the button reacts
                // within a few seconds of the staff stepping in/out of
                // their home radius.
                delay(5_000L)
            }
        }
    }

    private fun stopGeofenceWatcher() {
        geofenceWatcherJob?.cancel()
        geofenceWatcherJob = null
    }

    /** Great-circle distance in meters — mirrors convex/lib/geo.ts. */
    private fun haversineMeters(
        lat1: Double, lng1: Double, lat2: Double, lng2: Double,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) *
            Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    @Suppress("DEPRECATION")
    private fun resolveAddress(location: Location): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            val results = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            results?.firstOrNull()?.getAddressLine(0)
        } catch (_: Exception) {
            null
        }
    }

    private fun navigateToPunchDetail(
        mode: PunchMode,
        photoPath: String,
        latitude: Double,
        longitude: Double,
        address: String?,
    ) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(
                R.id.fragmentContainer,
                SelfieClockInDetailFragment.newInstance(
                    mode = mode.name,
                    photoPath = photoPath,
                    latitude = latitude,
                    longitude = longitude,
                    address = address,
                ),
            )
            .addToBackStack(null)
            .commit()
    }

    private fun updateAttendanceLoadingUi() {
        if (_binding == null) return
        val stillLoading = isTodayLoading || isHistoryLoading
        // Mark the first load complete the moment both feeds have resolved.
        if (!stillLoading) hasCompletedFirstAttendanceLoad = true
        // The full skeleton is a FIRST-LOAD affordance only. Once we've shown
        // real content, subsequent refreshes must never hide the clock-in card
        // back to skeleton — keep it visible and update in place.
        val showSkeleton = stillLoading && !hasCompletedFirstAttendanceLoad
        if (showSkeleton) {
            com.manjugroups.m_connect.ui.common.SkeletonUtils.startSkeletonPulse(binding.attendanceSkeletonContainer)
            binding.cardAttendanceSummary.visibility = View.GONE
            binding.cardHistory1.visibility = View.GONE
            binding.cardHistory2.visibility = View.GONE
            binding.cardHistory3.visibility = View.GONE
        } else {
            com.manjugroups.m_connect.ui.common.SkeletonUtils.stopSkeletonPulse(binding.attendanceSkeletonContainer)
            binding.cardAttendanceSummary.visibility = View.VISIBLE
            bindRecentHistoryCards(recentHistoryRecords)
            // Trigger the staggered card entrance once we have real content to show.
            if (wasShowingSkeleton || !hasPlayedEntryAnimation) {
                binding.root.post { playContentEntryAnimation() }
            }
        }
        wasShowingSkeleton = showSkeleton
    }

    private fun forEachLeafBlock(group: android.view.ViewGroup, action: (View) -> Unit) {
        // Not used anymore with SkeletonUtils
    }
}
