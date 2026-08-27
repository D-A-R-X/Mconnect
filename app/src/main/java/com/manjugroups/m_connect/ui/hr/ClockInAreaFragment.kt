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
import android.provider.MediaStore
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.databinding.FragmentClockInAreaBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.HomeFenceData
import com.manjugroups.m_connect.network.TodayShiftDay
import com.manjugroups.m_connect.network.TodayShiftResponse
import com.manjugroups.m_connect.network.TodayShiftSchedule
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.pushDetail
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.manjugroups.m_connect.ui.common.commitOnce

class ClockInAreaFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentClockInAreaBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private var googleMap: GoogleMap? = null
    private var pendingPunchMode: PunchMode? = null
    private var pendingPunchImageFile: File? = null
    private var pendingPunchImageUri: android.net.Uri? = null
    private var isLaunchingCamera = false
    /** True once a capture came back, so the dismiss callback can tell a
     *  confirmed selfie from the user backing out of the camera. */
    private var punchSelfieHandled = false

    // Home geofence enforcement — mirrors HrDashboardFragment. Blocks
    // the Selfie-To-Clock-In path the same way the dashboard blocks
    // Clock-In Now, so the staff can't sidestep the dashboard guard by
    // tapping through to this screen first.
    private var homeFence: HomeFenceData? = null
    private var isInsideHomeFence: Boolean = false
    // Last good GPS fix this screen obtained — reused as a fallback so a
    // momentary dropout doesn't dead-end the punch flow, and as the truth
    // signal for the "you are in the clock-in area" banner.
    private var lastGoodFix: Location? = null
    // Bounded auto-retry when the first fix attempt comes back empty.
    private var locationAutoRetries = 0
    private var geofenceWatcherJob: Job? = null
    // Geographic circle for the home fence drawn on the Clock-In Area
    // map. Stays anchored to the home pin's coords — Google Maps moves
    // it across the screen as the user pans, which is the correct
    // behaviour for a real geofence.
    private var homeFenceCircle: Circle? = null
    private var lastFenceStatus: String? = null
    // Real geo-anchored marker for the user's position. Replaces the
    // userPinOuter screen overlay so the "V" stays at the staff's
    // actual latitude/longitude when the map is panned.
    private var userMarker: Marker? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            updateUserLocationOnMap()
        } else {
            binding.tvProfileLatLng.text = "Location permission not granted"
            Toast.makeText(requireContext(), "Location permission is needed to show your map position.", Toast.LENGTH_SHORT).show()
        }
    }

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraGranted && (fineGranted || coarseGranted)) {
            launchSelfieCamera()
        } else {
            isLaunchingCamera = false
            Toast.makeText(
                requireContext(),
                "Camera and GPS permissions are required for punch.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClockInAreaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBar.updatePadding(top = sys.top)
            binding.bottomActionPanel.updatePadding(bottom = sys.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        renderProfileInfo()

        binding.mapViewClockIn.onCreate(savedInstanceState)
        binding.mapViewClockIn.getMapAsync(this)

        binding.btnBack.setOnClickListener {
            navigateUp()
        }

        binding.btnRefresh.setOnClickListener {
            updateUserLocationOnMap()
            loadTodayShift()
        }

        binding.btnSelfieClockIn.setOnClickListener {
            // Button stays visually active. Refuse the tap with the
            // "You are at Home!" warning dialog if the user is inside
            // their home fence.
            if (isInsideHomeFence) {
                HomeFenceWarningDialog.show(parentFragmentManager)
                return@setOnClickListener
            }
            if (lastGoodFix != null) {
                beginPunchCapture(PunchMode.PUNCH_IN)
                return@setOnClickListener
            }
            // No fix yet — resolve one BEFORE launching the camera, so the
            // user isn't dead-ended by the GPS toast after taking a selfie.
            binding.layoutPunchLoading.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                // Warm up a location fix, but a null (offline / indoors) must NOT
                // stop the clock-in — begin the capture anyway. Location is
                // re-fetched after the selfie and a null location is allowed
                // downstream (the punch records + queues offline).
                fetchLocationOrNull()
                if (_binding == null) return@launch
                binding.layoutPunchLoading.visibility = View.GONE
                updateAreaBanner()
                beginPunchCapture(PunchMode.PUNCH_IN)
            }
        }

        loadTodayShift()
        loadHomeFence()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        (activity as? MainActivity)?.setTopBarAppearance(Color.TRANSPARENT, true, fullBleed = true)
        _binding?.mapViewClockIn?.onResume()
        // Refresh fence policy + restart the watcher so coming back to
        // this screen picks up an HR-side change (radius tweak / toggle
        // flip) without restarting the app.
        loadHomeFence()
        startGeofenceWatcher()
    }

    override fun onStart() {
        super.onStart()
        _binding?.mapViewClockIn?.onStart()
    }

    override fun onPause() {
        _binding?.mapViewClockIn?.onPause()
        stopGeofenceWatcher()
        super.onPause()
    }

    override fun onStop() {
        _binding?.mapViewClockIn?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        _binding?.mapViewClockIn?.onLowMemory()
    }

    override fun onDestroyView() {
        _binding?.mapViewClockIn?.onDestroy()
        googleMap = null
        super.onDestroyView()
        _binding = null
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map.apply {
            uiSettings.isMapToolbarEnabled = false
            uiSettings.isCompassEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isRotateGesturesEnabled = false
            uiSettings.isTiltGesturesEnabled = false
            // Scroll is now ON — the user can pan the map for context.
            // The V isn't a screen overlay anymore: it's rendered as a
            // real geo-anchored marker (see updateUserMarker below), so
            // it stays at the staff's actual latitude/longitude when
            // the map is panned.
        }
        // Hide the old screen-anchored overlay; the geo-anchored marker
        // takes over.
        binding.userPinOuter.visibility = View.GONE
        // If the fence is already loaded, draw it the moment the map is
        // ready (don't wait for the next watcher tick).
        homeFenceCircle = null
        drawHomeFenceCircleOnMap()
        updateUserLocationOnMap()
    }

    private fun renderProfileInfo() {
        val rawName = (session.userName ?: "User").ifBlank { "User" }
        val normalized = rawName
            .lowercase()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { segment -> segment.replaceFirstChar { it.titlecase() } }
        binding.tvProfileName.text = normalized
        binding.tvUserPinInitial.text = normalized.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        binding.tvProfileDate.text = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date())
        binding.tvProfileLatLng.text = "Fetching current location..."
    }

    private fun updateUserLocationOnMap() {
        if (!hasLocationPermission()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchLocationOrNull()
            if (_binding == null) return@launch
            if (location == null) {
                binding.tvProfileLatLng.text = "Unable to fetch current GPS location"
                updateAreaBanner()
                // Don't strand the map at world zoom: frame the home fence —
                // the only geo anchor we know — while waiting for a fix.
                val fence = homeFence
                val fLat = fence?.lat
                val fLng = fence?.lng
                if (fLat != null && fLng != null) {
                    googleMap?.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(fLat, fLng), 16f)
                    )
                }
                // GPS often just needs a warm-up — retry a couple of times.
                if (locationAutoRetries < 2) {
                    locationAutoRetries++
                    binding.root.postDelayed(
                        { if (_binding != null) updateUserLocationOnMap() },
                        5_000L,
                    )
                }
                return@launch
            }
            locationAutoRetries = 0
            val lat = location.latitude
            val lng = location.longitude
            binding.tvProfileLatLng.text = String.format(Locale.US, "Lat %.6f Long %.6f", lat, lng)
            // Re-evaluate the home fence with the fresh fix; that path also
            // refreshes the banner.
            refreshGeofenceState()

            val map = googleMap ?: return@launch
            val point = LatLng(lat, lng)
            map.clear()
            // map.clear() removes ALL overlays — null the cached
            // handles so the next draw re-adds them instead of trying
            // to patch removed objects.
            homeFenceCircle = null
            userMarker = null
            drawHomeFenceCircleOnMap()
            updateUserMarker(point)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 17f))
        }
    }

    /** Keep the blue banner truthful: it only claims "in the clock-in area"
     *  once we actually have a fix and the user isn't inside the home fence. */
    private fun updateAreaBanner() {
        val b = _binding ?: return
        when {
            lastGoodFix == null -> {
                b.tvAreaBannerTitle.text = "Getting your location…"
                b.tvAreaBannerSubtitle.text = "Make sure GPS is on to clock in"
            }
            isInsideHomeFence -> {
                b.tvAreaBannerTitle.text = "You're inside your home area"
                b.tvAreaBannerSubtitle.text = "Move away from home to clock in"
            }
            else -> {
                b.tvAreaBannerTitle.text = "You are in the clock-in area!"
                b.tvAreaBannerSubtitle.text = "Now you can press clock in in this area"
            }
        }
    }

    /** Render the existing userPinOuter View to a Bitmap so we can
     *  reuse the same design as a real map marker. */
    private fun renderUserPinBitmap(): BitmapDescriptor? {
        val v = binding.userPinOuter
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return null
        val bm = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888,
        )
        v.draw(android.graphics.Canvas(bm))
        return BitmapDescriptorFactory.fromBitmap(bm)
    }

    /** Add or move the geo-anchored user marker. Center-anchored so the
     *  avatar inside the V circle sits on the user's actual lat/lng. */
    private fun updateUserMarker(latLng: LatLng) {
        val map = googleMap ?: return
        val icon = renderUserPinBitmap()
        val existing = userMarker
        if (existing != null) {
            existing.position = latLng
            if (icon != null) existing.setIcon(icon)
            return
        }
        userMarker = map.addMarker(
            MarkerOptions()
                .position(latLng)
                .anchor(0.5f, 0.5f)
                .let { if (icon != null) it.icon(icon) else it }
        )
    }

    private fun loadTodayShift() {
        val staffId = session.staffId
        val token = session.bearerToken
        if (staffId.isNullOrBlank() || token.isBlank()) return
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching { api.getTodayShift(token, staffId, today) }.getOrNull()
            applyShiftToSchedule(response)
        }
    }

    private fun applyShiftToSchedule(response: TodayShiftResponse?) {
        val binding = _binding ?: return
        if (response == null || response.isWeekoff == true) {
            binding.tvShiftClockIn.text = "--:--"
            binding.tvShiftClockOut.text = "--:--"
            return
        }
        val day = response.shift?.schedule?.let { todayFromSchedule(it) }
        binding.tvShiftClockIn.text = formatShiftTime(day?.startTime)
        binding.tvShiftClockOut.text = formatShiftTime(day?.endTime)
    }

    private fun todayFromSchedule(schedule: TodayShiftSchedule): TodayShiftDay? =
        when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> schedule.sunday
            Calendar.MONDAY -> schedule.monday
            Calendar.TUESDAY -> schedule.tuesday
            Calendar.WEDNESDAY -> schedule.wednesday
            Calendar.THURSDAY -> schedule.thursday
            Calendar.FRIDAY -> schedule.friday
            Calendar.SATURDAY -> schedule.saturday
            else -> null
        }

    private fun formatShiftTime(raw: String?): String {
        if (raw.isNullOrBlank()) return "--:--"
        return runCatching {
            val patterns = listOf("HH:mm:ss", "HH:mm", "hh:mm a", "h:mm a")
            for (pattern in patterns) {
                val parser = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val parsed = runCatching { parser.parse(raw) }.getOrNull()
                if (parsed != null) {
                    return@runCatching SimpleDateFormat("HH:mm", Locale.US).format(parsed)
                }
            }
            raw
        }.getOrElse { raw }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    private fun hasPunchPermissions(): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && hasLocationPermission()
    }

    private fun beginPunchCapture(mode: PunchMode) {
        if (isLaunchingCamera) return
        pendingPunchMode = mode
        if (hasPunchPermissions()) {
            launchSelfieCamera()
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

    private fun launchSelfieCamera() {
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
        // In-app FRONT-lens-only camera. The system ACTION_IMAGE_CAPTURE
        // intent used before could not enforce a selfie: its camera-facing
        // extras are OEM hints that most camera apps ignore, and the user
        // could always flip to the rear lens (or pick a gallery image on some
        // OEM camera apps). CustomCameraBottomSheet in selfieOnly mode locks
        // the front lens and hides the switch / gallery / video controls.
        // Plain local, NOT .apply{} — inside apply the sheet becomes the
        // implicit receiver and unqualified requireContext() resolves to IT.
        val camera = com.manjugroups.m_connect.ui.chat.CustomCameraBottomSheet()
        camera.setSelfieOnly(true)
        camera.setListener(object :
            com.manjugroups.m_connect.ui.chat.CustomCameraBottomSheet.CameraResultListener {
            override fun onMediaCaptured(uri: android.net.Uri, isVideo: Boolean) {
                if (isVideo) return
                onPunchSelfieCaptured(uri)
            }

            // Unreachable in selfieOnly mode (the gallery button is hidden),
            // but the interface requires it.
            override fun onGalleryClicked() = Unit
        })
        // Closing the camera without capturing must release the re-entrancy
        // guard, otherwise the Clock In button stays dead for the rest of the
        // screen's life. A confirmed capture sets punchSelfieHandled first, so
        // this only fires for a genuine cancel.
        punchSelfieHandled = false
        camera.setOnDismissedListener {
            if (!punchSelfieHandled) isLaunchingCamera = false
        }
        isLaunchingCamera = true
        camera.showOnce(parentFragmentManager, "punch_selfie_camera")
    }

    /**
     * Shared post-capture path for the punch selfie: copy the captured photo
     * into the pending punch file, resolve a best-effort location, and hand
     * off to the punch-detail screen. Mirrors what the old
     * ActivityResultLauncher callback did.
     */
    private fun onPunchSelfieCaptured(uri: android.net.Uri) {
        punchSelfieHandled = true
        if (!isAdded || _binding == null) return
        val mode = pendingPunchMode
        val target = pendingPunchImageFile
        if (mode == null || target == null) {
            isLaunchingCamera = false
            return
        }
        val copied = runCatching {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied || !target.exists() || target.length() <= 0L) {
            isLaunchingCamera = false
            Toast.makeText(requireContext(), "Failed to read captured selfie.", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Best-effort location. Offline / indoors it may be null — that must
            // NOT block the clock-in. The punch still records and queues offline
            // when there's no network; the backend accepts an optional location.
            val location = fetchLocationOrNull()
            val address = location?.let { resolveAddress(it) }
            isLaunchingCamera = false
            navigateToPunchDetail(
                mode = mode,
                photoPath = target.absolutePath,
                latitude = location?.latitude,
                longitude = location?.longitude,
                address = address,
            )
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
        latitude: Double?,
        longitude: Double?,
        address: String?,
    ) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.pushDetail(
            SelfieClockInDetailFragment.newInstance(
                mode = mode.name,
                photoPath = photoPath,
                latitude = latitude,
                longitude = longitude,
                address = address,
                // Carry the trip that triggered this clock-in so Home can
                // auto-start it once the punch succeeds.
                targetVisitId = arguments?.getString(ARG_TARGET_VISIT_ID),
            ),
        )
    }

    private suspend fun fetchLocationOrNull(): Location? {
        if (!isAdded) return lastGoodFix
        val fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        // getCurrentLocation can hang indefinitely on a cold GPS start (the
        // token was never cancelled before), which is what left this screen
        // stuck on "Unable to fetch". Give each attempt a hard deadline,
        // fall back to lastLocation, and try once more — the first request
        // warms the GPS chip up, so the retry usually lands.
        repeat(2) { attempt ->
            val cts = CancellationTokenSource()
            val current = try {
                kotlinx.coroutines.withTimeoutOrNull(if (attempt == 0) 6_000L else 10_000L) {
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                        .await()
                } ?: run { cts.cancel(); null }
            } catch (_: Exception) {
                cts.cancel()
                null
            }
            val fix = current ?: try {
                fusedClient.lastLocation.await()
            } catch (_: Exception) {
                null
            }
            if (fix != null && !(fix.latitude == 0.0 && fix.longitude == 0.0)) {
                lastGoodFix = fix
                return fix
            }
        }
        // Both attempts dry — an earlier fix from this screen is still far
        // better than dead-ending the flow.
        return lastGoodFix
    }

    // ── Home geofence enforcement (same shape as HrDashboardFragment) ──
    //
    // Single fetch per resume + a 15 s watcher; recomputes distance via
    // haversine and disables btnSelfieClockIn the moment the user is
    // inside their enforceable home radius. Server-side checkHomeBlock
    // is the final word, so a stale tick can only ever fail-open and
    // get rejected with ATTENDANCE_BLOCKED_LOCATION at punch time.

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
            refreshGeofenceState()
        }
    }

    private suspend fun computeInsideHomeFence(): Boolean {
        val fence = homeFence
        if (fence == null) { lastFenceStatus = "Home-block policy off (HR setting)"; return false }
        if (!fence.enabled) { lastFenceStatus = "Home-block policy off"; return false }
        val lat = fence.lat
        val lng = fence.lng
        if (lat == null || lng == null) {
            lastFenceStatus = "No home pin set for this staff"
            return false
        }
        if (!hasLocationPermission()) {
            lastFenceStatus = "Grant location permission to enforce home fence"
            return false
        }
        val loc = fetchLocationOrNull()
        if (loc == null || (loc.latitude == 0.0 && loc.longitude == 0.0)) {
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

    private fun refreshGeofenceState() {
        viewLifecycleOwner.lifecycleScope.launch {
            val inside = computeInsideHomeFence()
            if (_binding == null) return@launch
            isInsideHomeFence = inside
            applyGeofenceToButton()
            updateAreaBanner()
        }
    }

    private fun applyGeofenceToButton() {
        if (_binding == null) return
        // Button stays visually active — no drawable swap, no banner.
        // The click listener is the only enforcement, via a top Toast.
        binding.tvClockInFenceStatus.visibility = View.GONE
        drawHomeFenceCircleOnMap()
    }

    /** Brief top-of-screen Toast for the geofence refusal. */
    private fun showTopToast(message: String) {
        val act = activity ?: return
        com.manjugroups.m_connect.ui.common.TopToast.show(act, message)
    }

    /**
     * Draw the home geofence circle on the Clock-In Area map so the
     * staff can see WHERE their home fence is. The circle is anchored
     * to the home pin's coordinates — when the user pans the map the
     * circle moves across the screen with the map, which is correct
     * geographic anchoring (not a bug).
     */
    private fun drawHomeFenceCircleOnMap() {
        val map = googleMap ?: return
        val fence = homeFence
        val lat = fence?.lat
        val lng = fence?.lng
        if (fence == null || !fence.enabled || lat == null || lng == null) {
            homeFenceCircle?.remove()
            homeFenceCircle = null
            return
        }
        val existing = homeFenceCircle
        if (existing == null) {
            homeFenceCircle = map.addCircle(
                CircleOptions()
                    .center(LatLng(lat, lng))
                    .radius(fence.radiusMeters.toDouble())
                    .strokeWidth(3f)
                    // Red border + faint red fill — distinct from the
                    // work-area circle (blue) so the two are visually
                    // distinguishable on the same map.
                    .strokeColor(android.graphics.Color.parseColor("#DC2626"))
                    .fillColor(android.graphics.Color.parseColor("#33DC2626")),
            )
        } else {
            existing.center = LatLng(lat, lng)
            existing.radius = fence.radiusMeters.toDouble()
        }
    }

    private fun startGeofenceWatcher() {
        if (geofenceWatcherJob?.isActive == true) return
        geofenceWatcherJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && _binding != null) {
                refreshGeofenceState()
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

    companion object {
        private const val ARG_TARGET_VISIT_ID = "arg_target_visit_id"

        /**
         * @param targetVisitId when set, a successful PUNCH_IN emits
         * [SelfieClockInDetailFragment.RESULT_KEY_CLOCK_IN_FOR_TRIP] so Home can
         * auto-start that trip — used when the staffer taps "Start Trip" while
         * clocked out and is routed here to clock in first.
         */
        fun newInstance(targetVisitId: String? = null): ClockInAreaFragment =
            ClockInAreaFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TARGET_VISIT_ID, targetVisitId)
                }
            }
    }
}
