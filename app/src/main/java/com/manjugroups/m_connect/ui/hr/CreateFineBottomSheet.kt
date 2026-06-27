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
            uploadPhotoUri(uri)
        }
    }

    private val capturePhotoLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            val file = pendingImageFile
            if (file != null && file.exists()) {
                uploadPhotoFile(file)
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

    private fun uploadPhotoUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null) {
                    Toast.makeText(requireContext(), "Failed to read image", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                performUpload(bytes)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun uploadPhotoFile(file: File) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    file.readBytes()
                }
                performUpload(bytes)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Upload failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun performUpload(bytes: ByteArray) {
        val toast = Toast.makeText(requireContext(), "Uploading photo...", Toast.LENGTH_SHORT)
        toast.show()
        
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val mime = "image/jpeg"
                api.uploadStorageFile(
                    token = session.bearerToken,
                    body = bytes.toRequestBody(mime.toMediaTypeOrNull())
                )
            }
        }
        
        toast.cancel()
        result.onSuccess { resp ->
            if (resp.success && resp.storageId != null) {
                uploadedPhotoId = resp.storageId
                val resolvedUrl = ProfilePhotos.resolve(resp.storageId)
                binding.ivUploadedPreview.imageTintList = null // Clear blue tint
                binding.ivUploadedPreview.load(resolvedUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
                binding.tvUploadTitle.text = "Photo Uploaded Successfully"
                Toast.makeText(requireContext(), "Photo uploaded successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Upload failed: ${resp.error ?: "Unknown error"}", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { err ->
            Toast.makeText(requireContext(), "Upload failed: ${err.message}", Toast.LENGTH_SHORT).show()
        }
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
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_employee_picker, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val etSearch = dialogView.findViewById<android.widget.EditText>(R.id.etSearch)
        val llContainer = dialogView.findViewById<android.widget.LinearLayout>(R.id.llEmployeeContainer)

        fun populateList(query: String) {
            llContainer.removeAllViews()
            val filtered = if (query.isEmpty()) {
                currentList
            } else {
                currentList.filter { (it.name ?: "").contains(query, ignoreCase = true) }
            }

            filtered.forEach { staff ->
                val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_picker_employee, llContainer, false)
                val tvName = row.findViewById<android.widget.TextView>(R.id.tvName)
                val rbSelect = row.findViewById<android.widget.RadioButton>(R.id.rbSelect)

                tvName.text = staff.name ?: "Unknown Employee"
                rbSelect.isChecked = (selectedStaff?.id == staff.id)

                row.setOnClickListener {
                    selectedStaff = staff
                    binding.tvSelectedEmployee.text = staff.name
                    binding.tvSelectedEmployee.setTextColor(Color.parseColor("#1D2939"))
                    dialog.dismiss()
                }
                llContainer.addView(row)
            }
        }

        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        populateList("")
        dialog.show()
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

        listener?.onFineCreated(
            name = staffName,
            department = department,
            fineType = fineType,
            amount = amount,
            dateStr = dateStr,
            photo = uploadedPhotoId ?: selectedStaff?.photo
        )

        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = CreateFineBottomSheet()
    }
}
