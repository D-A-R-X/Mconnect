package com.manjugroups.m_connect.ui.common

import androidx.fragment.app.Fragment

/**
 * A notice from a fragment that is safe to call at any time.
 *
 * Screens load over slow networks (dev answers in 5–13 s). Tapping back
 * mid-load detaches the fragment and cancels its coroutine; the error handler
 * then ran `Toast.makeText(requireContext(), …)`, and requireContext() threw
 * "Fragment … not attached to a context", crashing the app (seen on
 * Collections and Loan Desk). Once the fragment is gone there is nobody to
 * show the message to, so this simply does nothing.
 */
fun Fragment.toastSafe(text: CharSequence, duration: Int = AppToast.LENGTH_SHORT) {
    val ctx = context ?: return
    AppToast.makeText(ctx, text, duration).show()
}
