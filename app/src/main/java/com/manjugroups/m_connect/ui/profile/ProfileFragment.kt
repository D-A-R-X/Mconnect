package com.manjugroups.m_connect.ui.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.auth.WelcomeActivity
import com.manjugroups.m_connect.databinding.FragmentProfileBinding
import com.manjugroups.m_connect.notifications.PushTokenManager
import kotlinx.coroutines.launch
import java.util.Locale

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager

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

        val name = session.userName?.trim().orEmpty().ifBlank { "Tonald Drump" }
        binding.tvProfileName.text = name
        binding.tvProfileAvatar.text = initialsFor(name)
        binding.tvProfileRole.text = if (session.isAdmin) {
            "Administrator"
        } else {
            "Junior Full Stack Developer"
        }

        binding.tvContactEmail.text = buildEmail(name)
        binding.tvContactAddress.text = "Taman Anggrek"

        bindActions()
    }

    private fun bindActions() {
        binding.btnProfileBack.setOnClickListener {
            parentFragmentManager.popBackStackImmediate()
        }

        binding.rowPersonalData.setOnClickListener { showSoon("Personal Data") }
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

    private fun buildEmail(name: String): String {
        val slug = name
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "")
            .takeIf { it.isNotBlank() }
            ?: "tonald"
        return "$slug@gmail.com"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
