package com.manjugroups.m_connect.ui.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.manjugroups.m_connect.R

/**
 * Reliable back navigation for the top-left blue arrow on every
 * nested fragment.
 */
fun Fragment.navigateUp() {
    val fm = parentFragmentManager
    // Use the ASYNC popBackStack, never popBackStackImmediate(). Immediate pop
    // rebuilds the destination fragment's whole view tree synchronously on the
    // tap (these screens navigate with replace(), so the previous page is
    // re-inflated from scratch) — that blocks the click handler and makes the
    // back arrow feel frozen. The async pop returns instantly; the rebuild then
    // overlaps the slide transition instead of stalling the tap.
    if (fm.isStateSaved || fm.backStackEntryCount > 0) {
        runCatching { fm.popBackStack() }
        return
    }
    runCatching {
        requireActivity().onBackPressedDispatcher.onBackPressed()
    }
}

/**
 * Applies a premium slide-in/slide-out transition to fragment transactions.
 */
fun FragmentTransaction.applySmoothTransitions(): FragmentTransaction {
    return this.setCustomAnimations(
        R.anim.slide_in_right,
        R.anim.slide_out_left,
        R.anim.slide_in_left,
        R.anim.slide_out_right
    )
}

