package com.manjugroups.m_connect.ui.library.loans

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import java.io.File

class LoanDeskUploadBottomSheet : BottomSheetDialogFragment() {

    private var onSubmitted: (() -> Unit)? = null

    // Track upload states for the 4 documents
    private var isDoc1Uploaded = false
    private var isDoc2Uploaded = false
    private var isDoc3Uploaded = false
    private var isDoc4Uploaded = false

    private var activeSlot = 0
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    private lateinit var layoutUnuploaded1: View
    private lateinit var layoutUploaded1: View
    private lateinit var tvUploadedName1: TextView

    private lateinit var layoutUnuploaded2: View
    private lateinit var layoutUploaded2: View
    private lateinit var tvUploadedName2: TextView

    private lateinit var layoutUnuploaded3: View
    private lateinit var layoutUploaded3: View
    private lateinit var tvUploadedName3: TextView

    private lateinit var layoutUnuploaded4: View
    private lateinit var layoutUploaded4: View
    private lateinit var tvUploadedName4: TextView

    private lateinit var btnSubmit: TextView

    // Launchers
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = getFileName(uri) ?: when (activeSlot) {
                1 -> "pan_card_doc.pdf"
                2 -> "aadhaar_card_doc.pdf"
                3 -> "bank_statement.pdf"
                else -> "pay_slip.pdf"
            }
            onFileUploaded(activeSlot, name)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        cameraUri?.let { uri ->
            runCatching {
                requireContext().revokeUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        if (result.resultCode == Activity.RESULT_OK) {
            val f = cameraFile
            if (f != null && f.exists() && f.length() > 0) {
                onFileUploaded(activeSlot, f.name)
            }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    fun setOnSubmittedListener(listener: () -> Unit) {
        this.onSubmitted = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                it.setBackgroundResource(R.drawable.bg_auth_sheet)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_loan_desk_upload, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind Doc 1 Views (PAN Card)
        val btnCamera1 = view.findViewById<View>(R.id.btnCamera1)
        layoutUnuploaded1 = view.findViewById(R.id.layoutUnuploaded1)
        layoutUploaded1 = view.findViewById(R.id.layoutUploaded1)
        tvUploadedName1 = view.findViewById(R.id.tvUploadedName1)

        // Bind Doc 2 Views (Aadhaar Card)
        val btnCamera2 = view.findViewById<View>(R.id.btnCamera2)
        layoutUnuploaded2 = view.findViewById(R.id.layoutUnuploaded2)
        layoutUploaded2 = view.findViewById(R.id.layoutUploaded2)
        tvUploadedName2 = view.findViewById(R.id.tvUploadedName2)

        // Bind Doc 3 Views (Bank Statement)
        val btnCamera3 = view.findViewById<View>(R.id.btnCamera3)
        layoutUnuploaded3 = view.findViewById(R.id.layoutUnuploaded3)
        layoutUploaded3 = view.findViewById(R.id.layoutUploaded3)
        tvUploadedName3 = view.findViewById(R.id.tvUploadedName3)

        // Bind Doc 4 Views (IT / Pay Slip)
        val btnCamera4 = view.findViewById<View>(R.id.btnCamera4)
        layoutUnuploaded4 = view.findViewById(R.id.layoutUnuploaded4)
        layoutUploaded4 = view.findViewById(R.id.layoutUploaded4)
        tvUploadedName4 = view.findViewById(R.id.tvUploadedName4)

        btnSubmit = view.findViewById(R.id.btnSubmitUploads)

        // --- Doc 1 Action Listeners ---
        layoutUnuploaded1.setOnClickListener {
            activeSlot = 1
            filePickerLauncher.launch("image/*")
        }
        btnCamera1.setOnClickListener {
            activeSlot = 1
            checkCameraPermissionAndLaunch()
        }
        layoutUploaded1.setOnClickListener {
            isDoc1Uploaded = false
            layoutUploaded1.visibility = View.GONE
            layoutUnuploaded1.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        // --- Doc 2 Action Listeners ---
        layoutUnuploaded2.setOnClickListener {
            activeSlot = 2
            filePickerLauncher.launch("image/*")
        }
        btnCamera2.setOnClickListener {
            activeSlot = 2
            checkCameraPermissionAndLaunch()
        }
        layoutUploaded2.setOnClickListener {
            isDoc2Uploaded = false
            layoutUploaded2.visibility = View.GONE
            layoutUnuploaded2.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        // --- Doc 3 Action Listeners ---
        layoutUnuploaded3.setOnClickListener {
            activeSlot = 3
            filePickerLauncher.launch("*/*")
        }
        btnCamera3.setOnClickListener {
            activeSlot = 3
            checkCameraPermissionAndLaunch()
        }
        layoutUploaded3.setOnClickListener {
            isDoc3Uploaded = false
            layoutUploaded3.visibility = View.GONE
            layoutUnuploaded3.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        // --- Doc 4 Action Listeners ---
        layoutUnuploaded4.setOnClickListener {
            activeSlot = 4
            filePickerLauncher.launch("*/*")
        }
        btnCamera4.setOnClickListener {
            activeSlot = 4
            checkCameraPermissionAndLaunch()
        }
        layoutUploaded4.setOnClickListener {
            isDoc4Uploaded = false
            layoutUploaded4.visibility = View.GONE
            layoutUnuploaded4.visibility = View.VISIBLE
            updateSubmitButtonState()
        }

        btnSubmit.setOnClickListener {
            Toast.makeText(requireContext(), "Documents submitted successfully", Toast.LENGTH_SHORT).show()
            onSubmitted?.invoke()
            dismiss()
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
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val f = createTempPhotoFile("loandesk_cam_") ?: run {
            Toast.makeText(requireContext(), "Unable to create photo file", Toast.LENGTH_SHORT).show()
            return
        }
        cameraFile = f
        val uri = runCatching {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                f,
            )
        }.getOrElse {
            Toast.makeText(requireContext(), "Unable to open camera", Toast.LENGTH_SHORT).show()
            return
        }
        cameraUri = uri
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            clipData = ClipData.newUri(requireContext().contentResolver, "LoanDesk", uri)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTempPhotoFile(prefix: String): File? = try {
        val dir = File(requireContext().cacheDir, "loandesk").apply { if (!exists()) mkdirs() }
        File.createTempFile(prefix, ".jpg", dir)
    } catch (_: Exception) {
        null
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }

    private fun onFileUploaded(slot: Int, name: String) {
        when (slot) {
            1 -> {
                isDoc1Uploaded = true
                tvUploadedName1.text = name
                layoutUnuploaded1.visibility = View.GONE
                layoutUploaded1.visibility = View.VISIBLE
            }
            2 -> {
                isDoc2Uploaded = true
                tvUploadedName2.text = name
                layoutUnuploaded2.visibility = View.GONE
                layoutUploaded2.visibility = View.VISIBLE
            }
            3 -> {
                isDoc3Uploaded = true
                tvUploadedName3.text = name
                layoutUnuploaded3.visibility = View.GONE
                layoutUploaded3.visibility = View.VISIBLE
            }
            4 -> {
                isDoc4Uploaded = true
                tvUploadedName4.text = name
                layoutUnuploaded4.visibility = View.GONE
                layoutUploaded4.visibility = View.VISIBLE
            }
        }
        updateSubmitButtonState()
    }

    private fun updateSubmitButtonState() {
        val allUploaded = isDoc1Uploaded && isDoc2Uploaded && isDoc3Uploaded && isDoc4Uploaded
        btnSubmit.isEnabled = allUploaded
        btnSubmit.alpha = if (allUploaded) 1.0f else 0.5f
    }

    companion object {
        fun newInstance(onSubmitted: () -> Unit): LoanDeskUploadBottomSheet {
            return LoanDeskUploadBottomSheet().apply {
                setOnSubmittedListener(onSubmitted)
            }
        }
    }
}
