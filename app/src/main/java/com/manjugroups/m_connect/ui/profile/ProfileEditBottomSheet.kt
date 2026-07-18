package com.manjugroups.m_connect.ui.profile

import android.app.Dialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.SetProfilePhotoRequest
import com.manjugroups.m_connect.network.UpdateMyProfileRequest
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import com.manjugroups.m_connect.ui.common.showOnce

class ProfileEditBottomSheet : BottomSheetDialogFragment() {

    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private lateinit var etEditName: EditText
    private lateinit var etEditPhone: EditText
    private lateinit var etEditMail: EditText
    private lateinit var tvEditDepartment: TextView
    private lateinit var tvEditDesignation: TextView
    private lateinit var btnChangeProfilePic: View
    private lateinit var ivProfilePicPreview: ImageView
    private lateinit var ivCameraIcon: ImageView
    private lateinit var btnSaveProfile: View

    private var uploadedPhotoPath: String? = null

    private val pickProfileImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) launchCropDialog(uri)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // ADJUST_RESIZE keeps the focused input above the soft keyboard;
        // without it the keyboard covers the lower fields and the submit
        // button with no way to scroll them back into view.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
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
    ): View? {
        return inflater.inflate(R.layout.dialog_profile_edit, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        etEditName = view.findViewById(R.id.etEditName)
        etEditPhone = view.findViewById(R.id.etEditPhone)
        etEditMail = view.findViewById(R.id.etEditMail)
        tvEditDepartment = view.findViewById(R.id.tvEditDepartment)
        tvEditDesignation = view.findViewById(R.id.tvEditDesignation)
        btnChangeProfilePic = view.findViewById(R.id.btnChangeProfilePic)
        ivProfilePicPreview = view.findViewById(R.id.ivProfilePicPreview)
        ivCameraIcon = view.findViewById(R.id.ivCameraIcon)
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile)

        // Optimistic load from local session
        etEditName.setText(session.userName.orEmpty())
        etEditPhone.setText(session.userPhone.orEmpty())
        uploadedPhotoPath = session.userPhotoUrl
        applyPhotoPreview(uploadedPhotoPath)

        btnChangeProfilePic.setOnClickListener {
            pickProfileImage.launch(arrayOf("image/*"))
        }

        btnSaveProfile.setOnClickListener {
            saveProfileDetails()
        }

        loadStaffProfile()
    }

    private fun applyPhotoPreview(path: String?) {
        val resolved = ProfilePhotos.resolve(path)
        if (resolved != null) {
            ivProfilePicPreview.visibility = View.VISIBLE
            ivProfilePicPreview.load(resolved) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
            ivCameraIcon.visibility = View.GONE
        } else {
            ivProfilePicPreview.visibility = View.GONE
            ivCameraIcon.visibility = View.VISIBLE
        }
    }

    private fun launchCropDialog(uri: Uri) {
        val dialog = ProfilePhotoCropDialog()
        dialog.setSource(uri)
        dialog.setListener { bitmap -> uploadProfilePhoto(bitmap) }
        dialog.showOnce(childFragmentManager, "ProfilePhotoCrop")
    }

    private fun uploadProfilePhoto(bitmap: Bitmap) {
        viewLifecycleOwner.lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = ByteArrayOutputStream().also {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
                    }.toByteArray()
                    val mime = "image/jpeg"
                    val storageResp = api.uploadStorageFile(
                        token = session.bearerToken,
                        body = bytes.toRequestBody(mime.toMediaTypeOrNull())
                    )
                    val storageId = storageResp.storageId
                        ?: error(storageResp.error ?: "Upload failed")
                    val photoResp = api.setMyProfilePhoto(
                        token = session.bearerToken,
                        body = SetProfilePhotoRequest(storageId = storageId)
                    )
                    if (!photoResp.success) {
                        error(photoResp.error ?: "Could not set photo")
                    }
                    val canonical = photoResp.photo?.storageId
                        ?: photoResp.staff?.photo
                        ?: photoResp.photo?.url
                        ?: error("Upload succeeded but no photo reference returned")
                    true to canonical
                }.getOrElse { err -> false to (err.message ?: "Upload error") }
            }
            if (ok) {
                session.userPhotoUrl = msg
                uploadedPhotoPath = msg
                applyPhotoPreview(msg)
                Toast.makeText(requireContext(), "Profile photo updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Couldn't update photo: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadStaffProfile() {
        val staffId = session.staffId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaffDetail(session.bearerToken, staffId)
                if (resp.success && resp.staff != null) {
                    val staff = resp.staff
                    etEditName.setText(staff.name.orEmpty())
                    etEditPhone.setText(staff.phone.orEmpty())
                    etEditMail.setText(staff.email.orEmpty())
                    tvEditDepartment.text = staff.department.orEmpty().ifBlank { "Sales Department" }
                    tvEditDesignation.text = staff.designation.orEmpty().ifBlank { "Field Officer" }
                    if (!staff.photo.isNullOrBlank()) {
                        session.userPhotoUrl = staff.photo
                        uploadedPhotoPath = staff.photo
                        applyPhotoPreview(staff.photo)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveProfileDetails() {
        val newName = etEditName.text.toString().trim()

        if (newName.isEmpty()) {
            Toast.makeText(requireContext(), "Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveProfile.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val (ok, errorMsg) = withContext(Dispatchers.IO) {
                runCatching {
                    val resp = api.updateMyProfile(
                        token = session.bearerToken,
                        body = UpdateMyProfileRequest(
                            id = session.staffId,
                            name = newName
                        )
                    )
                    if (resp.success) {
                        true to null
                    } else {
                        false to (resp.error ?: "Update failed")
                    }
                }.getOrElse { err -> false to (err.message ?: "Network error") }
            }

            btnSaveProfile.isEnabled = true
            if (ok) {
                session.userName = newName
                Toast.makeText(requireContext(), "Profile updated successfully", Toast.LENGTH_SHORT).show()
                setFragmentResult(REQUEST_KEY, bundleOf(RESULT_UPDATED to true))
                dismiss()
            } else {
                Toast.makeText(requireContext(), "Error: $errorMsg", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val REQUEST_KEY = "profile_edit_request"
        const val RESULT_UPDATED = "profile_updated"

        fun newInstance(): ProfileEditBottomSheet {
            return ProfileEditBottomSheet()
        }
    }
}
