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
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest
import com.manjugroups.m_connect.network.MmsFleetDriverStartRequest
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DriverStartTripBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private var visitId: String = ""
    private var scheduledDate: String? = null
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
            if (isAdded) Toast.makeText(requireContext(), "Camera capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            if (isAdded) Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
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
        scheduledDate = requireArguments().getString(ARG_SCHEDULED_DATE)

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
        val uri = runCatching {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        }.getOrElse {
            if (isAdded) Toast.makeText(requireContext(), "Unable to open camera", Toast.LENGTH_SHORT).show()
            return
        }
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
        // Pre-empt the backend's "Trip actions are only available on the
        // assignment date (...)" throw so the driver doesn't waste a
        // photo upload + two HTTP calls only to see the same error in a
        // toast. We do the local check using the IST calendar day to
        // match the server's `toIndiaDateString()` — the backend gate
        // lives in lib/driverTripAssignment.ts and compares the visit's
        // scheduledDate (YYYY-MM-DD) against that exact string.
        val sd = scheduledDate
        val todayInIndia = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
        }.format(Date())
        if (!sd.isNullOrBlank() && sd != todayInIndia) {
            Toast.makeText(
                requireContext(),
                "This trip is scheduled for $sd. You can start it on that day (today is $todayInIndia).",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

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
                val startKm = kmText.toDoubleOrNull()
                if (startKm == null || startKm < 0) {
                    btnSubmit.isEnabled = true
                    Toast.makeText(requireContext(), "Please enter a valid starting Km", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val storageId = uploadOdometerPhoto(file)
                geoApi.markMmsFleetDriverArrived(
                    session.bearerToken,
                    MmsFleetDriverSiteVisitRequest(visitId),
                )
                val response = geoApi.startMmsFleetDriverTrip(
                    session.bearerToken,
                    MmsFleetDriverStartRequest(
                        siteVisitId = visitId,
                        photoIds = listOf(storageId),
                        startKm = startKm,
                    ),
                )
                if (!response.success) {
                    throw IllegalStateException(response.error ?: "Failed to start trip")
                }
                
                // Save details locally
                val timeStr = SimpleDateFormat("hh:mm a | dd MMM yyyy", Locale.getDefault()).format(Date())
                session.saveDriverTripStart(
                    visitId = visitId,
                    startKm = kmText,
                    startImagePath = file.absolutePath,
                    startTime = timeStr
                )

                // Adaptive tracking notification: Fleet trip + live elapsed timer.
                session.setFieldActivity(
                    kind = "fleet",
                    title = "Fleet Trip",
                    sub = "Trip in progress",
                    startMs = System.currentTimeMillis(),
                )
                com.manjugroups.m_connect.geotrack.service.TrackingNotification.refresh(requireContext())

                setFragmentResult(RESULT_KEY, bundleOf("success" to true, "visitId" to visitId))
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                btnSubmit.isEnabled = true
                Toast.makeText(requireContext(), readApiError(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun uploadOdometerPhoto(file: File): String {
        val result = com.manjugroups.m_connect.network.StorageUploader
            .upload(api, session.bearerToken, file)
        return result.storageId
            ?: throw IllegalStateException(result.errorMessage ?: "Failed to upload odometer photo")
    }

    private fun readApiError(error: Throwable): String {
        val httpError = error as? HttpException
        val raw = runCatching { httpError?.response()?.errorBody()?.string() }.getOrNull()
        val serverMessage = raw?.let {
            runCatching { JSONObject(it).optString("error").takeIf { msg -> msg.isNotBlank() } }.getOrNull()
        }
        val message = serverMessage ?: error.message ?: "Network error"
        return cleanDriverErrorMessage(message)
    }

    /**
     * Strip Convex's noisy wrappers from the message we got back from
     * the HTTP route and rewrite a few well-known business errors into
     * concise human copy. The backend throws `new Error("Trip actions
     * are only available on the assignment date (…)")` which Convex
     * surfaces as `Uncaught Error: Trip actions are only available …`
     * — useful for a stack trace, miserable for a driver staring at a
     * toast. Same idea for `[CONVEX M(…)]` prefixes that come back on
     * server validation throws.
     */
    private fun cleanDriverErrorMessage(raw: String): String {
        var msg = raw.trim()
        // Drop common Convex prefixes: "Uncaught Error: ", "Error: ",
        // "[CONVEX M(...)] ", "ConvexError: ".
        val noisePrefixes = listOf(
            "Uncaught Error:",
            "ConvexError:",
            "Convex error:",
            "Error:",
        )
        var changed = true
        while (changed) {
            changed = false
            for (prefix in noisePrefixes) {
                if (msg.startsWith(prefix, ignoreCase = true)) {
                    msg = msg.removePrefix(prefix).trim()
                    changed = true
                }
            }
            // Strip [CONVEX M(name)] prefix that wraps mutation throws.
            val bracketMatch = Regex("^\\[CONVEX [A-Z]\\([^)]*\\)]\\s*").find(msg)
            if (bracketMatch != null) {
                msg = msg.removePrefix(bracketMatch.value).trim()
                changed = true
            }
        }
        // Trim trailing "..." or whitespace that callers append to
        // help-line text.
        msg = msg.trimEnd('.', ' ', '…')
        if (msg.isEmpty()) return "Something went wrong. Try again."

        // Rewrite the two well-known driver-flow errors into clean
        // copy that tells the driver what to do next.
        val assignmentDateRegex = Regex(
            "Trip actions are only available on the assignment date \\(([^)]+)\\)\\.\\s*Today is ([^.]+)",
            RegexOption.IGNORE_CASE,
        )
        assignmentDateRegex.find(msg)?.let { m ->
            val assignmentDate = m.groupValues.getOrNull(1)?.trim().orEmpty()
            val today = m.groupValues.getOrNull(2)?.trim().orEmpty()
            return if (assignmentDate.isNotEmpty()) {
                "This trip is scheduled for $assignmentDate." +
                    if (today.isNotEmpty()) " You can start it on that day (today is $today)." else ""
            } else {
                "This trip can only be started on its assignment date."
            }
        }
        if (msg.contains("Mark arrived at client location", ignoreCase = true)) {
            return "Mark arrival at the client location before starting the trip."
        }
        if (msg.contains("Trip is already completed", ignoreCase = true)) {
            return "This trip is already marked completed."
        }
        if (msg.contains("Trip has already started", ignoreCase = true)) {
            return "This trip has already been started."
        }
        if (msg.contains("Driver access only", ignoreCase = true)) {
            return "Your account isn't registered as a fleet driver. Ask the admin to set your designation or add a fleet driver entry."
        }
        return msg
    }

    companion object {
        const val RESULT_KEY = "driver_start_trip_result"
        private const val ARG_VISIT_ID = "arg_visit_id"
        private const val ARG_SCHEDULED_DATE = "arg_scheduled_date"

        fun newInstance(
            visitId: String,
            scheduledDate: String? = null,
        ): DriverStartTripBottomSheet {
            return DriverStartTripBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_VISIT_ID, visitId)
                    putString(ARG_SCHEDULED_DATE, scheduledDate)
                }
            }
        }
    }
}
