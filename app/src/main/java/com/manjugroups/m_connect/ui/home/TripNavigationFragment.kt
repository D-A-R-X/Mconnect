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
import com.google.android.gms.location.CurrentLocationRequest
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.geotrack.AttendanceTrackingGate
import com.manjugroups.m_connect.geotrack.GeoTrackConsentActivity
import com.manjugroups.m_connect.geotrack.service.GeoTrackService
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ArrivalOtpRequestBody
import com.manjugroups.m_connect.network.CompleteVisitRequest
import com.manjugroups.m_connect.network.CpRevisitInfo
import com.manjugroups.m_connect.network.CreateVisitRequest
import com.manjugroups.m_connect.network.DirectionsClient
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.JointCpParticipant
import com.manjugroups.m_connect.network.JointCpSummary
import com.manjugroups.m_connect.network.JointCpCompleteReviewRequest
import com.manjugroups.m_connect.network.JointCpLocationRequest
import com.manjugroups.m_connect.network.JointCpSubmitReviewRequest
import com.manjugroups.m_connect.network.JointCpWorkflow
import com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest
import com.manjugroups.m_connect.network.StartVisitRequest
import com.manjugroups.m_connect.network.StorageUploader
import com.manjugroups.m_connect.network.TrackingBootstrapData
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.OutcomeRemarksBottomSheet
import com.manjugroups.m_connect.ui.common.OutcomeSelectionDialog
import com.manjugroups.m_connect.ui.marketing.cpTypeSupportsOtherOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import com.manjugroups.m_connect.ui.common.showOnce

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

    // Full-screen map expand/collapse. We reparent the single MapView between
    // the small preview card and the full-screen host so all markers/camera
    // state survive the toggle (no second map, no re-render).
    private var mapCard: FrameLayout? = null
    private var mapFullScreenContainer: FrameLayout? = null
    private var mapFullScreenHost: FrameLayout? = null
    private var isMapFullScreen = false
    private var mapBackCallback: androidx.activity.OnBackPressedCallback? = null

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
    private var pendingArrivalLat: Double? = null
    private var pendingArrivalLng: Double? = null
    // KOS-37: CP-visit context — set from fragment args. The decision flag
    // tracks whether we already collected Client Met + Outcome for this run.
    private var tripType: String? = null
    private var cpVisitId: String? = null
    private var cpClientMet: Boolean? = null
    private var cpOutcome: String? = null
    private var cpOutcomeNotes: String? = null
    private var cpPostponeReasons: List<String>? = null
    private var cpFollowUpDate: String? = null
    private var cpFollowUpTime: String? = null
    private var cpVisitDecisionCaptured: Boolean = false
    // CP Type from the row's cpType field. When this is
    // "gift_distribution" the post-arrival flow finalises directly
    // after photo + OTP (Yes path) or photo only (No path) — the big
    // CompleteCpVisitBottomSheet booking-outcome flow is skipped.
    private var cpType: String? = null
    private val isGiftDistribution: Boolean
        get() = cpType?.equals("gift_distribution", ignoreCase = true) == true
    private val isOldClient: Boolean
        get() = cpType?.equals("old_client", ignoreCase = true) == true
    // Collection CP — Yes path opens the Payment Entry sheet which
    // writes a customerCollections row and closes the visit with
    // outcome="collection_done". No path mirrors the gift / old-client
    // behaviour (photo only, terminal outcome=collection_done).
    private val isCollectionCp: Boolean
        get() = cpType?.equals("collection_cp", ignoreCase = true) == true
    // Mobile of the CP's client — passed in from the home / CP-list
    // call sites (which already carry it as `leadPhone`). Used by the
    // Collection CP Yes path to look up confirmed bookings without
    // round-tripping back to creation state.
    private var clientMobile: String? = null
    // True once the reconcile detects this CP visit was opened as part of
    // a telecaller-fixed SV (proposedSiteVisit / lead.sv_fixed / party
    // data). Drives two UI changes:
    //   - the post-arrival CTA reads "Complete SV details" instead of
    //     "Complete CP details"
    //   - the outcome sheet opens directly in locked SV mode (no flash
    //     of the Booking tab while detect runs async inside the sheet)
    private var cpIsSvFixed: Boolean = false
    private var jointWorkflow: JointCpWorkflow? = null
    private var jointWorkflowPollJob: Job? = null
    private var jointMutationInProgress = false
    private var autoOpenedJointReviewRevision: Long? = null

    // True when this Trip Details is rendering a pure SV row (no CP
    // behind it). Set from the visitCategory arg; lets renderPreStartPhase
    // skip "Start Trip" and surface the outcome flow directly via
    // CompleteCpVisitBottomSheet.forSiteVisit. Pure-SV visits don't go
    // through the trip-tracking lifecycle on mobile — staff arrive
    // directly and record the outcome.
    private var isPureSiteVisit: Boolean = false
    private var showClientNotSeenCompletion = false
    private var pendingCpRevisit: CpRevisitInfo? = null
    // KOS-52: Set when the user picked "No, didn't see client" on the Yes/No
    // sheet. We still capture an arrival photo for proof but skip the OTP
    // request and the outcome form, then mark the visit as not-met.
    private var cpNoPathPhotoCapture = false

    // Debounce flag for the "Complete CP details" (and its per-cpType
    // variants — Submit Payment Entry / Add Visit Remarks / Confirm
    // Gift Distribution) button. The bottom sheets we open here are
    // async — show() returns before the dialog actually renders, so
    // a quick double-tap from a confused user stacks two sheets on top
    // of each other. Set on first tap, reset after the open completes
    // OR after a short backstop delay so the user can retry if the
    // open silently failed.
    private var isOpeningOutcomeSheet = false
    private val outcomeSheetGuardResetDelayMs = 1200L

    // Gift Distribution post-OTP photo capture flag — true while we
    // wait for the user to take a picture of the gift handover AFTER
    // OTP verify. The pre-OTP photo step is skipped entirely for
    // gift_distribution; the proof we care about is the gift being
    // handed over, which only happens once OTP confirms the client.
    // The camera-result handler branches on this flag to route into
    // `completeGiftDistributionWithPhoto()` instead of the usual
    // arrival-photo + OTP path.
    private var isGiftDistributionPostOtpPhotoCapture = false

    private var tvTitle: TextView? = null
    private var tvDestName: TextView? = null
    private var tvDestAddress: TextView? = null
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
    /** Driver tapped "Picked from Site" — the return leg has begun. */
    private var pickedFromSiteConfirmed = false

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
            isGiftDistributionPostOtpPhotoCapture = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            Toast.makeText(requireContext(), "Photo capture cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        // Photo captured — branch by flow:
        // - Gift Distribution post-OTP: upload, then close visit
        //   with outcome=gift_distributed + the photo as proof of
        //   the handover.
        // - Yes path (default): upload, then ask the client OTP
        // - No path (CP only): review photo and optional remarks, then upload
        when {
            isGiftDistributionPostOtpPhotoCapture ->
                uploadGiftDistributionPhotoThenComplete(photoFile!!)
            cpNoPathPhotoCapture ->
                showClientNotMetProofReview(photoFile!!)
            else ->
                uploadArrivalPhotoThenAskOtp(photoFile!!)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when {
                isGiftDistributionPostOtpPhotoCapture -> launchArrivalCamera()
                cpNoPathPhotoCapture -> {
                    arrivalInProgress = true
                    launchArrivalCamera()
                }
                else -> requestArrivalOtpThenOpenCamera()
            }
        }
        else {
            arrivalInProgress = false
            cpNoPathPhotoCapture = false
            isGiftDistributionPostOtpPhotoCapture = false
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
        mapCard = view.findViewById(R.id.mapCard)
        mapFullScreenContainer = view.findViewById(R.id.mapFullScreenContainer)
        mapFullScreenHost = view.findViewById(R.id.mapFullScreenHost)
        view.findViewById<View>(R.id.btnMapExpand)?.setOnClickListener { expandMap() }
        view.findViewById<View>(R.id.btnMapCollapse)?.setOnClickListener { collapseMap() }

        // Edge-to-edge shell: drop the top bar below the status bar so the back
        // button + title don't sit under the notch / status icons.
        view.findViewById<View>(R.id.topBar)?.let {
            com.manjugroups.m_connect.ui.common.BottomActionInsets
                .applyStatusBarTop(it)
        }

        // Edge-to-edge shell: lift the pinned action row above the gesture nav
        // bar (and the main tab bar when visible) so the swipe button isn't
        // jammed against the bottom edge.
        view.findViewById<View>(R.id.bottomActions)?.let {
            com.manjugroups.m_connect.ui.common.BottomActionInsets
                .applyAboveSystemNavAndTabs(it)
        }

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
        cpType = args.getString(ARG_CP_TYPE)
        clientMobile = args.getString(ARG_CLIENT_MOBILE)
        // A CP visit decision is complete only when an outcome exists. Client
        // Met alone can be saved before the staff exits, so reopen the sheet
        // later until outcome/project conversion is captured.
        cpVisitDecisionCaptured = !cpOutcome.isNullOrBlank()

        // LMO (telecaller) + deadline — shown only when the caller supplied
        // them (both are optional args threaded from the visit row).
        bindTripMeta(
            view,
            lmoName = args.getString(ARG_LMO_NAME),
            fieldStaffName = args.getString(ARG_FIELD_STAFF_NAME),
            deadline = args.getString(ARG_DEADLINE),
        )

        tvTitle?.text = "Trip Details"
        // The "Type" cell on the Trip Details card now surfaces the visit
        // category rather than echoing the client name (which the header
        // already shows). Same vocabulary as Home / CP Visits rows so the
        // user gets one consistent label across surfaces.
        val visitCategory = args.getString(ARG_VISIT_CATEGORY)
        val cpVisitIdLocal = args.getString(ARG_CP_VISIT_ID)
        val isPlaceOnly = args.containsKey(ARG_PLACE_ID) && args.getString(ARG_VISIT_ID).isNullOrBlank()
        // Pure-SV detection. visitCategory=site_visit AND no CP visit id
        // behind it → this is a real siteVisits row whose trip lifecycle
        // doesn't go through the legacy fieldVisits flow. The CTA at
        // the bottom of the screen becomes "Complete Outcome" instead
        // of "Start Trip"; tap opens the outcome sheet in SV mode.
        isPureSiteVisit = (visitCategory == "site_visit") && cpVisitIdLocal.isNullOrBlank()
        tvDestName?.text = com.manjugroups.m_connect.ui.marketing.formatCpVisitTypeLabel(
            visitCategory = visitCategory,
            cpType = cpType,
            isPlaceOnly = isPlaceOnly,
            hasCpRow = !cpVisitIdLocal.isNullOrBlank(),
        )
        tvDestAddress?.text = placeAddress?.takeIf { it.isNotBlank() } ?: "Address not available"
        // Full client address card above the map — wraps, no truncation.
        // Never fall back to the client name here: showing the name in the
        // address slot reads as a real address and hides that coordinates /
        // address are actually missing. Show an explicit placeholder instead.
        view.findViewById<TextView>(R.id.tvClientAddressFull)?.text =
            placeAddress?.takeIf { it.isNotBlank() }
                ?: "Address not available"
        bindTripClientHeader(view, placeName)

        // Full-width "Call client" action above the address card. Hidden when
        // the trip carries no client mobile so the constraint chain collapses
        // cleanly (the address card slides up under the trip-info card).
        val btnCallClient = view.findViewById<View>(R.id.btnCallClient)
        val callDigits = clientMobile?.filter { it.isDigit() }.orEmpty()
        if (callDigits.length >= 10) {
            btnCallClient?.visibility = View.VISIBLE
            btnCallClient?.setOnClickListener { dialPhone(callDigits) }
        } else {
            btnCallClient?.visibility = View.GONE
        }

        btnBack?.setOnClickListener { navigateUp() }
        // System back closes the full-screen map first (if open), otherwise
        // falls through to normal up-navigation.
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(false) {
                override fun handleOnBackPressed() {
                    if (isMapFullScreen) collapseMap()
                }
            }.also { mapBackCallback = it },
        )
        btnOpenMaps?.setOnClickListener { ensureVisitStarted() }
        swipeArrived?.onConfirmed = { onArrivalSwipeConfirmed() }
        btnCompleteCpDetails?.setOnClickListener { onCompleteCpDetailsClicked() }

        // Listen for OTP verify result from the bottom sheet.
        setFragmentResultListener(ArrivalOtpBottomSheet.RESULT_KEY) { _, bundle ->
            val otp = bundle.getString(ArrivalOtpBottomSheet.KEY_OTP).orEmpty()
            onArrivalOtpVerified(otp)
        }

        // KOS-37: CP-visit only — listen for the Client Met / Outcome sheet
        // result and finalize completion afterward.
        setFragmentResultListener(CompleteCpVisitBottomSheet.RESULT_KEY) { _, bundle ->
            isOpeningOutcomeSheet = false
            cpVisitDecisionCaptured = true
            pendingCpRevisit = CpRevisitConfirmation.fromResult(bundle)
            val outcome = bundle.getString(CompleteCpVisitBottomSheet.KEY_OUTCOME)
            cpClientMet = if (bundle.containsKey(CompleteCpVisitBottomSheet.KEY_CLIENT_MET)) {
                bundle.getBoolean(CompleteCpVisitBottomSheet.KEY_CLIENT_MET)
            } else {
                cpClientMet
            }
            cpOutcome = outcome ?: cpOutcome
            cpOutcomeNotes = bundle.getString(CompleteCpVisitBottomSheet.KEY_OUTCOME_NOTES)
            cpPostponeReasons = bundle.getStringArrayList(CompleteCpVisitBottomSheet.KEY_POSTPONE_REASONS)
            cpFollowUpDate = bundle.getString(CompleteCpVisitBottomSheet.KEY_FOLLOW_UP_DATE)
            cpFollowUpTime = bundle.getString(CompleteCpVisitBottomSheet.KEY_FOLLOW_UP_TIME)
            if (outcome == "cancelled" || outcome == "rejected") {
                // These atomic outcome routes already close the CP row, linked
                // SV, field visit and daily task. Completing the field visit a
                // second time can turn a successful outcome into a false error.
                clearVisitLocallyStarted()
                pendingArrivalStorageId = null
                arrivalInProgress = false
                val message = if (outcome == "rejected") {
                    "Visit rejected and follow-up created"
                } else {
                    "Site visit cancelled"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                navigateUp()
            } else if (isJointCpWorkflow() && jointWorkflow?.actorRole == "outcome_owner") {
                submitJointCpForReview()
            } else if (isJointCpWorkflow() && jointWorkflow?.actorRole == "reviewer") {
                completeJointCpReview()
            } else {
                finalizeCompleteVisit()
            }
        }
        setFragmentResultListener(CpClientSeenBottomSheet.RESULT_KEY) { _, bundle ->
            val clientSeen = bundle.getBoolean(CpClientSeenBottomSheet.KEY_CLIENT_SEEN)
            if (clientSeen) {
                startCpYesPath()
            } else {
                startCpNoPath()
            }
        }
        setFragmentResultListener(CpClientNotMetProofBottomSheet.RESULT_KEY) { _, bundle ->
            val photo = bundle.getString(CpClientNotMetProofBottomSheet.KEY_PHOTO_PATH)
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
            when (bundle.getString(CpClientNotMetProofBottomSheet.KEY_ACTION)) {
                CpClientNotMetProofBottomSheet.ACTION_SUBMIT -> {
                    if (photo == null || !photo.exists()) {
                        resetClientNotMetCapture("Captured photo is no longer available. Please retake it.")
                    } else {
                        uploadArrivalPhotoThenCompleteWithoutClient(
                            photo,
                            bundle.getString(CpClientNotMetProofBottomSheet.KEY_REMARKS),
                        )
                    }
                }
                CpClientNotMetProofBottomSheet.ACTION_RETAKE -> {
                    photo?.let(::discardUploadedArrivalPhoto)
                    cpNoPathPhotoCapture = true
                    arrivalInProgress = true
                    launchArrivalCamera()
                }
                else -> {
                    photo?.let(::discardUploadedArrivalPhoto)
                    resetClientNotMetCapture()
                }
            }
        }
        setFragmentResultListener(CpTripCompletedBottomSheet.RESULT_KEY) { _, _ ->
            navigateUp()
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
            "arrived", "arrival_verified", "arrival-verified", "on_site", "on-site" -> {
                visitStarted = true
                arrivalConfirmedForProgress = true
                showTripStartTime()
                applyStatusPill(if (session.isDriverMode) "On Site" else "Reaching")
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
                        btnOpenMaps?.visibility = View.GONE
                        if (isJointCpWorkflow() && jointWorkflow?.state != "completed") {
                            arrivalConfirmedForProgress = true
                            applyStatusPill("Waiting review")
                            renderArrivalPhase(alreadyArrived = true)
                        } else {
                            applyStatusPill("Complete")
                            swipeArrived?.visibility = View.GONE
                            btnCompleteCpDetails?.visibility = View.GONE
                        }
                    }
        }

        // Deploy-independent bridge: if the backend list still reports a
        // pre-start status but this device already started the trip, show the
        // enroute/arrival phase so re-opening the card doesn't reset to
        // "Start Trip". A backend arrived/completed status above already took
        // over and skipped this.
        val isPreStartStatus =
            incomingStatus.isBlank() ||
                incomingStatus in setOf("scheduled", "assigned", "pending", "in_progress_cp")
        if (isPreStartStatus && !visitStarted && isVisitLocallyStarted()) {
            visitStarted = true
            showTripStartTime()
            applyStatusPill("Enroute")
            btnOpenMaps?.visibility = View.GONE
            renderArrivalPhase(alreadyArrived = false)
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

        setFragmentResultListener(DriverEndTripBottomSheet.RESULT_KEY) { _, bundle ->
            val success = bundle.getBoolean("success")
            if (success) {
                Toast.makeText(requireContext(), "Trip completed successfully", Toast.LENGTH_SHORT).show()
                navigateUp()
            }
        }
    }

    // ---------- Joint CP ----------

    private fun isJointCpWorkflow(): Boolean =
        cpType?.equals("joint_cp", ignoreCase = true) == true || jointWorkflow != null

    private fun startJointWorkflowPolling() {
        if (!isJointCpWorkflow() || cpVisitId.isNullOrBlank() || jointWorkflowPollJob != null) return
        jointWorkflowPollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshJointWorkflow()
                delay(JOINT_WORKFLOW_POLL_MS)
            }
        }
    }

    private suspend fun refreshJointWorkflow() {
        val cpId = cpVisitId ?: return
        runCatching { geoApi.getJointCpWorkflow(session.bearerToken, cpId) }
            .onSuccess { response ->
                if (!response.success || response.workflow == null || !isAdded) return@onSuccess
                jointWorkflow = response.workflow
                view?.let { applyJointWorkflowPresentation(it, response.workflow) }
                val workflow = response.workflow
                if (workflow.actorRole == "reviewer" && workflow.canReview &&
                    workflow.outcomeRevision != null &&
                    autoOpenedJointReviewRevision != workflow.outcomeRevision &&
                    !isOpeningOutcomeSheet
                ) {
                    autoOpenedJointReviewRevision = workflow.outcomeRevision
                    isOpeningOutcomeSheet = true
                    btnCompleteCpDetails?.post { showCpCompletionSheet() }
                }
            }
            .onFailure {
                // The endpoint is additive. Until the backend deployment lands,
                // preserve the existing trip flow instead of disabling a live CP.
                android.util.Log.d("TripNav", "Joint workflow refresh unavailable", it)
            }
    }

    private fun applyJointWorkflowPresentation(view: View, workflow: JointCpWorkflow) {
        view.findViewById<TextView>(R.id.tvJointPendingFor)?.apply {
            text = jointWaitingMessage(workflow)
            visibility = View.VISIBLE
        }
        if (workflow.state == "completed") {
            applyStatusPill("Complete")
            swipeArrived?.visibility = View.GONE
            btnCompleteCpDetails?.visibility = View.GONE
            return
        }
        if (visitStarted) renderArrivalPhase(arrivalConfirmedForProgress)
    }

    private fun jointWaitingMessage(workflow: JointCpWorkflow): String = when {
        workflow.state == "completed" -> {
            val reviewer = workflow.reviewedByTemplateName ?: workflow.reviewedByName ?: "reviewer"
            "Outcome reviewed by $reviewer"
        }
        workflow.actorRole == "reviewer" && workflow.canReview ->
            "Review the BDO outcome and make any required changes"
        workflow.actorRole == "reviewer" ->
            "Waiting for ${workflow.outcomeOwnerName ?: "BDO"} outcome"
        workflow.actorRole == "outcome_owner" && workflow.canSubmitOutcome ->
            "OTP verified. Complete the outcome and send it for review"
        workflow.actorRole == "outcome_owner" ->
            "Complete OTP and photo while both partners are within 50 metres"
        else -> "Waiting for Joint CP workflow update"
    }

    /** Returns true when Joint CP owns the bottom actions for this phase. */
    private fun renderJointWorkflowActions(alreadyArrived: Boolean): Boolean {
        val workflow = jointWorkflow ?: return false
        if (!visitStarted) return false

        if (!alreadyArrived && workflow.actorRole == "outcome_owner" && workflow.canRequestOtp) {
            return false
        }

        swipeArrived?.visibility = View.GONE
        btnCompleteCpDetails?.visibility = View.VISIBLE
        btnCompleteCpDetails?.isEnabled = !jointMutationInProgress &&
            (workflow.canSubmitOutcome || workflow.canReview)
        btnCompleteCpDetails?.text = when {
            jointMutationInProgress -> "Updating Joint CP..."
            workflow.actorRole == "reviewer" && workflow.canReview -> "Review outcome"
            workflow.actorRole == "outcome_owner" && workflow.canSubmitOutcome -> "Enter outcome"
            workflow.actorRole == "reviewer" -> "Waiting for BDO outcome"
            else -> "Waiting for partner"
        }
        return true
    }

    private fun preflightJointCpArrival() {
        val cpId = cpVisitId ?: return
        val fieldId = visitId ?: return
        val workflow = jointWorkflow
        if (workflow != null && !workflow.canRequestOtp) {
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            Toast.makeText(requireContext(), jointWaitingMessage(workflow), Toast.LENGTH_LONG).show()
            return
        }
        swipeArrived?.lockAsBusy("Checking both staff locations...")
        viewLifecycleOwner.lifecycleScope.launch {
            val location = fetchCurrentLocation()
            if (location == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(requireContext(), "Could not read your GPS. Try again in open sky.", Toast.LENGTH_LONG).show()
                return@launch
            }
            try {
                val response = geoApi.preflightJointCpArrival(
                    session.bearerToken,
                    JointCpLocationRequest(
                        id = cpId,
                        fieldVisitId = fieldId,
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                        capturedAt = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    ),
                )
                val refreshed = response.workflow
                if (!response.success || refreshed == null || !refreshed.isWithinCompletionRadius) {
                    jointWorkflow = refreshed ?: jointWorkflow
                    arrivalInProgress = false
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    val measured = refreshed?.separationMeters?.let(::formatDistance)
                    val message = response.error ?: if (measured != null) {
                        "Joint CP completion is blocked. Both staff must be within 50 metres. Current separation: $measured."
                    } else {
                        "Both Joint CP staff must share a fresh location and be within 50 metres."
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                    return@launch
                }
                jointWorkflow = refreshed
                checkReachingAndAskClientSeen()
            } catch (e: Exception) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(requireContext(), serverErrorMessage(e) ?: "Could not verify both staff locations", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitJointCpForReview() {
        if (jointMutationInProgress) return
        val cpId = cpVisitId ?: return
        val fieldId = visitId ?: return
        jointMutationInProgress = true
        renderArrivalPhase(alreadyArrived = true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val location = fetchCurrentLocation()
                    ?: throw IllegalStateException("Could not read your current location")
                val response = geoApi.submitJointCpReview(
                    session.bearerToken,
                    java.util.UUID.randomUUID().toString(),
                    JointCpSubmitReviewRequest(
                        id = cpId,
                        fieldVisitId = fieldId,
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                        capturedAt = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        arrivalPhotoStorageId = pendingArrivalStorageId,
                        expectedOutcomeRevision = jointWorkflow?.outcomeRevision,
                    ),
                )
                check(response.success && response.workflow != null) {
                    response.error ?: "Could not send outcome for review"
                }
                jointWorkflow = response.workflow
                clearVisitLocallyStarted()
                Toast.makeText(requireContext(), "Outcome sent for review", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), serverErrorMessage(e) ?: e.message ?: "Could not send review", Toast.LENGTH_LONG).show()
            } finally {
                jointMutationInProgress = false
                jointWorkflow?.let { view?.let { root -> applyJointWorkflowPresentation(root, it) } }
            }
        }
    }

    private fun completeJointCpReview() {
        if (jointMutationInProgress) return
        val cpId = cpVisitId ?: return
        val revision = jointWorkflow?.outcomeRevision ?: run {
            Toast.makeText(requireContext(), "Refresh the submitted outcome before completing", Toast.LENGTH_LONG).show()
            return
        }
        jointMutationInProgress = true
        renderArrivalPhase(alreadyArrived = true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = geoApi.completeJointCpReview(
                    session.bearerToken,
                    java.util.UUID.randomUUID().toString(),
                    JointCpCompleteReviewRequest(cpId, revision),
                )
                check(response.success && response.workflow != null) {
                    response.error ?: "Could not complete Joint CP review"
                }
                jointWorkflow = response.workflow
                clearVisitLocallyStarted()
                val reviewedBy = response.workflow.reviewedByTemplateName
                    ?: response.workflow.reviewedByName
                    ?: "reviewer"
                Toast.makeText(requireContext(), "Outcome reviewed by $reviewedBy", Toast.LENGTH_LONG).show()
                navigateUp()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), serverErrorMessage(e) ?: e.message ?: "Could not complete review", Toast.LENGTH_LONG).show()
            } finally {
                jointMutationInProgress = false
            }
        }
    }

    /**
     * Renders a Joint CP onto the field-staff card: both participants with
     * their own progress, and who the visit is still waiting on.
     *
     * A Joint CP is two independent trips against one client place, so a
     * single "Field Staff" name would be actively misleading — the person
     * reading this screen may be either participant, or a manager looking at
     * both. Called with null for every other cpType, which restores the plain
     * single-name card.
     */
    private fun bindJointCp(view: View, joint: JointCpSummary?) {
        val block = view.findViewById<LinearLayout>(R.id.jointParticipantsBlock)
        val pill = view.findViewById<TextView>(R.id.tvJointCpPill)
        val rows = view.findViewById<LinearLayout>(R.id.jointParticipantRows)
        val pending = view.findViewById<TextView>(R.id.tvJointPendingFor)
        val label = view.findViewById<TextView>(R.id.tvFieldStaffLabel)
        val nameView = view.findViewById<TextView>(R.id.tvTripFieldStaff)
        val card = view.findViewById<View>(R.id.fieldStaffCard)

        val participants = joint?.participants.orEmpty()
        if (participants.isEmpty()) {
            block?.visibility = View.GONE
            pill?.visibility = View.GONE
            label?.text = "Field Staff"
            return
        }

        card?.visibility = View.VISIBLE
        pill?.visibility = View.VISIBLE
        block?.visibility = View.VISIBLE
        label?.text = "Field Staff (2) · Joint"
        // The headline names both, because "assigned to" on a Joint CP means
        // both people, not the one whose id happens to sit on the visit row.
        nameView?.text = participants
            .mapNotNull { it.staffName?.takeIf { n -> n.isNotBlank() } }
            .joinToString(" & ")
            .ifBlank { "—" }

        rows?.removeAllViews()
        participants.forEach { p -> rows?.addView(jointParticipantRow(p)) }

        // Workflow roles are resolved from IAM templates by the backend. Never
        // infer authority from the displayed designation or participant order.
        val workflow = joint?.workflow
        if (workflow == null) {
            pending?.visibility = View.GONE
        } else {
            jointWorkflow = workflow
            pending?.text = jointWaitingMessage(workflow)
            pending?.visibility = View.VISIBLE
        }
    }

    /** One participant line: name on the left, their own status chip right. */
    private fun jointParticipantRow(p: JointCpParticipant): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(8), 0, dpToPx(8))
        }
        row.addView(TextView(ctx).apply {
            text = p.staffName?.takeIf { it.isNotBlank() } ?: "Unnamed staff"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#101828"))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        })
        // The outcome matters as much as the status: a manager wants to see
        // that one said Interested and the other did not.
        p.outcome?.takeIf { it.isNotBlank() }?.let { outcome ->
            row.addView(TextView(ctx).apply {
                text = com.manjugroups.m_connect.ui.marketing.formatCpOutcomeLabel(outcome)
                textSize = 11f
                setTextColor(android.graphics.Color.parseColor("#475467"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dpToPx(8) }
            })
        }
        row.addView(jointStatusChip(p.status))
        return row
    }

    private fun jointStatusChip(status: String?): View {
        val (text, fg, bg) = when (status) {
            "completed" -> Triple("Done", "#067647", "#ECFDF3")
            "in_progress" -> Triple("On the way", "#B54708", "#FFFAEB")
            "pending_gm_approval" -> Triple("Awaiting GM", "#B54708", "#FFFAEB")
            "cancelled" -> Triple("Cancelled", "#B42318", "#FEF3F2")
            else -> Triple("Not started", "#475467", "#F2F4F7")
        }
        return TextView(requireContext()).apply {
            this.text = text
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor(fg))
            setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpToPx(9).toFloat()
                setColor(android.graphics.Color.parseColor(bg))
            }
        }
    }

    private fun dpToPx(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Fill (or hide) the LMO + Deadline cells on the Trip Details card. */
    private fun bindTripMeta(
        view: View,
        lmoName: String?,
        fieldStaffName: String?,
        deadline: String?,
    ) {
        // Assigned field staff, on its own full-width card above Call Client.
        // Hidden when unknown rather than showing a dash — an empty labelled
        // card is worse than no card.
        val staffCard = view.findViewById<View>(R.id.fieldStaffCard)
        val staff = fieldStaffName?.takeIf { it.isNotBlank() }
        if (staff != null) {
            view.findViewById<TextView>(R.id.tvTripFieldStaff)?.text = staff
            staffCard?.visibility = View.VISIBLE
        } else {
            staffCard?.visibility = View.GONE
        }

        val lmoRow = view.findViewById<View>(R.id.rowTripLmo)
        val lmo = lmoName?.takeIf { it.isNotBlank() }
        if (lmo != null) {
            view.findViewById<TextView>(R.id.tvTripLmo)?.text = lmo
            lmoRow?.visibility = View.VISIBLE
        } else {
            lmoRow?.visibility = View.GONE
        }

        val deadlineRow = view.findViewById<View>(R.id.rowTripDeadline)
        val dl = deadline?.takeIf { it.isNotBlank() }
        if (dl != null) {
            view.findViewById<TextView>(R.id.tvTripDeadline)?.text = dl
            deadlineRow?.visibility = View.VISIBLE
        } else {
            deadlineRow?.visibility = View.GONE
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
                    // Wide window so this specific CP is reliably in the result
                    // set — the default (~10 newest) could omit it and silently
                    // skip the status reconcile.
                    limit = 200,
                )
                if (!resp.success) return@launch
                val cp = resp.visits.firstOrNull { it.id == cpId } ?: return@launch
                if (!isAdded) return@launch
                cpType = cp.cpType ?: cpType
                cp.joint?.workflow?.let { jointWorkflow = it }
                startJointWorkflowPolling()

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

                // Joint CP: paint both participants and who the visit is
                // still waiting on. Done on the reconcile rather than at
                // bind time because the partner's leg advances while this
                // staff is on the road, and this is the call that already
                // re-reads the server's view of the visit.
                view?.let { bindJointCp(it, cp.joint) }

                // Pick the most-advanced authoritative status: prefer the
                // spawned fieldVisits row (where "arrived" lives) over
                // the CP-side lifecycle (which only tracks
                // scheduled/in_progress/completed).
                val effective = (cp.fieldVisit?.status ?: cp.status).orEmpty()
                    .lowercase(Locale.getDefault())
                when (effective) {
                    "arrived", "arrival_verified", "arrival-verified", "on_site", "on-site" -> {
                        visitStarted = true
                        arrivalConfirmedForProgress = true
                        showTripStartTime()
                        applyStatusPill(if (session.isDriverMode) "On Site" else "Reaching")
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
                        // Terminal on the server — drop the local started bridge
                        // so it never masks a genuinely finished trip.
                        clearVisitLocallyStarted()
                    }
                    // "scheduled" or empty: the server hasn't advanced the row
                    // yet. If THIS device started it, surface the enroute phase
                    // instead of leaving the user on "Start Trip".
                    else -> {
                        if (!arrivalConfirmedForProgress && !visitStarted && isVisitLocallyStarted()) {
                            visitStarted = true
                            showTripStartTime()
                            applyStatusPill("Enroute")
                            btnOpenMaps?.visibility = View.GONE
                            renderArrivalPhase(alreadyArrived = false)
                        }
                    }
                }
            } catch (_: Exception) {
                // Network blip: don't disturb the locally-rendered UI.
            }
        }
    }

    // ── Local "trip started on this device" bridge ───────────────────────────
    // The backend CP list reports the clientPlaceVisit's own lifecycle status
    // ("scheduled" until the outcome is recorded) — the authoritative
    // in-progress state lives on the spawned fieldVisit, which older backends
    // don't surface in the list. Without a bridge, exiting and re-opening Trip
    // Details after Start Trip drops the staff back on "Start Trip". We record
    // the started ids on-device and treat them as enroute on re-open, until the
    // backend advances the row itself (arrived/completed still win below).
    private fun localStartedIds(): MutableSet<String> =
        requireContext()
            .getSharedPreferences("trip_local_started", android.content.Context.MODE_PRIVATE)
            .getStringSet("ids", emptySet())
            ?.toMutableSet() ?: mutableSetOf()

    private fun startedKeys(): List<String> =
        listOfNotNull(visitId, cpVisitId, arguments?.getString(ARG_PLACE_ID))
            .filter { it.isNotBlank() }

    private fun markVisitLocallyStarted() {
        if (!isAdded) return
        val set = localStartedIds()
        set.addAll(startedKeys())
        requireContext()
            .getSharedPreferences("trip_local_started", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("ids", set).apply()
    }

    private fun clearVisitLocallyStarted() {
        if (!isAdded) return
        val set = localStartedIds()
        if (set.removeAll(startedKeys().toSet())) {
            requireContext()
                .getSharedPreferences("trip_local_started", android.content.Context.MODE_PRIVATE)
                .edit().putStringSet("ids", set).apply()
        }
    }

    private fun isVisitLocallyStarted(): Boolean {
        val set = localStartedIds()
        return startedKeys().any { it in set }
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
        startJointWorkflowPolling()
    }

    override fun onPause() {
        mapView?.onPause()
        // Only restore the tab bar when the fragment is actually being
        // removed (back navigation, parent pop). When onPause fires
        // because a BottomSheetDialog is overlaying us (sheet show()
        // pauses the host fragment), `isRemoving` and
        // `activity.isFinishing` are both false — in that case we must
        // KEEP the tab bar hidden, otherwise it briefly pops up
        // underneath every outcome / remarks / payment-entry sheet we
        // open from this screen.
        if (isRemoving || activity?.isFinishing == true) {
            (activity as? MainActivity)?.setTabBarVisible(true)
        }
        super.onPause()
    }

    override fun onStop() {
        jointWorkflowPollJob?.cancel()
        jointWorkflowPollJob = null
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
            // The map sits inside a NestedScrollView; let vertical drags scroll
            // the page instead of panning this small preview (otherwise the map
            // swallows the gesture and the page feels stuck over it).
            uiSettings.isScrollGesturesEnabled = false
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

    /**
     * Reparent the single MapView from the preview card into the full-screen
     * host and show the overlay. Keeping one MapView means all markers, the
     * route line and the camera survive the toggle. In full view we allow
     * panning + zoom controls (the preview locks them so page scroll works).
     */
    private fun expandMap() {
        if (isMapFullScreen) return
        val mv = mapView ?: return
        val host = mapFullScreenHost ?: return
        (mv.parent as? ViewGroup)?.removeView(mv)
        host.addView(
            mv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        mapFullScreenContainer?.visibility = View.VISIBLE
        googleMap?.uiSettings?.isScrollGesturesEnabled = true
        googleMap?.uiSettings?.isZoomControlsEnabled = true
        isMapFullScreen = true
        mapBackCallback?.isEnabled = true
        renderMapMarkersAndRoute()
    }

    /** Reparent the MapView back into the preview card (below the loading
     *  overlay + expand button) and hide the full-screen overlay. */
    private fun collapseMap() {
        if (!isMapFullScreen) return
        val mv = mapView ?: return
        val card = mapCard ?: return
        (mv.parent as? ViewGroup)?.removeView(mv)
        card.addView(
            mv,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        mapFullScreenContainer?.visibility = View.GONE
        googleMap?.uiSettings?.isScrollGesturesEnabled = false
        googleMap?.uiSettings?.isZoomControlsEnabled = false
        isMapFullScreen = false
        mapBackCallback?.isEnabled = false
        renderMapMarkersAndRoute()
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
        // Primary area / locality shown right under the client name (e.g.
        // "Medavakkam", "Ashok Nagar"), parsed from the client address. Hidden
        // when we can't derive an area.
        val area = primaryAreaFromAddress(destinationAddress)
        if (area.isNullOrBlank()) {
            roleView.visibility = View.GONE
        } else {
            roleView.text = area
            roleView.visibility = View.VISIBLE
            val px = (14 * view.resources.displayMetrics.density).toInt()
            val pin = androidx.core.content.ContextCompat
                .getDrawable(view.context, R.drawable.ic_cp_locality)?.mutate()
            pin?.setBounds(0, 0, px, px)
            roleView.setCompoundDrawables(pin, null, null, null)
        }
    }

    /**
     * The client's primary area — i.e. the "Address Line 1" the CP form
     * captures. CP addresses are the labeled, comma-joined shape from
     * CreateCpVisit: "Door/Plot No: .., Street: .., Address: <line1>,
     * Landmark: .., City: .., State: .., Pincode: ..". So the area is the
     * value of the `Address:` segment — NOT the first token (which is the
     * door/plot number). Falls back to the first non-door token for an
     * unlabeled backend-composed address. Returns null when nothing usable.
     */
    private fun primaryAreaFromAddress(address: String?): String? {
        val s = address?.trim().orEmpty()
        if (s.isEmpty()) return null
        val segments = s.split(",").map { it.trim() }.filter { it.isNotBlank() }
        // Preferred: the value after the "Address:" label (= addressLine1).
        val addressLabel = Regex("^Address\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        segments.firstNotNullOfOrNull { seg ->
            addressLabel.find(seg)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }?.let { return it }
        // Fallback (unlabeled "doorNo, area, city, .." shape): skip any other
        // labeled segments and bare door/plot numbers, take the first real name.
        val otherLabel = Regex(
            "^(Door/Plot No|Door No|Street|Landmark|City|State|District|Pincode|Pin)\\s*:",
            RegexOption.IGNORE_CASE,
        )
        val looksLikeDoorNo: (String) -> Boolean = { t ->
            val cleaned = t.replace(Regex("(?i)\\b(no|door|plot|flat|d\\.?no|#)\\.?\\b"), "").trim()
            cleaned.isNotEmpty() && cleaned.all { it.isDigit() || it == '/' || it == '-' || it == ' ' }
        }
        return segments.firstOrNull { seg ->
            !otherLabel.containsMatchIn(seg) && !looksLikeDoorNo(seg)
        }?.takeIf { it.isNotBlank() }
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

    private fun applyTripProgressStage(stageRaw: Int) {
        // The "Complete" step fills only once the visit's OUTCOME decision is
        // recorded (booking / not interested / postponed / SV outcome).
        // Physically finishing the trip = "Reached", not "Complete", until the
        // decision is captured. Driver-mode fleet trips have no outcome sheet,
        // so they complete as usual.
        val stage = if (
            stageRaw >= 4 && !session.isDriverMode && !cpVisitDecisionCaptured
        ) 3 else stageRaw

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
        // Pure SV: skip "Start Trip" entirely — the staff is already at
        // the project; tap routes straight to the outcome sheet so
        // they can record Booking / Postpone / Not Interested. Trip
        // tracking on a real SV happens server-side on the parent row
        // when the office side advances status. CP-derived visits
        // keep the original "Start Trip" → trip-lifecycle flow.
        if (isPureSiteVisit) {
            tvStartTripLabel?.text = "Complete Outcome"
            btnOpenMaps?.setOnClickListener { openSiteVisitOutcomeSheet() }
        } else {
            tvStartTripLabel?.text = "Start Trip"
            btnOpenMaps?.setOnClickListener { ensureVisitStarted() }
        }
        swipeArrived?.visibility = View.GONE
        btnCompleteCpDetails?.visibility = View.GONE
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
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived", "on_site", "on-site"
        )
        val alreadyArrived = existingStatus in setOf("arrived", "arrival_verified", "arrival-verified", "on_site", "on-site")

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
                // Remember locally that this device started the trip so a
                // later exit + re-open doesn't reset to "Start Trip" while the
                // backend list still reports the CP as "scheduled".
                markVisitLocallyStarted()
                if (alreadyArrived) arrivalConfirmedForProgress = true
                showTripStartTime()
                if (location != null) currentLocation = LatLng(location.latitude, location.longitude)
                loadingOverlay?.visibility = View.GONE
                btnOpenMaps?.visibility = View.GONE
                btnOpenMaps?.isEnabled = true
                applyStatusPill(when {
                    alreadyArrived && session.isDriverMode -> "On Site"
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
        navigateUp()
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
            renderMapMarkersAndRoute()
            geocodeDestinationIfNeeded()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun fetchCurrentLocation(): Location? {
        if (!hasLocationPermission()) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(requireContext())
            val token = CancellationTokenSource()
            // Force a FRESH fix. The old getCurrentLocation(priority) overload
            // could hand back a cached fused location from where the trip
            // STARTED — so a staff who has reached the client read as
            // "79 km away" and could never complete. maxUpdateAge=10s rejects
            // that stale cache; the GeoTrackService running during the trip
            // keeps a sub-second fix ready, so this still resolves fast. Wait
            // up to 20s for GPS before giving up.
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(10_000L)
                .setDurationMillis(20_000L)
                .build()
            client.getCurrentLocation(request, token.token).await()
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
            // The live "My Location" blue dot (map.isMyLocationEnabled,
            // enabled in onMapReady) already marks the user and tracks
            // their real position as they move. Dropping a second STATIC
            // azure pin at the one-time fix sat on top of that dot and
            // read as "the app thinks I'm parked here" — which is what
            // looked wrong. Only add a fallback "You" pin when the live
            // layer is unavailable (location permission not granted), so
            // the user still sees their start point in that case.
            if (!hasLocationPermission()) {
                map.addMarker(
                    MarkerOptions()
                        .position(origin)
                        .title("You")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                )
            }
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

        if (session.isDriverMode) {
            markDriverOnSite()
            return
        }

        if (isJointCpWorkflow()) {
            preflightJointCpArrival()
            return
        }
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

    private fun markDriverOnSite() {
        val id = visitId ?: run {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe if Onsite Reached")
            Toast.makeText(requireContext(), "No active visit", Toast.LENGTH_SHORT).show()
            return
        }
        swipeArrived?.lockAsBusy("Updating on-site…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = geoApi.markMmsFleetDriverOnSite(
                    session.bearerToken,
                    MmsFleetDriverSiteVisitRequest(id),
                )
                if (!response.success) {
                    throw IllegalStateException(response.error ?: "Failed to mark on-site")
                }
                arrivalConfirmedForProgress = true
                session.saveDriverTripArrival(id)
                applyStatusPill("On Site")
                renderArrivalPhase(alreadyArrived = true)
                Toast.makeText(requireContext(), "On Site Reached", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                swipeArrived?.reset(newLabel = "Swipe if Onsite Reached")
                Toast.makeText(
                    requireContext(),
                    "Failed to mark on-site: ${e.message ?: "Network error"}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                arrivalInProgress = false
            }
        }
    }

    /**
     * The return pickup: the client is done at the site and back in the
     * vehicle. Sits between on-site and end-trip so the SV timeline records
     * when the return leg actually started instead of inferring it from the
     * drop.
     */
    private fun markDriverPickedFromSite() {
        val id = visitId ?: run {
            Toast.makeText(requireContext(), "No active visit", Toast.LENGTH_SHORT).show()
            return
        }
        btnCompleteCpDetails?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = geoApi.markMmsFleetDriverPickedFromSite(
                    session.bearerToken,
                    MmsFleetDriverSiteVisitRequest(id),
                )
                if (!response.success) {
                    throw IllegalStateException(
                        response.error ?: "Failed to mark picked from site"
                    )
                }
                pickedFromSiteConfirmed = true
                session.saveDriverTripPickedFromSite(id)
                applyStatusPill("Picked from Site")
                renderArrivalPhase(alreadyArrived = true)
                Toast.makeText(
                    requireContext(),
                    "Picked from site",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to mark picked from site: ${e.message ?: "Network error"}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                btnCompleteCpDetails?.isEnabled = true
            }
        }
    }

    private fun checkReachingAndAskClientSeen() {
        swipeArrived?.lockAsBusy("Checking location...")
        viewLifecycleOwner.lifecycleScope.launch {
            // Only a FRESH fix decides the geofence — never the cached
            // currentLocation, which may be the (far-away) trip-start point and
            // would wrongly report the staff as kilometres from a client they
            // have already reached.
            val freshLocation = fetchCurrentLocation()
            val dest = destination
            if (freshLocation == null || dest == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Couldn't get a fresh GPS fix. Stand in the open and swipe again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val effLat = freshLocation.latitude
            val effLng = freshLocation.longitude
            currentLocation = LatLng(effLat, effLng)

            // Continue into the normal completion flow (client-seen → photo →
            // OTP). Extracted so the out-of-geofence warning below can gate it.
            val proceed = {
                arrivalConfirmedForProgress = true
                applyStatusPill("Reaching")
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                CpClientSeenBottomSheet().showOnce(parentFragmentManager, "cp_client_seen")
            }

            // Geofence is no longer a HARD block (the arrival OTP is the real
            // proof of presence, and the old block mis-fired on stale client
            // coordinates), but when the staff is clearly away from the client's
            // saved location, warn first: completing from here is allowed but the
            // server holds it for GM approval. Cancel aborts; Complete runs the
            // normal photo + OTP flow.
            val distance = haversineMeters(currentLocation!!, dest)
            if (distance > GEOFENCE_APPROVAL_RADIUS_METERS) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                val sheet = OutOfGeofenceWarningBottomSheet.newInstance(formatDistance(distance))
                sheet.onCancel = { arrivalInProgress = false }
                sheet.onComplete = { reason ->
                    // Stash the reason on the visit so the approving GM sees why
                    // the staff completed away from the client, then run the
                    // normal client-seen → photo → OTP flow.
                    (cpVisitId ?: visitId)?.let { cpId ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            runCatching {
                                geoApi.setCpGeofenceRemark(
                                    session.bearerToken,
                                    com.manjugroups.m_connect.network
                                        .CpGeofenceRemarkRequest(cpId, reason),
                                )
                            }
                        }
                    }
                    proceed()
                }
                sheet.showOnce(parentFragmentManager, "out_of_geofence_warning")
                return@launch
            }
            proceed()
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
            // Require a FRESH fix — the server re-checks arrival distance
            // against this, so a stale cached location (trip-start point) would
            // make the server reject a staff who is actually at the client.
            val freshLocation = fetchCurrentLocation()
            val effLat = freshLocation?.latitude
            val effLng = freshLocation?.longitude
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
                        arrivalInProgress = false
                        arrivalConfirmedForProgress = true
                        renderArrivalPhase(alreadyArrived = true)
                        return@launch
                    }
                    // Fallback for the OTP request rate-limit ("Maximum OTP
                    // requests reached"): an admin — or a staff member with
                    // access to the client's OTP — can relay the code
                    // out-of-band. Don't dead-end the trip on a toast; no NEW
                    // OTP is sent, but we continue into the normal proof + OTP
                    // entry flow so the staff has a field to type the relayed
                    // code. Verification still checks the code the server
                    // already issued AND re-validates arrival location, so
                    // integrity holds even though the send was rate-limited.
                    val otpLimitReached =
                        errMsg.contains("maximum otp") ||
                            errMsg.contains("otp requests reached") ||
                            errMsg.contains("max otp") ||
                            errMsg.contains("too many otp")
                    if (!otpLimitReached) {
                        arrivalInProgress = false
                        swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                        Toast.makeText(
                            requireContext(),
                            arrivalBlockedMessage(resp),
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }
                    Toast.makeText(
                        requireContext(),
                        "OTP limit reached — enter the code shared by your admin to complete.",
                        Toast.LENGTH_LONG
                    ).show()
                    // fall through to the normal proof + OTP entry flow below.
                }

                pendingArrivalOtpPhoneMasked = resp.contactPhoneMasked
                pendingArrivalLat = effLat
                pendingArrivalLng = effLng
                // Gift Distribution shortcut: skip the pre-OTP photo
                // step entirely. The proof we capture for gift_distri-
                // bution is the gift handover itself, which only
                // happens AFTER OTP verifies the client is present.
                // Open the OTP sheet directly without a photo
                // storage id; the post-OTP "Confirm Gift Distri-
                // bution" button then handles the camera + upload.
                if (isGiftDistribution) {
                    pendingArrivalStorageId = null
                    swipeArrived?.lockAsBusy("Enter OTP to confirm")
                    ArrivalOtpBottomSheet.newInstance(
                        visitId = id,
                        cpVisitId = cpVisitId,
                        phoneMasked = pendingArrivalOtpPhoneMasked,
                        lat = effLat,
                        lng = effLng,
                        arrivalPhotoStorageId = null,
                    ).showOnce(parentFragmentManager, "arrival_otp")
                    return@launch
                }
                swipeArrived?.lockAsBusy("Opening camera…")
                launchArrivalCamera()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                context?.let { ctx ->
                    Toast.makeText(
                        ctx,
                        "Network error: ${e.message ?: "unknown"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
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
        swipeArrived?.lockAsBusy("Optimizing and uploading photo…")
        viewLifecycleOwner.lifecycleScope.launch {
            val upload = uploadArrivalPhoto(photoFile)
            val storageId = upload.storageId
            if (storageId == null) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                context?.let { ctx ->
                    Toast.makeText(
                        ctx,
                        upload.errorMessage ?: "Photo upload failed. Try again.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            pendingArrivalStorageId = storageId
            discardUploadedArrivalPhoto(photoFile)

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
                cpVisitId = cpVisitId,
                phoneMasked = pendingArrivalOtpPhoneMasked,
                lat = otpLat,
                lng = otpLng,
                // Hand the already-uploaded photo's storage id to the
                // OTP sheet so it can attach the photo to the
                // fieldVisit row at OTP-verify time. Without this the
                // photo only got linked at completeVisit (trip-end),
                // which left the web admin showing "No arrival photo
                // yet" for the entire in-flight window.
                arrivalPhotoStorageId = pendingArrivalStorageId,
            ).showOnce(parentFragmentManager, "arrival_otp")
        }
    }

    private suspend fun uploadArrivalPhoto(file: File): StorageUploader.Result =
        StorageUploader.upload(
            api = api,
            token = session.bearerToken,
            file = file,
            attempts = 2,
            contentType = "image/jpeg",
            // Arrival proof is viewed on a phone/web card, not printed. This
            // keeps text/faces clear while materially reducing weak-uplink time.
            imageMaxEdge = 1280,
            imageQuality = 74,
            imageSkipBelowBytes = 250_000L,
        )

    private fun onArrivalOtpVerified(@Suppress("UNUSED_PARAMETER") otp: String) {
        // The OTP itself is already verified server-side by /verify; here we
        // route through the CP-visit decision sheet when applicable, then
        // finalize the visit with the photo proof.
        if (visitId == null) return

        val isCpVisit = isCpVisit()
        // sv_cum_cp rows are CP-backed but don't carry tripType="client_place",
        // so isCpVisit() misses them. They still need the CP confirm sheet after
        // OTP — otherwise the trip finalizes and the linked SV is never confirmed
        // (stays "Fixed"). Handled at the routing check below (not in isCpVisit()
        // itself, to avoid changing the pre-OTP swipe/client-seen path).
        val isSvCumCp = !cpVisitId.isNullOrBlank() &&
            arguments?.getString(ARG_VISIT_CATEGORY) == "sv_cum_cp"

        // Gift Distribution: after OTP verify we DO NOT auto-complete.
        // The proof photo for gift_distribution is of the gift
        // handover itself (which only happens after OTP confirms the
        // right client), so we render the arrival phase with the
        // "Confirm Gift Distribution" button visible — the user taps
        // it once the gift is actually handed over, which launches
        // the camera + uploads the photo + closes the visit.
        if (isCpVisit && isGiftDistribution && !cpVisitDecisionCaptured) {
            renderArrivalPhase(alreadyArrived = true)
            return
        }

        // Old Client shortcut: photo + OTP, then a remarks popup
        // captures free-text notes before we close the visit. Skips
        // the full booking-outcome sheet for the same reason as
        // Gift Distribution — this is a re-engagement touch, not a
        // booking funnel step.
        if (isCpVisit && isOldClient && !cpVisitDecisionCaptured) {
            renderArrivalPhase(alreadyArrived = true)
            promptOldClientOutcome()
            return
        }

        // Collection CP shortcut: photo + OTP, then ask whether collection
        // happened. Yes opens Payment Entry; No asks only for the next
        // follow-up date and optional reason.
        // On submit we write a customerCollections row in
        // pending_accounts and close the visit with
        // outcome="collection_done". Same booking-outcome-sheet bypass
        // as gift_distribution / old_client.
        if (isCpVisit && isCollectionCp && !cpVisitDecisionCaptured) {
            renderArrivalPhase(alreadyArrived = true)
            promptCollectionDecision()
            return
        }

        if ((isCpVisit || isSvCumCp) && !cpVisitDecisionCaptured) {
            renderArrivalPhase(alreadyArrived = true)
            showCpCompletionSheet()
            return
        }
        finalizeCompleteVisit()
    }

    /** Yes-path entry for Old Client CPs. Opens the remarks popup and
     *  listens for the result; on Submit we close the visit with
     *  outcome="old_client_visited" and the remarks as notes. On
     *  cancel we reset the swipe so the user can retry without
     *  losing the trip-arrived state. */
    private fun promptOldClientRemarks() {
        val cpId = cpVisitId ?: run {
            finalizeCompleteVisit()
            return
        }
        setFragmentResultListener(OldClientRemarksBottomSheet.RESULT_KEY) { _, bundle ->
            isOpeningOutcomeSheet = false
            val submitted = bundle.getBoolean(OldClientRemarksBottomSheet.KEY_SUBMITTED, false)
            if (!submitted) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Add remarks to close this old-client visit.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setFragmentResultListener
            }
            val remarks = bundle.getString(OldClientRemarksBottomSheet.KEY_REMARKS).orEmpty()
            completeOldClientVisit(cpId, remarks)
        }
        // Must use parentFragmentManager: the result listener above is
        // registered via setFragmentResultListener (which targets THIS
        // fragment's parentFragmentManager). Showing the sheet on
        // childFragmentManager posts the submit result to a different
        // manager, so the listener never fires and the visit never
        // completes after Submit.
        OldClientRemarksBottomSheet.newInstance()
            .showOnce(parentFragmentManager, "old_client_remarks")
    }

    /** Old Client and Gift Distribution are dedicated CP flows, so they do
     *  not use the shared Booking/SV/Postpone picker. Give only these two
     *  approved special categories their normal action plus Others. */
    private fun promptOldClientOutcome() {
        showSpecialCpOutcomeChoice(
            primaryKey = "OLD_CLIENT_VISITED",
            primaryLabel = "Old client visited",
            primaryIcon = R.drawable.ic_outcome_site_visit,
            onPrimary = ::promptOldClientRemarks,
        )
    }

    private fun promptGiftDistributionOutcome() {
        showSpecialCpOutcomeChoice(
            primaryKey = "GIFT_DISTRIBUTED",
            primaryLabel = "Gift distributed",
            primaryIcon = R.drawable.ic_outcome_booking,
            onPrimary = ::completeGiftDistributionMet,
        )
    }

    private fun showSpecialCpOutcomeChoice(
        primaryKey: String,
        primaryLabel: String,
        primaryIcon: Int,
        onPrimary: () -> Unit,
    ) {
        val options = mutableListOf(
            OutcomeSelectionDialog.Option(primaryKey, primaryLabel, primaryIcon),
        )
        if (cpTypeSupportsOtherOutcome(cpType)) {
            options += OutcomeSelectionDialog.Option(
                "OTHER",
                "Others",
                R.drawable.ic_chat_more_dots,
            )
        }
        OutcomeSelectionDialog.show(
            context = requireContext(),
            title = "What happened with the client?",
            subtitle = "Choose an outcome to continue.",
            options = options,
            onSelect = { selected ->
                if (selected == "OTHER") promptOtherCpRemarks() else onPrimary()
            },
        )
    }

    private fun promptOtherCpRemarks() {
        if (!cpTypeSupportsOtherOutcome(cpType)) return
        setFragmentResultListener(OutcomeRemarksBottomSheet.RESULT_KEY) { _, bundle ->
            isOpeningOutcomeSheet = false
            val submitted = bundle.getBoolean(OutcomeRemarksBottomSheet.KEY_SUBMITTED, false)
            if (!submitted) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                return@setFragmentResultListener
            }
            val remarks = bundle.getString(OutcomeRemarksBottomSheet.KEY_REMARKS).orEmpty().trim()
            completeOtherCpVisit(remarks)
        }
        OutcomeRemarksBottomSheet.newInstance(
            title = "Other outcome",
            subtitle = "Add remarks to explain why this visit is being closed.",
            hint = "What happened with the client?",
        ).showOnce(parentFragmentManager, "other_cp_outcome_remarks")
    }

    private fun completeOtherCpVisit(remarks: String) {
        val cpId = cpVisitId ?: return
        if (!cpTypeSupportsOtherOutcome(cpType) || remarks.isBlank()) return
        swipeArrived?.lockAsBusy("Completing visit…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = true,
                        clientNoShowReason = null,
                    ),
                )
                if (!metResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        metResp.error ?: "Failed to mark client met",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SetOutcomeRequest(
                        id = cpId,
                        outcome = "other",
                        notes = remarks,
                        arrivalPhotoStorageId = pendingArrivalStorageId,
                    ),
                )
                if (!outcomeResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        outcomeResp.error ?: "Failed to close visit",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pendingCpRevisit = outcomeResp.revisit
                cpClientMet = true
                cpOutcome = "other"
                cpOutcomeNotes = remarks
                cpVisitDecisionCaptured = true
                finalizeCompleteVisit()
            } catch (e: Exception) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    httpErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Finalises an Old Client CP after remarks are captured. Same
     *  shape as completeGiftDistributionMet(), just with a different
     *  outcome literal + the remarks as notes. */
    private fun completeOldClientVisit(cpId: String, remarks: String) {
        swipeArrived?.lockAsBusy("Completing visit…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = true,
                        clientNoShowReason = null,
                    ),
                )
                if (!metResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        metResp.error ?: "Failed to mark client met",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SetOutcomeRequest(
                        id = cpId,
                        outcome = "old_client_visited",
                        notes = remarks,
                    ),
                )
                if (!outcomeResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        outcomeResp.error ?: "Failed to set outcome",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pendingCpRevisit = outcomeResp.revisit
                cpClientMet = true
                cpOutcome = "old_client_visited"
                cpOutcomeNotes = remarks
                cpVisitDecisionCaptured = true
                finalizeCompleteVisit()
            } catch (e: Exception) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    httpErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Yes-path entry for Collection CPs. Looks up the client's
     *  confirmed bookings by mobile, opens the Payment Entry sheet,
     *  and on Submit writes a customerCollections row + closes the
     *  visit with outcome="collection_done". Cancel resets the swipe
     *  so the user can retry. */
    private fun promptCollectionPayment() {
        val cpId = cpVisitId ?: run {
            finalizeCompleteVisit()
            return
        }
        val mobile = clientMobile?.filter { it.isDigit() }?.takeLast(10).orEmpty()
        if (mobile.length != 10) {
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            Toast.makeText(
                requireContext(),
                "Client mobile is missing — re-open this visit from the CP list.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        swipeArrived?.lockAsBusy("Loading bookings…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.getPostSaleCasesByMobile(session.bearerToken, mobile)
                if (!resp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to load bookings",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                if (resp.cases.isEmpty()) {
                    // No bookings → the visit was created against a
                    // client who isn't eligible for Collection CP (the
                    // web gate should have blocked this; legacy /
                    // pre-gate visits can still slip through). Don't
                    // reset the swipe to leave the user in limbo where
                    // they'd fall back into the booking outcome sheet —
                    // auto-close the visit as "rejected" with a clear
                    // reason so the trip ends cleanly. Photo + OTP have
                    // already been captured and stand as proof of the
                    // attempt.
                    Toast.makeText(
                        requireContext(),
                        "No confirmed bookings for this client — closing visit as not eligible.",
                        Toast.LENGTH_LONG,
                    ).show()
                    closeCollectionCpAsNotEligible(cpId)
                    return@launch
                }
                setFragmentResultListener(CollectionPaymentEntryBottomSheet.RESULT_KEY) { _, bundle ->
                    isOpeningOutcomeSheet = false
                    // "Nothing collected" — close as Not Collected (₹0), no
                    // customerCollections row, optional remarks.
                    val notCollected = bundle.getBoolean(
                        CollectionPaymentEntryBottomSheet.KEY_NOT_COLLECTED,
                        false,
                    )
                    if (notCollected) {
                        completeNotCollectedVisit(cpId, bundle)
                        return@setFragmentResultListener
                    }
                    val submitted = bundle.getBoolean(
                        CollectionPaymentEntryBottomSheet.KEY_SUBMITTED,
                        false,
                    )
                    if (!submitted) {
                        swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                        Toast.makeText(
                            requireContext(),
                            "Add the collection details to close this visit.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@setFragmentResultListener
                    }
                    completeCollectionVisit(cpId, bundle)
                }
                // parentFragmentManager so the submit result reaches the
                // setFragmentResultListener registered above (same reason as
                // the old-client remarks sheet) — otherwise the collection
                // visit never completes after Submit.
                CollectionPaymentEntryBottomSheet.newInstance(resp.cases)
                    .showOnce(parentFragmentManager, "collection_payment_entry")
            } catch (e: Exception) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    httpErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Collection CPs branch before the payment form so a zero-collection visit
     * does not fetch bookings or expose irrelevant payment fields.
     */
    private fun promptCollectionDecision() {
        if (isOpeningOutcomeSheet) return
        val cpId = cpVisitId ?: run {
            finalizeCompleteVisit()
            return
        }
        isOpeningOutcomeSheet = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Collection done?")
            .setMessage("Choose Yes only when an amount was collected from the client.")
            .setNegativeButton("No") { _, _ -> showNotCollectedDialog(cpId) }
            .setPositiveButton("Yes") { _, _ ->
                isOpeningOutcomeSheet = false
                promptCollectionPayment()
            }
            .setOnCancelListener {
                isOpeningOutcomeSheet = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            }
            .show()
    }

    /** No-path form: follow-up date is required; reason is optional. */
    private fun showNotCollectedDialog(cpId: String) {
        val content = layoutInflater.inflate(R.layout.dialog_collection_not_collected, null)
        val dateInput = content.findViewById<TextInputEditText>(R.id.etCollectionFollowUpDate)
        val reasonInput = content.findViewById<TextInputEditText>(R.id.etCollectionNotCollectedReason)
        val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        dateInput.setText(formatter.format(calendar.time))
        dateInput.setOnClickListener {
            val picker = android.app.DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    calendar.set(year, month, day, 0, 0, 0)
                    dateInput.setText(formatter.format(calendar.time))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            )
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            picker.datePicker.minDate = today.timeInMillis
            picker.datePicker.maxDate = today.timeInMillis + 5L * 24 * 60 * 60 * 1000
            picker.show()
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Collection not done")
            .setMessage("Choose the follow-up date. This visit will close as Not Collected with amount ₹0.")
            .setView(content)
            .setNegativeButton("Back") { _, _ ->
                isOpeningOutcomeSheet = false
                promptCollectionDecision()
            }
            .setPositiveButton("Close visit", null)
            .setOnCancelListener {
                isOpeningOutcomeSheet = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val followUpDate = dateInput.text?.toString()?.trim().orEmpty()
                if (followUpDate.isBlank()) {
                    dateInput.error = "Follow-up date is required"
                    return@setOnClickListener
                }
                val result = Bundle().apply {
                    putBoolean(CollectionPaymentEntryBottomSheet.KEY_NOT_COLLECTED, true)
                    putString(CollectionPaymentEntryBottomSheet.KEY_NOTES, reasonInput.text?.toString()?.trim().orEmpty())
                    putString(CollectionPaymentEntryBottomSheet.KEY_FOLLOWUP_DATE, followUpDate)
                }
                dialog.dismiss()
                isOpeningOutcomeSheet = false
                completeNotCollectedVisit(cpId, result)
            }
        }
        dialog.show()
    }

    /** Auto-closes a Collection CP visit when no confirmed booking
     *  exists for the client. Marks the client as met (photo + OTP
     *  were already captured upstream) and stamps outcome="rejected"
     *  with a clear reason so the trip ends terminally — without
     *  this, the post-arrival CTA falls back to "Complete CP
     *  details" → booking-outcome sheet, which is unrelated to
     *  Collection CP. The web type-pick gate prevents this for new
     *  visits; this path covers visits created before the gate
     *  landed or via paths that skip it. */
    private fun closeCollectionCpAsNotEligible(cpId: String) {
        swipeArrived?.lockAsBusy("Closing visit…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = true,
                        clientNoShowReason = null,
                    ),
                )
                if (!metResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        metResp.error ?: "Failed to mark client met",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SetOutcomeRequest(
                        id = cpId,
                        outcome = "rejected",
                        notes = "Collection CP not eligible — client has no confirmed booking",
                    ),
                )
                if (!outcomeResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        outcomeResp.error ?: "Failed to close visit",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pendingCpRevisit = outcomeResp.revisit
                cpClientMet = true
                cpOutcome = "rejected"
                cpOutcomeNotes = "Collection CP not eligible — client has no confirmed booking"
                cpVisitDecisionCaptured = true
                finalizeCompleteVisit()
            } catch (e: Exception) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    httpErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Finalises a Collection CP after the Payment Entry sheet
     *  submits. Posts to /api/postsales/collections/submit (writes a
     *  customerCollections row in pending_accounts), then closes the
     *  CP visit with outcome="collection_done" and the money summary
     *  as notes so the web detail page surfaces it next to the badge. */
    private fun completeCollectionVisit(cpId: String, bundle: Bundle) {
        val caseId = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_CASE_ID).orEmpty()
        val bookingRef = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_BOOKING_REF).orEmpty()
        val amount = bundle.getDouble(CollectionPaymentEntryBottomSheet.KEY_AMOUNT, 0.0)
        val mode = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_PAYMENT_MODE).orEmpty()
        val modeLabel = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_PAYMENT_MODE_LABEL).orEmpty()
        val reference = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_TRANSACTION_REF).orEmpty()
        val bankName = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_BANK_NAME).orEmpty()
        val branchName = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_BRANCH_NAME).orEmpty()
        val instrumentDate = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_INSTRUMENT_DATE).orEmpty()
        val notes = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_NOTES).orEmpty()
        val proofStorageId = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_PROOF_STORAGE_ID).orEmpty()
        val proofFileName = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_PROOF_FILE_NAME).orEmpty()
        // Present only for a PARTIAL collection — spawns a follow-up collection CP.
        val followUpDate = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_FOLLOWUP_DATE)
            ?.takeIf { it.isNotBlank() }
        val followUpTime = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_FOLLOWUP_TIME)
            ?.takeIf { it.isNotBlank() }

        if (caseId.isBlank() || amount <= 0 || mode.isBlank()) {
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            Toast.makeText(requireContext(), "Collection details incomplete", Toast.LENGTH_SHORT).show()
            return
        }

        swipeArrived?.lockAsBusy("Submitting collection…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val submitResp = geoApi.submitCustomerCollection(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SubmitCollectionRequest(
                        cpVisitId = cpId,
                        caseId = caseId,
                        amount = amount,
                        paymentMode = mode,
                        transactionReference = reference.takeIf { it.isNotBlank() },
                        bankName = bankName.takeIf { it.isNotBlank() },
                        branchName = branchName.takeIf { it.isNotBlank() },
                        paymentInstrumentDate = instrumentDate.takeIf { it.isNotBlank() },
                        proofStorageId = proofStorageId.takeIf { it.isNotBlank() },
                        proofFileName = proofFileName.takeIf { it.isNotBlank() },
                        notes = notes.takeIf { it.isNotBlank() },
                    ),
                )
                if (!submitResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        submitResp.error ?: "Failed to submit collection",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = true,
                        clientNoShowReason = null,
                    ),
                )
                if (!metResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        metResp.error ?: "Failed to mark client met",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val summary = buildCollectionSummary(
                    bookingRef = bookingRef,
                    amount = amount,
                    modeLabel = modeLabel.ifBlank { mode.uppercase() },
                    reference = reference,
                    refNo = submitResp.collectionRefNo,
                    notes = notes,
                )
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SetOutcomeRequest(
                        id = cpId,
                        outcome = "collection_done",
                        notes = summary,
                        // Present only for a partial collection — the backend
                        // spawns a follow-up collection CP for the pending amount.
                        followUpDate = followUpDate,
                        followUpTime = followUpTime,
                    ),
                )
                if (!outcomeResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        outcomeResp.error ?: "Failed to set outcome",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pendingCpRevisit = outcomeResp.revisit
                cpClientMet = true
                cpOutcome = "collection_done"
                cpOutcomeNotes = notes
                cpVisitDecisionCaptured = true
                Toast.makeText(
                    requireContext(),
                    "Collection submitted for Accounts review",
                    Toast.LENGTH_SHORT,
                ).show()
                finalizeCompleteVisit()
            } catch (e: Exception) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    httpErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Closes a Collection CP visit when the client was met but nothing
     *  was collected. No customerCollections row is written; the CP visit
     *  is stamped outcome="not_collected" (₹0) with the optional remarks,
     *  so the web shows a "Not Collected" badge. */
    private fun completeNotCollectedVisit(cpId: String, bundle: Bundle) {
        val notes = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_NOTES).orEmpty()
        val followUpDate = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_FOLLOWUP_DATE)
            ?.takeIf { it.isNotBlank() }
        val followUpTime = bundle.getString(CollectionPaymentEntryBottomSheet.KEY_FOLLOWUP_TIME)
            ?.takeIf { it.isNotBlank() }
        val summary =
            if (notes.isNotBlank()) "Not collected — $notes" else "Not collected"

        swipeArrived?.lockAsBusy("Closing visit…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = true,
                        clientNoShowReason = null,
                    ),
                )
                if (!metResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        metResp.error ?: "Failed to mark client met",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SetOutcomeRequest(
                        id = cpId,
                        outcome = "not_collected",
                        notes = summary,
                        // The backend spawns the next collection CP for this slot.
                        followUpDate = followUpDate,
                        followUpTime = followUpTime,
                    ),
                )
                if (!outcomeResp.success) {
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        outcomeResp.error ?: "Failed to close visit",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pendingCpRevisit = outcomeResp.revisit
                cpClientMet = true
                cpOutcome = "not_collected"
                cpOutcomeNotes = notes
                cpFollowUpDate = followUpDate
                cpFollowUpTime = followUpTime
                cpVisitDecisionCaptured = true
                Toast.makeText(
                    requireContext(),
                    "Visit closed — nothing collected",
                    Toast.LENGTH_SHORT,
                ).show()
                finalizeCompleteVisit()
            } catch (e: Exception) {
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    httpErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun buildCollectionSummary(
        bookingRef: String,
        amount: Double,
        modeLabel: String,
        reference: String,
        refNo: String?,
        notes: String,
    ): String {
        val amountStr = "₹" + "%,.0f".format(amount)
        val parts = mutableListOf<String>()
        parts += "$amountStr collected"
        if (modeLabel.isNotBlank()) parts += "via $modeLabel"
        if (bookingRef.isNotBlank()) parts += "for $bookingRef"
        if (reference.isNotBlank()) parts += "ref $reference"
        if (!refNo.isNullOrBlank()) parts += "($refNo)"
        val base = parts.joinToString(" · ")
        return if (notes.isNotBlank()) "$base — $notes" else base
    }

    /** Yes-path completion for Gift Distribution CPs.
     *
     *  Flow after the OTP-only refactor: we DON'T auto-finalise here.
     *  Instead we launch the camera so the staff can capture proof of
     *  the gift handover — the photo is of the actual gift being given
     *  to the (now OTP-verified) client. The camera result handler at
     *  the top of the file routes the captured file into
     *  [uploadGiftDistributionPhotoThenComplete], which uploads it,
     *  attaches the storage id to the visit, stamps the outcome, and
     *  finalises.
     *
     *  If the user cancels the camera, the visit stays in the
     *  arrival-verified state and the "Confirm Gift Distribution"
     *  button on the trip nav reopens this same flow so they can
     *  retry. */
    private fun completeGiftDistributionMet() {
        if (cpVisitId == null) {
            finalizeCompleteVisit()
            return
        }
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            isGiftDistributionPostOtpPhotoCapture = true
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }
        isGiftDistributionPostOtpPhotoCapture = true
        swipeArrived?.lockAsBusy("Opening camera…")
        launchArrivalCamera()
    }

    /** Camera-result handler for the post-OTP gift handover photo.
     *  Uploads the photo to convex storage, attaches the storage id
     *  to the field-visit row (via finalizeCompleteVisit's existing
     *  pendingArrivalStorageId plumbing), then closes the CP visit
     *  with outcome="gift_distributed". */
    private fun uploadGiftDistributionPhotoThenComplete(photoFile: java.io.File) {
        val cpId = cpVisitId ?: run {
            isGiftDistributionPostOtpPhotoCapture = false
            finalizeCompleteVisit()
            return
        }
        swipeArrived?.lockAsBusy("Optimizing and uploading photo…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val upload = uploadArrivalPhoto(photoFile)
                val storageId = upload.storageId
                if (storageId == null) {
                    isGiftDistributionPostOtpPhotoCapture = false
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        upload.errorMessage
                            ?: "Photo upload failed. Tap Confirm Gift Distribution to retry.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pendingArrivalStorageId = storageId
                discardUploadedArrivalPhoto(photoFile)

                swipeArrived?.lockAsBusy("Completing visit…")
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = true,
                        clientNoShowReason = null,
                    ),
                )
                if (!metResp.success) {
                    isGiftDistributionPostOtpPhotoCapture = false
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        metResp.error ?: "Failed to mark client met",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.SetOutcomeRequest(
                        id = cpId,
                        outcome = "gift_distributed",
                        notes = "Gift distributed — handover photo attached",
                        // Hand the just-uploaded handover photo to setOutcome so the
                        // backend attaches it before its arrival-photo proof check;
                        // otherwise this completes with a 500 (photo not linked yet).
                        arrivalPhotoStorageId = pendingArrivalStorageId,
                    ),
                )
                if (!outcomeResp.success) {
                    isGiftDistributionPostOtpPhotoCapture = false
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(
                        requireContext(),
                        outcomeResp.error ?: "Failed to set outcome",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                pendingCpRevisit = outcomeResp.revisit
                cpClientMet = true
                cpOutcome = "gift_distributed"
                cpOutcomeNotes = "Gift distributed — handover photo attached"
                cpVisitDecisionCaptured = true
                isGiftDistributionPostOtpPhotoCapture = false
                finalizeCompleteVisit()
            } catch (e: Exception) {
                isGiftDistributionPostOtpPhotoCapture = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                // Show the backend's real {error:"..."} instead of a bare "HTTP 500".
                val msg = httpErrorMessage(e) ?: e.message ?: "Network error"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Pull the backend {error:"..."} out of a Retrofit 5xx body (else null). */
    private fun httpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }
            .getOrNull() ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun renderArrivalPhase(alreadyArrived: Boolean) {
        if (alreadyArrived) arrivalConfirmedForProgress = true

        if (session.isDriverMode) {
            if (alreadyArrived) {
                // A reopened screen has to recover the return-pickup state,
                // otherwise the driver would be asked to mark it twice.
                if (!pickedFromSiteConfirmed) {
                    pickedFromSiteConfirmed = visitId
                        ?.let { session.getDriverTrip(it)?.status }
                        ?.equals("picked_from_site", ignoreCase = true) == true
                }
                swipeArrived?.visibility = View.GONE
                btnCompleteCpDetails?.visibility = View.VISIBLE
                if (pickedFromSiteConfirmed) {
                    btnCompleteCpDetails?.text = "End Trip"
                    btnCompleteCpDetails?.setOnClickListener {
                        DriverEndTripBottomSheet.newInstance(visitId!!)
                            .showOnce(parentFragmentManager, "driver_end_trip")
                    }
                } else {
                    btnCompleteCpDetails?.text = "Picked from Site"
                    btnCompleteCpDetails?.setOnClickListener { markDriverPickedFromSite() }
                }
            } else {
                btnCompleteCpDetails?.visibility = View.GONE
                swipeArrived?.visibility = View.VISIBLE
                swipeArrived?.reset(newLabel = "Swipe if Onsite Reached")
            }
            return
        }

        if (renderJointWorkflowActions(alreadyArrived)) return

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

        if (shouldFillCpDetails) {
            arrivalInProgress = false
            applyStatusPill("Reaching")
            swipeArrived?.visibility = View.GONE
            btnCompleteCpDetails?.visibility = View.VISIBLE
            // Per-cpType button label — the user has different flows
            // for each branch (Payment Entry for collection, remarks
            // popup for old client, no form for gift distribution),
            // so the button name + click handler should match. Without
            // this, every CP showed "Complete CP details" and tapping
            // it opened the default booking-outcome sheet — wrong for
            // the three special types.
            btnCompleteCpDetails?.text = when {
                cpIsSvFixed -> "Complete SV details"
                isCollectionCp -> "Complete Collection"
                isOldClient -> "Add Visit Remarks"
                isGiftDistribution -> "Confirm Gift Distribution"
                else -> "Complete CP details"
            }
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

    /** Dispatcher for the "Complete CP details" button. Routes each
     *  cpType to its dedicated flow instead of the default booking
     *  outcome sheet — without this branching, closing the Payment
     *  Entry sheet (or any of the special-type sheets) and tapping
     *  the CTA again opens the wrong form.
     *
     *  Debounced via `isOpeningOutcomeSheet`: bottom-sheet show() is
     *  async on Android, so a confused user can tap the button twice
     *  before the first sheet visibly renders and stack two copies.
     *  The flag short-circuits subsequent taps; it auto-resets after
     *  outcomeSheetGuardResetDelayMs so a silent open-failure doesn't
     *  permanently brick the button. */
    private fun onCompleteCpDetailsClicked() {
        val workflow = jointWorkflow
        if (isJointCpWorkflow() && workflow != null) {
            if (workflow.actorRole == "reviewer" && workflow.canReview) {
                showCpCompletionSheet()
            } else if (workflow.actorRole == "outcome_owner" && workflow.canSubmitOutcome) {
                showCpCompletionSheet()
            } else {
                Toast.makeText(
                    requireContext(),
                    jointWaitingMessage(workflow),
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        // Collection owns its own two-stage dialog guard. Route it before the
        // generic sheet guard so repeated taps cannot stack decision dialogs.
        if (!cpIsSvFixed && isCollectionCp && cpVisitId != null) {
            promptCollectionDecision()
            return
        }
        if (isOpeningOutcomeSheet) return
        isOpeningOutcomeSheet = true
        btnCompleteCpDetails?.postDelayed(
            { isOpeningOutcomeSheet = false },
            outcomeSheetGuardResetDelayMs,
        )
        val cpId = cpVisitId
        when {
            cpId == null -> showCpCompletionSheet()
            // SV-fixed CPs still go through the outcome sheet (in
            // locked SV mode), so leave that path alone.
            cpIsSvFixed -> showCpCompletionSheet()
            isCollectionCp -> promptCollectionDecision()
            isOldClient -> promptOldClientOutcome()
            isGiftDistribution -> promptGiftDistributionOutcome()
            else -> showCpCompletionSheet()
        }
    }

    private fun showCpCompletionSheet() {
        val cpId = cpVisitId ?: return
        // Belt-and-braces guard — any caller that bypassed the
        // onCompleteCpDetailsClicked dispatcher (older code paths,
        // reconcile races, async result listeners) STILL routes to
        // the dedicated sheet for our three special CP types. The
        // booking-outcome sheet (Booking / SV / Postpone / Not
        // Interested) is wrong UI for these flows.
        if (!cpIsSvFixed) {
            when {
                isCollectionCp -> { promptCollectionDecision(); return }
                isOldClient -> { promptOldClientOutcome(); return }
                isGiftDistribution -> { promptGiftDistributionOutcome(); return }
            }
        }
        arrivalConfirmedForProgress = true
        applyStatusPill("Reaching")
        // Treat the row as SV-fix when EITHER signal fires:
        //   - cpIsSvFixed (set by the async reconcile when it completes)
        //   - visitCategory == "sv_cum_cp" (handed down synchronously
        //     from the caller — Home or CP list — which already
        //     classified this row via the same payload signals)
        // The visitCategory check fixes the visible flicker where users
        // tap Complete-Outcome before reconcile finishes, briefly see
        // the Booking tab body, then watch it snap to locked SV. With
        // the synchronous hint the very first paint is already locked.
        val visitCategory = arguments?.getString(ARG_VISIT_CATEGORY)
        val svFix = cpIsSvFixed || visitCategory == "sv_cum_cp"
        CompleteCpVisitBottomSheet
            .newInstance(
                cpVisitId = cpId,
                cpClientMet = cpClientMet,
                cpOutcome = cpOutcome,
                isSvFixedHint = svFix,
                cpType = cpType,
                jointCtaMode = when (jointWorkflow?.actorRole) {
                    "outcome_owner" -> "send_review"
                    "reviewer" -> "complete_review"
                    else -> null
                },
                jointOutcomeSummary = jointWorkflow?.outcomeSummary,
            )
            .showOnce(parentFragmentManager, "cp_visit_complete")
    }

    /**
     * Pure-SV outcome flow. Opens [CompleteCpVisitBottomSheet] in
     * SV-mode (Site Visit tab disabled, Booking / Postpone /
     * Not Interested all persisting to the
     * /api/marketing/siteVisits/setOutcome endpoint).
     * Triggered by the "Complete Outcome" CTA that replaces "Start
     * Trip" on pure-SV trip detail screens.
     */
    private fun openSiteVisitOutcomeSheet() {
        val svId = arguments?.getString(ARG_VISIT_ID)
        if (svId.isNullOrBlank()) {
            android.widget.Toast.makeText(
                requireContext(),
                "Site visit id is missing",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        CompleteCpVisitBottomSheet
            .forSiteVisit(svId)
            .showOnce(parentFragmentManager, "sv_outcome")
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
        isGiftDistributionPostOtpPhotoCapture = false
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

    private fun showClientNotMetProofReview(photoFile: File) {
        swipeArrived?.lockAsBusy("Review captured photo")
        CpClientNotMetProofBottomSheet
            .newInstance(photoFile.absolutePath)
            .showOnce(parentFragmentManager, "cp_client_not_met_proof")
    }

    private fun resetClientNotMetCapture(message: String? = null) {
        arrivalInProgress = false
        cpNoPathPhotoCapture = false
        swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
        message?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show()
        }
    }

    private fun uploadArrivalPhotoThenCompleteWithoutClient(
        photoFile: File,
        optionalRemark: String?,
    ) {
        swipeArrived?.lockAsBusy("Optimizing and uploading photo…")
        viewLifecycleOwner.lifecycleScope.launch {
            val upload = uploadArrivalPhoto(photoFile)
            val storageId = upload.storageId
            if (storageId == null) {
                arrivalInProgress = false
                cpNoPathPhotoCapture = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    upload.errorMessage ?: "Photo upload failed. Try again.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            pendingArrivalStorageId = storageId
            discardUploadedArrivalPhoto(photoFile)
            completeCpVisitWithoutClient(optionalRemark)
        }
    }

    private fun completeCpVisitWithoutClient(optionalRemark: String?) {
        val cpId = cpVisitId ?: run {
            arrivalInProgress = false
            swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
            return
        }
        swipeArrived?.lockAsBusy("Completing visit...")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Gift Distribution + Old Client both close with their
                // own terminal outcome on the No path so dashboards can
                // separate "didn't deliver gift" / "didn't find old
                // client" from the generic "Client not seen — needs HR
                // review" bucket that everything else falls into. Photo
                // is the sole proof for either flow — no OTP.
                val noShowReason = when {
                    isGiftDistribution -> "Gift distribution — client not present"
                    isOldClient -> "Old client visit — client not present"
                    isCollectionCp -> "Collection visit — client not present"
                    else -> "Client not seen"
                }
                val terminalOutcome = when {
                    isGiftDistribution -> "gift_distributed"
                    isOldClient -> "old_client_visited"
                    isCollectionCp -> "not_collected"
                    else -> "other"
                }
                val completionNotes = optionalRemark
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "$noShowReason. Staff remark: $it" }
                    ?: noShowReason

                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.MarkClientMetRequest(
                        id = cpId,
                        clientMet = false,
                        clientNoShowReason = noShowReason,
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
                        outcome = terminalOutcome,
                        notes = completionNotes,
                        // Not-met completions have NO arrival OTP (the client
                        // wasn't there) — the proof photo is the sole evidence.
                        // Pass it so the backend attaches it to the field visit
                        // before the completion-proof check (which, for a
                        // client-not-met visit, requires only the photo).
                        arrivalPhotoStorageId = pendingArrivalStorageId,
                    )
                )
                if (!outcomeResp.success) {
                    arrivalInProgress = false
                    cpNoPathPhotoCapture = false
                    swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                    Toast.makeText(requireContext(), outcomeResp.error ?: "Failed to set outcome", Toast.LENGTH_LONG).show()
                    return@launch
                }
                pendingCpRevisit = outcomeResp.revisit
                cpClientMet = false
                cpOutcome = terminalOutcome
                cpOutcomeNotes = completionNotes
                cpVisitDecisionCaptured = true
                cpNoPathPhotoCapture = false
                if (isJointCpWorkflow()) {
                    arrivalInProgress = false
                    submitJointCpForReview()
                    return@launch
                }
                showClientNotSeenCompletion = true
                finalizeCompleteVisit()
            } catch (e: Exception) {
                arrivalInProgress = false
                cpNoPathPhotoCapture = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    serverErrorMessage(e) ?: e.message ?: "Network error",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Pull the server's `error`/`message` out of a non-2xx response body so the
     * user sees the real reason (e.g. "A photo proof of the visit must be
     * uploaded…") instead of a bare "HTTP 500". Returns null for non-HTTP
     * errors, so callers fall back to e.message.
     */
    /** Opens the phone dialer pre-filled with the client's number (ACTION_DIAL,
     *  so no CALL_PHONE permission is needed and the staff confirms the call). */
    private fun dialPhone(phone: String) {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_DIAL,
            android.net.Uri.parse("tel:$phone"),
        )
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), "No dialer app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun serverErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun finalizeCompleteVisit() {
        val id = visitId ?: return
        // The trip is being finished — drop the local "started" bridge so a
        // future re-open reflects the real (completed) state, not enroute.
        clearVisitLocallyStarted()
        val storageId = pendingArrivalStorageId
        swipeArrived?.lockAsBusy("Completing visit…")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val loc = fetchCurrentLocation()
                // Photo + OTP are tracked in dedicated columns
                // (arrivalPhotoStorageId, arrivalVerifiedAt). `remarks` stays
                // human-readable so it can carry future free-text notes
                // without us having to parse it again.
                val completion = geoApi.completeVisit(
                    session.bearerToken,
                    CompleteVisitRequest(
                        visitId = id,
                        lat = loc?.latitude,
                        lng = loc?.longitude,
                        remarks = "Arrival verified",
                        arrivalPhotoStorageId = storageId,
                        clientMet = cpClientMet.takeIf { !cpVisitId.isNullOrBlank() },
                        outcome = cpOutcome?.takeIf { !cpVisitId.isNullOrBlank() },
                        cpOutcomeNotes = cpOutcomeNotes?.takeIf { !cpVisitId.isNullOrBlank() },
                        postponeReasons = cpPostponeReasons?.takeIf { !cpVisitId.isNullOrBlank() },
                        followUpDate = cpFollowUpDate?.takeIf { !cpVisitId.isNullOrBlank() },
                        followUpTime = cpFollowUpTime?.takeIf { !cpVisitId.isNullOrBlank() },
                    ),
                )
                check(completion.success) {
                    completion.error ?: "Visit completion was rejected"
                }
                val isCpBackedVisit = !cpVisitId.isNullOrBlank()
                val cpStatus = completion.status?.trim()?.lowercase(Locale.US)
                if (isCpBackedVisit && !cpOutcome.isNullOrBlank()) {
                    check(cpStatus in setOf("completed", "pending_gm_approval", "postponed", "cancelled", "canceled")) {
                        "The trip was saved, but the CP outcome state was not confirmed. Refresh before retrying."
                    }
                }
                applyStatusPill(if (cpStatus == "pending_gm_approval") "Pending Approval" else "Complete")
                // Completion is already committed. A dashboard refresh failure
                // must not tell staff the visit failed and tempt a duplicate.
                runCatching {
                    val bootstrap = geoApi
                        .getTrackingBootstrap(session.bearerToken, session.trackingDeviceId)
                        .data
                    applyTrackingBootstrap(
                        bootstrap,
                        attendanceActive = runCatching {
                            AttendanceTrackingGate.isClockedInForToday(
                                session.bearerToken,
                                api,
                            )
                        }.getOrDefault(false),
                    )
                }.onFailure {
                    android.util.Log.w("TripNav", "Post-completion refresh failed", it)
                }
                val revisit = pendingCpRevisit
                if (revisit != null) {
                    showClientNotSeenCompletion = false
                    CpRevisitConfirmation.show(this@TripNavigationFragment, revisit) {
                        pendingCpRevisit = null
                        navigateUp()
                    }
                } else if (showClientNotSeenCompletion) {
                    showClientNotSeenCompletion = false
                    CpTripCompletedBottomSheet().showOnce(parentFragmentManager, "cp_trip_completed")
                } else {
                    val message = if (cpStatus == "pending_gm_approval") {
                        "Outcome saved and waiting for GM approval"
                    } else {
                        "Visit completed"
                    }
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    navigateUp()
                }
            } catch (e: Exception) {
                arrivalInProgress = false
                swipeArrived?.reset(newLabel = "Swipe to Complete Trip")
                Toast.makeText(
                    requireContext(),
                    "Failed to complete: ${serverErrorMessage(e) ?: e.message}",
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

    private fun discardUploadedArrivalPhoto(file: File) {
        runCatching { file.delete() }
        if (pendingArrivalPhoto?.absolutePath == file.absolutePath) {
            pendingArrivalPhoto = null
            pendingArrivalPhotoUri = null
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
        // Client geofence used for the out-of-range completion warning. Matches
        // the backend default (CP_GEOFENCE_DEFAULT_RADIUS_M) so the warning
        // fires for the same completions the server holds for GM approval.
        private const val GEOFENCE_APPROVAL_RADIUS_METERS = 300.0
        private const val JOINT_WORKFLOW_POLL_MS = 5_000L
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
        // CP Type (sv_cum_cp / follow_up / booking_cp / collection_cp /
        // old_client / gift_distribution). Drives the post-arrival
        // branch: gift_distribution finalises straight from photo+OTP
        // without the booking-outcome sheet.
        private const val ARG_CP_TYPE = "arg_cp_type"
        // Mobile of the CP's client (= TodayVisit.leadPhone). Threaded
        // through so the Collection CP Yes path can look up confirmed
        // bookings via /api/postsales/cases/byMobile without going
        // back to a prior creation step.
        private const val ARG_CLIENT_MOBILE = "arg_client_mobile"
        private const val ARG_LMO_NAME = "arg_lmo_name"
        private const val ARG_FIELD_STAFF_NAME = "arg_field_staff_name"
        private const val ARG_DEADLINE = "arg_deadline"

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
            cpType: String? = null,
            clientMobile: String? = null,
            lmoName: String? = null,
            /** Who the visit is assigned to — shown so a manager viewing
             *  someone else's trip can tell whose it is. */
            fieldStaffName: String? = null,
            deadline: String? = null,
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
                if (cpType != null) putString(ARG_CP_TYPE, cpType)
                if (clientMobile != null) putString(ARG_CLIENT_MOBILE, clientMobile)
                if (lmoName != null) putString(ARG_LMO_NAME, lmoName)
                if (fieldStaffName != null) putString(ARG_FIELD_STAFF_NAME, fieldStaffName)
                if (deadline != null) putString(ARG_DEADLINE, deadline)
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
