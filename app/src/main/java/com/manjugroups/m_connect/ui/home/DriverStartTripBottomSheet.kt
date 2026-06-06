package com.manjugroups.m_connect.ui.home

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.StartVisitRequest
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriverStartTripBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager
    private var visitId: String = ""
    private var currentPhotoFile: File? = null
    private var currentPhotoUri: Uri? = null

    private lateinit var etStartKm: EditText
    private lateinit var btnUploadImage: FrameLayout
    private lateinit var layoutUploadPlaceholder: LinearLayout
    private lateinit var ivUploadedPhoto: ImageView
    private lateinit var btnSubmit: View

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        currentPhotoUri?.let { uri ->
            runCatching {
                requireContext().revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        val file = currentPhotoFile
        if (result.resultCode == Activity.RESULT_OK && file != null && file.exists()) {
            layoutUploadPlaceholder.visibility = View.GONE
            ivUploadedPhoto.visibility = View.VISIBLE
            ivUploadedPhoto.load(file)
        } else {
            Toast.makeText(requireContext(), "Camera capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_driver_start_trip, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        visitId = requireArguments().getString(ARG_VISIT_ID).orEmpty()

        etStartKm = view.findViewById(R.id.etStartKm)
        btnUploadImage = view.findViewById(R.id.btnUploadImage)
        layoutUploadPlaceholder = view.findViewById(R.id.layoutUploadPlaceholder)
        ivUploadedPhoto = view.findViewById(R.id.ivUploadedPhoto)
        btnSubmit = view.findViewById(R.id.btnSubmit)

        btnUploadImage.setOnClickListener {
            checkCameraPermissionAndLaunch()
        }

        btnSubmit.setOnClickListener {
            performSubmit()
        }
    }

    private fun checkCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val dir = File(requireContext().cacheDir, "arrival_photos").apply {
            if (!exists()) mkdirs()
        }
        val file = File.createTempFile("start_", ".jpg", dir)
        currentPhotoFile = file
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        currentPhotoUri = uri

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = android.content.ClipData.newUri(
                requireContext().contentResolver,
                "StartPhoto",
                uri
            )
        }
        try {
            cameraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No camera application found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSubmit() {
        val kmText = etStartKm.text.toString().trim()
        if (kmText.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter starting Km", Toast.LENGTH_SHORT).show()
            return
        }
        val file = currentPhotoFile
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), "Please capture a photo of the odometer", Toast.LENGTH_SHORT).show()
            return
        }

        btnSubmit.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Call start visit API on backend to begin trip tracking
                geoApi.startVisit(session.bearerToken, StartVisitRequest(visitId, null, null))
                
                // Save details locally
                val timeStr = SimpleDateFormat("hh:mm a | dd MMM yyyy", Locale.getDefault()).format(Date())
                session.saveDriverTripStart(
                    visitId = visitId,
                    startKm = kmText,
                    startImagePath = file.absolutePath,
                    startTime = timeStr
                )

                setFragmentResult(RESULT_KEY, bundleOf("success" to true, "visitId" to visitId))
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                btnSubmit.isEnabled = true
                Toast.makeText(requireContext(), "Failed to start trip: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val RESULT_KEY = "driver_start_trip_result"
        private const val ARG_VISIT_ID = "arg_visit_id"

        fun newInstance(visitId: String): DriverStartTripBottomSheet {
            return DriverStartTripBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_VISIT_ID, visitId)
                }
            }
        }
    }
}
