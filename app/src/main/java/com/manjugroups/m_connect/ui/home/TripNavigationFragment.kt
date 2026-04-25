package com.manjugroups.m_connect.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
import com.manjugroups.m_connect.network.CompleteVisitRequest
import com.manjugroups.m_connect.network.CreateVisitRequest
import com.manjugroups.m_connect.network.DirectionsClient
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.StartVisitRequest
import com.manjugroups.m_connect.network.TrackingBootstrapData
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    private lateinit var session: SessionManager

    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var routePolyline: Polyline? = null

    private var currentLocation: LatLng? = null
    private var destination: LatLng? = null
    private var visitId: String? = null
    private var visitStarted = false

    private var tvTitle: TextView? = null
    private var tvDestName: TextView? = null
    private var tvDestAddress: TextView? = null
    private var tvDistance: TextView? = null
    private var tvEta: TextView? = null
    private var tvStatus: TextView? = null
    private var btnBack: ImageView? = null
    private var btnOpenMaps: Button? = null
    private var btnArrived: Button? = null
    private var loadingOverlay: FrameLayout? = null

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
        btnArrived = view.findViewById(R.id.btnMarkArrived)
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

        tvTitle?.text = "Trip to $placeName"
        tvDestName?.text = placeName
        tvDestAddress?.text = placeAddress?.takeIf { it.isNotBlank() } ?: "Address not available"

        btnBack?.setOnClickListener { parentFragmentManager.popBackStack() }
        btnOpenMaps?.setOnClickListener { openInGoogleMaps() }
        btnArrived?.setOnClickListener { onArrivedTapped() }

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

                geoApi.startVisit(
                    session.bearerToken,
                    StartVisitRequest(effectiveVisitId, location?.latitude, location?.longitude)
                )

                // Refresh tracking session + start GeoTrackService
                val bootstrap = geoApi
                    .getTrackingBootstrap(session.bearerToken, session.trackingDeviceId)
                    .data
                applyTrackingBootstrap(bootstrap)

                visitStarted = true
                if (location != null) currentLocation = LatLng(location.latitude, location.longitude)
                loadingOverlay?.visibility = View.GONE
                tvStatus?.text = "On the way"
                renderMapMarkersAndRoute()
                Toast.makeText(requireContext(), "Trip started", Toast.LENGTH_SHORT).show()
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

    private fun onArrivedTapped() {
        // TODO(step-3): replace with arrival OTP flow.
        // For now: complete the visit as a placeholder so the existing UI works.
        val id = visitId ?: run {
            Toast.makeText(requireContext(), "No active visit", Toast.LENGTH_SHORT).show()
            return
        }
        if (!visitStarted) {
            Toast.makeText(requireContext(), "Trip is still starting", Toast.LENGTH_SHORT).show()
            return
        }
        btnArrived?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loc = fetchCurrentLocation()
                geoApi.completeVisit(
                    session.bearerToken,
                    CompleteVisitRequest(id, loc?.latitude, loc?.longitude)
                )
                val bootstrap = geoApi
                    .getTrackingBootstrap(session.bearerToken, session.trackingDeviceId)
                    .data
                applyTrackingBootstrap(bootstrap)
                Toast.makeText(requireContext(), "Visit completed", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                btnArrived?.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    "Failed to complete: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

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

        fun forVisit(
            visitId: String,
            placeName: String?,
            placeAddress: String?,
            destLat: Double?,
            destLng: Double?
        ): TripNavigationFragment = TripNavigationFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_VISIT_ID, visitId)
                putString(ARG_PLACE_NAME, placeName)
                putString(ARG_PLACE_ADDRESS, placeAddress)
                if (destLat != null) putDouble(ARG_DEST_LAT, destLat)
                if (destLng != null) putDouble(ARG_DEST_LNG, destLng)
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
