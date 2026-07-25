package com.manjugroups.m_connect.ui.library

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
import android.widget.TextView
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
import com.manjugroups.m_connect.network.MmsFleetDriverEndRequest
import com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest
import com.manjugroups.m_connect.network.MmsFleetDriverStartRequest
import com.manjugroups.m_connect.network.StorageUploader
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskDriverTripRequest
import com.manjugroups.m_connect.network.TravelDeskEndTripRequest
import com.manjugroups.m_connect.network.TravelDeskStartTripRequest
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File

/**
 * Capture sheet for an external-agency driver's trip lifecycle: a dashboard
 * photo + a km reading, then the matching travel-desk mutation.
 *
 * START uploads the odometer photo and calls arrive → start (the backend
 * requires arrival before start, so we chain them). END uploads the closing
 * odometer photo and calls end. The client OTP the backend checks is a fixed
 * dummy ("0000") on this deployment, so the driver never has to type it.
 */
class AgencyDriverTripActionSheet : BottomSheetDialogFragment() {

    enum class Mode { START, END }

    private val api = TravelDeskApi.create()
    private val geoApi = GeoTrackApi.create()
    private val staffApi = ApiService.create()
    private lateinit var session: SessionManager
    private var visitId: String = ""
    private var mode: Mode = Mode.START
    // Internal MFPL fleet driver (staff token, mms-fleet endpoints) vs the
    // external agency driver (travel-desk endpoints). Same UI + flow.
    private var internal: Boolean = false
    private var currentPhotoFile: File? = null
    private var currentPhotoUri: Uri? = null

    private lateinit var etKm: EditText
    private var otpField: EditText? = null
    private lateinit var btnUpload: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var ivPhoto: ImageView
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
            placeholder.visibility = View.GONE
            ivPhoto.visibility = View.VISIBLE
            ivPhoto.load(file)
        } else {
            if (isAdded) Toast.makeText(requireContext(), "Camera capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else if (isAdded) Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
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
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.dialog_agency_trip_capture, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        visitId = requireArguments().getString(ARG_VISIT_ID).orEmpty()
        mode = if (requireArguments().getString(ARG_MODE) == Mode.END.name) Mode.END else Mode.START
        internal = requireArguments().getBoolean(ARG_INTERNAL, false)

        etKm = view.findViewById(R.id.etCaptureKm)
        btnUpload = view.findViewById(R.id.btnCaptureImage)
        placeholder = view.findViewById(R.id.layoutCapturePlaceholder)
        ivPhoto = view.findViewById(R.id.ivCapturedPhoto)
        btnSubmit = view.findViewById(R.id.btnCaptureSubmit)

        val otpLabel = view.findViewById<TextView>(R.id.tvOtpLabel)
        val etOtp = view.findViewById<EditText>(R.id.etCaptureOtp)
        val isStart = mode == Mode.START
        // OTP is only for starting the trip at the client pickup.
        otpLabel.visibility = if (isStart) View.VISIBLE else View.GONE
        etOtp.visibility = if (isStart) View.VISIBLE else View.GONE
        this.otpField = etOtp
        view.findViewById<TextView>(R.id.tvCaptureTitle).text =
            if (isStart) "Start Trip" else "End Trip"
        view.findViewById<TextView>(R.id.tvCaptureSubtitle).text =
            if (isStart) "Enter odometer start details for the trip"
            else "Enter closing odometer details to end the trip"
        view.findViewById<TextView>(R.id.tvKmLabel).text =
            if (isStart) "Start Km *" else "End Km *"
        view.findViewById<TextView>(R.id.tvCaptureHint).text =
            if (isStart) "Document the dashboard odometer at trip start."
            else "Document the dashboard odometer at trip end."
        (btnSubmit as? android.widget.Button)?.text =
            if (isStart) "Start Trip" else "End Trip"

        btnUpload.setOnClickListener { checkCameraPermissionAndLaunch() }
        btnSubmit.setOnClickListener { performSubmit() }
    }

    private fun checkCameraPermissionAndLaunch() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val dir = File(requireContext().cacheDir, "agency_trip_photos").apply {
            if (!exists()) mkdirs()
        }
        val file = File.createTempFile("trip_", ".jpg", dir)
        currentPhotoFile = file
        val uri = runCatching {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file,
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
                requireContext().contentResolver, "TripPhoto", uri,
            )
        }
        try {
            cameraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No camera application found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSubmit() {
        val kmText = etKm.text.toString().trim()
        val km = kmText.toDoubleOrNull()
        if (km == null || km < 0) {
            Toast.makeText(requireContext(), "Enter a valid Km reading", Toast.LENGTH_SHORT).show()
            return
        }
        val otp = otpField?.text?.toString()?.trim().orEmpty()
        if (mode == Mode.START && otp.isEmpty()) {
            Toast.makeText(requireContext(), "Enter the client OTP", Toast.LENGTH_SHORT).show()
            return
        }
        val file = currentPhotoFile
        if (file == null || !file.exists()) {
            Toast.makeText(requireContext(), "Capture the odometer photo first", Toast.LENGTH_SHORT).show()
            return
        }

        btnSubmit.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val storageId = uploadPhoto(file)
                val ok: Pair<Boolean, String?> = if (internal) {
                    if (mode == Mode.START) {
                        // Backend requires arrival before start; chain them. The
                        // internal start takes no OTP field (the OTP here is the
                        // client-side gate), only photo + km.
                        geoApi.markMmsFleetDriverArrived(
                            session.bearerToken, MmsFleetDriverSiteVisitRequest(visitId),
                        )
                        val r = geoApi.startMmsFleetDriverTrip(
                            session.bearerToken,
                            MmsFleetDriverStartRequest(
                                siteVisitId = visitId,
                                photoIds = listOf(storageId),
                                startKm = km,
                            ),
                        )
                        r.success to r.error
                    } else {
                        val r = geoApi.endMmsFleetDriverTrip(
                            session.bearerToken,
                            MmsFleetDriverEndRequest(
                                siteVisitId = visitId,
                                photoIds = listOf(storageId),
                                endKm = km,
                            ),
                        )
                        r.success to r.error
                    }
                } else if (mode == Mode.START) {
                    // Backend requires arrival before start; chain them.
                    api.driverMarkArrived(
                        session.bearerToken, TravelDeskDriverTripRequest(visitId),
                    )
                    val r = api.driverStartTrip(
                        session.bearerToken,
                        TravelDeskStartTripRequest(
                            siteVisitId = visitId,
                            otp = otp,
                            photoIds = listOf(storageId),
                            startKm = km,
                        ),
                    )
                    r.success to r.error
                } else {
                    // On-site + picked-from-site are their own buttons now; end
                    // just closes the trip out with the closing odometer.
                    val r = api.driverEndTrip(
                        session.bearerToken,
                        TravelDeskEndTripRequest(
                            siteVisitId = visitId,
                            photoIds = listOf(storageId),
                            endKm = km,
                        ),
                    )
                    r.success to r.error
                }
                if (!ok.first) {
                    throw IllegalStateException(ok.second ?: "Could not update the trip")
                }
                setFragmentResult(RESULT_KEY, bundleOf("success" to true, "visitId" to visitId))
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                btnSubmit.isEnabled = true
                Toast.makeText(requireContext(), readApiError(e), Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun uploadPhoto(file: File): String {
        if (internal) {
            // Staff storage upload (generated upload URL), not the agency route.
            val result = StorageUploader.upload(staffApi, session.bearerToken, file)
            return result.storageId
                ?: throw IllegalStateException(result.errorMessage ?: "Failed to upload photo")
        }
        val upload = com.manjugroups.m_connect.util.ImageCompressor.compress(file)
        val body = upload.asRequestBody("image/jpeg".toMediaType())
        val res = api.uploadStorageFile(session.bearerToken, body)
        if (upload !== file) runCatching { upload.delete() }
        return res.storageId
            ?: throw IllegalStateException(res.error ?: "Failed to upload photo")
    }

    private fun readApiError(error: Throwable): String {
        val httpError = error as? HttpException
        val raw = runCatching { httpError?.response()?.errorBody()?.string() }.getOrNull()
        val serverMessage = raw?.let {
            runCatching {
                JSONObject(it).optString("error").takeIf { m -> m.isNotBlank() }
            }.getOrNull()
        }
        val msg = (serverMessage ?: error.message ?: "Network error")
            .removePrefix("Uncaught Error:")
            .removePrefix("Error:")
            .trim()
        return msg.ifBlank { "Something went wrong. Try again." }
    }

    companion object {
        const val RESULT_KEY = "agency_trip_action_result"
        private const val ARG_VISIT_ID = "arg_visit_id"
        private const val ARG_MODE = "arg_mode"
        private const val ARG_INTERNAL = "arg_internal"
        private const val CLIENT_OTP = "0000"

        fun newInstance(
            visitId: String,
            mode: Mode,
            internal: Boolean = false,
        ): AgencyDriverTripActionSheet =
            AgencyDriverTripActionSheet().apply {
                arguments = bundleOf(
                    ARG_VISIT_ID to visitId,
                    ARG_MODE to mode.name,
                    ARG_INTERNAL to internal,
                )
            }
    }
}
