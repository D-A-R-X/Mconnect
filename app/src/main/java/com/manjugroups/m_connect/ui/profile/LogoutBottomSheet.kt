package com.manjugroups.m_connect.ui.profile

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.LoginActivity
import com.manjugroups.m_connect.auth.OnboardingPrefs
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.notifications.PushTokenManager
import kotlinx.coroutines.launch

class LogoutBottomSheet : BottomSheetDialogFragment() {

    private lateinit var session: SessionManager
    private val api = ApiService.create()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
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
        return inflater.inflate(R.layout.dialog_logout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<View>(R.id.btnLogoutCancel).setOnClickListener {
            dismiss()
        }

        view.findViewById<View>(R.id.btnLogoutConfirm).setOnClickListener {
            performLogout()
        }
    }

    private fun performLogout() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                PushTokenManager.unregisterCurrentToken(requireContext(), session)
            }
            runCatching {
                api.logout(session.bearerToken)
            }
            session.clearSession()
            com.manjugroups.m_connect.ui.common.LocalCache.clearAll(requireContext())
            OnboardingPrefs(requireContext()).onboardingCompleted = true
            startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            requireActivity().finish()
        }
    }

    companion object {
        fun newInstance(): LogoutBottomSheet {
            return LogoutBottomSheet()
        }
    }
}
