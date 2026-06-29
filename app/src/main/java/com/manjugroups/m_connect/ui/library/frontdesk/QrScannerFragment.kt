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
import com.manjugroups.m_connect.databinding.FragmentQrScannerBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat


class QrScannerFragment : Fragment() {

    private var _binding: FragmentQrScannerBinding? = null
    private val binding get() = _binding!!

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var isScanningActive = true
    private var laserAnimator: ObjectAnimator? = null
    private val barcodeScanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }

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

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = binding.statusBarPlaceholder.layoutParams
            lp.height = sysBars.top
            binding.statusBarPlaceholder.layoutParams = lp
            insets
        }

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
            
            showVerificationModal(value)
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
        binding.tvPrimaryCompany.text = "Company: $primaryCompany"
        binding.tvPrimaryPhone.text = "Phone: $primaryPhone"
        binding.tvPrimaryEmail.text = "Email: $primaryEmail"
        binding.tvPrimaryAge.text = "Age: $primaryAge"

        binding.tvVisitCategoryType.text = "Type: $visitorType ($purpose)"
        binding.tvVisitHost.text = "Host: $hostPerson"
        binding.tvVisitTimeWindow.text = "Time Window: $expectedTime"
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
                tvSecCompany.text = "Company: ${sec.optString("company", "N/A")}"
                tvSecPhone.text = "Phone: ${sec.optString("phone", "N/A")}"
                tvSecEmail.text = "Email: ${sec.optString("email", "N/A")}"
                tvSecAge.text = "Age: ${sec.optString("age", "N/A")}"

                binding.containerSecondaryList.addView(secView)
            }
        }

        // Setup button listeners
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
