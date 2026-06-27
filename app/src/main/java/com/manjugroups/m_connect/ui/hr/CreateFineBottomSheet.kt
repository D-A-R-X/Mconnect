package com.manjugroups.m_connect.ui.hr

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.SheetCreateFineBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreateFineBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCreateFineBinding? = null
    private val binding get() = _binding!!

    private val api by lazy { ApiService.create() }
    private val session by lazy { SessionManager(requireContext()) }
    private val staffList = mutableListOf<StaffData>()
    private var selectedStaff: StaffData? = null

    private var pendingImageFile: File? = null
    private var pendingLocalPhotoFile: File? = null
    private var pendingLocalPhotoUri: android.net.Uri? = null
    private var uploadedPhotoId: String? = null

    interface OnFineCreatedListener {
        fun onFineCreated(name: String, department: String, fineType: String, amount: Double, dateStr: String, photo: String?)
    }

    private var listener: OnFineCreatedListener? = null

    fun setOnFineCreatedListener(listener: OnFineCreatedListener) {
        this.listener = listener
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingLocalPhotoUri = uri
            pendingLocalPhotoFile = null
            binding.ivUploadedPreview.imageTintList = null // Clear blue tint
            binding.ivUploadedPreview.load(uri) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
            binding.tvUploadTitle.text = "Photo Selected"
        }
    }

    private val capturePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val file = pendingImageFile
            if (file != null && file.exists()) {
                pendingLocalPhotoFile = file
                pendingLocalPhotoUri = null
                binding.ivUploadedPreview.imageTintList = null // Clear blue tint
                binding.ivUploadedPreview.load(file) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
                binding.tvUploadTitle.text = "Photo Captured"
            }
        } else {
            Toast.makeText(requireContext(), "Camera capture cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCamera()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required to capture photos", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                // Fix background appearing at bottom/corners
                bottomSheet.setBackgroundResource(android.R.color.transparent)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                
                // No drop shadow needed
                bottomSheet.elevation = 0f
            }
        }
        return dialog
    }

    override fun getTheme(): Int {
        return R.style.CustomCameraBottomSheetTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCreateFineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Handle navigation bar bottom padding dynamically
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(
                binding.root.paddingLeft,
                binding.root.paddingTop,
                binding.root.paddingRight,
                sysBars.bottom + (24 * resources.displayMetrics.density).toInt()
            )
            insets
        }

        loadStaffList()

        binding.btnSelectEmployee.setOnClickListener {
            showEmployeePicker()
        }

        binding.btnCamera.setOnClickListener {
            checkCameraPermissionAndOpen()
        }

        binding.btnUploadFile.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnSubmitFine.setOnClickListener {
            submitFine()
        }
    }

    private fun checkCameraPermissionAndOpen() {
        val permission = Manifest.permission.CAMERA
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            requestCameraPermissionLauncher.launch(permission)
        }
    }

    private fun openCamera() {
        try {
            val dir = File(requireContext().cacheDir, "punch_photos")
            if (!dir.exists()) dir.mkdirs()
            val file = File.createTempFile("fine_photo_", ".jpg", dir)
            pendingImageFile = file
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            capturePhotoLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to open camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetSubmitButton() {
        binding.btnSubmitFine.isEnabled = true
        binding.btnSubmitFine.text = "Create It"
    }

    private suspend fun uploadBytesToServer(bytes: ByteArray): String? {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val mime = "image/jpeg"
                api.uploadStorageFile(
                    token = session.bearerToken,
                    body = bytes.toRequestBody(mime.toMediaTypeOrNull())
                )
            }
        }
        var storageId: String? = null
        result.onSuccess { resp ->
            if (resp.success && resp.storageId != null) {
                storageId = resp.storageId
            } else {
                Toast.makeText(requireContext(), "Upload failed: ${resp.error ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { err ->
            Toast.makeText(requireContext(), "Upload failed: ${err.message}", Toast.LENGTH_SHORT).show()
        }
        return storageId
    }

    private fun loadStaffList() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaff(session.bearerToken, status = "active")
                if (resp.success && !resp.staff.isNullOrEmpty()) {
                    staffList.clear()
                    staffList.addAll(resp.staff)
                }
            } catch (_: Exception) {
                // Fallback to local items if network count fails
            }
        }
    }

    private fun showEmployeePicker() {
        val fallbackStaff = listOf(
            StaffData(id = "1", name = "Mari Muthu.R", phone = null, role = null, designation = null, status = "active", employeeId = null, department = "Sales Department", photo = null),
            StaffData(id = "2", name = "Sudalai Muthu.R", phone = null, role = null, designation = null, status = "active", employeeId = null, department = "Sales Department", photo = null)
        )
        
        val currentList = if (staffList.isNotEmpty()) staffList else fallbackStaff
        val picker = EmployeeSelectBottomSheet().apply {
            this.staffList = ArrayList(currentList)
            this.selectedStaff = this@CreateFineBottomSheet.selectedStaff
            this.onEmployeeSelected = { staff ->
                this@CreateFineBottomSheet.selectedStaff = staff
                binding.tvSelectedEmployee.text = staff.name
                binding.tvSelectedEmployee.setTextColor(Color.parseColor("#1D2939"))
            }
        }
        picker.show(parentFragmentManager, "EmployeeSelect")
    }

    private fun submitFine() {
        val staffName = selectedStaff?.name ?: binding.tvSelectedEmployee.text.toString()
        if (staffName == "Select Employee" || (selectedStaff == null && staffList.isNotEmpty())) {
            Toast.makeText(requireContext(), "Please select an employee", Toast.LENGTH_SHORT).show()
            return
        }

        val fineType = binding.etFineType.text.toString().trim()
        if (fineType.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter fine type", Toast.LENGTH_SHORT).show()
            return
        }

        val amountStr = binding.etFineAmount.text.toString().trim()
        if (amountStr.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter amount", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(requireContext(), "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val department = selectedStaff?.department ?: "Sales Department"
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())

        // Disable views during upload/submit
        binding.btnSubmitFine.isEnabled = false
        binding.btnSubmitFine.text = "Submitting..."

        fun completeCreation(photoId: String?) {
            listener?.onFineCreated(
                name = staffName,
                department = department,
                fineType = fineType,
                amount = amount,
                dateStr = dateStr,
                photo = photoId ?: selectedStaff?.photo
            )
            dismiss()
        }

        val file = pendingLocalPhotoFile
        val uri = pendingLocalPhotoUri

        if (file != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    binding.btnSubmitFine.text = "Uploading Photo..."
                    val compressedFile = withContext(Dispatchers.IO) {
                        compressImage(file)
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        if (compressedFile.exists()) compressedFile.readBytes() else null
                    }
                    // Clean up temp compressed file
                    try {
                        if (compressedFile.exists() && compressedFile != file) compressedFile.delete()
                    } catch (_: Exception) {}

                    if (bytes == null) {
                        Toast.makeText(requireContext(), "Failed to read photo", Toast.LENGTH_SHORT).show()
                        resetSubmitButton()
                        return@launch
                    }
                    val storageId = uploadBytesToServer(bytes)
                    if (storageId != null) {
                        completeCreation(storageId)
                    } else {
                        resetSubmitButton()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Submission failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    resetSubmitButton()
                }
            }
        } else if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    binding.btnSubmitFine.text = "Uploading Photo..."
                    val tempFile = withContext(Dispatchers.IO) {
                        val file = File(requireContext().cacheDir, "temp_uri_${System.currentTimeMillis()}.jpg")
                        requireContext().contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        file
                    }
                    val compressedFile = withContext(Dispatchers.IO) {
                        compressImage(tempFile)
                    }
                    val bytes = withContext(Dispatchers.IO) {
                        if (compressedFile.exists()) compressedFile.readBytes() else null
                    }
                    // Clean up temp files
                    try {
                        if (tempFile.exists()) tempFile.delete()
                        if (compressedFile.exists() && compressedFile != tempFile) compressedFile.delete()
                    } catch (_: Exception) {}

                    if (bytes == null) {
                        Toast.makeText(requireContext(), "Failed to read photo", Toast.LENGTH_SHORT).show()
                        resetSubmitButton()
                        return@launch
                    }
                    val storageId = uploadBytesToServer(bytes)
                    if (storageId != null) {
                        completeCreation(storageId)
                    } else {
                        resetSubmitButton()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Submission failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    resetSubmitButton()
                }
            }
        } else {
            completeCreation(null)
        }
    }

    private fun compressImage(source: File): File {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(source.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return source

            val maxEdge = 1080
            var sample = 1
            var w = bounds.outWidth
            var h = bounds.outHeight
            while (w / sample > maxEdge * 2 || h / sample > maxEdge * 2) {
                sample *= 2
            }

            val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
            val decoded = android.graphics.BitmapFactory.decodeFile(source.absolutePath, decodeOpts) ?: return source

            val rotated = applyExifRotation(source, decoded)

            val scale = minOf(
                1f,
                maxEdge.toFloat() / rotated.width.toFloat(),
                maxEdge.toFloat() / rotated.height.toFloat()
            )
            val finalBitmap = if (scale < 1f) {
                val scaledW = (rotated.width * scale).toInt().coerceAtLeast(1)
                val scaledH = (rotated.height * scale).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(rotated, scaledW, scaledH, true).also {
                    if (it !== rotated) rotated.recycle()
                }
            } else rotated

            val out = java.io.ByteArrayOutputStream()
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, out)
            finalBitmap.recycle()

            val target = File(source.parentFile, "fine_compressed_${System.currentTimeMillis()}.jpg")
            java.io.FileOutputStream(target).use { it.write(out.toByteArray()) }
            target
        } catch (_: Exception) {
            source
        }
    }

    private fun applyExifRotation(source: File, bitmap: android.graphics.Bitmap): android.graphics.Bitmap {
        val degrees = try {
            val exif = android.media.ExifInterface(source.absolutePath)
            when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }
        if (degrees == 0f) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = CreateFineBottomSheet()
    }
}
