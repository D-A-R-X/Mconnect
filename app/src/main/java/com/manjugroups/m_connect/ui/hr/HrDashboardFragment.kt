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
import com.manjugroups.m_connect.ui.notifications.NotificationsFragment
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HrDashboardFragment : Fragment() {

    private var _binding: FragmentHrDashboardBinding? = null
    private val binding get() = _binding!!
    private val flowViewModel: AttendanceFlowViewModel by activityViewModels()
    private val api = ApiService.create()
    private lateinit var session: SessionManager
    
    private var pendingPunchMode: PunchMode? = null
    private var pendingPunchImageFile: File? = null
    private var pendingPunchImageUri: android.net.Uri? = null
    private var isLaunchingCamera = false
    
    private var isTodayLoading = true
    private var isHistoryLoading = true
    private var wasShowingSkeleton = false
    private var recentHistoryRecords: List<AttendanceRecord> = emptyList()
    private var liveTickerJob: Job? = null

    private var hasAnimatedBanner = false
    private var isBannerCollapsed = false
    private var bannerMeasuredHeight = 0

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val locGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || 
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraGranted && locGranted) {
            launchSystemCamera()
        } else {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "Permissions required for punch.", Toast.LENGTH_SHORT).show()
        }
    }

    private val captureSelfieLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val imageUri = pendingPunchImageUri
        if (imageUri != null) {
            runCatching {
                requireContext().revokeUriPermission(
                    imageUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        val mode = pendingPunchMode
        val imageFile = pendingPunchImageFile
        if (mode == null || imageFile == null) {
            isLaunchingCamera = false
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK && imageFile.exists()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val location = fetchLocationOrNull()
                if (location == null) {
                    isLaunchingCamera = false
                    Toast.makeText(requireContext(), "Unable to fetch GPS location.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val address = resolveAddress(location)
                isLaunchingCamera = false
                navigateToPunchDetail(mode, imageFile.absolutePath, location.latitude, location.longitude, address)
            }
        } else {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "Capture cancelled.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHrDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        setupHeader()
        setupActions()
        setupScroll()
        
        // Show structural wrapper immediately
        binding.hrContentWrapper.visibility = View.VISIBLE
        binding.hrMainContent.visibility = View.VISIBLE
        updateAttendanceLoadingUi()

        parentFragmentManager.setFragmentResultListener(ClockOutConfirmBottomSheet.RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ClockOutConfirmBottomSheet.KEY_CONFIRMED, false)) {
                beginPunchCapture(PunchMode.PUNCH_OUT)
            }
        }

        parentFragmentManager.setFragmentResultListener(SelfieClockInDetailFragment.RESULT_KEY_PUNCH_COMPLETED, viewLifecycleOwner) { _, bundle ->
            val rawMode = bundle.getString(SelfieClockInDetailFragment.KEY_MODE)
            val mode = runCatching { PunchMode.valueOf(rawMode ?: "") }.getOrNull()
            if (mode == PunchMode.PUNCH_OUT) ClockOutSuccessBottomSheet().show(parentFragmentManager, "out_success")
            else if (mode == PunchMode.PUNCH_IN) ClockInSuccessBottomSheet().show(parentFragmentManager, "in_success")
        }

        collectState()
        collectEvents()
        flowViewModel.loadTodayAttendance(session.bearerToken)
        loadRecentHistoryCards()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(true)
        flowViewModel.loadTodayAttendance(session.bearerToken)
        loadRecentHistoryCards()
        
        if (!hasAnimatedBanner) {
            animateBannerExpansion()
        }
    }

    override fun onPause() {
        super.onPause()
        hasAnimatedBanner = false
        isBannerCollapsed = false
        if (_binding != null) {
            binding.hrBannerExpandable.visibility = View.INVISIBLE
            binding.hrBannerExpandable.alpha = 0f
            binding.hrBannerExpandable.layoutParams.height = 0
        }
    }

    private fun setupHeader() {
        val name = (session.userName ?: "User").ifBlank { "User" }
        binding.tvHrAvatarInitial.text = name.first().uppercase()
    }

    private fun setupActions() {
        binding.btnHrProfile.setOnClickListener {
            parentFragmentManager.beginTransaction().replace(R.id.fragmentContainer, ProfileFragment()).addToBackStack(null).commit()
        }
        binding.btnHrBell.setOnClickListener {
            parentFragmentManager.beginTransaction().replace(R.id.fragmentContainer, NotificationsFragment()).addToBackStack(null).commit()
        }
        binding.btnClockInNow.setOnClickListener {
            parentFragmentManager.beginTransaction().replace(R.id.fragmentContainer, ClockInAreaFragment()).addToBackStack(null).commit()
        }
        binding.btnClockOut.setOnClickListener {
            ClockOutConfirmBottomSheet().show(parentFragmentManager, "out_confirm")
        }
    }

    private fun setupScroll() {
        binding.hrScrollView.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            if (scrollY > 50 && !isBannerCollapsed && bannerMeasuredHeight > 0) {
                collapseBanner()
            } else if (scrollY < 10 && isBannerCollapsed) {
                expandBanner()
            }
        })
    }

    private fun collapseBanner() {
        if (isBannerCollapsed) return
        isBannerCollapsed = true
        val animator = android.animation.ValueAnimator.ofInt(binding.hrBannerExpandable.height, 0)
        animator.addUpdateListener { anim ->
            if (_binding == null) return@addUpdateListener
            val value = anim.animatedValue as Int
            binding.hrBannerExpandable.layoutParams = binding.hrBannerExpandable.layoutParams.apply { height = value }
        }
        animator.duration = 300
        animator.start()
        binding.hrBannerExpandable.animate().alpha(0f).setDuration(200).start()
    }

    private fun expandBanner() {
        if (!isBannerCollapsed) return
        isBannerCollapsed = false
        val animator = android.animation.ValueAnimator.ofInt(0, bannerMeasuredHeight)
        animator.addUpdateListener { anim ->
            if (_binding == null) return@addUpdateListener
            val value = anim.animatedValue as Int
            binding.hrBannerExpandable.layoutParams = binding.hrBannerExpandable.layoutParams.apply { height = value }
        }
        animator.duration = 300
        animator.start()
        binding.hrBannerExpandable.animate().alpha(1f).setDuration(300).start()
    }

    private fun animateBannerExpansion() {
        if (hasAnimatedBanner || _binding == null) return
        hasAnimatedBanner = true
        
        binding.hrBannerExpandable.visibility = View.VISIBLE
        binding.hrBannerExpandable.alpha = 0f
        
        binding.hrBannerExpandable.post {
            if (_binding == null) return@post
            val widthSpec = View.MeasureSpec.makeMeasureSpec(binding.hrBannerExpandable.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            binding.hrBannerExpandable.measure(widthSpec, heightSpec)
            bannerMeasuredHeight = binding.hrBannerExpandable.measuredHeight

            if (bannerMeasuredHeight <= 0) {
                hasAnimatedBanner = false
                return@post
            }

            val animator = android.animation.ValueAnimator.ofInt(0, bannerMeasuredHeight)
            animator.addUpdateListener { anim ->
                if (_binding == null) return@addUpdateListener
                val value = anim.animatedValue as Int
                binding.hrBannerExpandable.layoutParams = binding.hrBannerExpandable.layoutParams.apply { height = value }
            }
            animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    _binding?.hrBannerExpandable?.animate()?.alpha(1f)?.setDuration(400)?.start()
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
                flowViewModel.uiState.collect { state ->
                    isTodayLoading = state.isLoading
                    updateAttendanceLoadingUi()
                    
                    binding.tvTodayHours.text = state.todayHours
                    binding.tvPayPeriodLabel.text = state.payPeriodLabel.ifBlank { "Current Period" }
                    binding.tvPayPeriodHours.text = state.payPeriodHours
                    binding.btnClockInNow.isEnabled = !state.isLoading && !state.isSubmitting
                    binding.btnClockOut.isEnabled = !state.isLoading && !state.isSubmitting
                    
                    if (state.isClockedIn) {
                        binding.clockInButtonGroup.visibility = View.GONE
                        binding.clockedInButtonGroup.visibility = View.VISIBLE
                        startLiveTodayTicker(state.firstPunchInIso)
                    } else {
                        binding.clockInButtonGroup.visibility = View.VISIBLE
                        binding.clockedInButtonGroup.visibility = View.GONE
                        stopLiveTodayTicker()
                    }
                }
            }
        }
    }

    private fun startLiveTodayTicker(firstPunchIso: String?) {
        if (liveTickerJob?.isActive == true || firstPunchIso.isNullOrBlank()) return
        val firstMillis = parseIsoMillisOrNull(firstPunchIso) ?: return
        liveTickerJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && _binding != null) {
                val elapsed = (System.currentTimeMillis() - firstMillis).coerceAtLeast(0)
                _binding?.tvTodayHours?.text = AttendanceFlowViewModel.formatMinutesForToday((elapsed / 60000).toInt())
                delay(1000L)
            }
        }
    }

    private fun stopLiveTodayTicker() {
        liveTickerJob?.cancel()
        liveTickerJob = null
    }

    private fun parseIsoMillisOrNull(iso: String): Long? {
        val pats = arrayOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        for (p in pats) {
            try {
                return SimpleDateFormat(p, Locale.US).parse(iso)?.time
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    private fun collectEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                flowViewModel.events.collect { event ->
                    when (event) {
                        is AttendanceFlowEvent.Error -> Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                        is AttendanceFlowEvent.SubmissionFailed -> {
                            Toast.makeText(requireContext(), "${event.message} Please retry.", Toast.LENGTH_LONG).show()
                            loadRecentHistoryCards()
                        }
                        is AttendanceFlowEvent.Success -> loadRecentHistoryCards()
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
        hasAnimatedBanner = false
        isBannerCollapsed = false
        bannerMeasuredHeight = 0
    }

    private fun hasPunchPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return cam && (fine || coarse)
    }

    private fun beginPunchCapture(mode: PunchMode) {
        if (isLaunchingCamera) return
        pendingPunchMode = mode
        if (hasPunchPermissions()) {
            launchSystemCamera()
        } else {
            isLaunchingCamera = true
            capturePermissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    private fun launchSystemCamera() {
        val imageFile = createPunchPhotoFile() ?: return
        pendingPunchImageFile = imageFile
        val imageUri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", imageFile)
        pendingPunchImageUri = imageUri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(requireContext().contentResolver, "PunchSelfie", imageUri)
        }
        try {
            isLaunchingCamera = true
            captureSelfieLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "No camera app available.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createPunchPhotoFile(): File? = try {
        val dir = File(requireContext().cacheDir, "punch_photos").apply { if (!exists()) mkdirs() }
        File.createTempFile("punch_selfie_", ".jpg", dir)
    } catch (e: Exception) { null }

    private fun loadRecentHistoryCards() {
        isHistoryLoading = true
        updateAttendanceLoadingUi()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cal = Calendar.getInstance()
                val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val from = String.format(Locale.US, "%04d-%02d-01", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                val resp = api.getMyAttendance(session.bearerToken, from, ymd.format(cal.time))
                bindRecentHistoryCards(if (resp.success) resp.records else emptyList())
            } catch (e: Exception) {
                bindRecentHistoryCards(emptyList())
            } finally {
                isHistoryLoading = false
                updateAttendanceLoadingUi()
            }
        }
    }

    private fun bindRecentHistoryCards(records: List<AttendanceRecord>) {
        recentHistoryRecords = records
        val sorted = records.sortedByDescending { it.date ?: "" }
        val primary = sorted.getOrNull(0)
        
        if (primary != null) {
            binding.cardHistory1.visibility = View.VISIBLE
            binding.tvHistoryDate1.text = formatDashboardDate(primary.date)
            binding.tvLatestTotalHours.text = formatMinutesAsPeriod(primary.totalMinutes ?: 0)
            binding.tvLatestRange.text = buildPunchRange(primary)
        } else {
            binding.cardHistory1.visibility = View.GONE
        }
        
        binding.historyListContainer.removeAllViews()
        for (i in 1 until sorted.size) {
            val record = sorted[i]
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_attendance_history_card, binding.historyListContainer, false)
            card.findViewById<TextView>(R.id.tvHistoryItemDate).text = formatDashboardDate(record.date)
            card.findViewById<TextView>(R.id.tvHistoryItemHours).text = formatMinutesAsPeriod(record.totalMinutes ?: 0)
            card.findViewById<TextView>(R.id.tvHistoryItemRange).text = buildPunchRange(record)
            binding.historyListContainer.addView(card)
        }
    }

    private fun formatDashboardDate(date: String?): String {
        val raw = date ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        return try {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw)
            SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(parsed ?: Date())
        } catch (e: Exception) { raw }
    }

    private fun formatMinutesAsPeriod(m: Int): String {
        return String.format(Locale.US, "%02d:%02d:00 hrs", m / 60, m % 60)
    }

    private fun buildPunchRange(r: AttendanceRecord): String {
        val first = r.punchInTime ?: r.sessions?.firstOrNull()?.punchInTime
        val last = r.punchOutTime ?: r.sessions?.lastOrNull()?.punchOutTime
        val inL = first?.let { formatIsoTime(it) } ?: "--"
        val outL = if (r.hasOpenSession == true) "--" else (last?.let { formatIsoTime(it) } ?: "--")
        return "$inL - $outL"
    }

    private fun formatIsoTime(iso: String): String {
        val millis = parseIsoMillis(iso) ?: return "--"
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun parseIsoMillis(iso: String): Long? {
        val pats = arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        for (p in pats) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(iso)?.time
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    private suspend fun fetchLocationOrNull(): Location? = try {
        val client = LocationServices.getFusedLocationProviderClient(requireContext())
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await() ?: client.lastLocation.await()
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun resolveAddress(l: Location): String? = try {
        if (Geocoder.isPresent()) Geocoder(requireContext(), Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.getAddressLine(0) else null
    } catch (e: Exception) { null }

    private fun navigateToPunchDetail(m: PunchMode, p: String, lat: Double, lon: Double, a: String?) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, SelfieClockInDetailFragment.newInstance(m.name, p, lat, lon, a))
            .addToBackStack(null)
            .commit()
    }

    private fun updateAttendanceLoadingUi() {
        if (_binding == null) return
        
        val showSkeleton = isHistoryLoading
        
        binding.hrLoadingOverlay.visibility = View.GONE
        binding.tvTodayHours.alpha = if (isTodayLoading) 0.5f else 1.0f

        if (showSkeleton) {
            if (!wasShowingSkeleton) {
                val pulse = AnimationUtils.loadAnimation(requireContext(), R.anim.skeleton_pulse)
                forEachLeafBlock(binding.attendanceSkeletonContainer) { it.startAnimation(pulse) }
                wasShowingSkeleton = true
                binding.attendanceSkeletonContainer.visibility = View.VISIBLE
            }
        } else {
            if (wasShowingSkeleton) {
                forEachLeafBlock(binding.attendanceSkeletonContainer) { it.clearAnimation() }
                bindRecentHistoryCards(recentHistoryRecords)
                wasShowingSkeleton = false
                binding.attendanceSkeletonContainer.visibility = View.GONE
            }
        }
    }

    private fun forEachLeafBlock(group: ViewGroup, action: (View) -> Unit) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is ViewGroup) forEachLeafBlock(child, action) else action(child)
        }
    }
}
