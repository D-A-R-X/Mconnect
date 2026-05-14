package com.manjugroups.m_connect.ui.profile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.auth.WelcomeActivity
import com.manjugroups.m_connect.databinding.FragmentProfileBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffFullData
import com.manjugroups.m_connect.notifications.PushTokenManager
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch

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
        loadStaffProfile()
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

        binding.tvProfileRole.text = listOfNotNull(
            staff.designation?.takeIf { it.isNotBlank() },
            staff.department?.takeIf { it.isNotBlank() }
        ).joinToString(" • ").ifBlank {
            if (session.isAdmin) "Administrator" else "Staff"
        }

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

    private fun signOut() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                PushTokenManager.unregisterCurrentToken(requireContext(), session)
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
