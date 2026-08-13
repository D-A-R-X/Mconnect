package com.manjugroups.m_connect.ui.library.frontdesk

import android.Manifest
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.ui.common.pushDetail
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentQrScannerBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CheckinInvitationRequest
import com.manjugroups.m_connect.network.CheckoutInvitationRequest
import com.manjugroups.m_connect.network.InvitationDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SiteVisitIdRequest
import com.manjugroups.m_connect.network.SiteVisitQrScanRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import com.manjugroups.m_connect.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.manjugroups.m_connect.ui.common.commitOnce
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.ui.marketing.SiteVisitCounsellingConfirmBottomSheet
import com.manjugroups.m_connect.ui.marketing.SiteVisitOverviewFragment


class QrScannerFragment : Fragment() {

    private var _binding: FragmentQrScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isScanningActive = true
    private var laserAnimator: ObjectAnimator? = null
    private val barcodeScanner: BarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    private var statusBarHeight = 0
    private var siteVisitScanJob: Job? = null

    private val api by lazy { ApiService.create() }
    private val geoTrackApi by lazy { GeoTrackApi.create() }
    private val session by lazy { SessionManager(requireContext()) }
    // Set when a real invitation is loaded; needed for the Confirm -> check-in call.
    private var currentInvitationId: String? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(
                requireContext(),
                "Camera permission is required to scan QR codes.",
                Toast.LENGTH_SHORT
            ).show()
            parentFragmentManager.popBackStack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQrScannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setFragmentResultListener(SiteVisitCounsellingConfirmBottomSheet.RESULT_KEY) { _, result ->
            val siteVisitId =
                result.getString(SiteVisitCounsellingConfirmBottomSheet.KEY_SITE_VISIT_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@setFragmentResultListener
            markSiteVisitOnCounselling(
                siteVisitId = siteVisitId,
                leadTemperature =
                    result.getString(SiteVisitCounsellingConfirmBottomSheet.KEY_LEAD_TEMPERATURE),
            )
        }

        // Ongoing visit + authorised viewer chose "Go to outcome page": counselling
        // is already started, so navigate straight to the SV outcome page.
        setFragmentResultListener(
            SiteVisitCounsellingConfirmBottomSheet.RESULT_KEY_OPEN_OUTCOME,
        ) { _, result ->
            val siteVisitId =
                result.getString(SiteVisitCounsellingConfirmBottomSheet.KEY_SITE_VISIT_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: return@setFragmentResultListener
            openSiteVisitOutcome(
                siteVisitId = siteVisitId,
                leadTemperature =
                    result.getString(SiteVisitCounsellingConfirmBottomSheet.KEY_LEAD_TEMPERATURE),
            )
        }

        // The counselling sheet was dismissed without an action (Cancel / swipe
        // / back). Scanning was paused and the camera unbound when the QR was
        // read, so re-arm the scanner for the next SV — without this the second
        // scan stays frozen (reported for superadmin + site incharge).
        setFragmentResultListener(
            SiteVisitCounsellingConfirmBottomSheet.RESULT_KEY_CLOSED,
        ) { _, _ ->
            resumeScanning()
        }

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Scan history is IAM-gated (frontdesk.view). Everyone can scan, but only
        // permitted roles see the history log of check-ins / check-outs.
        binding.btnHistory.visibility = if (canViewHistory) View.VISIBLE else View.GONE
        binding.btnHistory.setOnClickListener {
            parentFragmentManager.pushDetail(QrHistoryFragment())
        }

        binding.btnHistoryVerification.setOnClickListener {
            binding.visitorVerificationContainer.visibility = View.GONE
            binding.laserLine.visibility = View.VISIBLE
            laserAnimator?.resume()
            bindCameraUseCases() // Re-bind to start preview
            isScanningActive = true // Resume scan

            parentFragmentManager.pushDetail(QrHistoryFragment())
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarHeight = sysBars.top
            
            // Scanner top bar spacer
            val lp = binding.statusBarPlaceholder.layoutParams
            lp.height = sysBars.top
            binding.statusBarPlaceholder.layoutParams = lp

            // Verification layout top spacer (static push-down for header)
            val lpVerification = binding.spacerStatusBarVerification.layoutParams
            lpVerification.height = sysBars.top
            binding.spacerStatusBarVerification.layoutParams = lpVerification

            // Add navigation bar bottom padding to absolute floating buttons panel
            val extraBottomPadding = (16 * resources.displayMetrics.density).toInt()
            binding.llBottomButtonsPanel.setPadding(
                binding.llBottomButtonsPanel.paddingLeft,
                binding.llBottomButtonsPanel.paddingTop,
                binding.llBottomButtonsPanel.paddingRight,
                sysBars.bottom + extraBottomPadding
            )

            insets
        }

        // Setup Bottom Sheet Callback to dynamically adjust top spacer height on slide
        val behavior = BottomSheetBehavior.from(binding.verificationBottomSheet)
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    val lp = binding.spacerStatusBarVerificationContent.layoutParams
                    lp.height = 0
                    binding.spacerStatusBarVerificationContent.layoutParams = lp
                } else if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    val lp = binding.spacerStatusBarVerificationContent.layoutParams
                    lp.height = statusBarHeight
                    binding.spacerStatusBarVerificationContent.layoutParams = lp
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // slideOffset goes from 0.0 (collapsed) to 1.0 (expanded)
                if (slideOffset >= 0f) {
                    val lp = binding.spacerStatusBarVerificationContent.layoutParams
                    lp.height = (statusBarHeight * slideOffset).toInt()
                    binding.spacerStatusBarVerificationContent.layoutParams = lp
                }
            }
        })

        startLaserAnimation()
        checkCameraPermissionAndStart()
    }

    private fun checkCameraPermissionAndStart() {
        val hasCamera = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCamera) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startLaserAnimation() {
        val density = resources.displayMetrics.density
        // Scan window height is 260dp. Let's animate within 258dp to avoid passing border
        val travelDistance = 258f * density
        
        laserAnimator = ObjectAnimator.ofFloat(binding.laserLine, "translationY", 0f, travelDistance).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Camera binding failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null && isScanningActive) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue
                        if (rawValue != null) {
                            onQrCodeScanned(rawValue)
                            break
                        }
                    }
                }
                .addOnFailureListener {
                    // Failures can happen on blank frames
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun onQrCodeScanned(value: String) {
        isScanningActive = false
        // Trigger a light haptic feel
        try {
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Exception) {}

        activity?.runOnUiThread {
            // Stop laser line and animator
            laserAnimator?.pause()
            binding.laserLine.visibility = View.GONE
            // Freeze camera feed
            cameraProvider?.unbindAll()

            // A Front Desk invite QR encodes <origin>/frontdesk/invite/<token>.
            // For those we resolve real visitor data from the backend; any other
            // QR falls back to the legacy local/mock parsing.
            val siteVisitId = extractSiteVisitId(value)
            val inviteToken = extractInviteToken(value)
            if (siteVisitId != null) {
                confirmSiteVisitConsulting(siteVisitId)
            } else if (inviteToken != null) {
                fetchInvitation(inviteToken)
            } else {
                showVerificationModal(value)
            }
        }
    }

    /** Pull the invite token out of a scanned `.../frontdesk/invite/<token>` URL. */
    private fun extractInviteToken(value: String): String? {
        val marker = "/frontdesk/invite/"
        val idx = value.indexOf(marker)
        if (idx < 0) return null
        val token = value.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('/')
        return token.ifEmpty { null }
    }

    /** Accept both the current `SV:<id>` payload and the legacy client QR URL. */
    private fun extractSiteVisitId(value: String): String? {
        val raw = value.trim()
        if (raw.startsWith("SV:", ignoreCase = true)) {
            return raw.substringAfter(':').trim().ifEmpty { null }
        }
        val marker = "/site-visit/consulting/"
        val idx = raw.indexOf(marker)
        if (idx < 0) return null
        val id = raw.substring(idx + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trimEnd('/')
        return id.ifEmpty { null }
    }

    private fun confirmSiteVisitConsulting(siteVisitId: String) {
        siteVisitScanJob?.cancel()
        showSiteVisitLoading(true)
        siteVisitScanJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = loadSiteVisitQrWithRetry(siteVisitId)
                val visit = response.visit
                if (!response.success || visit == null) {
                    throw IllegalStateException(response.error ?: "Unable to validate this SV QR")
                }
                if (_binding == null) return@launch
                val projectName = visit.project?.projectName ?: visit.project?.name
                val clientName =
                    visit.lead?.clientName
                        ?: visit.lead?.contactName
                        ?: visit.client?.clientName
                val bdoName = visit.bdoStaff?.name ?: visit.telecallerStaff?.name
                val scheduledAt = listOfNotNull(
                    visit.scheduledDate?.takeIf { it.isNotBlank() },
                    visit.scheduledTime?.takeIf { it.isNotBlank() },
                ).joinToString(" - ")
                val additionalVisitors = visit.attendees.orEmpty()
                    .filterNot {
                        it.name?.trim()?.equals(clientName?.trim(), ignoreCase = true) == true
                    }
                    .mapIndexed { index, attendee ->
                        val identity = attendee.name?.trim().takeUnless { it.isNullOrBlank() }
                            ?: "Visitor ${index + 1}"
                        val details = listOfNotNull(
                            attendee.relation?.trim()?.takeIf { it.isNotBlank() },
                            attendee.age?.trim()?.takeIf { it.isNotBlank() }?.let { "Age $it" },
                        )
                        if (details.isEmpty()) identity else "$identity (${details.joinToString(", ")})"
                    }
                    .ifEmpty {
                        val count = (visit.expectedAttendeeCount ?: 1).minus(1).coerceAtLeast(0)
                        if (count > 0) listOf("$count additional visitor${if (count == 1) "" else "s"}")
                        else emptyList()
                    }
                    .joinToString("\n")
                // Whoever is the assigned Site Incharge / BDO (or a
                // scanConsulting-granted staff / admin) may start counselling
                // AND record the outcome. The scan payload carries each staff
                // `_id`, so authorise client-side too — this works even before
                // the broadened backend flags deploy, and fixes "reached dropped
                // → can't record outcome" (setOutcome accepts on_counselling →
                // picked_from_site → dropped).
                val myStaffId = session.staffId
                val assignedToMe = !myStaffId.isNullOrBlank() && (
                    myStaffId == visit.inchargeStaff?.id ||
                        myStaffId == visit.bdoStaff?.id ||
                        myStaffId == visit.telecallerStaff?.id
                )
                val scanAuthorized = assignedToMe ||
                    session.hasPermission("marketing.siteVisits.scanConsulting")
                val statusLc = visit.status?.trim()?.lowercase().orEmpty()
                val canStartNow = statusLc in setOf(
                    "scheduled", "client_started", "picked_up", "on_site",
                )
                val canRecordNow = statusLc in setOf(
                    "on_counselling", "picked_from_site", "dropped",
                )
                SiteVisitCounsellingConfirmBottomSheet.newInstance(
                    siteVisitId = visit._id,
                    projectName = projectName,
                    clientName = clientName,
                    bdoName = bdoName,
                    inchargeName = visit.inchargeStaff?.name,
                    scheduledAt = scheduledAt,
                    mobileNumber = visit.lead?.mobileNumber
                        ?: visit.lead?.mobileNumberNormalized
                        ?: visit.client?.mobileNumber
                        ?: visit.client?.mobileNumberNormalized,
                    additionalVisitors = additionalVisitors,
                    foodPreferences = visit.foodPreferences,
                    occupation = visit.lead?.profession
                        ?: visit.client?.profession,
                    leadTemperature = visit.lead?.temperature,
                    visitStatus = visit.status,
                    outcome = visit.outcome,
                    outcomeNotes = visit.notes,
                    canStartCounselling = visit.canStartCounselling ||
                        (scanAuthorized && canStartNow),
                    canRecordOutcome = visit.canRecordOutcome ||
                        (scanAuthorized && canRecordNow),
                ).showOnce(parentFragmentManager, "SiteVisitCounsellingConfirmBottomSheet")
            } catch (error: Exception) {
                if (_binding == null) return@launch
                val message = if (error is TimeoutCancellationException) {
                    "The SV details are taking too long to load. Check the connection and scan again."
                } else {
                    error.message ?: "This SV QR could not be confirmed"
                }
                Toast.makeText(
                    requireContext(),
                    message,
                    Toast.LENGTH_LONG,
                ).show()
                resumeScanning()
            } finally {
                showSiteVisitLoading(false)
                siteVisitScanJob = null
            }
        }
    }

    private suspend fun loadSiteVisitQrWithRetry(siteVisitId: String): com.manjugroups.m_connect.network.SiteVisitQrScanResponse {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return withTimeout(SV_SCAN_TIMEOUT_MS) {
                    geoTrackApi.scanSiteVisitQr(
                        session.bearerToken,
                        SiteVisitQrScanRequest("SV:$siteVisitId"),
                    )
                }
            } catch (error: Exception) {
                lastError = error
                val retryable = error is java.io.IOException || error is TimeoutCancellationException
                if (!retryable || attempt == 1) throw error
                delay(SV_SCAN_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("Unable to load this SV")
    }

    private fun showSiteVisitLoading(show: Boolean) {
        val currentBinding = _binding ?: return
        currentBinding.siteVisitScanLoading.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** Open the SV outcome page for a visit already on counselling — no status
     *  change, just navigation (the authorised viewer records the outcome there). */
    private fun openSiteVisitOutcome(siteVisitId: String, leadTemperature: String?) {
        val root = _binding?.root ?: return
        // This can be invoked synchronously from a setFragmentResult dispatch (the
        // countdown's onFinish), during which this fragment may be mid-transition and
        // not yet associated with a fragment manager. Defer to the next frame and
        // re-check isAdded so parentFragmentManager is safe to touch.
        root.post {
            if (!isAdded || _binding == null) return@post
            val fm = parentFragmentManager
            fm.popBackStackImmediate()
            SiteVisitOverviewFragment
                .forScannedVisit(siteVisitId, leadTemperature)
                .showOnce(fm, "SiteVisitOutcomeAfterQr")
        }
    }

    private fun markSiteVisitOnCounselling(
        siteVisitId: String,
        leadTemperature: String?,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = runCatching {
                geoTrackApi.markSiteVisitOnCounselling(
                    session.bearerToken,
                    SiteVisitIdRequest(siteVisitId),
                )
            }
            if (_binding == null) return@launch
            val response = outcome.getOrNull()
            if (response?.success == true) {
                Toast.makeText(requireContext(), "Counselling started", Toast.LENGTH_SHORT).show()
                openSiteVisitOutcome(siteVisitId, leadTemperature)
            } else {
                // Counselling did NOT start (route missing, not authorised, or an
                // invalid transition). The SV status never advanced, so opening
                // the outcome page would strand the user on a greyed, dead-ended
                // form — exactly the "scanned the QR but the outcome stays locked"
                // report. Surface the real reason and stay on the scanner so they
                // can retry or hand off to the Site Incharge instead.
                val msg = response?.error
                    ?: outcome.exceptionOrNull()?.message
                    ?: "Couldn't start counselling. Try again, or ask the Site Incharge to start it."
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                resumeScanning()
            }
        }
    }

    private fun fetchInvitation(inviteToken: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getInvitationByToken(session.bearerToken, inviteToken)
                if (_binding == null) return@launch
                val inv = resp.invitation
                if (resp.success && inv != null) {
                    // Resolve whether an admitted visitor is still in or already
                    // out before rendering, so a checked-out QR never offers the
                    // Check Out action again.
                    val resolved = resolveCheckinState(inv)
                    if (_binding == null) return@launch
                    showRealVerification(resolved)
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Invitation not found", Toast.LENGTH_LONG).show()
                    resumeScanning()
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(requireContext(), "Couldn't load invitation: ${e.message ?: "network error"}", Toast.LENGTH_LONG).show()
                resumeScanning()
            }
        }
    }

    /** Hide the verification sheet and resume live scanning. */
    private fun resumeScanning() {
        if (_binding == null) return
        showSiteVisitLoading(false)
        currentInvitationId = null
        binding.visitorVerificationContainer.visibility = View.GONE
        binding.laserLine.visibility = View.VISIBLE
        laserAnimator?.resume()
        bindCameraUseCases()
        isScanningActive = true
    }

    private fun formatWindow(inv: InvitationDetail): String {
        val time = when {
            !inv.expectedTimeFrom.isNullOrBlank() && !inv.expectedTimeTo.isNullOrBlank() ->
                "${inv.expectedTimeFrom} - ${inv.expectedTimeTo}"
            !inv.expectedTimeFrom.isNullOrBlank() -> inv.expectedTimeFrom
            else -> ""
        }
        return listOf(inv.expectedDate ?: "", time ?: "")
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { "—" }
    }

    private fun showRealVerification(inv: InvitationDetail) {
        currentInvitationId = inv.id

        binding.tvPrimaryName.text = inv.visitorName ?: "—"
        binding.tvPrimaryCompany.text = inv.visitorCompany ?: "—"
        binding.tvPrimaryPhone.text = inv.visitorPhone ?: "—"
        binding.tvPrimaryEmail.text = inv.visitorEmail ?: "—"
        binding.tvPrimaryAge.text = "Age: ${inv.visitorAge?.toString() ?: "—"}"

        val category = inv.categoryName ?: "Visitor"
        binding.tvVisitCategoryType.text =
            if (!inv.purposeName.isNullOrBlank()) "$category (${inv.purposeName})" else category
        binding.tvVisitHost.text = inv.hostName ?: "—"
        binding.tvVisitTimeWindow.text = formatWindow(inv)
        binding.tvVisitNotes.text = inv.meetingNotes?.takeIf { it.isNotBlank() } ?: "—"

        // Additional visitors
        binding.containerSecondaryList.removeAllViews()
        val additional = inv.additionalVisitors ?: emptyList()
        if (additional.isEmpty()) {
            binding.layoutSecondaryVisitors.visibility = View.GONE
        } else {
            binding.layoutSecondaryVisitors.visibility = View.VISIBLE
            for (a in additional) {
                val secView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_secondary_visitor, binding.containerSecondaryList, false)
                secView.findViewById<android.widget.TextView>(R.id.tvSecondaryName).text = a.name ?: "—"
                secView.findViewById<android.widget.TextView>(R.id.tvSecondaryCompany).text = inv.visitorCompany ?: "—"
                secView.findViewById<android.widget.TextView>(R.id.tvSecondaryPhone).text = a.phone ?: "—"
                secView.findViewById<android.widget.TextView>(R.id.tvSecondaryEmail).text = a.email ?: "—"
                secView.findViewById<android.widget.TextView>(R.id.tvSecondaryAge).text = "Age: ${a.age?.toString() ?: "—"}"
                binding.containerSecondaryList.addView(secView)
            }
        }

        binding.btnCancelHeaderVerification.setOnClickListener { resumeScanning() }
        binding.btnCancelVerification.setOnClickListener { resumeScanning() }
        applyCheckStateUi(inv)

        BottomSheetBehavior.from(binding.verificationBottomSheet).state = BottomSheetBehavior.STATE_COLLAPSED
        binding.visitorVerificationContainer.visibility = View.VISIBLE
    }

    /**
     * Toggle the verification action based on the invitation's live check-in
     * state: not yet in -> Confirm Admission; currently in -> Check Out (re-scan
     * verifies the visitor and shows the check-in time); already out -> a
     * read-only summary with both timestamps. "Rescan" (btnCancelVerification)
     * is always available.
     */
    private fun applyCheckStateUi(inv: InvitationDetail) {
        // The enriched backend gives an explicit checkinState ("in"/"out").
        // When it's absent (older backend), fall back to the invitation status,
        // which flips to "checked_in" the instant a visitor is admitted — so the
        // Check Out action + Checked In tag still work without the enrichment.
        val checkedOut = inv.checkinState == "out"
        val checkedIn = !checkedOut &&
            (inv.checkinState == "in" || inv.status.equals("checked_in", ignoreCase = true))

        when {
            checkedOut -> {
                // Grey "Checked Out" tag + read-only summary; no action button.
                binding.tvCheckedInTag.visibility = View.VISIBLE
                binding.tvCheckedInTag.text = "● Checked Out"
                binding.tvCheckedInTag.setBackgroundResource(R.drawable.bg_tag_checked_out)
                binding.tvCheckedInTag.setTextColor(android.graphics.Color.parseColor("#475467"))
                val parts = mutableListOf<String>()
                if (inv.checkinAt != null) parts.add("Checked in ${fmtCheckTime(inv.checkinAt)}")
                if (inv.checkoutAt != null) parts.add("Checked out ${fmtCheckTime(inv.checkoutAt)}")
                binding.tvVerificationSubtitle.text =
                    if (parts.isEmpty()) "This visitor has already checked out"
                    else parts.joinToString("  ·  ")
                showActionButton(false)
            }
            checkedIn -> {
                binding.tvCheckedInTag.visibility = View.VISIBLE
                binding.tvCheckedInTag.text = "● Checked In"
                binding.tvCheckedInTag.setBackgroundResource(R.drawable.bg_tag_checked_in)
                binding.tvCheckedInTag.setTextColor(android.graphics.Color.parseColor("#15803D"))
                // Only a staff member with frontdesk.checkout may release a
                // visitor; everyone else just sees the checked-in details.
                val canAct = canCheckout
                binding.tvVerificationSubtitle.text = when {
                    inv.checkinAt != null && canAct -> "Checked in ${fmtCheckTime(inv.checkinAt)} · tap Check Out when leaving"
                    inv.checkinAt != null -> "Checked in ${fmtCheckTime(inv.checkinAt)}"
                    canAct -> "Visitor is checked in · tap Check Out when leaving"
                    else -> "Visitor is checked in"
                }
                if (canAct) {
                    binding.tvConfirmAdmissionText.text = "Check Out"
                    binding.btnConfirmAdmission.setOnClickListener { confirmCheckout(inv) }
                }
                showActionButton(canAct)
            }
            else -> {
                binding.tvCheckedInTag.visibility = View.GONE
                // Only a staff member with frontdesk.checkin may admit a visitor.
                binding.tvVerificationSubtitle.text =
                    if (canCheckin) "Verify the visitor details before confirming admission"
                    else "Verify the visitor details"
                if (canCheckin) {
                    binding.tvConfirmAdmissionText.text = "Confirm"
                    binding.btnConfirmAdmission.setOnClickListener { confirmAdmission(inv) }
                }
                showActionButton(canCheckin)
            }
        }
    }

    // Frontdesk IAM: anyone may open the scanner and pull up a record, but only
    // permitted roles get the admit / release actions. isAdmin bypasses.
    private val canCheckin get() = session.hasPermission("frontdesk.checkin")
    private val canCheckout get() = session.hasPermission("frontdesk.checkout")
    private val canViewHistory get() = session.hasPermission("frontdesk.view")
    /**
     * Show or hide the primary action (Confirm / Check Out) and keep the layout
     * responsive: when it's hidden (no permission or checked-out), Rescan is the
     * only weighted child so it fills the row — we just drop its trailing gap so
     * it centres cleanly instead of leaving an 8dp hole on the right.
     */
    private fun showActionButton(visible: Boolean) {
        binding.btnConfirmAdmission.visibility = if (visible) View.VISIBLE else View.GONE
        binding.btnConfirmAdmission.isEnabled = visible
        val lp = binding.btnCancelVerification.layoutParams as android.view.ViewGroup.MarginLayoutParams
        lp.marginEnd = if (visible) (8 * resources.displayMetrics.density).toInt() else 0
        binding.btnCancelVerification.layoutParams = lp
    }

    private fun fmtCheckTime(ms: Long?): String {
        if (ms == null || ms <= 0L) return "—"
        return java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    }

    private fun confirmCheckout(inv: InvitationDetail) {
        val invitationId = currentInvitationId ?: run {
            Toast.makeText(requireContext(), "Missing invitation reference", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnConfirmAdmission.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // Prefer the dedicated HTTP route; if that isn't deployed yet (404),
            // fall back to calling the already-deployed frontdesk.checkout
            // mutation directly via Convex's function API so check-out still works.
            val ok = try {
                api.checkoutInvitation(
                    session.bearerToken,
                    CheckoutInvitationRequest(invitationId = invitationId),
                ).success
            } catch (e: Exception) {
                checkoutViaConvexApi(invitationId)
            }
            if (_binding == null) return@launch
            binding.btnConfirmAdmission.isEnabled = true
            if (ok) {
                Toast.makeText(
                    requireContext(),
                    "${inv.visitorName ?: "Visitor"} checked out",
                    Toast.LENGTH_LONG,
                ).show()
                applyCheckStateUi(
                    inv.copy(checkinState = "out", checkoutAt = System.currentTimeMillis()),
                )
                updateHistoryStatus(invitationId, "checked_out")
            } else {
                Toast.makeText(requireContext(), "Check-out failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Check-out fallback that works before the /api/frontdesk/invitations/checkout
     * HTTP route is deployed: it calls the already-deployed frontdesk.checkout
     * mutation through Convex's function API. It first resolves the active
     * check-in id for this invitation (listActive), then marks it out. Runs on
     * IO because the OkHttp calls block.
     */
    private fun sessionTokenRaw(): String =
        session.bearerToken.removePrefix("Bearer ").trim()

    /**
     * Blocking Convex function-API call (run on IO). Returns the success
     * envelope, or null on any error / non-success. Lets the scanner use the
     * already-deployed frontdesk.* functions even before the dedicated HTTP
     * routes ship.
     */
    private fun convexFunctionCall(endpoint: String, path: String, args: JSONObject): JSONObject? {
        return try {
            val cloudBase = BuildConfig.BASE_URL.trimEnd('/')
                .replace(".convex.site", ".convex.cloud")
            val request = Request.Builder()
                .url("$cloudBase/api/$endpoint")
                .post(
                    JSONObject().put("path", path).put("args", args).put("format", "json")
                        .toString().toRequestBody("application/json".toMediaType()),
                )
                .build()
            OkHttpClient().newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: return null
                val obj = JSONObject(text)
                if (obj.optString("status") == "success") obj else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun checkoutViaConvexApi(invitationId: String): Boolean =
        withContext(Dispatchers.IO) {
            val active = convexFunctionCall(
                "query", "frontdesk:listActive",
                JSONObject().put("sessionToken", sessionTokenRaw()),
            ) ?: return@withContext false
            val rows = active.optJSONArray("value") ?: return@withContext false
            var checkinId: String? = null
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (row.optString("invitationId") == invitationId) {
                    checkinId = row.optString("_id")
                    break
                }
            }
            if (checkinId.isNullOrBlank()) return@withContext false
            convexFunctionCall(
                "mutation", "frontdesk:checkout",
                JSONObject().put("sessionToken", sessionTokenRaw()).put("checkinId", checkinId),
            ) != null
        }

    /**
     * Derive the true check-in state when the backend hasn't enriched it yet: a
     * "checked_in" invitation whose check-in is no longer among active visitors
     * has been checked out. Keeps an explicit checkinState when the backend
     * already provides one, and leaves the row untouched if the lookup fails.
     */
    private suspend fun resolveCheckinState(inv: InvitationDetail): InvitationDetail {
        if (inv.checkinState != null) return inv
        if (!inv.status.equals("checked_in", ignoreCase = true)) return inv
        val invId = inv.id ?: return inv
        return withContext(Dispatchers.IO) {
            val active = convexFunctionCall(
                "query", "frontdesk:listActive",
                JSONObject().put("sessionToken", sessionTokenRaw()),
            ) ?: return@withContext inv
            val rows = active.optJSONArray("value") ?: return@withContext inv
            var isActive = false
            for (i in 0 until rows.length()) {
                if (rows.getJSONObject(i).optString("invitationId") == invId) {
                    isActive = true
                    break
                }
            }
            inv.copy(checkinState = if (isActive) "in" else "out")
        }
    }

    private fun confirmAdmission(inv: InvitationDetail) {
        val invitationId = currentInvitationId
        if (invitationId == null) {
            Toast.makeText(requireContext(), "Missing invitation reference", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnConfirmAdmission.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.checkinInvitation(
                    session.bearerToken,
                    CheckinInvitationRequest(invitationId = invitationId),
                )
                if (_binding == null) return@launch
                binding.btnConfirmAdmission.isEnabled = true
                if (resp.success) {
                    saveInvitationToHistory(inv, resp.passNumber)
                    val pass = resp.passNumber?.let { " · Pass $it" } ?: ""
                    Toast.makeText(
                        requireContext(),
                        "Admission confirmed$pass for ${inv.visitorName}",
                        Toast.LENGTH_LONG,
                    ).show()
                    // Stay on the screen but flip to the checked-in state so the
                    // operator can Check Out or Rescan to verify.
                    applyCheckStateUi(
                        inv.copy(checkinState = "in", checkinAt = System.currentTimeMillis()),
                    )
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Check-in failed", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.btnConfirmAdmission.isEnabled = true
                Toast.makeText(requireContext(), "Check-in failed: ${e.message ?: "network error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveInvitationToHistory(inv: InvitationDetail, passNumber: String?) {
        val historyJson = JSONObject().apply {
            put("isStructured", true)
            // Key + status so the history row can show Checked In / Checked Out
            // (updated by updateHistoryStatus on check-out).
            put("invitationId", currentInvitationId ?: "")
            put("status", "checked_in")
            put("primaryName", inv.visitorName ?: "")
            put("primaryCompany", inv.visitorCompany ?: "")
            put("primaryPhone", inv.visitorPhone ?: "")
            put("primaryEmail", inv.visitorEmail ?: "")
            put("primaryAge", inv.visitorAge?.toString() ?: "")
            val secArr = JSONArray()
            inv.additionalVisitors?.forEach { a ->
                secArr.put(JSONObject().apply {
                    put("name", a.name ?: "")
                    put("phone", a.phone ?: "")
                    put("age", a.age?.toString() ?: "")
                    put("email", a.email ?: "")
                })
            }
            put("secondaryList", secArr)
            put("visitorType", inv.categoryName ?: "")
            put("purpose", inv.purposeName ?: "")
            put("hostPerson", inv.hostName ?: "")
            put("expectedTime", formatWindow(inv))
            put("meetingNotes", inv.meetingNotes ?: "")
            passNumber?.let { put("passNumber", it) }
        }
        saveScanToHistory(historyJson.toString())
    }

    /** Flip a saved history entry's status (e.g. to "checked_out") by invitationId. */
    private fun updateHistoryStatus(invitationId: String, status: String) {
        if (invitationId.isBlank()) return
        val prefs = requireContext()
            .getSharedPreferences("qr_scanner_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("qr_history_list", "[]") ?: "[]"
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val payload = try {
                    JSONObject(entry.optString("value", ""))
                } catch (e: Exception) {
                    continue
                }
                if (payload.optString("invitationId") == invitationId) {
                    payload.put("status", status)
                    entry.put("value", payload.toString())
                    prefs.edit().putString("qr_history_list", arr.toString()).apply()
                    return
                }
            }
        } catch (e: Exception) {
            // ignore — history is best-effort
        }
    }

    private fun showVerificationModal(qrValue: String) {
        var primaryName = "Jane Doe"
        var primaryPhone = "+91 98765 43210"
        var primaryAge = "28"
        var primaryEmail = "jane.doe@example.com"
        var primaryCompany = "Tech Solutions"
        var visitorType = "Vendor"
        var purpose = "Installation"
        var hostPerson = "Dev Super Admin"
        var expectedTime = "29 Jun 2026, 11:30 AM - 01:30 PM"
        var meetingNotes = "Technical interview and workspace verification."
        val secondaryList = ArrayList<JSONObject>()

        try {
            val json = JSONObject(qrValue)
            if (json.has("primaryVisitor")) {
                val prim = json.getJSONObject("primaryVisitor")
                primaryName = prim.optString("name", primaryName)
                primaryPhone = prim.optString("phone", primaryPhone)
                primaryAge = prim.optString("age", primaryAge)
                primaryEmail = prim.optString("email", primaryEmail)
                primaryCompany = prim.optString("company", primaryCompany)
            }
            if (json.has("additionalVisitors")) {
                val secArr = json.getJSONArray("additionalVisitors")
                for (i in 0 until secArr.length()) {
                    secondaryList.add(secArr.getJSONObject(i))
                }
            }
            if (json.has("visitDetails")) {
                val det = json.getJSONObject("visitDetails")
                visitorType = det.optString("visitorType", visitorType)
                purpose = det.optString("purpose", purpose)
                hostPerson = det.optString("hostPerson", hostPerson)
                expectedTime = det.optString("expectedDateTimeWindow", expectedTime)
                meetingNotes = det.optString("meetingNotes", meetingNotes)
            }
        } catch (e: Exception) {
            // Not a JSON or missing fields: fallback to mock details
            meetingNotes = "Scanned Raw Payload: $qrValue"
            
            // Add a mock secondary visitor for rich display
            val sec1 = JSONObject().apply {
                put("name", "John Smith")
                put("phone", "+91 87654 32109")
                put("age", "32")
                put("email", "john.smith@example.com")
                put("company", "Tech Solutions")
            }
            secondaryList.add(sec1)
        }

        // Bind data to views
        binding.tvPrimaryName.text = primaryName
        binding.tvPrimaryCompany.text = primaryCompany
        binding.tvPrimaryPhone.text = primaryPhone
        binding.tvPrimaryEmail.text = primaryEmail
        binding.tvPrimaryAge.text = "Age: $primaryAge"

        binding.tvVisitCategoryType.text = "$visitorType ($purpose)"
        binding.tvVisitHost.text = hostPerson
        binding.tvVisitTimeWindow.text = expectedTime
        binding.tvVisitNotes.text = meetingNotes

        // Populate secondary list
        binding.containerSecondaryList.removeAllViews()
        if (secondaryList.isEmpty()) {
            binding.layoutSecondaryVisitors.visibility = View.GONE
        } else {
            binding.layoutSecondaryVisitors.visibility = View.VISIBLE
            for (sec in secondaryList) {
                val secView = LayoutInflater.from(requireContext()).inflate(R.layout.item_secondary_visitor, binding.containerSecondaryList, false)
                val tvSecName = secView.findViewById<android.widget.TextView>(R.id.tvSecondaryName)
                val tvSecCompany = secView.findViewById<android.widget.TextView>(R.id.tvSecondaryCompany)
                val tvSecPhone = secView.findViewById<android.widget.TextView>(R.id.tvSecondaryPhone)
                val tvSecEmail = secView.findViewById<android.widget.TextView>(R.id.tvSecondaryEmail)
                val tvSecAge = secView.findViewById<android.widget.TextView>(R.id.tvSecondaryAge)

                tvSecName.text = sec.optString("name", "N/A")
                tvSecCompany.text = sec.optString("company", "N/A")
                tvSecPhone.text = sec.optString("phone", "N/A")
                tvSecEmail.text = sec.optString("email", "N/A")
                tvSecAge.text = "Age: ${sec.optString("age", "N/A")}"

                binding.containerSecondaryList.addView(secView)
            }
        }

        // Setup button listeners
        binding.btnCancelHeaderVerification.setOnClickListener {
            binding.visitorVerificationContainer.visibility = View.GONE
            binding.laserLine.visibility = View.VISIBLE
            laserAnimator?.resume()
            bindCameraUseCases() // Re-bind to start preview
            isScanningActive = true // Resume scan
        }

        binding.btnCancelVerification.setOnClickListener {
            binding.visitorVerificationContainer.visibility = View.GONE
            binding.laserLine.visibility = View.VISIBLE
            laserAnimator?.resume()
            bindCameraUseCases() // Re-bind to start preview
            isScanningActive = true // Resume scan
        }

        binding.btnConfirmAdmission.setOnClickListener {
            // Save structured JSON string in history
            val historyJson = JSONObject().apply {
                put("isStructured", true)
                put("primaryName", primaryName)
                put("primaryCompany", primaryCompany)
                put("primaryPhone", primaryPhone)
                put("primaryEmail", primaryEmail)
                put("primaryAge", primaryAge)
                
                val secArr = JSONArray()
                for (sec in secondaryList) {
                    secArr.put(sec)
                }
                put("secondaryList", secArr)
                
                put("visitorType", visitorType)
                put("purpose", purpose)
                put("hostPerson", hostPerson)
                put("expectedTime", expectedTime)
                put("meetingNotes", meetingNotes)
            }
            saveScanToHistory(historyJson.toString())
            
            binding.visitorVerificationContainer.visibility = View.GONE
            Toast.makeText(requireContext(), "Admission Confirmed for $primaryName", Toast.LENGTH_SHORT).show()
            
            // Resume scan, camera, and laser line
            binding.laserLine.visibility = View.VISIBLE
            laserAnimator?.resume()
            bindCameraUseCases() // Re-bind to start preview
            isScanningActive = true // Resume scan
        }

        BottomSheetBehavior.from(binding.verificationBottomSheet).state = BottomSheetBehavior.STATE_COLLAPSED
        binding.visitorVerificationContainer.visibility = View.VISIBLE
    }

    private fun saveScanToHistory(value: String) {
        val sharedPrefs = requireContext().getSharedPreferences("qr_scanner_prefs", Context.MODE_PRIVATE)
        val historyStr = sharedPrefs.getString("qr_history_list", "[]") ?: "[]"
        
        try {
            val jsonArray = JSONArray(historyStr)
            
            val newScan = JSONObject().apply {
                put("value", value)
                put("timestamp", SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault()).format(Date()))
            }
            
            // Insert at the beginning of the list
            val tempArray = JSONArray()
            tempArray.put(newScan)
            for (i in 0 until jsonArray.length()) {
                tempArray.put(jsonArray.get(i))
            }
            
            sharedPrefs.edit().putString("qr_history_list", tempArray.toString()).apply()
        } catch (e: Exception) {
            // Handle parsing exceptions
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(android.graphics.Color.BLACK, false, true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        siteVisitScanJob?.cancel()
        siteVisitScanJob = null
        laserAnimator?.cancel()
        barcodeScanner.close()
        cameraExecutor.shutdown()
        _binding = null
    }

    private companion object {
        const val SV_SCAN_TIMEOUT_MS = 10_000L
        const val SV_SCAN_RETRY_DELAY_MS = 400L
    }
}
