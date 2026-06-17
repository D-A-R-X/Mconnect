package com.manjugroups.m_connect.ui.library.collections

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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.setFragmentResult
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.manjugroups.m_connect.R
import java.io.File

class CollectionCreateBottomSheet : BottomSheetDialogFragment() {

    private val bookings = listOf(
        "Manju Groups Site A - Plot 12",
        "Manju Groups Site A - Plot 45",
        "Manju Groups Site B - Plot 8",
        "Manju Groups Site C - Plot 19"
    )

    private val paymentModes = listOf("UPI", "Cash", "Bank Transfer", "Cheque")

    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var selectedPhotoFile: File? = null

    private lateinit var tvUploadTitle: TextView
    private lateinit var tvUploadSubtitle: TextView
    private lateinit var ivUploadIcon: android.widget.ImageView

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val file = copyUriToTempFile(uri)
            if (file != null) {
                selectedPhotoFile = file
                showImageAttached(file.name)
            }
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
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
                selectedPhotoFile = f
                showImageAttached(f.name)
            }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(R.drawable.bg_bottom_sheet)
                androidx.core.view.ViewCompat.setElevation(it, 0f)
                val behavior = BottomSheetBehavior.from(it)
                val metrics = resources.displayMetrics
                val peekH = (metrics.heightPixels * 0.55f).toInt()
                behavior.peekHeight = peekH
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.skipCollapsed = false
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_collection_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etBooking = view.findViewById<AutoCompleteTextView>(R.id.etBooking)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etAmount)
        val etPaymentMode = view.findViewById<AutoCompleteTextView>(R.id.etPaymentMode)
        val etRefId = view.findViewById<TextInputEditText>(R.id.etRefId)
        val etNotes = view.findViewById<TextInputEditText>(R.id.etNotes)
        val btnSubmit = view.findViewById<MaterialButton>(R.id.btnSubmit)
        val btnCamera = view.findViewById<View>(R.id.btnCamera)
        val btnUploadImage = view.findViewById<View>(R.id.btnUploadImage)

        tvUploadTitle = view.findViewById(R.id.tvUploadTitle)
        tvUploadSubtitle = view.findViewById(R.id.tvUploadSubtitle)
        ivUploadIcon = view.findViewById(R.id.ivUploadIcon)

        // Bookings adapter
        etBooking.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, bookings))
        etBooking.setOnClickListener { etBooking.showDropDown() }

        // Payment Mode adapter
        etPaymentMode.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, paymentModes))
        etPaymentMode.setOnClickListener { etPaymentMode.showDropDown() }

        btnCamera.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                cameraPermLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        btnUploadImage.setOnClickListener {
            galleryLauncher.launch("image/*")
        }

        btnSubmit.setOnClickListener {
            val booking = etBooking.text?.toString()
            val amountStr = etAmount.text?.toString()
            val paymentMode = etPaymentMode.text?.toString()
            val refId = etRefId.text?.toString()
            val notes = etNotes.text?.toString() ?: ""

            if (booking.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Select a Booking/Customer", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val amount = amountStr?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(requireContext(), "Enter a valid Amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (paymentMode.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Select a Payment Mode", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (refId.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Enter Transaction Reference", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Return result
            val bundle = Bundle().apply {
                putDouble("amount", amount)
                putString("booking", booking)
                putString("paymentMode", paymentMode)
                putString("refId", refId)
                putString("notes", notes)
                putString("photoPath", selectedPhotoFile?.absolutePath)
            }
            setFragmentResult(RESULT_KEY, bundle)
            dismissAllowingStateLoss()
        }
    }

    private fun showImageAttached(fileName: String) {
        tvUploadTitle.text = "Image Attached"
        tvUploadSubtitle.text = fileName
        ivUploadIcon.setImageResource(R.drawable.ic_check_circle)
        ivUploadIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor("#12B76A")
        )
    }

    private fun launchCamera() {
        val f = createTempPhotoFile("collection_cam_") ?: run {
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
            clipData = ClipData.newUri(requireContext().contentResolver, "Collection", uri)
        }
        try {
            cameraLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No camera app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTempPhotoFile(prefix: String): File? = try {
        val dir = File(requireContext().cacheDir, "collections").apply { if (!exists()) mkdirs() }
        File.createTempFile(prefix, ".jpg", dir)
    } catch (_: Exception) {
        null
    }

    private fun copyUriToTempFile(uri: Uri): File? = try {
        val f = createTempPhotoFile("collection_pick_")
        if (f != null) {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
        }
        f?.takeIf { it.length() > 0 }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val RESULT_KEY = "CollectionCreated"
        fun newInstance() = CollectionCreateBottomSheet()
    }
}
