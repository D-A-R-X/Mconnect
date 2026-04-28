package com.manjugroups.m_connect.ui.home

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.GeoTrackConsentActivity
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ArrivalOtpRequestBody
import com.manjugroups.m_connect.network.CompleteVisitRequest
import com.manjugroups.m_connect.network.CreateVisitRequest
import com.manjugroups.m_connect.network.DirectionsClient
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.StartVisitRequest
import com.manjugroups.m_connect.network.TrackingBootstrapData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-app navigation page for an active site visit.
 *
 * Two entry modes:
 *   • Existing scheduled visit  — pass `visitId` (visit row already exists)
 *   • Ad-hoc trip from a place  — pass `placeId` (creates visit then starts)
 *
 * Once the start API succeeds the map renders origin + destination + a
 * straight-line route. The "I've Arrived" button currently completes the
 * visit; this will be replaced with the OTP-verify flow in a later step.
 */
class TripNavigationFragment : Fragment(), OnMapReadyCallback {

    private val geoApi = GeoTrackApi.create()
    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var routePolyline: Polyline? = null

    private var currentLocation: LatLng? = null
    private var destination: LatLng? = null
    private var visitId: String? = null
    private var visitStarted = false
    private var arrivalInProgress = false
    private var pendingArrivalPhoto: File? = null
    private var pendingArrivalPhotoUri: Uri? = null
    // KOS-37: CP-visit context — set from fragment args. The decision flag
    // tracks whether we already collected Client Met + Outcome for this run.
    private var tripType: String? = null
    private var cpVisitId: String? = null
    private var cpVisitDecisionCaptured: Boolean = false

    private var tvTitle: TextView? = null
    private var tvDestName: TextView? = null
    private var tvDestAddress: TextView? = null
    private var tvDistance: TextView? = null
    private var tvEta: TextView? = null
    private var tvStatus: TextView? = null
    private var btnBack: ImageView? = null
    private var btnOpenMaps: Button? = null
    private var swipeArrived: SwipeToConfirmButton? = null
    private var loadingOverlay: FrameLayout? = null

    private val arrivalCameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val photoFile = pendingArrivalPhoto
        val uri = pendingArrivalPhotoUri
        if (uri != null) {
            runCatching {
                requireContext().revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        if (!isAdded) return@registerForActivityResult

        val ok = result.resultCode == Activity.RESULT_OK && photoFile != null && photoFile.exists()
        if (!ok) {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to mark arrived")
            Toast.makeText(requireContext(), "Photo capture cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        // Photo captured — upload then prompt for OTP.
        uploadArrivalPhotoThenAskOtp(photoFile!!)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchArrivalCamera()
        else {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to mark arrived")
            Toast.makeText(
                requireContext(),
                "Camera permission required to mark arrival",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_trip_navigation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        tvTitle = view.findViewById(R.id.tvTripTitle)
        tvDestName = view.findViewById(R.id.tvTripDestinationName)
        tvDestAddress = view.findViewById(R.id.tvTripDestinationAddress)
        tvDistance = view.findViewById(R.id.tvTripDistance)
        tvEta = view.findViewById(R.id.tvTripEta)
        tvStatus = view.findViewById(R.id.tvTripStatus)
        btnBack = view.findViewById(R.id.btnTripBack)
        btnOpenMaps = view.findViewById(R.id.btnOpenInMaps)
        swipeArrived = view.findViewById(R.id.swipeArrived)
        loadingOverlay = view.findViewById(R.id.tripLoadingOverlay)
        mapView = view.findViewById(R.id.mapViewTrip)

        val args = requireArguments()
        visitId = args.getString(ARG_VISIT_ID)
        val placeName = args.getString(ARG_PLACE_NAME) ?: "Destination"
        val placeAddress = args.getString(ARG_PLACE_ADDRESS)
        val destLat = if (args.containsKey(ARG_DEST_LAT)) args.getDouble(ARG_DEST_LAT) else null
        val destLng = if (args.containsKey(ARG_DEST_LNG)) args.getDouble(ARG_DEST_LNG) else null
        if (destLat != null && destLng != null) {
            destination = LatLng(destLat, destLng)
        }
        tripType = args.getString(ARG_TRIP_TYPE)
        cpVisitId = args.getString(ARG_CP_VISIT_ID)
        // If a prior session already recorded Client Met for this CP visit,
        // skip the bottom sheet on this run — we only need it once per visit.
        cpVisitDecisionCaptured = args.containsKey(ARG_CP_CLIENT_MET)

        tvTitle?.text = "Trip to $placeName"
        tvDestName?.text = placeName
        tvDestAddress?.text = placeAddress?.takeIf { it.isNotBlank() } ?: "Address not available"

        btnBack?.setOnClickListener { parentFragmentManager.popBackStack() }
        btnOpenMaps?.setOnClickListener { openInGoogleMaps() }
        swipeArrived?.onConfirmed = { onArrivalSwipeConfirmed() }

        // Listen for OTP verify result from the bottom sheet.
        setFragmentResultListener(ArrivalOtpBottomSheet.RESULT_KEY) { _, bundle ->
            val otp = bundle.getString(ArrivalOtpBottomSheet.KEY_OTP).orEmpty()
            onArrivalOtpVerified(otp)
        }

        // KOS-37: CP-visit only — listen for the Client Met / Outcome sheet
        // result and finalize completion afterward.
        setFragmentResultListener(CompleteCpVisitBottomSheet.RESULT_KEY) { _, _ ->
            cpVisitDecisionCaptured = true
            finalizeCompleteVisit()
        }

        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(this)

        // Kick off start-visit (or create+start) immediately
        ensureVisitStarted()
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        mapView?.onResume()
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onPause() {
        mapView?.onPause()
        // Restore tab bar so the parent (HomeFragment) shows it after pop.
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        mapView?.onDestroy()
        googleMap = null
        mapView = null
        super.onDestroyView()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map.apply {
            uiSettings.isMapToolbarEnabled = false
            uiSettings.isCompassEnabled = true
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isMyLocationButtonEnabled = false
        }
        if (hasLocationPermission()) {
            try {
                map.isMyLocationEnabled = true
            } catch (_: SecurityException) { /* race: permission revoked */ }
        }
        renderMapMarkersAndRoute()
        fetchCurrentLocationAndUpdate()
    }

    private fun ensureVisitStarted() {
        loadingOverlay?.visibility = View.VISIBLE
        tvStatus?.text = "Starting…"

        val args = requireArguments()
        val placeId = args.getString(ARG_PLACE_ID)
        val existingVisit = visitId
        val existingStatus = args.getString(ARG_STATUS).orEmpty().lowercase(Locale.getDefault())
        val alreadyInFlight = existingStatus in setOf(
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val location = if (hasLocationPermission()) fetchCurrentLocation() else null
                val effectiveVisitId = when {
                    existingVisit != null -> existingVisit
                    placeId != null -> {
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(Date())
                        val createResp = geoApi.createVisit(
                            session.bearerToken,
                            CreateVisitRequest(
                                clientPlaceId = placeId,
                                scheduledDate = today,
                                notes = "Ad-hoc trip started from mobile"
                            )
                        )
                        if (!createResp.success || createResp.visitId == null) {
                            failAndClose(createResp.error ?: "Failed to create visit")
                            return@launch
                        }
                        createResp.visitId
                    }
                    else -> {
                        failAndClose("Missing visit or place identifier")
                        return@launch
                    }
                }
                visitId = effectiveVisitId

                if (!alreadyInFlight) {
                    geoApi.startVisit(
                        session.bearerToken,
                        StartVisitRequest(effectiveVisitId, location?.latitude, location?.longitude)
                    )
                }

                // Refresh tracking session + start GeoTrackService
                val bootstrap = geoApi
                    .getTrackingBootstrap(session.bearerToken, session.trackingDeviceId)
                    .data
                applyTrackingBootstrap(bootstrap)

                visitStarted = true
                if (location != null) currentLocation = LatLng(location.latitude, location.longitude)
                loadingOverlay?.visibility = View.GONE
                tvStatus?.text = if (alreadyInFlight) "In progress" else "On the way"
                renderMapMarkersAndRoute()
                if (!alreadyInFlight) {
                    Toast.makeText(requireContext(), "Trip started", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                failAndClose("Failed to start trip: ${e.message}")
            }
        }
    }

    private fun failAndClose(message: String) {
        if (!isAdded) return
        loadingOverlay?.visibility = View.GONE
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        parentFragmentManager.popBackStack()
    }

    private fun fetchCurrentLocationAndUpdate() {
        if (!hasLocationPermission()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchCurrentLocation() ?: return@launch
            currentLocation = LatLng(location.latitude, location.longitude)
            renderMapMarkersAndRoute()
        }
    }

    private suspend fun fetchCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(requireContext())
            val token = CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).await()
        } catch (_: Exception) {
            null
        }
    }

    private fun renderMapMarkersAndRoute() {
        val map = googleMap ?: return
        val dest = destination ?: return
        map.clear()
        routePolyline = null

        map.addMarker(
            MarkerOptions()
                .position(dest)
                .title(tvDestName?.text?.toString() ?: "Destination")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        val origin = currentLocation
        if (origin != null) {
            map.addMarker(
                MarkerOptions()
                    .position(origin)
                    .title("You")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            )
            // Show a quick straight-line preview, then upgrade to road route.
            drawStraightFallback(origin, dest)
            updateDistanceAndEtaFromHaversine(origin, dest)
            val bounds = LatLngBounds.Builder().include(origin).include(dest).build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 160))
            fetchAndRenderRoadRoute(origin, dest)
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(dest, 14f))
            tvDistance?.text = "—"
            tvEta?.text = "—"
        }
    }

    private fun drawStraightFallback(origin: LatLng, dest: LatLng) {
        val map = googleMap ?: return
        routePolyline?.remove()
        routePolyline = map.addPolyline(
            PolylineOptions()
                .add(origin, dest)
                .width(6f)
                .color(Color.parseColor("#33795FFC"))
        )
    }

    private fun fetchAndRenderRoadRoute(origin: LatLng, dest: LatLng) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = DirectionsClient.fetchDriving(session.bearerToken, origin, dest)
                ?: return@launch
            val map = googleMap ?: return@launch
            if (result.polyline.size < 2) return@launch
            routePolyline?.remove()
            routePolyline = map.addPolyline(
                PolylineOptions()
                    .addAll(result.polyline)
                    .width(10f)
                    .color(Color.parseColor("#795FFC"))
            )
            val boundsBuilder = LatLngBounds.Builder()
            for (p in result.polyline) boundsBuilder.include(p)
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 160))

            tvDistance?.text =
                if (result.distanceText.isNotBlank()) result.distanceText
                else formatDistance(result.distanceMeters.toDouble())
            tvEta?.text =
                if (result.durationText.isNotBlank()) result.durationText
                else formatDuration(result.durationSeconds)
        }
    }

    private fun updateDistanceAndEtaFromHaversine(origin: LatLng, dest: LatLng) {
        val meters = haversineMeters(origin, dest)
        tvDistance?.text = formatDistance(meters)
        // Rough urban-driving ETA: 30 km/h average. Replaced by Directions API
        // result if/when the road-route call succeeds.
        val minutes = (meters / 500.0).roundToInt()
        tvEta?.text = if (minutes < 1) "<1 min" else "$minutes min"
    }

    private fun formatDuration(seconds: Int): String {
        val minutes = (seconds / 60.0).roundToInt()
        if (minutes < 60) return "$minutes min"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h hr" else "$h hr $m min"
    }

    private fun openInGoogleMaps() {
        val dest = destination ?: run {
            Toast.makeText(requireContext(), "Destination unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse("google.navigation:q=${dest.latitude},${dest.longitude}&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Fallback: any maps-capable app
            val webUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=${dest.latitude},${dest.longitude}&travelmode=driving"
            )
            try {
                startActivity(Intent(Intent.ACTION_VIEW, webUri))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(requireContext(), "No maps app available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Arrival flow: swipe → camera → upload → OTP → completeVisit ──────────

    private fun onArrivalSwipeConfirmed() {
        if (visitId == null) {
            Toast.makeText(requireContext(), "No active visit", Toast.LENGTH_SHORT).show()
            swipeArrived?.reset(newLabel = "Swipe to mark arrived")
            return
        }
        if (!visitStarted) {
            Toast.makeText(requireContext(), "Trip is still starting", Toast.LENGTH_SHORT).show()
            swipeArrived?.reset(newLabel = "Swipe to mark arrived")
            return
        }
        if (arrivalInProgress) return
        arrivalInProgress = true
        swipeArrived?.lockAsBusy("Opening camera…")

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        launchArrivalCamera()
    }

    private fun launchArrivalCamera() {
        val photoFile = createArrivalPhotoFile()
        if (photoFile == null) {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to mark arrived")
            Toast.makeText(requireContext(), "Unable to create photo file", Toast.LENGTH_SHORT).show()
            return
        }
        pendingArrivalPhoto = photoFile
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        pendingArrivalPhotoUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(
                requireContext().contentResolver,
                "ArrivalSelfie",
                uri
            )
        }
        try {
            arrivalCameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to mark arrived")
            Toast.makeText(requireContext(), "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadArrivalPhotoThenAskOtp(photoFile: File) {
        val id = visitId ?: run {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to mark arrived")
            return
        }
        swipeArrived?.lockAsBusy("Uploading photo…")
        viewLifecycleOwner.lifecycleScope.launch {
            val storageId = uploadArrivalPhoto(photoFile)
            if (storageId == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to mark arrived")
                Toast.makeText(
                    requireContext(),
                    "Photo upload failed. Try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            pendingArrivalStorageId = storageId

            // Use freshest GPS for geofence check on the server.
            swipeArrived?.lockAsBusy("Sending OTP to client…")
            val freshLocation = fetchCurrentLocation()
            val effLat = freshLocation?.latitude ?: currentLocation?.latitude
            val effLng = freshLocation?.longitude ?: currentLocation?.longitude
            if (effLat == null || effLng == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to mark arrived")
                Toast.makeText(
                    requireContext(),
                    "Could not read your GPS. Try again in open sky.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            try {
                val resp = geoApi.requestArrivalOtp(
                    session.bearerToken,
                    ArrivalOtpRequestBody(visitId = id, lat = effLat, lng = effLng)
                )
                if (!resp.success) {
                    arrivalInProgress = false
                    swipeArrived?.reset(newLabel = "Swipe to mark arrived")
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to send OTP",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                swipeArrived?.lockAsBusy("Enter OTP to confirm")
                ArrivalOtpBottomSheet.newInstance(
                    visitId = id,
                    phoneMasked = resp.contactPhoneMasked,
                    expiresInSeconds = resp.otpExpiresInSeconds ?: 600,
                    resendCooldownSeconds = resp.resendCooldownSeconds ?: 60,
                    lat = effLat,
                    lng = effLng,
                ).show(parentFragmentManager, "arrival_otp")
            } catch (e: Exception) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to mark arrived")
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun uploadArrivalPhoto(file: File): String? = withContext(Dispatchers.IO) {
        try {
            val body = file.asRequestBody("image/jpeg".toMediaType())
            val resp = api.uploadStorageFile(session.bearerToken, body)
            resp.storageId
        } catch (e: Exception) {
            android.util.Log.w("TripNav", "Arrival photo upload failed", e)
            null
        }
    }

    private fun onArrivalOtpVerified(@Suppress("UNUSED_PARAMETER") otp: String) {
        // The OTP itself is already verified server-side by /verify; here we
        // route through the CP-visit decision sheet when applicable, then
        // finalize the visit with the photo proof.
        if (visitId == null) return

        val isCpVisit = tripType == "client_place" && !cpVisitId.isNullOrBlank()
        if (isCpVisit && !cpVisitDecisionCaptured) {
            swipeArrived?.lockAsBusy("Capturing visit outcome…")
            CompleteCpVisitBottomSheet
                .newInstance(cpVisitId!!)
                .show(parentFragmentManager, "cp_visit_complete")
            return
        }
        finalizeCompleteVisit()
    }

    private fun finalizeCompleteVisit() {
        val id = visitId ?: return
        val storageId = pendingArrivalStorageId
        swipeArrived?.lockAsBusy("Completing visit…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loc = fetchCurrentLocation()
                // Photo + OTP are tracked in dedicated columns
                // (arrivalPhotoStorageId, arrivalVerifiedAt). `remarks` stays
                // human-readable so it can carry future free-text notes
                // without us having to parse it again.
                geoApi.completeVisit(
                    session.bearerToken,
                    CompleteVisitRequest(
                        visitId = id,
                        lat = loc?.latitude,
                        lng = loc?.longitude,
                        remarks = "Arrival verified",
                        arrivalPhotoStorageId = storageId,
                    )
                )
                val bootstrap = geoApi
                    .getTrackingBootstrap(session.bearerToken, session.trackingDeviceId)
                    .data
                applyTrackingBootstrap(bootstrap)
                Toast.makeText(requireContext(), "Visit completed", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to mark arrived")
                Toast.makeText(
                    requireContext(),
                    "Failed to complete: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createArrivalPhotoFile(): File? {
        return try {
            val dir = File(requireContext().cacheDir, "arrival_photos")
            if (!dir.exists()) dir.mkdirs()
            File.createTempFile("arrival_", ".jpg", dir)
        } catch (_: Exception) {
            null
        }
    }

    private var pendingArrivalStorageId: String? = null

    private fun applyTrackingBootstrap(bootstrap: TrackingBootstrapData?) {
        session.activeTrackingSessionId = bootstrap?.activeSession?.id
        session.shouldTrackNow = bootstrap?.shouldTrack == true
        session.geoTrackingEnabled =
            bootstrap?.assignment?.attendance != null || bootstrap?.assignment?.siteVisit != null
        session.geoConsentGiven = bootstrap?.consent?.status == "granted"
        session.geoConsentDeclined =
            bootstrap?.consent?.status == "declined" || bootstrap?.consent?.status == "revoked"

        if (bootstrap?.shouldPromptConsent == true) {
            startActivity(Intent(requireContext(), GeoTrackConsentActivity::class.java))
            return
        }
        if (bootstrap?.shouldTrack == true && !bootstrap.activeSession?.id.isNullOrBlank()) {
            GeoTrackService.start(requireContext())
        } else {
            GeoTrackService.stop(requireContext())
        }
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = requireContext()
        return ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun haversineMeters(a: LatLng, b: LatLng): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val sa = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) *
            sin(dLng / 2).let { it * it }
        val c = 2 * atan2(sqrt(sa), sqrt(1 - sa))
        return r * c
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) String.format(Locale.getDefault(), "%.1f km", meters / 1000.0)
        else "${meters.roundToInt()} m"

    companion object {
        private const val ARG_VISIT_ID = "arg_visit_id"
        private const val ARG_PLACE_ID = "arg_place_id"
        private const val ARG_PLACE_NAME = "arg_place_name"
        private const val ARG_PLACE_ADDRESS = "arg_place_address"
        private const val ARG_DEST_LAT = "arg_dest_lat"
        private const val ARG_DEST_LNG = "arg_dest_lng"
        private const val ARG_STATUS = "arg_status"
        // KOS-37: CP-visit context. tripType=client_place gates the
        // Client-Met / Outcome flow on completion. cpClientMet / cpOutcome let
        // us skip the bottom sheet when those decisions are already recorded
        // (e.g. resuming a visit that was partially completed).
        private const val ARG_TRIP_TYPE = "arg_trip_type"
        private const val ARG_CP_VISIT_ID = "arg_cp_visit_id"
        private const val ARG_CP_CLIENT_MET = "arg_cp_client_met"
        private const val ARG_CP_OUTCOME = "arg_cp_outcome"

        fun forVisit(
            visitId: String,
            placeName: String?,
            placeAddress: String?,
            destLat: Double?,
            destLng: Double?,
            status: String? = null,
            tripType: String? = null,
            clientPlaceVisitId: String? = null,
            cpClientMet: Boolean? = null,
            cpOutcome: String? = null,
        ): TripNavigationFragment = TripNavigationFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_VISIT_ID, visitId)
                putString(ARG_PLACE_NAME, placeName)
                putString(ARG_PLACE_ADDRESS, placeAddress)
                if (destLat != null) putDouble(ARG_DEST_LAT, destLat)
                if (destLng != null) putDouble(ARG_DEST_LNG, destLng)
                if (status != null) putString(ARG_STATUS, status)
                if (tripType != null) putString(ARG_TRIP_TYPE, tripType)
                if (clientPlaceVisitId != null) putString(ARG_CP_VISIT_ID, clientPlaceVisitId)
                if (cpClientMet != null) putBoolean(ARG_CP_CLIENT_MET, cpClientMet)
                if (cpOutcome != null) putString(ARG_CP_OUTCOME, cpOutcome)
            }
        }

        fun forPlace(
            placeId: String,
            placeName: String?,
            placeAddress: String?,
            destLat: Double?,
            destLng: Double?
        ): TripNavigationFragment = TripNavigationFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PLACE_ID, placeId)
                putString(ARG_PLACE_NAME, placeName)
                putString(ARG_PLACE_ADDRESS, placeAddress)
                if (destLat != null) putDouble(ARG_DEST_LAT, destLat)
                if (destLng != null) putDouble(ARG_DEST_LNG, destLng)
            }
        }
    }
}
