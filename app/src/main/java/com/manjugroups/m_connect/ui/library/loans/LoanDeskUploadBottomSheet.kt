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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import java.io.File

class LoanDeskUploadBottomSheet : BottomSheetDialogFragment() {

    private var onSubmitted: ((doc1: String, doc2: String, doc3: String, doc4: String) -> Unit)? = null

    // Track upload states for the 4 documents
    private var isDoc1Uploaded = false
    private var isDoc2Uploaded = false
    private var isDoc3Uploaded = false
    private var isDoc4Uploaded = false

    private var doc1Name: String? = null
    private var doc2Name: String? = null
    private var doc3Name: String? = null
    private var doc4Name: String? = null

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
        object : ActivityResultContracts.GetContent() {
            override fun createIntent(context: android.content.Context, input: String): Intent {
                val intent = super.createIntent(context, input)
                intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                return intent
            }
        }
    ) { uri ->
        if (uri != null) {
            val copiedFile = copyUriToCache(uri)
            val name = copiedFile?.name ?: getFileName(uri) ?: when (activeSlot) {
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

    fun setOnSubmittedListener(listener: (doc1: String, doc2: String, doc3: String, doc4: String) -> Unit) {
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
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
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

        val isViewMode = arguments?.getBoolean("isViewMode", false) ?: false
        if (isViewMode) {
            view.findViewById<TextView>(R.id.tvUploadSheetTitle)?.text = "Review Documents"
            view.findViewById<TextView>(R.id.tvUploadSheetSubtitle)?.text = "Click on any file to view it"

            btnCamera1.visibility = View.GONE
            btnCamera2.visibility = View.GONE
            btnCamera3.visibility = View.GONE
            btnCamera4.visibility = View.GONE

            val d1 = arguments?.getString("doc1Name") ?: "pan_card.jpg"
            val d2 = arguments?.getString("doc2Name") ?: "aadhaar_card.jpg"
            val d3 = arguments?.getString("doc3Name") ?: "bank_statement.pdf"
            val d4 = arguments?.getString("doc4Name") ?: "pay_slip.jpg"

            tvUploadedName1.text = d1
            tvUploadedName2.text = d2
            tvUploadedName3.text = d3
            tvUploadedName4.text = d4

            layoutUnuploaded1.visibility = View.GONE
            layoutUploaded1.visibility = View.VISIBLE
            layoutUnuploaded2.visibility = View.GONE
            layoutUploaded2.visibility = View.VISIBLE
            layoutUnuploaded3.visibility = View.GONE
            layoutUploaded3.visibility = View.VISIBLE
            layoutUnuploaded4.visibility = View.GONE
            layoutUploaded4.visibility = View.VISIBLE

            layoutUploaded1.setOnClickListener { showFullscreenImagePreview(d1) }
            layoutUploaded2.setOnClickListener { showFullscreenImagePreview(d2) }
            layoutUploaded3.setOnClickListener { showFullscreenImagePreview(d3) }
            layoutUploaded4.setOnClickListener { showFullscreenImagePreview(d4) }

            btnSubmit.text = "Close"
            btnSubmit.isEnabled = true
            btnSubmit.alpha = 1.0f
            btnSubmit.setOnClickListener {
                dismiss()
            }
            return
        }

        // --- Doc 1 Action Listeners ---
        layoutUnuploaded1.setOnClickListener {
            activeSlot = 1
            filePickerLauncher.launch("*/*")
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
            filePickerLauncher.launch("*/*")
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
            onSubmitted?.invoke(
                doc1Name ?: "pan_card.jpg",
                doc2Name ?: "aadhaar_card.jpg",
                doc3Name ?: "bank_statement.pdf",
                doc4Name ?: "pay_slip.jpg"
            )
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

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val contentResolver = requireContext().contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val displayName = getFileName(uri) ?: "temp_file_${System.currentTimeMillis()}"
            val folder = File(requireContext().cacheDir, "loandesk").apply { if (!exists()) mkdirs() }
            val cacheFile = File(folder, displayName)
            cacheFile.outputStream().use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            cacheFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
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
                doc1Name = name
                tvUploadedName1.text = name
                layoutUnuploaded1.visibility = View.GONE
                layoutUploaded1.visibility = View.VISIBLE
            }
            2 -> {
                isDoc2Uploaded = true
                doc2Name = name
                tvUploadedName2.text = name
                layoutUnuploaded2.visibility = View.GONE
                layoutUploaded2.visibility = View.VISIBLE
            }
            3 -> {
                isDoc3Uploaded = true
                doc3Name = name
                tvUploadedName3.text = name
                layoutUnuploaded3.visibility = View.GONE
                layoutUploaded3.visibility = View.VISIBLE
            }
            4 -> {
                isDoc4Uploaded = true
                doc4Name = name
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

    private fun showFullscreenImagePreview(fileName: String) {
        val builder = AlertDialog.Builder(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.popup_image_preview, null)
        val imageView = view.findViewById<ImageView>(R.id.ivPreview)
        val closeBtn = view.findViewById<View>(R.id.btnPreviewClose)
        val btnBack = view.findViewById<View>(R.id.btnBack)

        val folder = File(requireContext().cacheDir, "loandesk")
        val file = File(folder, fileName)
        if (file.exists() && file.length() > 0) {
            imageView.load(file)
        } else {
            imageView.setImageResource(R.drawable.ic_cash_proof)
        }

        val dialog = builder.setView(view).create()
        closeBtn?.setOnClickListener {
            dialog.dismiss()
        }
        btnBack?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    companion object {
        fun newInstance(
            item: LoanDeskItem,
            isViewMode: Boolean,
            onSubmitted: (doc1: String, doc2: String, doc3: String, doc4: String) -> Unit
        ): LoanDeskUploadBottomSheet {
            return LoanDeskUploadBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean("isViewMode", isViewMode)
                    putString("itemId", item.id)
                    putString("doc1Name", item.doc1Name)
                    putString("doc2Name", item.doc2Name)
                    putString("doc3Name", item.doc3Name)
                    putString("doc4Name", item.doc4Name)
                }
                setOnSubmittedListener(onSubmitted)
            }
        }
    }
}
