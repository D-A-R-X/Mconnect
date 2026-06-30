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
    if (fm.isStateSaved) {
        runCatching { fm.popBackStack() }
        return
    }
    val popped = runCatching { fm.popBackStackImmediate() }.getOrDefault(false)
    if (popped) return
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

