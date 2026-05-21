package com.manjugroups.m_connect.ui.profile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.auth.WelcomeActivity
import com.manjugroups.m_connect.databinding.FragmentProfileBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.SetProfilePhotoRequest
import com.manjugroups.m_connect.network.StaffFullData
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Optimistic render from cached session, then refresh from API.
        val cachedName = session.userName?.trim().orEmpty().ifBlank { "User" }
        binding.tvProfileName.text = cachedName
        binding.tvProfileAvatar.text = initialsFor(cachedName)
        binding.tvProfileRole.text = ""
        binding.tvContactEmail.text = session.userPhone?.takeIf { it.isNotBlank() } ?: "—"
        binding.tvContactAddress.text = "—"

        bindActions()
        setupScrollAnimation()
        applyCachedAvatar()
        loadStaffProfile()
    }

    private val pickProfileImage = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) launchCropDialog(uri)
    }

    private fun launchCropDialog(uri: Uri) {
        val dialog = ProfilePhotoCropDialog()
        dialog.setSource(uri)
        dialog.setListener { bitmap -> uploadProfilePhoto(bitmap) }
        dialog.show(childFragmentManager, "ProfilePhotoCrop")
    }

    private fun setupScrollAnimation() {
        val density = resources.displayMetrics.density
        val fadeDistance = 220f * density
        val compactThreshold = 180f * density
        binding.btnCompactBack.setOnClickListener {
            parentFragmentManager.popBackStackImmediate()
        }
        binding.profileContentScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (_binding == null) return@setOnScrollChangeListener
            val alpha = (1f - scrollY / fadeDistance).coerceIn(0f, 1f)
            val parallax = scrollY * 0.35f
            binding.profileAvatarContainer.translationY = -parallax
            binding.profileAvatarContainer.alpha = alpha
            val scale = (1f - scrollY / (fadeDistance * 1.6f)).coerceIn(0.7f, 1f)
            binding.profileAvatarContainer.scaleX = scale
            binding.profileAvatarContainer.scaleY = scale
            binding.tvProfileName.alpha = alpha
            binding.tvProfileRole.alpha = alpha

            // Compact sticky header crossfades in once the big avatar has
            // scrolled past about its own height; linear ramp over the next
            // 80dp so the swap doesn't pop.
            val compactProgress =
                ((scrollY - compactThreshold) / (80f * density)).coerceIn(0f, 1f)
            binding.compactHeader.alpha = compactProgress
            binding.compactHeader.visibility =
                if (compactProgress <= 0f) View.GONE else View.VISIBLE
        }
    }

    private fun applyCompactAvatar(url: String?) {
        val initials = binding.tvProfileAvatar.text?.toString().orEmpty()
        binding.tvCompactAvatar.text = initials
        val resolved = ProfilePhotos.resolve(url)
        if (resolved == null) {
            binding.ivCompactAvatar.setImageDrawable(null)
            binding.tvCompactAvatar.visibility = View.VISIBLE
            return
        }
        binding.tvCompactAvatar.visibility = View.GONE
        binding.ivCompactAvatar.load(resolved) {
            crossfade(true)
            transformations(CircleCropTransformation())
        }
    }

    private fun applyCachedAvatar() {
        val url = session.userPhotoUrl
        val resolved = ProfilePhotos.resolve(url)
        if (resolved != null) loadAvatarUrl(resolved)
        else binding.ivProfileAvatar.setImageDrawable(null)
        applyCompactAvatar(url)
    }

    private fun loadAvatarUrl(url: String) {
        // Upload is a square 1:1 image. Profile tile shows it as a rounded
        // square (clipped by the container's outline). Chat/header avatars
        // elsewhere apply their own CircleCropTransformation for the circular
        // look — same source bitmap, different mask per surface.
        binding.ivProfileAvatar.load(ProfilePhotos.resolve(url) ?: url) {
            crossfade(true)
        }
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
                    // Prefer the storageId — that's what web/iOS read out of
                    // staff.photo, so storing it keeps the cross-device source
                    // of truth consistent and lets ProfilePhotos.resolve build
                    // a fresh serve URL against the current BASE_URL on every
                    // render. Fall back to the URL only if the response is
                    // missing the storageId for some reason.
                    val canonical = photoResp.photo?.storageId
                        ?: photoResp.staff?.photo
                        ?: photoResp.photo?.url
                        ?: error("Upload succeeded but no photo reference returned")
                    true to canonical
                }.getOrElse { err -> false to (err.message ?: "Upload error") }
            }
            if (_binding == null) return@launch
            if (ok) {
                session.userPhotoUrl = msg
                loadAvatarUrl(msg)
                applyCompactAvatar(msg)
                Toast.makeText(requireContext(), "Profile photo updated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Couldn't update photo: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadStaffProfile() {
        val staffId = session.staffId?.takeIf { it.isNotBlank() } ?: run {
            binding.tvProfileRole.text =
                if (session.isAdmin) "Administrator" else "Staff"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
            binding.profileContentScroll.visibility = View.GONE
            try {
                val resp = api.getStaffDetail(session.bearerToken, staffId)
                if (resp.success) resp.staff?.let(::renderStaff)
            } catch (_: Exception) {
                // Keep cached values; surface a soft fallback role string.
                if (_binding != null) {
                    binding.tvProfileRole.text =
                        if (session.isAdmin) "Administrator" else "Staff"
                }
            } finally {
                SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
                binding.profileContentScroll.visibility = View.VISIBLE
            }
        }
    }

    private fun renderStaff(staff: StaffFullData) {
        if (_binding == null) return
        val name = staff.name?.trim().orEmpty().ifBlank {
            session.userName?.trim().orEmpty().ifBlank { "User" }
        }
        binding.tvProfileName.text = name
        binding.tvProfileAvatar.text = initialsFor(name)

        // Only update when the server returned a photo. Never clear here — a
        // freshly-uploaded photo can race with the next getStaffDetail() and
        // get wiped if the staff doc hasn't been re-read yet.
        staff.photo?.takeIf { it.isNotBlank() }?.let { photo ->
            session.userPhotoUrl = photo
            loadAvatarUrl(photo)
            applyCompactAvatar(photo)
        }

        binding.tvProfileRole.text = listOfNotNull(
            staff.designation?.takeIf { it.isNotBlank() },
            staff.department?.takeIf { it.isNotBlank() }
        ).joinToString(" • ").ifBlank {
            if (session.isAdmin) "Administrator" else "Staff"
        }

        // Refresh cached reporting officer — keeps leave/permission apply
        // routing in sync if the user is reassigned to a different manager.
        session.reportingToId = staff.reportingTo
        session.reportingToName = staff.reportingToName

        binding.tvContactEmail.text = staff.email?.takeIf { it.isNotBlank() }
            ?: staff.phone?.takeIf { it.isNotBlank() }
            ?: "—"

        val locationParts = listOfNotNull(
            staff.address?.takeIf { it.isNotBlank() },
            staff.city?.takeIf { it.isNotBlank() },
            staff.state?.takeIf { it.isNotBlank() }
        )
        binding.tvContactAddress.text = locationParts
            .joinToString(", ")
            .ifBlank {
                staff.branch?.takeIf { it.isNotBlank() }
                    ?: staff.company?.takeIf { it.isNotBlank() }
                    ?: "—"
            }
    }

    private fun bindActions() {
        binding.btnProfileBack.setOnClickListener {
            parentFragmentManager.popBackStackImmediate()
        }
        val photoPicker = View.OnClickListener {
            pickProfileImage.launch(arrayOf("image/*"))
        }
        binding.profileAvatarContainer.setOnClickListener(photoPicker)
        binding.btnProfileAvatarEdit.setOnClickListener(photoPicker)
        // Long-press the avatar to surface the remove option. Keeping the
        // single-tap behaviour the same (opens the picker) avoids regressing
        // the existing photo-change UX while still wiring delete-my-photo.
        val photoChooser = View.OnLongClickListener {
            showPhotoOptionsDialog()
            true
        }
        binding.profileAvatarContainer.setOnLongClickListener(photoChooser)
        binding.btnProfileAvatarEdit.setOnLongClickListener(photoChooser)

        binding.rowPersonalData.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, ProfileEditFragment())
                .addToBackStack(null)
                .commit()
        }
        binding.rowOfficeAssets.setOnClickListener { showSoon("Office Assets") }
        binding.rowPayrollTax.setOnClickListener { showSoon("Payroll & Tax") }
        binding.rowChangePassword.setOnClickListener { showSoon("Change Password") }
        binding.rowFaqHelp.setOnClickListener { showSoon("FAQ and Help") }

        binding.rowVersioning.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "Mconnect ${BuildConfig.VERSION_NAME}",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.rowLogout.setOnClickListener {
            signOut()
        }
    }

    private fun showPhotoOptionsDialog() {
        val hasPhoto = !session.userPhotoUrl.isNullOrBlank()
        val options = if (hasPhoto) {
            arrayOf("Change photo", "Remove photo")
        } else {
            arrayOf("Change photo")
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Profile photo")
            .setItems(options) { _, which ->
                when {
                    which == 0 -> pickProfileImage.launch(arrayOf("image/*"))
                    hasPhoto && which == 1 -> removeProfilePhoto()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeProfilePhoto() {
        viewLifecycleOwner.lifecycleScope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                runCatching {
                    val resp = api.deleteMyProfilePhoto(session.bearerToken)
                    if (!resp.success) error(resp.error ?: "Could not remove photo")
                    true to "Photo removed"
                }.getOrElse { err -> false to (err.message ?: "Network error") }
            }
            if (_binding == null) return@launch
            if (ok) {
                session.userPhotoUrl = null
                binding.ivProfileAvatar.setImageDrawable(null)
                applyCompactAvatar(null)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Couldn't remove photo: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun signOut() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                PushTokenManager.unregisterCurrentToken(requireContext(), session)
            }
            // Invalidate the bearer token server-side before wiping the local
            // session — otherwise the token stays valid for its full TTL even
            // though the device has signed out.
            runCatching {
                api.logout(session.bearerToken)
            }

            session.clearSession()
            startActivity(Intent(requireContext(), WelcomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            requireActivity().finish()
        }
    }

    private fun showSoon(label: String) {
        Toast.makeText(requireContext(), "$label coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun initialsFor(name: String): String {
        val parts = name.split(" ").filter { it.isNotBlank() }.take(2)
        val initials = parts.joinToString("") { part ->
            part.first().uppercaseChar().toString()
        }
        return initials.ifBlank { "TD" }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTabBarVisible(false)
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#795FFC"), false)
        if (_binding != null) loadStaffProfile()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
        _binding = null
    }
}
