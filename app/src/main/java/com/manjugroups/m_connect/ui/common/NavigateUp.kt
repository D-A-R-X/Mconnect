package com.manjugroups.m_connect.ui.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager

/**
 * Reliable back navigation for the top-left blue arrow on every
 * nested fragment.
 *
 * The classic pattern was:
 *
 *     view.findViewById<View>(R.id.btnBack).setOnClickListener {
 *         parentFragmentManager.popBackStack()
 *     }
 *
 * `popBackStack()` is **asynchronous** — it just posts to the
 * FragmentManager's transaction queue. When the main thread is busy
 * (heavy layout inflation, big lists, image loads, the outcome
 * sheet's 2k-line view tree, etc.) the queued pop can sit for so
 * long that the user sees the tap register but no navigation
 * happens; tap again and the second tap looks ignored because the
 * first was still mid-queue. On Android 13+ predictive back this
 * gets worse because the system pops have priority.
 *
 * [navigateUp] flips the call to [FragmentManager.popBackStackImmediate],
 * which runs the pop **synchronously** on the call thread. The
 * back-arrow tap now navigates on the same frame regardless of
 * whatever else the main thread is doing — same code path the
 * system back gesture already uses internally, just without the
 * queueing.
 *
 * Falls back to the activity's [onBackPressedDispatcher] when the
 * immediate pop reports nothing to pop (e.g. root tab fragments
 * that were never pushed via addToBackStack). Dispatcher routes
 * through the same predictive-back machinery the system back
 * gesture uses, so behaviour matches the OS back button exactly.
 */
fun Fragment.navigateUp() {
    val fm = parentFragmentManager
    // Don't call popBackStackImmediate if the manager is mid-save —
    // it throws IllegalStateException. Fall back to the queued pop
    // which the OS will run when state is restored.
    if (fm.isStateSaved) {
        runCatching { fm.popBackStack() }
        return
    }
    val popped = runCatching { fm.popBackStackImmediate() }.getOrDefault(false)
    if (popped) return
    // Nothing on this manager's stack — let the activity handle it
    // (system back dispatcher will dismiss the activity if nothing
    // else handles it). Same behaviour as the hardware back press
    // on a screen that doesn't have a stack entry.
    runCatching {
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }
}
