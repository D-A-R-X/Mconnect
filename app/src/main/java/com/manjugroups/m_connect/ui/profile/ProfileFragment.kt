package com.manjugroups.m_connect.ui.profile

import android.Manifest
import android.graphics.Color
import android.os.Build
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
import com.manjugroups.m_connect.databinding.FragmentProfileBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffFullData
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.ui.common.ProfilePhotos
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()

    // Android 13+ runtime notification-permission request, triggered when
    // the user flips Manage Notification ON without it. Granted → finish
    // enabling (register the push token); denied → revert the toggle and
    // point them at system settings.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (_binding == null) return@registerForActivityResult
        if (granted) {
            enableNotifications()
        } else {
            session.isNotificationEnabled = false
            setSwitchCheckedSilently(false)
            Toast.makeText(
                requireContext(),
                "Allow notifications in system settings to turn them on.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

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

        // Optimistic render from cached session
        updateUiFromCache()

        bindActions()
        loadStaffProfile()

        // Listen for updates from the Edit Profile Bottom Sheet
        childFragmentManager.setFragmentResultListener(
            ProfileEditBottomSheet.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            if (bundle.getBoolean(ProfileEditBottomSheet.RESULT_UPDATED, false)) {
                updateUiFromCache()
                loadStaffProfile()
            }
        }
    }

    private fun updateUiFromCache() {
        val cachedName = session.userName?.trim().orEmpty().ifBlank { "User" }
        binding.tvProfileName.text = cachedName
        binding.tvProfileAvatar.text = initialsFor(cachedName)
        binding.tvProfilePhone.text = session.userPhone?.trim().orEmpty().ifBlank { "—" }
        binding.tvAppVersion.text = "v.${BuildConfig.VERSION_NAME}"
        // The toggle is ON only when the user opted in AND the OS still
        // grants notification permission. If permission was revoked in
        // system settings while the flag was on, reconcile the flag so the
        // UI and reality agree.
        val effectiveEnabled = session.isNotificationEnabled &&
            PushTokenManager.hasNotificationPermission(requireContext())
        if (session.isNotificationEnabled && !effectiveEnabled) {
            session.isNotificationEnabled = false
        }
        setSwitchCheckedSilently(effectiveEnabled)

        val url = session.userPhotoUrl
        val resolved = ProfilePhotos.resolve(url)
        if (resolved != null) {
            binding.tvProfileAvatar.visibility = View.GONE
            binding.ivProfileAvatar.visibility = View.VISIBLE
            binding.ivProfileAvatar.load(resolved) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            binding.ivProfileAvatar.visibility = View.GONE
            binding.tvProfileAvatar.visibility = View.VISIBLE
            binding.ivProfileAvatar.setImageDrawable(null)
        }
    }

    private fun loadStaffProfile() {
        val staffId = session.staffId?.takeIf { it.isNotBlank() } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaffDetail(session.bearerToken, staffId)
                if (resp.success && resp.staff != null) {
                    val staff = resp.staff
                    val name = staff.name?.trim().orEmpty().ifBlank {
                        session.userName?.trim().orEmpty().ifBlank { "User" }
                    }
                    binding.tvProfileName.text = name
                    binding.tvProfileAvatar.text = initialsFor(name)
                    binding.tvProfilePhone.text = staff.phone?.trim().orEmpty().ifBlank { "—" }

                    if (!staff.photo.isNullOrBlank()) {
                        session.userPhotoUrl = staff.photo
                        val resolved = ProfilePhotos.resolve(staff.photo)
                        if (resolved != null) {
                            binding.tvProfileAvatar.visibility = View.GONE
                            binding.ivProfileAvatar.visibility = View.VISIBLE
                            binding.ivProfileAvatar.load(resolved) {
                                crossfade(true)
                                transformations(CircleCropTransformation())
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun bindActions() {
        binding.btnProfileBack.setOnClickListener {
            parentFragmentManager.popBackStackImmediate()
        }

        val openEditProfile = View.OnClickListener {
            val bottomSheet = ProfileEditBottomSheet.newInstance()
            bottomSheet.show(childFragmentManager, "ProfileEditBottomSheet")
        }
        binding.profileAvatarContainer.setOnClickListener(openEditProfile)
        binding.rowPersonalData.setOnClickListener(openEditProfile)

        binding.rowLanguage.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .applySmoothTransitions()
                .replace(R.id.fragmentContainer, LanguageFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.rowAppearance.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .applySmoothTransitions()
                .replace(R.id.fragmentContainer, AppearanceFragment())
                .addToBackStack(null)
                .commit()
        }

        bindNotificationToggle()

        binding.rowLogout.setOnClickListener {
            val logoutSheet = LogoutBottomSheet.newInstance()
            logoutSheet.show(childFragmentManager, "LogoutBottomSheet")
        }
    }

    private fun bindNotificationToggle() {
        binding.switchNotification.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Android 13+ needs the runtime permission before we can
                // actually deliver notifications — request it first; the
                // launcher callback finishes the enable on grant.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !PushTokenManager.hasNotificationPermission(requireContext())
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    enableNotifications()
                }
            } else {
                disableNotifications()
            }
        }
    }

    /** Sets the switch state without re-triggering the change listener. */
    private fun setSwitchCheckedSilently(checked: Boolean) {
        if (_binding == null) return
        binding.switchNotification.setOnCheckedChangeListener(null)
        binding.switchNotification.isChecked = checked
        bindNotificationToggle()
    }

    /**
     * Persists the opt-in AND registers this device's push token with the
     * backend so it actually starts receiving notifications. Without the
     * token registration the toggle was cosmetic — the server had no way
     * to target this device.
     */
    private fun enableNotifications() {
        session.isNotificationEnabled = true
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { PushTokenManager.syncCurrentToken(appContext, session) }
        }
        Toast.makeText(requireContext(), "Notifications Enabled", Toast.LENGTH_SHORT).show()
    }

    /**
     * Persists the opt-out AND unregisters the push token so the backend
     * stops sending pushes to this device. The FCM service also suppresses
     * display while the flag is off, covering the unregister round-trip.
     */
    private fun disableNotifications() {
        session.isNotificationEnabled = false
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { PushTokenManager.unregisterCurrentToken(appContext, session) }
        }
        Toast.makeText(requireContext(), "Notifications Disabled", Toast.LENGTH_SHORT).show()
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
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#FFFFFF"), true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
