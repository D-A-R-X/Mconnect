package com.manjugroups.m_connect.ui.library.frontdesk

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
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentQrScannerBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CheckinInvitationRequest
import com.manjugroups.m_connect.network.InvitationDetail
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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


class QrScannerFragment : Fragment() {

    private var _binding: FragmentQrScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isScanningActive = true
    private var laserAnimator: ObjectAnimator? = null
    private val barcodeScanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }
    private var statusBarHeight = 0

    private val api by lazy { ApiService.create() }
    private val session by lazy { SessionManager(requireContext()) }
    // Set when a real invitation is loaded; needed for the Confirm -> check-in call.
    private var currentInvitationId: String? = null

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

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnHistory.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, QrHistoryFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.btnHistoryVerification.setOnClickListener {
            binding.visitorVerificationContainer.visibility = View.GONE
            binding.laserLine.visibility = View.VISIBLE
            laserAnimator?.resume()
            bindCameraUseCases() // Re-bind to start preview
            isScanningActive = true // Resume scan

            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, QrHistoryFragment())
                .addToBackStack(null)
                .commit()
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
        startCamera()
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
            val inviteToken = extractInviteToken(value)
            if (inviteToken != null) {
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

    private fun fetchInvitation(inviteToken: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getInvitationByToken(session.bearerToken, inviteToken)
                if (_binding == null) return@launch
                val inv = resp.invitation
                if (resp.success && inv != null) {
                    showRealVerification(inv)
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
        binding.btnConfirmAdmission.setOnClickListener { confirmAdmission(inv) }

        BottomSheetBehavior.from(binding.verificationBottomSheet).state = BottomSheetBehavior.STATE_COLLAPSED
        binding.visitorVerificationContainer.visibility = View.VISIBLE
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
                    resumeScanning()
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
        laserAnimator?.cancel()
        cameraExecutor.shutdown()
        _binding = null
    }
}
