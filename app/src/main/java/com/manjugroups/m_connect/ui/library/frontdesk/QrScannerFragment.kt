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

        saveScanToHistory(value)

        // Show a custom styled alert dialog for successful scan
        activity?.runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Scan Successful")
                .setMessage("Content:\n$value")
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                    isScanningActive = true // Resume scan
                }
                .setOnDismissListener {
                    isScanningActive = true
                }
                .setCancelable(false)
                .show()
        }
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
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        laserAnimator?.cancel()
        cameraExecutor.shutdown()
        _binding = null
    }
}
