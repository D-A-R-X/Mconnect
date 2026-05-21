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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
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
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
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
    private var destinationAddress: String? = null
    private var geocodeAttempted = false
    private var visitId: String? = null
    private var visitStarted = false
    private var arrivalInProgress = false
    private var pendingArrivalPhoto: File? = null
    private var pendingArrivalPhotoUri: Uri? = null
    private var pendingArrivalOtpPhoneMasked: String? = null
    private var pendingArrivalOtpExpiresInSeconds: Int = 600
    private var pendingArrivalOtpResendCooldownSeconds: Int = 60
    private var pendingArrivalLat: Double? = null
    private var pendingArrivalLng: Double? = null
    // KOS-37: CP-visit context — set from fragment args. The decision flag
    // tracks whether we already collected Client Met + Outcome for this run.
    private var tripType: String? = null
    private var cpVisitId: String? = null
    private var cpClientMet: Boolean? = null
    private var cpOutcome: String? = null
    private var cpVisitDecisionCaptured: Boolean = false
    // True once the reconcile detects this CP visit was opened as part of
    // a telecaller-fixed SV (proposedSiteVisit / lead.sv_fixed / party
    // data). Drives two UI changes:
    //   - the post-arrival CTA reads "Complete SV details" instead of
    //     "Complete CP details"
    //   - the outcome sheet opens directly in locked SV mode (no flash
    //     of the Booking tab while detect runs async inside the sheet)
    private var cpIsSvFixed: Boolean = false
    private var showClientNotSeenCompletion = false
    // KOS-52: Set when the user picked "No, didn't see client" on the Yes/No
    // sheet. We still capture an arrival photo for proof but skip the OTP
    // request and the outcome form, then mark the visit as not-met.
    private var cpNoPathPhotoCapture = false

    private var tvTitle: TextView? = null
    private var tvDestName: TextView? = null
    private var tvDestAddress: TextView? = null
    private var tvOriginName: TextView? = null
    private var tvDistance: TextView? = null
    private var tvEta: TextView? = null
    private var tvStatus: TextView? = null
    private var tvTripStartTime: TextView? = null
    private var tvStartTripLabel: TextView? = null
    private var tripStatusPill: LinearLayout? = null
    private var btnBack: ImageView? = null
    private var btnOpenMaps: LinearLayout? = null
    private var swipeArrived: SwipeToConfirmButton? = null
    private var btnCompleteCpDetails: Button? = null
    private var loadingOverlay: FrameLayout? = null

    // Trip Progress card (Figma 5-stage stepper)
    private var tvTripStateLabel: TextView? = null
    private var tripStepStart: FrameLayout? = null
    private var tripStepEnroute: FrameLayout? = null
    private var tripStepReaching: FrameLayout? = null
    private var tripStepComplete: FrameLayout? = null
    private var tripStepStartIcon: ImageView? = null
    private var tripStepEnrouteIcon: ImageView? = null
    private var tripStepReachingIcon: ImageView? = null
    private var tripStepCompleteIcon: ImageView? = null
    private var tripStepStartLabel: TextView? = null
    private var tripStepEnrouteLabel: TextView? = null
    private var tripStepReachingLabel: TextView? = null
    private var tripStepCompleteLabel: TextView? = null
    private var tripLineSegment1: View? = null
    private var tripLineSegment2: View? = null
    private var tripLineSegment3: View? = null
    private var arrivalConfirmedForProgress = false

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
            cpNoPathPhotoCapture = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            Toast.makeText(requireContext(), "Photo capture cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        // Photo captured — branch by flow:
        // - Yes path (default): upload, then ask the client OTP
        // - No path (CP only): upload, then mark not-met + complete
        if (cpNoPathPhotoCapture) {
            uploadArrivalPhotoThenCompleteWithoutClient(photoFile!!)
        } else {
            uploadArrivalPhotoThenAskOtp(photoFile!!)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (cpNoPathPhotoCapture) {
                arrivalInProgress = true
                launchArrivalCamera()
            } else {
                requestArrivalOtpThenOpenCamera()
            }
        }
        else {
            arrivalInProgress = false
            cpNoPathPhotoCapture = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
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
        tvOriginName = view.findViewById(R.id.tvTripOriginName)
        tvDistance = view.findViewById(R.id.tvTripDistance)
        tvEta = view.findViewById(R.id.tvTripEta)
        tvStatus = view.findViewById(R.id.tvTripStatus)
        tvTripStartTime = view.findViewById(R.id.tvTripStartTime)
        tvStartTripLabel = view.findViewById(R.id.tvStartTripLabel)
        tripStatusPill = view.findViewById(R.id.tripStatusPill)
        btnBack = view.findViewById(R.id.btnTripBack)
        btnOpenMaps = view.findViewById(R.id.btnOpenInMaps)
        swipeArrived = view.findViewById(R.id.swipeArrived)
        btnCompleteCpDetails = view.findViewById(R.id.btnCompleteCpDetails)
        loadingOverlay = view.findViewById(R.id.tripLoadingOverlay)
        mapView = view.findViewById(R.id.mapViewTrip)

        tvTripStateLabel = view.findViewById(R.id.tvTripStateLabel)
        tripStepStart = view.findViewById(R.id.tripStepStart)
        tripStepEnroute = view.findViewById(R.id.tripStepEnroute)
        tripStepReaching = view.findViewById(R.id.tripStepReaching)
        tripStepComplete = view.findViewById(R.id.tripStepComplete)
        tripStepStartIcon = view.findViewById(R.id.tripStepStartIcon)
        tripStepEnrouteIcon = view.findViewById(R.id.tripStepEnrouteIcon)
        tripStepReachingIcon = view.findViewById(R.id.tripStepReachingIcon)
        tripStepCompleteIcon = view.findViewById(R.id.tripStepCompleteIcon)
        tripStepStartLabel = view.findViewById(R.id.tripStepStartLabel)
        tripStepEnrouteLabel = view.findViewById(R.id.tripStepEnrouteLabel)
        tripStepReachingLabel = view.findViewById(R.id.tripStepReachingLabel)
        tripStepCompleteLabel = view.findViewById(R.id.tripStepCompleteLabel)
        tripLineSegment1 = view.findViewById(R.id.tripLineSegment1)
        tripLineSegment2 = view.findViewById(R.id.tripLineSegment2)
        tripLineSegment3 = view.findViewById(R.id.tripLineSegment3)

        val args = requireArguments()
        visitId = args.getString(ARG_VISIT_ID)
        val placeName = args.getString(ARG_PLACE_NAME) ?: "Destination"
        val placeAddress = args.getString(ARG_PLACE_ADDRESS)
        destinationAddress = placeAddress
        val destLat = if (args.containsKey(ARG_DEST_LAT)) args.getDouble(ARG_DEST_LAT) else null
        val destLng = if (args.containsKey(ARG_DEST_LNG)) args.getDouble(ARG_DEST_LNG) else null
        if (destLat != null && destLng != null) {
            destination = LatLng(destLat, destLng)
        }
        tripType = args.getString(ARG_TRIP_TYPE)
        cpVisitId = args.getString(ARG_CP_VISIT_ID)
        cpClientMet = if (args.containsKey(ARG_CP_CLIENT_MET)) args.getBoolean(ARG_CP_CLIENT_MET) else null
        cpOutcome = args.getString(ARG_CP_OUTCOME)
        // A CP visit decision is complete only when an outcome exists. Client
        // Met alone can be saved before the staff exits, so reopen the sheet
        // later until outcome/project conversion is captured.
        cpVisitDecisionCaptured = !cpOutcome.isNullOrBlank()

        tvTitle?.text = "Trip Details"
        // The "Type" cell on the Trip Details card now surfaces the visit
        // category rather than echoing the client name (which the header
        // already shows). Same vocabulary as Home / CP Visits rows so the
        // user gets one consistent label across surfaces.
        val visitCategory = args.getString(ARG_VISIT_CATEGORY)
        val cpVisitIdLocal = args.getString(ARG_CP_VISIT_ID)
        val isPlaceOnly = args.containsKey(ARG_PLACE_ID) && args.getString(ARG_VISIT_ID).isNullOrBlank()
        tvDestName?.text = when (visitCategory) {
            "sv_cum_cp" -> "SV confirmation CP"
            "direct_cp" -> "Direct CP"
            "site_visit" -> "Site Visit"
            else -> when {
                isPlaceOnly -> "Assigned place"
                !cpVisitIdLocal.isNullOrBlank() -> "CP visit"
                else -> "Visit"
            }
        }
        tvDestAddress?.text = placeAddress?.takeIf { it.isNotBlank() } ?: "Address not available"
        tvOriginName?.text = "Current Location"
        bindTripClientHeader(view, placeName)

        btnBack?.setOnClickListener { parentFragmentManager.popBackStack() }
        btnOpenMaps?.setOnClickListener { ensureVisitStarted() }
        swipeArrived?.onConfirmed = { onArrivalSwipeConfirmed() }
        btnCompleteCpDetails?.setOnClickListener { showCpCompletionSheet() }

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
        setFragmentResultListener(CpClientSeenBottomSheet.RESULT_KEY) { _, bundle ->
            val clientSeen = bundle.getBoolean(CpClientSeenBottomSheet.KEY_CLIENT_SEEN)
            if (clientSeen) {
                startCpYesPath()
            } else {
                startCpNoPath()
            }
        }
        setFragmentResultListener(CpTripCompletedBottomSheet.RESULT_KEY) { _, _ ->
            parentFragmentManager.popBackStack()
        }

        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(this)

        // Opening the card is view-only. The trip start API must run only
        // from the explicit Start Trip button.
        renderPreStartPhase()

        // KOS: status pill must mirror the home card. If the visit is already
        // in-progress/arrived, override the "Start" pill rendered above so
        // there's no flicker where the detail page disagrees with the list.
        val incomingStatus = args.getString(ARG_STATUS).orEmpty().lowercase(Locale.getDefault())
        when (incomingStatus) {
            "arrived", "arrival_verified", "arrival-verified" -> {
                visitStarted = true
                arrivalConfirmedForProgress = true
                showTripStartTime()
                applyStatusPill("Reaching")
                btnOpenMaps?.visibility = View.GONE
                renderArrivalPhase(alreadyArrived = true)
            }
            "in-progress", "in_progress", "ongoing", "started", "active" -> {
                visitStarted = true
                showTripStartTime()
                applyStatusPill("Enroute")
                btnOpenMaps?.visibility = View.GONE
                renderArrivalPhase(alreadyArrived = false)
            }
            "completed", "complete", "done", "closed" -> {
                visitStarted = true
                showTripStartTime()
                applyStatusPill("Complete")
                btnOpenMaps?.visibility = View.GONE
                swipeArrived?.visibility = View.GONE
                btnCompleteCpDetails?.visibility = View.GONE
            }
        }

        // Self-healing: ARG_STATUS comes from whatever the home list said,
        // which can lag the server (e.g. the legacy /today-visits row
        // says "scheduled" but the spawned fieldVisits row is already
        // "arrived" because the staff finished arrival OTP on a prior
        // session). For CP visits, re-check the server-side truth and
        // jump straight to the post-arrival phase so we never push the
        // user through Start Trip → Swipe again on a trip they've
        // already done.
        if (!cpVisitId.isNullOrBlank()) {
            reconcileCpVisitStatusFromServer()
        }
    }

    private fun reconcileCpVisitStatusFromServer() {
        val cpId = cpVisitId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.getMyMarketingCpVisits(
                    session.bearerToken,
                    fromDate = null,
                    toDate = null,
                )
                if (!resp.success) return@launch
                val cp = resp.visits.firstOrNull { it.id == cpId } ?: return@launch
                if (!isAdded) return@launch

                // Detect SV-fix mode from the same three signals the
                // outcome sheet uses (proposedSiteVisit / lead.sv_fixed
                // / party data). Drives two surfaces: the CTA label on
                // this screen flips to "Complete SV details", and the
                // sheet open is hinted so it skips the Booking-tab
                // default render that was causing the visible flash.
                val proposedHasFields = cp.proposedSiteVisit?.let { p ->
                    !p.projectId.isNullOrBlank() ||
                        !p.scheduledDate.isNullOrBlank() ||
                        !p.scheduledTime.isNullOrBlank() ||
                        !p.inchargeStaffId.isNullOrBlank() ||
                        !p.hodStaffId.isNullOrBlank() ||
                        !p.bdoStaffId.isNullOrBlank() ||
                        !p.avpStaffId.isNullOrBlank() ||
                        !p.gmStaffId.isNullOrBlank() ||
                        !p.seniorManagerStaffId.isNullOrBlank()
                } ?: false
                val leadFlaggedSvFixed = cp.lead?.followUpStatus
                    ?.lowercase(Locale.getDefault())
                    ?.let { s -> s == "sv_fixed" || s.contains("sv_fixed") || s.contains("sv-fixed") }
                    ?: false
                val hasSvFixParty = (cp.expectedAttendeeCount ?: 0) > 0 ||
                    (cp.attendees?.isNotEmpty() == true) ||
                    !cp.foodPreferences.isNullOrBlank() ||
                    !cp.vehiclePreference.isNullOrBlank()
                cpIsSvFixed = proposedHasFields || leadFlaggedSvFixed || hasSvFixParty
                if (cpIsSvFixed) {
                    btnCompleteCpDetails?.text = "Complete SV details"
                }
                android.util.Log.d(
                    "TripNav",
                    "reconcile: cpIsSvFixed=$cpIsSvFixed (proposed=$proposedHasFields " +
                        "lead=$leadFlaggedSvFixed party=$hasSvFixParty)",
                )

                // Pick the most-advanced authoritative status: prefer the
                // spawned fieldVisits row (where "arrived" lives) over
                // the CP-side lifecycle (which only tracks
                // scheduled/in_progress/completed).
                val effective = (cp.fieldVisit?.status ?: cp.status).orEmpty()
                    .lowercase(Locale.getDefault())
                when (effective) {
                    "arrived", "arrival_verified", "arrival-verified" -> {
                        visitStarted = true
                        arrivalConfirmedForProgress = true
                        showTripStartTime()
                        applyStatusPill("Reaching")
                        btnOpenMaps?.visibility = View.GONE
                        renderArrivalPhase(alreadyArrived = true)
                    }
                    "in-progress", "in_progress", "ongoing", "started", "active" -> {
                        // Only flip if we weren't already past the
                        // enroute phase locally. Don't downgrade the UI
                        // from arrived to enroute.
                        if (!arrivalConfirmedForProgress) {
                            visitStarted = true
                            showTripStartTime()
                            applyStatusPill("Enroute")
                            btnOpenMaps?.visibility = View.GONE
                            renderArrivalPhase(alreadyArrived = false)
                        }
                    }
                    "completed", "complete", "done", "closed" -> {
                        visitStarted = true
                        showTripStartTime()
                        applyStatusPill("Complete")
                        btnOpenMaps?.visibility = View.GONE
                        swipeArrived?.visibility = View.GONE
                        btnCompleteCpDetails?.visibility = View.GONE
                    }
                    // "scheduled" or empty: leave the locally-rendered
                    // pre-start phase as-is so the user can Start Trip.
                    else -> { /* no-op */ }
                }
            } catch (_: Exception) {
                // Network blip: don't disturb the locally-rendered UI.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            // The trip top bar is white (#FEFEFE) and only has paddingTop=14dp,
            // which is not enough to clear the OS status bar when the previous
            // screen left the activity in full-bleed mode. Tell MainActivity to
            // paint a matching white strip behind the status bar with dark
            // icons, so the back button + "Trip Details" title sit cleanly
            // below the notification icons instead of overlapping them.
            main.setTopBarAppearance(
                android.graphics.Color.parseColor("#FEFEFE"),
                darkStatusIcons = true,
                fullBleed = false,
            )
        }
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
        geocodeDestinationIfNeeded()
        fetchCurrentLocationAndUpdate()
    }

    private fun bindTripClientHeader(view: View, clientName: String) {
        val avatar = view.findViewById<TextView>(R.id.tvTripStaffAvatar)
        val nameView = view.findViewById<TextView>(R.id.tvTripStaffName)
        val roleView = view.findViewById<TextView>(R.id.tvTripStaffRole)
        val name = clientName.ifBlank { "Client" }.lowercase().split(" ").filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
            .ifBlank { "Client" }
        avatar.text = name.firstOrNull()?.uppercase() ?: "M"
        nameView.text = name
        roleView.visibility = View.GONE
    }

    /**
     * Apply pill background + text color matching the home card's status colors.
     * Home card uses: bg_home_trip_status_ready (green) for Start/Ready,
     * bg_home_trip_status_progress (orange) for Enroute/Reaching,
     * bg_home_trip_status_done (gray) for Complete.
     */
    private fun applyStatusPill(label: String) {
        tvStatus?.text = label
        val ctx = context ?: return
        when (label.lowercase(Locale.getDefault())) {
            "enroute", "en route", "in progress", "in-progress", "reaching" -> {
                tripStatusPill?.background = ctx.getDrawable(R.drawable.bg_home_trip_status_progress)
                tvStatus?.setTextColor(Color.parseColor("#B54708"))
            }
            "complete", "completed", "done" -> {
                tripStatusPill?.background = ctx.getDrawable(R.drawable.bg_home_trip_status_done)
                tvStatus?.setTextColor(Color.parseColor("#475467"))
            }
            else -> {
                tripStatusPill?.background = ctx.getDrawable(R.drawable.bg_home_trip_status_ready)
                tvStatus?.setTextColor(Color.parseColor("#169B2F"))
            }
        }
        applyTripProgressFromLabel(label)
    }

    /**
     * Drives the 5-stage Trip Progress stepper card (matches Figma frames
     * 331:12144 / 331:12190 / 331:12328 / 331:12375 / 331:12423).
     *
     *  Stage 0 — Trip not started   (label = "Start" / "Starting…")
     *  Stage 1 — Trip started       (label = "Enroute")
     *  Stage 2 — Enroute (close)    (label = "Reaching", before arrival confirm)
     *  Stage 3 — Trip reached       (label = "Reaching", after arrival confirm)
     *  Stage 4 — Trip completed     (label = "Complete" / "Completed")
     */
    private fun applyTripProgressFromLabel(label: String) {
        val stage = when (label.lowercase(Locale.getDefault())) {
            "complete", "completed", "done" -> 4
            "reaching" -> if (arrivalConfirmedForProgress) 3 else 2
            "enroute", "en route", "in progress", "in-progress" -> 1
            else -> 0
        }
        applyTripProgressStage(stage)
    }

    private fun applyTripProgressStage(stage: Int) {
        // Right-side state label
        val (rightLabel, rightColor) = when (stage) {
            0 -> "Not Started" to "#8E8E93"
            1 -> "Started" to "#19B900"
            2 -> "En Route" to "#19B900"
            3 -> "Reached" to "#19B900"
            else -> "Completed" to "#19B900"
        }
        tvTripStateLabel?.text = rightLabel
        tvTripStateLabel?.setTextColor(Color.parseColor(rightColor))

        bindTripStep(
            container = tripStepStart,
            icon = tripStepStartIcon,
            label = tripStepStartLabel,
            stepStateFor(stage, ownIndex = 0),
            doneIcon = R.drawable.ic_trip_progress_play_white,
            activeIcon = R.drawable.ic_trip_progress_play_white,
            inactiveIcon = R.drawable.ic_trip_progress_play_gray
        )
        bindTripStep(
            container = tripStepEnroute,
            icon = tripStepEnrouteIcon,
            label = tripStepEnrouteLabel,
            stepStateFor(stage, ownIndex = 1),
            doneIcon = R.drawable.ic_trip_progress_location_white,
            activeIcon = R.drawable.ic_trip_progress_location_green,
            inactiveIcon = R.drawable.ic_trip_progress_location_gray
        )
        bindTripStep(
            container = tripStepReaching,
            icon = tripStepReachingIcon,
            label = tripStepReachingLabel,
            stepStateFor(stage, ownIndex = 2),
            doneIcon = R.drawable.ic_trip_progress_location_white,
            activeIcon = R.drawable.ic_trip_progress_location_green,
            inactiveIcon = R.drawable.ic_trip_progress_location_gray
        )
        bindTripStep(
            container = tripStepComplete,
            icon = tripStepCompleteIcon,
            label = tripStepCompleteLabel,
            stepStateFor(stage, ownIndex = 3),
            doneIcon = R.drawable.ic_trip_progress_flag_white,
            activeIcon = R.drawable.ic_trip_progress_flag_green,
            inactiveIcon = R.drawable.ic_trip_progress_flag_gray_exact
        )

        // Connector line segments — green from step1 onwards as stages advance.
        bindTripLine(tripLineSegment1, isActive = stage >= 1)
        bindTripLine(tripLineSegment2, isActive = stage >= 2)
        bindTripLine(tripLineSegment3, isActive = stage >= 3)
    }

    private enum class TripStepState { DONE, ACTIVE, INACTIVE }

    private fun stepStateFor(stage: Int, ownIndex: Int): TripStepState {
        return when {
            ownIndex < stage -> TripStepState.DONE
            ownIndex == stage && stage in 1..3 -> TripStepState.ACTIVE
            stage == 4 -> TripStepState.DONE
            else -> TripStepState.INACTIVE
        }
    }

    private fun bindTripStep(
        container: FrameLayout?,
        icon: ImageView?,
        label: TextView?,
        state: TripStepState,
        doneIcon: Int,
        activeIcon: Int,
        inactiveIcon: Int
    ) {
        val ctx = context ?: return
        when (state) {
            TripStepState.DONE -> {
                container?.background = ctx.getDrawable(R.drawable.bg_trip_progress_figma_active)
                icon?.setImageResource(doneIcon)
                label?.setTextColor(Color.parseColor("#19B900"))
                label?.typeface = ResourcesCompat.getFont(ctx, R.font.inter_medium)
            }
            TripStepState.ACTIVE -> {
                // "Current step" look: same green fill + white icon as a DONE
                // step, but with the halo-ring drawable so the user can tell
                // at a glance which step they're on right now (matches the
                // En Route circle in the reference design).
                container?.background = ctx.getDrawable(R.drawable.bg_trip_progress_figma_active_current)
                icon?.setImageResource(doneIcon)
                label?.setTextColor(Color.parseColor("#19B900"))
                label?.typeface = ResourcesCompat.getFont(ctx, R.font.inter_semibold)
            }
            TripStepState.INACTIVE -> {
                container?.background = ctx.getDrawable(R.drawable.bg_trip_progress_figma_inactive)
                icon?.setImageResource(inactiveIcon)
                label?.setTextColor(Color.parseColor("#8E8E93"))
                label?.typeface = ResourcesCompat.getFont(ctx, R.font.inter_regular)
            }
        }
    }

    private fun bindTripLine(view: View?, isActive: Boolean) {
        val ctx = context ?: return
        view?.background = ctx.getDrawable(
            if (isActive) R.drawable.bg_trip_progress_line_active
            else R.drawable.bg_trip_progress_line_inactive
        )
    }

    private fun renderPreStartPhase() {
        applyStatusPill("Start")
        hideTripStartTime()
        loadingOverlay?.visibility = View.GONE
        btnOpenMaps?.visibility = View.VISIBLE
        tvStartTripLabel?.text = "Start Trip"
        btnOpenMaps?.setOnClickListener { ensureVisitStarted() }
        swipeArrived?.visibility = View.GONE
        btnCompleteCpDetails?.visibility = View.GONE
        tvOriginName?.text = "Current Location"
        tvDistance?.text = "Calculating..."
        tvEta?.text = "Calculating..."
        geocodeDestinationIfNeeded()
        fetchCurrentLocationAndUpdate()
    }

    private fun ensureVisitStarted() {
        loadingOverlay?.visibility = View.VISIBLE
        applyStatusPill("Starting…")
        hideTripStartTime()
        btnOpenMaps?.isEnabled = false
        tvStartTripLabel?.text = "Starting..."

        val args = requireArguments()
        val placeId = args.getString(ARG_PLACE_ID)
        val existingVisit = visitId
        val existingStatus = args.getString(ARG_STATUS).orEmpty().lowercase(Locale.getDefault())
        val alreadyInFlight = existingStatus in setOf(
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
        )
        val alreadyArrived = existingStatus in setOf("arrived", "arrival_verified", "arrival-verified")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val attendanceActive = AttendanceTrackingGate.isClockedInForToday(
                    session.bearerToken,
                    api,
                )
                if (!attendanceActive) {
                    failAndClose("Please clock in before starting a trip.")
                    return@launch
                }
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
                applyTrackingBootstrap(bootstrap, attendanceActive = true)

                visitStarted = true
                if (alreadyArrived) arrivalConfirmedForProgress = true
                showTripStartTime()
                if (location != null) currentLocation = LatLng(location.latitude, location.longitude)
                loadingOverlay?.visibility = View.GONE
                btnOpenMaps?.visibility = View.GONE
                btnOpenMaps?.isEnabled = true
                applyStatusPill(when {
                    alreadyArrived && !cpVisitDecisionCaptured -> "Reaching"
                    alreadyInFlight -> "Enroute"
                    else -> "Enroute"
                })
                renderMapMarkersAndRoute()
                geocodeDestinationIfNeeded()
                renderArrivalPhase(alreadyArrived)
                if (!alreadyInFlight) {
                    Toast.makeText(requireContext(), "Trip started", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                btnOpenMaps?.isEnabled = true
                tvStartTripLabel?.text = "Start Trip"
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

    private fun hideTripStartTime() {
        tvTripStartTime?.visibility = View.GONE
    }

    private fun showTripStartTime() {
        tvTripStartTime?.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        tvTripStartTime?.visibility = View.VISIBLE
    }

    private fun fetchCurrentLocationAndUpdate() {
        if (!hasLocationPermission()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchCurrentLocation() ?: return@launch
            currentLocation = LatLng(location.latitude, location.longitude)
            tvOriginName?.text = "Current Location"
            renderMapMarkersAndRoute()
            geocodeDestinationIfNeeded()
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
        val dest = destination
        if (dest == null) {
            tvDistance?.text = "—"
            tvEta?.text = "—"
            return
        }
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

    private fun geocodeDestinationIfNeeded() {
        if (destination != null || geocodeAttempted) return
        val address = destinationAddress?.takeIf { it.isNotBlank() } ?: return
        geocodeAttempted = true
        tvDistance?.text = "Resolving…"
        tvEta?.text = "—"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = DirectionsClient.geocodeAddress(session.bearerToken, address)
            if (!isAdded) return@launch
            if (result == null) {
                tvDistance?.text = "—"
                tvEta?.text = "—"
                Toast.makeText(
                    requireContext(),
                    "Could not locate address on map. Paste exact Maps link for best route.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            destination = result.latLng
            val formatted = result.formattedAddress?.takeIf { it.isNotBlank() }
            if (formatted != null) {
                destinationAddress = formatted
                tvDestAddress?.text = formatted
            }
            renderMapMarkersAndRoute()
        }
    }

    private fun drawStraightFallback(origin: LatLng, dest: LatLng) {
        val map = googleMap ?: return
        routePolyline?.remove()
        routePolyline = map.addPolyline(
            PolylineOptions()
                .add(origin, dest)
                .width(6f)
                .color(Color.parseColor("#0B56A8"))
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
                    .color(Color.parseColor("#0B56A8"))
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
            if (!visitStarted) applyStatusPill("Start")
        }
    }

    private fun updateDistanceAndEtaFromHaversine(origin: LatLng, dest: LatLng) {
        val meters = haversineMeters(origin, dest)
        tvDistance?.text = formatDistance(meters)
        if (visitStarted && meters <= REACHING_RADIUS_METERS && tvStatus?.text?.toString() != "Complete") {
            applyStatusPill("Reaching")
        }
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
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            return
        }
        if (!visitStarted) {
            Toast.makeText(requireContext(), "Trip is still starting", Toast.LENGTH_SHORT).show()
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            return
        }
        if (arrivalInProgress) return
        arrivalInProgress = true

        if (isCpVisit()) {
            checkReachingAndAskClientSeen()
            return
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        requestArrivalOtpThenOpenCamera()
    }

    private fun checkReachingAndAskClientSeen() {
        swipeArrived?.lockAsBusy("Checking location...")
        viewLifecycleOwner.lifecycleScope.launch {
            val freshLocation = fetchCurrentLocation()
            val effLat = freshLocation?.latitude ?: currentLocation?.latitude
            val effLng = freshLocation?.longitude ?: currentLocation?.longitude
            val dest = destination
            if (effLat == null || effLng == null || dest == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Could not verify you are near the client place.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            currentLocation = LatLng(effLat, effLng)
            val distance = haversineMeters(currentLocation!!, dest)
            if (distance > REACHING_RADIUS_METERS) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "You are ${formatDistance(distance)} away. Move within ${formatDistance(REACHING_RADIUS_METERS.toDouble())} to complete.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            arrivalConfirmedForProgress = true
            applyStatusPill("Reaching")
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            CpClientSeenBottomSheet().show(parentFragmentManager, "cp_client_seen")
        }
    }

    private fun requestArrivalOtpThenOpenCamera() {
        val id = visitId ?: run {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            return
        }
        swipeArrived?.lockAsBusy("Checking location…")
        viewLifecycleOwner.lifecycleScope.launch {
            val freshLocation = fetchCurrentLocation()
            val effLat = freshLocation?.latitude ?: currentLocation?.latitude
            val effLng = freshLocation?.longitude ?: currentLocation?.longitude
            if (effLat == null || effLng == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
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
                    // If the server tells us this visit is already in the
                    // "arrived" state (e.g. the user swiped successfully on
                    // a prior session, the OTP was verified, but the
                    // outcome step was never finished), don't dump them
                    // back on the "Swipe to Complete Trip" loop forever —
                    // jump straight to the post-arrival phase so the
                    // "Complete CP details" button surfaces.
                    val errMsg = resp.error.orEmpty().lowercase(Locale.getDefault())
                    val alreadyVerified =
                        errMsg.contains("already verified") ||
                            errMsg.contains("finish the outcome")
                    if (alreadyVerified) {
                        arrivalConfirmedForProgress = true
                        renderArrivalPhase(alreadyArrived = true)
                        return@launch
                    }
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        arrivalBlockedMessage(resp),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                pendingArrivalOtpPhoneMasked = resp.contactPhoneMasked
                pendingArrivalOtpExpiresInSeconds = resp.otpExpiresInSeconds ?: 600
                pendingArrivalOtpResendCooldownSeconds = resp.resendCooldownSeconds ?: 60
                pendingArrivalLat = effLat
                pendingArrivalLng = effLng
                swipeArrived?.lockAsBusy("Opening camera…")
                launchArrivalCamera()
            } catch (e: Exception) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun arrivalBlockedMessage(resp: com.manjugroups.m_connect.network.ArrivalOtpRequestResponse): String {
        val distance = resp.distance
        val radius = resp.radius
        if (distance != null && radius != null) {
            return "You are ${formatDistance(distance.toDouble())} away. Move within ${formatDistance(radius.toDouble())} to mark arrival."
        }
        return resp.error ?: "Could not verify arrival location"
    }

    private fun launchArrivalCamera() {
        val photoFile = createArrivalPhotoFile()
        if (photoFile == null) {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
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
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            Toast.makeText(requireContext(), "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadArrivalPhotoThenAskOtp(photoFile: File) {
        val id = visitId ?: run {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            return
        }
        swipeArrived?.lockAsBusy("Uploading photo…")
        viewLifecycleOwner.lifecycleScope.launch {
            val storageId = uploadArrivalPhoto(photoFile)
            if (storageId == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Photo upload failed. Try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            pendingArrivalStorageId = storageId

            val otpLat = pendingArrivalLat
            val otpLng = pendingArrivalLng
            if (otpLat == null || otpLng == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Arrival location expired. Swipe again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            swipeArrived?.lockAsBusy("Enter OTP to confirm")
            ArrivalOtpBottomSheet.newInstance(
                visitId = id,
                phoneMasked = pendingArrivalOtpPhoneMasked,
                expiresInSeconds = pendingArrivalOtpExpiresInSeconds,
                resendCooldownSeconds = pendingArrivalOtpResendCooldownSeconds,
                lat = otpLat,
                lng = otpLng,
            ).show(parentFragmentManager, "arrival_otp")
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

        val isCpVisit = isCpVisit()
        if (isCpVisit && !cpVisitDecisionCaptured) {
            renderArrivalPhase(alreadyArrived = true)
            showCpCompletionSheet()
            return
        }
        finalizeCompleteVisit()
    }

    private fun renderArrivalPhase(alreadyArrived: Boolean) {
        // Use cpVisitId presence (not the stricter tripType check in
        // isCpVisit()) as the gate for showing the outcome-sheet CTA.
        // Stale Home cache, older Home merge code, or a missing
        // tripType arg on legacy rows would otherwise drop the user
        // back onto the swipe path with no way out ("deadlock"). If
        // we have a CP visit id, we have everything needed to open the
        // outcome sheet — tripType is just an annotation.
        //
        // We also intentionally IGNORE cpVisitDecisionCaptured here.
        // The flag can be set from an ARG_CP_OUTCOME that originated
        // in a *prior* aborted attempt (e.g. user opened the sheet,
        // tapped a tab, dismissed without completing); the visit's
        // real terminal state has to come from the server (the
        // reconcile path will hide both buttons on "completed"). In
        // any non-terminal "arrived" state we always want the
        // outcome-sheet CTA visible, since that's the only way to
        // actually finish the trip.
        val hasCpRow = !cpVisitId.isNullOrBlank()
        val shouldFillCpDetails = alreadyArrived && hasCpRow

        android.util.Log.d(
            "TripNav",
            "renderArrivalPhase alreadyArrived=$alreadyArrived hasCpRow=$hasCpRow " +
                "tripType=$tripType cpVisitId=$cpVisitId " +
                "cpVisitDecisionCaptured=$cpVisitDecisionCaptured " +
                "-> showCpButton=$shouldFillCpDetails",
        )

        if (alreadyArrived) arrivalConfirmedForProgress = true

        if (shouldFillCpDetails) {
            arrivalInProgress = false
            applyStatusPill("Reaching")
            swipeArrived?.visibility = View.GONE
            btnCompleteCpDetails?.visibility = View.VISIBLE
            return
        }

        btnCompleteCpDetails?.visibility = View.GONE
        swipeArrived?.visibility = View.VISIBLE
        swipeArrived?.reset(newLabel = "Swipe to Complete Trip")

        // Non-CP arrival with decision captured (plain field visit where
        // OTP verify is the only decision needed) — finalize the trip
        // without further user action. CP visits never reach this branch
        // because shouldFillCpDetails captures every arrived CP above.
        if (alreadyArrived && isCpVisit() && cpVisitDecisionCaptured) {
            finalizeCompleteVisit()
        }
    }

    private fun showCpCompletionSheet() {
        val cpId = cpVisitId ?: return
        arrivalConfirmedForProgress = true
        applyStatusPill("Reaching")
        CompleteCpVisitBottomSheet
            .newInstance(
                cpVisitId = cpId,
                cpClientMet = cpClientMet,
                cpOutcome = cpOutcome,
                // Pre-pass the SV-fix verdict so the sheet can switch
                // straight to its locked Site Visit mode in onViewCreated
                // — no flash of the default Booking tab while the
                // sheet's own async detect runs.
                isSvFixedHint = cpIsSvFixed,
            )
            .show(parentFragmentManager, "cp_visit_complete")
    }

    // KOS-52: After the user confirms "Yes, I saw the client" we still need
    // to capture the arrival photo and validate the visit against the client
    // OTP before showing the outcome form. Reuse the non-CP arrival pipeline:
    // requestArrivalOtp → camera → upload → OTP sheet → onArrivalOtpVerified
    // (which already routes CP visits to the outcome bottom sheet).
    private fun startCpYesPath() {
        cpNoPathPhotoCapture = false
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        requestArrivalOtpThenOpenCamera()
    }

    // KOS-52: "No, didn't see client" path — capture a photo for proof, upload
    // it, then mark the visit as not-met with the existing API. No OTP and no
    // outcome form for this branch.
    private fun startCpNoPath() {
        cpNoPathPhotoCapture = true
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        arrivalInProgress = true
        launchArrivalCamera()
    }

    private fun uploadArrivalPhotoThenCompleteWithoutClient(photoFile: File) {
        swipeArrived?.lockAsBusy("Uploading photo…")
        viewLifecycleOwner.lifecycleScope.launch {
            val storageId = uploadArrivalPhoto(photoFile)
            if (storageId == null) {
                arrivalInProgress = false
                cpNoPathPhotoCapture = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Photo upload failed. Try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            pendingArrivalStorageId = storageId
            completeCpVisitWithoutClient()
        }
    }

    private fun completeCpVisitWithoutClient() {
        val cpId = cpVisitId ?: run {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            return
        }
        swipeArrived?.lockAsBusy("Completing visit...")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = false,
                        clientNoShowReason = "Client not seen"
                    )
                )
                if (!metResp.success) {
                    arrivalInProgress = false
                    cpNoPathPhotoCapture = false
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(requireContext(), metResp.error ?: "Failed to record client status", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SetOutcomeRequest(
                        id = cpId,
                        outcome = "other",
                        notes = "Client not seen"
                    )
                )
                if (!outcomeResp.success) {
                    arrivalInProgress = false
                    cpNoPathPhotoCapture = false
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(requireContext(), outcomeResp.error ?: "Failed to set outcome", Toast.LENGTH_LONG).show()
                    return@launch
                }
                cpClientMet = false
                cpOutcome = "other"
                cpVisitDecisionCaptured = true
                showClientNotSeenCompletion = true
                cpNoPathPhotoCapture = false
                finalizeCompleteVisit()
            } catch (e: Exception) {
                arrivalInProgress = false
                cpNoPathPhotoCapture = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG).show()
            }
        }
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
                applyStatusPill("Complete")
                val bootstrap = geoApi
                    .getTrackingBootstrap(session.bearerToken, session.trackingDeviceId)
                    .data
                applyTrackingBootstrap(
                    bootstrap,
                    attendanceActive = runCatching {
                        AttendanceTrackingGate.isClockedInForToday(session.bearerToken, api)
                    }.getOrDefault(false),
                )
                if (showClientNotSeenCompletion) {
                    showClientNotSeenCompletion = false
                    CpTripCompletedBottomSheet().show(parentFragmentManager, "cp_trip_completed")
                } else {
                    Toast.makeText(requireContext(), "Visit completed", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                }
            } catch (e: Exception) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
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

    private fun applyTrackingBootstrap(bootstrap: TrackingBootstrapData?, attendanceActive: Boolean) {
        session.activeTrackingSessionId = bootstrap?.activeSession?.id
        session.shouldTrackNow = attendanceActive && bootstrap?.shouldTrack == true
        session.geoTrackingEnabled =
            bootstrap?.assignment?.attendance != null || bootstrap?.assignment?.siteVisit != null
        session.geoConsentGiven = bootstrap?.consent?.status == "granted"
        session.geoConsentDeclined =
            bootstrap?.consent?.status == "declined" || bootstrap?.consent?.status == "revoked"

        if (attendanceActive && bootstrap?.shouldPromptConsent == true) {
            startActivity(Intent(requireContext(), GeoTrackConsentActivity::class.java))
            return
        }
        if (attendanceActive && bootstrap?.shouldTrack == true && !bootstrap.activeSession?.id.isNullOrBlank()) {
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

    private fun isCpVisit(): Boolean = tripType == "client_place" && !cpVisitId.isNullOrBlank()

    companion object {
        private const val REACHING_RADIUS_METERS = 500
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
        // Visit category — feeds the Trip Details "Type" cell. Same
        // vocabulary the Home + CP Visits lists use:
        // "sv_cum_cp" / "direct_cp" / "site_visit" / null (places).
        private const val ARG_VISIT_CATEGORY = "arg_visit_category"

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
            visitCategory: String? = null,
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
                if (visitCategory != null) putString(ARG_VISIT_CATEGORY, visitCategory)
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
