package com.manjugroups.m_connect.ui.hr

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
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
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Proof-of-travel capture for a Public Transport on-duty completion. The
 * staff must attach at least one photo (Rapido screenshot, bus/train
 * ticket, etc.) and may add a short note. On submit we upload each photo
 * to Convex storage, then hand the storageIds + note back to the host
 * (HrDashboardFragment) which closes the on-duty trip with the proof.
 */
class OnDutyProofBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private lateinit var layoutThumbs: LinearLayout
    private lateinit var btnAddProof: FrameLayout
    private lateinit var etNote: EditText
    private lateinit var btnSubmit: View

    // Captured proof files, in add order. Mirrors the thumbnails shown
    // before the add tile.
    private val proofFiles = mutableListOf<File>()
    private var pendingPhotoFile: File? = null
    private var pendingPhotoUri: Uri? = null
    private var isSubmitting = false

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingPhotoUri?.let { uri ->
            runCatching {
                requireContext().revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        val file = pendingPhotoFile
        if (result.resultCode == Activity.RESULT_OK && file != null && file.exists()) {
            proofFiles.add(file)
            addThumbnail(file)
            refreshSubmitState()
        } else if (file != null) {
            runCatching { file.delete() }
        }
        pendingPhotoFile = null
        pendingPhotoUri = null
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
    ): View = inflater.inflate(R.layout.sheet_on_duty_proof, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        layoutThumbs = view.findViewById(R.id.layoutProofThumbs)
        btnAddProof = view.findViewById(R.id.btnAddProof)
        etNote = view.findViewById(R.id.etProofNote)
        btnSubmit = view.findViewById(R.id.btnProofSubmit)

        btnAddProof.setOnClickListener { checkCameraPermissionAndLaunch() }
        btnSubmit.setOnClickListener { performSubmit() }
        refreshSubmitState()
    }

    private fun addThumbnail(file: File) {
        val thumb = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_on_duty_proof_thumb, layoutThumbs, false)
        thumb.findViewById<ImageView>(R.id.ivProofThumb).load(file)
        thumb.findViewById<ImageView>(R.id.btnRemoveProof).setOnClickListener {
            if (isSubmitting) return@setOnClickListener
            proofFiles.remove(file)
            runCatching { file.delete() }
            layoutThumbs.removeView(thumb)
            refreshSubmitState()
        }
        // Insert before the add tile (always the last child).
        layoutThumbs.addView(thumb, layoutThumbs.childCount - 1)
    }

    private fun refreshSubmitState() {
        val enabled = proofFiles.isNotEmpty() && !isSubmitting
        btnSubmit.isEnabled = enabled
        btnSubmit.alpha = if (enabled) 1f else 0.5f
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
        val dir = File(requireContext().cacheDir, "on_duty_proof").apply {
            if (!exists()) mkdirs()
        }
        val file = File.createTempFile("proof_", ".jpg", dir)
        pendingPhotoFile = file
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        pendingPhotoUri = uri

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(requireContext().contentResolver, "Proof", uri)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No camera application found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun performSubmit() {
        if (isSubmitting) return
        if (proofFiles.isEmpty()) {
            Toast.makeText(requireContext(), "Please attach at least one travel proof.", Toast.LENGTH_SHORT).show()
            return
        }
        isSubmitting = true
        refreshSubmitState()
        val note = etNote.text.toString().trim()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ids = ArrayList<String>(proofFiles.size)
                for (f in proofFiles) {
                    ids.add(uploadProof(f))
                }
                // Build the bundle explicitly: bundleOf() would store the
                // ArrayList as Serializable, and getStringArrayList() on the
                // host side would then return null.
                setFragmentResult(
                    RESULT_KEY,
                    Bundle().apply {
                        putStringArrayList(KEY_PROOF_IDS, ids)
                        putString(KEY_PROOF_NOTE, note)
                    },
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                isSubmitting = false
                refreshSubmitState()
                Toast.makeText(
                    requireContext(),
                    "Couldn't upload proof: ${e.message ?: "network error"}. Try again.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private suspend fun uploadProof(file: File): String {
        val upload = com.manjugroups.m_connect.util.ImageCompressor.compress(file)
        val body = upload.asRequestBody("image/jpeg".toMediaType())
        val response = api.uploadStorageFile(session.bearerToken, body)
        if (upload !== file) runCatching { upload.delete() }
        return response.storageId
            ?: throw IllegalStateException(response.error ?: "upload failed")
    }

    companion object {
        const val RESULT_KEY = "on_duty_proof_result"
        const val KEY_PROOF_IDS = "proof_ids"
        const val KEY_PROOF_NOTE = "proof_note"

        fun newInstance(): OnDutyProofBottomSheet = OnDutyProofBottomSheet()
    }
}
