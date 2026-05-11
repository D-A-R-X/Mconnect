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
    private var isLaunchingCamera = false
    private var isTodayLoading = true
    private var isHistoryLoading = true
    private var wasShowingSkeleton = false
    private var recentHistoryRecords: List<AttendanceRecord> = emptyList()
    private var liveTickerJob: Job? = null

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
            ClockOutConfirmBottomSheet().show(parentFragmentManager, "clock_out_confirm")
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
                    ClockOutSuccessBottomSheet().show(parentFragmentManager, "clock_out_success")
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
                    isTodayLoading = state.isLoading
                    updateAttendanceLoadingUi()
                    binding.tvTodayHours.text = state.todayHours
                    binding.tvLatestTotalHours.text = state.latestTotalHours
                    binding.tvLatestRange.text = state.latestRange
                    binding.tvPayPeriodLabel.text = state.payPeriodLabel
                        .ifBlank { binding.tvPayPeriodLabel.text }
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
                            // Background upload/punch failed after the optimistic Success
                            // sheet was already shown. Surface a clear retry prompt.
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
        val sorted = records.sortedByDescending { it.date ?: "" }
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
        binding.attendanceSkeletonContainer.visibility = if (showSkeleton) View.VISIBLE else View.GONE
        binding.cardAttendanceSummary.visibility = if (showSkeleton) View.GONE else View.VISIBLE
        binding.cardHistory1.visibility = if (showSkeleton) View.GONE else View.VISIBLE
        if (showSkeleton) {
            binding.cardHistory2.visibility = View.GONE
            binding.cardHistory3.visibility = View.GONE
            if (!wasShowingSkeleton) {
                val pulse = AnimationUtils.loadAnimation(requireContext(), R.anim.skeleton_pulse)
                // Only fade the leaf block Views — keep white card backgrounds
                // fully opaque so the blue header gradient doesn't bleed through.
                forEachLeafBlock(binding.attendanceSkeletonContainer) { it.startAnimation(pulse) }
            }
        } else if (!showSkeleton && wasShowingSkeleton) {
            forEachLeafBlock(binding.attendanceSkeletonContainer) { it.clearAnimation() }
            bindRecentHistoryCards(recentHistoryRecords)
        }
        wasShowingSkeleton = showSkeleton
    }

    private fun forEachLeafBlock(group: android.view.ViewGroup, action: (View) -> Unit) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.view.ViewGroup) forEachLeafBlock(child, action)
            else action(child)
        }
    }
}
