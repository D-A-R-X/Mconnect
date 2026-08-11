package com.manjugroups.m_connect.ui.hr

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentSelfieCameraBinding
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.pushDetail
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Locale
import com.manjugroups.m_connect.ui.common.commitOnce

class SelfieCameraFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentSelfieCameraBinding? = null
    private val binding get() = _binding!!
    private var pendingImageFile: File? = null
    private var isProcessingCapture = false
    private var googleMap: GoogleMap? = null
    private var latestLocation: Location? = null
    private var hasAutoLaunchedCamera = false
    private var pendingAutoLaunchAfterPermission = false
    private val mode: String
        get() = arguments?.getString(ARG_MODE) ?: PunchMode.PUNCH_IN.name

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        val cameraGranted = grants[Manifest.permission.CAMERA] == true
        val fineGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (cameraGranted && (fineGranted || coarseGranted)) {
            updateLocationPanel()
            launchCamera()
        } else {
            binding.tvLocationStatus.text = "Location permission denied"
            Toast.makeText(
                requireContext(),
                "Camera and GPS permissions are required for punch.",
                Toast.LENGTH_SHORT,
            ).show()
        }
        pendingAutoLaunchAfterPermission = false
    }

    private val captureSelfieLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (!isAdded || _binding == null) return@registerForActivityResult
        if (!success) {
            setProcessing(false)
            Toast.makeText(requireContext(), "Selfie capture cancelled.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val imageFile = pendingImageFile
        if (imageFile == null || !imageFile.exists()) {
            setProcessing(false)
            Toast.makeText(requireContext(), "Failed to read captured selfie.", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Compress the raw camera selfie up front. A full-res capture
            // (multi-MB, several megapixels) was large enough to OOM the
            // preview / upload on some devices — the reported clock-in crash.
            // Downscaling here means every downstream step gets a small file.
            val photoFile = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val out = com.manjugroups.m_connect.util.ImageCompressor.compress(imageFile)
                if (out !== imageFile) runCatching { imageFile.delete() }
                out
            }

            val location = latestLocation ?: getCurrentLocationOrNull()
            if (location == null) {
                setProcessing(false)
                Toast.makeText(
                    requireContext(),
                    "Unable to fetch GPS location. Please try again in open sky.",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            val address = resolveAddress(location)
            setProcessing(false)
            navigateToSelfieDetail(
                photoPath = photoFile.absolutePath,
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
        _binding = FragmentSelfieCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.mapViewSelfieLocation.onCreate(savedInstanceState)
        binding.mapViewSelfieLocation.getMapAsync(this)

        binding.btnBackCamera.setOnClickListener {
            navigateUp()
        }

        binding.btnCloseCamera.setOnClickListener {
            navigateUp()
        }

        binding.btnCapture.setOnClickListener {
            if (isProcessingCapture) return@setOnClickListener
            beginCaptureFlow(isAutomatic = false)
        }

        // Open the actual device camera as soon as this screen appears.
        if (!hasAutoLaunchedCamera) {
            hasAutoLaunchedCamera = true
            view.post { beginCaptureFlow(isAutomatic = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#000000"), false)
        _binding?.mapViewSelfieLocation?.onResume()
    }

    override fun onStart() {
        super.onStart()
        _binding?.mapViewSelfieLocation?.onStart()
    }

    override fun onPause() {
        _binding?.mapViewSelfieLocation?.onPause()
        super.onPause()
    }

    override fun onStop() {
        _binding?.mapViewSelfieLocation?.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        _binding?.mapViewSelfieLocation?.onLowMemory()
    }

    override fun onDestroyView() {
        _binding?.mapViewSelfieLocation?.onDestroy()
        googleMap = null
        super.onDestroyView()
        _binding = null
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map.apply {
            uiSettings.isMapToolbarEnabled = false
            uiSettings.isCompassEnabled = false
            uiSettings.isZoomControlsEnabled = false
        }
        updateLocationPanel()
    }

    private fun hasRequiredPermissions(): Boolean {
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

    private fun launchCamera() {
        val imageFile = createPunchPhotoFile()
        if (imageFile == null) {
            Toast.makeText(requireContext(), "Unable to create selfie file.", Toast.LENGTH_SHORT).show()
            return
        }
        pendingImageFile = imageFile
        val imageUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            imageFile,
        )
        setProcessing(true)
        captureSelfieLauncher.launch(imageUri)
    }

    private fun beginCaptureFlow(isAutomatic: Boolean) {
        if (hasRequiredPermissions()) {
            updateLocationPanel()
            launchCamera()
            return
        }
        pendingAutoLaunchAfterPermission = isAutomatic
        requestPermissionsLauncher.launch(
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun navigateToSelfieDetail(
        photoPath: String,
        latitude: Double,
        longitude: Double,
        address: String?,
    ) {
        if (!isAdded || parentFragmentManager.isStateSaved) return
        parentFragmentManager.pushDetail(
            SelfieClockInDetailFragment.newInstance(
                mode = mode,
                photoPath = photoPath,
                latitude = latitude,
                longitude = longitude,
                address = address,
            ),
        )
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

    private suspend fun getCurrentLocationOrNull(): Location? {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(requireContext())
            val token = CancellationTokenSource().token
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token).await()
                ?: client.lastLocation.await()
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun updateLocationPanel() {
        if (!hasLocationPermissionOnly()) {
            binding.tvLocationStatus.text = "Grant location to show map"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val location = getCurrentLocationOrNull()
            latestLocation = location
            if (location == null) {
                binding.tvLocationStatus.text = "Unable to fetch location"
                return@launch
            }

            val lat = location.latitude
            val lng = location.longitude
            binding.tvLocationStatus.text = String.format(Locale.US, "%.5f, %.5f", lat, lng)

            val map = googleMap ?: return@launch
            val point = LatLng(lat, lng)
            map.clear()
            map.addMarker(
                MarkerOptions()
                    .position(point)
                    .title("Current location"),
            )
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 16f))
        }
    }

    private fun hasLocationPermissionOnly(): Boolean {
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

    private fun setProcessing(processing: Boolean) {
        isProcessingCapture = processing
        if (_binding == null) return
        binding.btnCapture.isEnabled = !processing
        binding.btnCapture.alpha = if (processing) 0.6f else 1f
        binding.btnBackCamera.isEnabled = !processing
        binding.btnCloseCamera.isEnabled = !processing
    }

    companion object {
        private const val ARG_MODE = "arg_mode"

        fun newInstance(mode: String): SelfieCameraFragment {
            return SelfieCameraFragment().apply {
                arguments = Bundle().apply { putString(ARG_MODE, mode) }
            }
        }
    }
}
