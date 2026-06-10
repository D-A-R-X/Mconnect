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
    private var recentHistoryRecords: List<AttendanceRecord> = emptyList()
    private var liveTickerJob: Job? = null
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

        binding.btnClockInNow.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ClockInAreaFragment())
                .addToBackStack(null)
                .commit()
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
                    ClockOutSuccessBottomSheet()
                        .show(parentFragmentManager, "clock_out_success")
                }
                PunchMode.PUNCH_IN -> {
                    ClockInSuccessBottomSheet().show(parentFragmentManager, "clock_in_success")
                }
                null -> Unit
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
            binding.hrRefresh.postDelayed({ binding.hrRefresh.dismissRefresh() }, 800)
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
                    binding.tvLatestTotalHours.text = state.latestTotalHours
                    binding.tvLatestRange.text = state.latestRange
                    binding.tvPayPeriodLabel.text = state.payPeriodLabel
                        .ifBlank { binding.tvPayPeriodLabel.text }
                    binding.tvPayPeriodHours.text = state.payPeriodHours

                    // Today's clock-in/out summary on the main card.
                    // Visible the moment the user has clocked in at
                    // least once today, so the operator sees actual
                    // times alongside the Today / Pay Period hour
                    // counters instead of having to scroll into the
                    // history strip below for that info.
                    val firstPunchIso = state.firstPunchInIso
                    val lastPunchIso = state.lastPunchOutIso
                    if (!firstPunchIso.isNullOrBlank()) {
                        binding.todayPunchSummary.visibility = View.VISIBLE
                        binding.tvTodayClockedInAt.text =
                            AttendanceFlowViewModel.formatIsoToTime(firstPunchIso)
                        // Show "Clocked out" ONLY when there's a real
                        // punch-out that's different from the punch-in
                        // (the backend can occasionally echo the
                        // punch-in iso into lastPunchOutIso the moment
                        // the row is created — that produced the
                        // "Clocked in 2:17 PM / Clocked out 2:17 PM"
                        // confusion in the screenshot). The Today /
                        // Pay Period hour counters and the history
                        // strip below still surface the clock-out
                        // time once it's real.
                        val hasRealClockOut = !lastPunchIso.isNullOrBlank() &&
                            lastPunchIso != firstPunchIso
                        if (hasRealClockOut) {
                            binding.todayClockedOutGroup.visibility = View.VISIBLE
                            binding.tvTodayClockedOutAt.text =
                                AttendanceFlowViewModel.formatIsoToTime(lastPunchIso)
                        } else {
                            binding.todayClockedOutGroup.visibility = View.GONE
                        }
                    } else {
                        binding.todayPunchSummary.visibility = View.GONE
                    }
                    binding.btnClockInNow.isEnabled = !state.isLoading && !state.isSubmitting
                    binding.btnClockOut.isEnabled = !state.isLoading && !state.isSubmitting

                    // Three-state button logic now respects the "day stays
                    // editable until midnight" rule the user asked for:
                    //
                    //   1. Not punched in yet today → "Clock In Now"
                    //   2. Currently clocked in (open session) → "Clock Out"
                    //   3. Already clocked out today but day not finalized
                    //      → "Clock In" (re-opens a session on the same
                    //      attendance row; first-punch-in stays canonical,
                    //      and the last touch becomes the effective
                    //      punch-out at midnight or next manual close).
                    //
                    // This handles two real cases: a user who punched out
                    // for a break and wants to resume, and a user who
                    // accidentally tapped Clock Out and just wants to keep
                    // their day going. The old behaviour greyed out the
                    // button and forced them to wait for midnight.
                    val hasClockedOutToday =
                        !state.isClockedIn && !state.firstPunchInIso.isNullOrBlank()

                    if (state.isClockedIn) {
                        binding.clockInButtonGroup.visibility = View.GONE
                        binding.clockedInButtonGroup.visibility = View.VISIBLE
                        binding.btnClockOut.isEnabled = !state.isSubmitting
                        binding.btnClockOut.text = "Clock Out"
                        binding.btnClockOut.setBackgroundResource(
                            R.drawable.bg_attendance_btn_primary
                        )
                        startLiveTodayTicker(state.firstPunchInIso)
                        binding.tvAttendanceHeaderTitle.text = "You're Clocked In"
                        binding.tvAttendanceHeaderSubtitle.text =
                            "Have a productive day ahead"
                    } else if (hasClockedOutToday) {
                        // One-time Clock In rule: once the staff has punched in
                        // today the button never reverts to "Clock In". It
                        // stays "Clock Out" until the day ends. Clock Out is
                        // unlimited — each tap re-stamps the day's punch-out, so
                        // the last tap becomes the effective punch-out (locked
                        // by the midnight finalize).
                        binding.clockInButtonGroup.visibility = View.GONE
                        binding.clockedInButtonGroup.visibility = View.VISIBLE
                        binding.btnClockOut.isEnabled = !state.isSubmitting
                        binding.btnClockOut.text = "Clock Out"
                        binding.btnClockOut.setBackgroundResource(
                            R.drawable.bg_attendance_btn_primary
                        )
                        stopLiveTodayTicker()
                        binding.tvAttendanceHeaderTitle.text = "Clocked Out"
                        binding.tvAttendanceHeaderSubtitle.text =
                            "Tap Clock Out again to update — final time locks at midnight"
                    } else {
                        binding.clockInButtonGroup.visibility = View.VISIBLE
                        binding.clockedInButtonGroup.visibility = View.GONE
                        stopLiveTodayTicker()
                        binding.tvAttendanceHeaderTitle.text = "Let’s Clock-In!"
                        binding.tvAttendanceHeaderSubtitle.text =
                            "Don’t miss your clock in schedule"
                    }
                }
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
                delay(1000L)
            }
        }
    }

    private fun stopLiveTodayTicker() {
        liveTickerJob?.cancel()
        liveTickerJob = null
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

    private fun bindRecentHistoryCards(records: List<AttendanceRecord>) {
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
            filled.add(
                byDate[day] ?: AttendanceRecord(
                    date = day,
                    status = null,
                    totalMinutes = 0,
                    approvedAttendance = null,
                    punchInTime = null,
                    punchOutTime = null,
                    hasOpenSession = false,
                    sessions = emptyList(),
                ),
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        val sorted = filled.sortedByDescending { it.date ?: "" }
        val primary = sorted.getOrNull(0)
        if (primary != null) {
            binding.tvHistoryDate1.text = formatDashboardDate(primary.date)
            binding.tvLatestTotalHours.text = formatMinutesAsPeriod(primary.totalMinutes ?: 0)
            binding.tvLatestRange.text = buildPunchRange(primary)
        } else {
            binding.tvHistoryDate1.text = formatDashboardDate(null)
            binding.tvLatestTotalHours.text = formatMinutesAsPeriod(0)
            binding.tvLatestRange.text = "-- - --"
        }

        // Render the rest of the month with the same rich-card visual style
        // (calendar icon + date + inner Total Hours / Clock in & Out card).
        binding.historyListContainer.removeAllViews()
        sorted.drop(1).forEach { record ->
            val card = LayoutInflater.from(requireContext()).inflate(
                R.layout.item_attendance_history_card,
                binding.historyListContainer,
                false
            )
            card.findViewById<TextView>(R.id.tvHistoryItemDate).text =
                formatDashboardDate(record.date)
            card.findViewById<TextView>(R.id.tvHistoryItemHours).text =
                formatMinutesAsPeriod(record.totalMinutes ?: 0)
            card.findViewById<TextView>(R.id.tvHistoryItemRange).text =
                buildPunchRange(record)
            binding.historyListContainer.addView(card)
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
        val showSkeleton = isTodayLoading || isHistoryLoading
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
