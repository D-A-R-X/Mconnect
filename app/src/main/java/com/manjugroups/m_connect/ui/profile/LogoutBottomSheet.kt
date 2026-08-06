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
    // Guards against double-taps firing the logout (and its navigation) twice.
    private var isLoggingOut = false

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
        // Double-tap guard: a second tap while the first is in flight must not
        // start another logout (which launched LoginActivity twice / stacked).
        if (isLoggingOut) return
        isLoggingOut = true
        isCancelable = false
        view?.findViewById<View>(R.id.btnLogoutConfirm)?.isEnabled = false
        view?.findViewById<View>(R.id.btnLogoutCancel)?.isEnabled = false

        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // Cap all pre-logout network at ~2s so a slow / dead connection
            // can't make the button feel unresponsive. Everything here is
            // best-effort and durable: the tamper event is Room-buffered by
            // enqueue() (flush just tries to send it now; it survives a
            // timeout and flushes on the next session), and the server session
            // expires on its own if api.logout doesn't land.
            // Best-effort pre-logout work while the session is still valid:
            // the mid-shift logout tamper signal (Room-buffered by enqueue, so
            // it survives even if the flush times out) and the push-token
            // unregister. Capped so a dead connection can't hang the UI.
            kotlinx.coroutines.withTimeoutOrNull(2000) {
                if (session.shouldTrackNow) {
                    runCatching {
                        com.manjugroups.m_connect.geotrack.GeoTrackEventQueue.enqueue(
                            ctx,
                            "USER_LOGOUT",
                            com.manjugroups.m_connect.geotrack.GeoTrackDeviceMeta.capture(ctx),
                        )
                        com.manjugroups.m_connect.geotrack.GeoTrackEventQueue.flush(
                            ctx, session = session,
                        )
                    }
                }
                runCatching { PushTokenManager.unregisterCurrentToken(ctx, session) }
            }
            // Free the SERVER session before clearing local — this is what
            // releases the single-device login block so the staff can sign in on
            // another phone. Retry with a generous timeout so a slow logout hop
            // (e.g. the external dialer call the endpoint makes) still lands;
            // otherwise the server session stays active and re-login is refused.
            var serverLoggedOut = false
            repeat(2) { attempt ->
                if (serverLoggedOut) return@repeat
                kotlinx.coroutines.withTimeoutOrNull(8000) {
                    runCatching { api.logout(session.bearerToken) }
                        .getOrNull()
                        ?.let { if (it.success) serverLoggedOut = true }
                }
                if (!serverLoggedOut && attempt < 1) kotlinx.coroutines.delay(400)
            }
            session.clearSession()
            com.manjugroups.m_connect.ui.common.LocalCache.clearAll(ctx)
            OnboardingPrefs(ctx).onboardingCompleted = true
            startActivity(Intent(ctx, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            activity?.finish()
        }
    }

    companion object {
        fun newInstance(): LogoutBottomSheet {
            return LogoutBottomSheet()
        }
    }
}
