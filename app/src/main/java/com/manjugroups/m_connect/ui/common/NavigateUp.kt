package com.manjugroups.m_connect.ui.common

import android.os.SystemClock
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

/** Global last-push clock, shared with [commitOnce], to absorb double taps. */
private var lastDetailPushAt = 0L
private const val DETAIL_PUSH_DEDUPE_MS = 700L

/**
 * The single entry point for pushing a full-screen detail onto the back stack.
 *
 * Why this exists — the old pattern was `replace(fragmentContainer, next)`.
 * `replace()` removes EVERY fragment currently in the container, and all four
 * bottom-tab fragments (Home/HR/Chat/Profile) live in that one container via
 * add()+hide()/show(). So opening any detail tore down the active tab AND the
 * three hidden ones, and pressing back rebuilt all of them from scratch — Home
 * re-inflates every visit card in a loop, so the back tap felt frozen.
 *
 * This pushes with add()+hide() instead: the fragments underneath keep their
 * views, so popping back just re-shows the retained view instantly with no
 * re-inflation and no data reload. It mirrors exactly how the tabs themselves
 * are already toggled, so it introduces no new lifecycle behaviour.
 *
 * IMPORTANT: every back-stack detail navigation must go through here. A single
 * stray `replace(fragmentContainer, …).addToBackStack()` left behind would tear
 * down the retained stack underneath it and bring the lag back for that path.
 */
fun FragmentManager.pushDetail(next: Fragment, allowStateLoss: Boolean = false) {
    if (isDestroyed) return
    if (isStateSaved && !allowStateLoss) return

    // Debounce double taps the same way commitOnce() does — a slow-drawing push
    // invites a second tap that would otherwise stack a duplicate screen.
    val now = SystemClock.uptimeMillis()
    if (now - lastDetailPushAt < DETAIL_PUSH_DEDUPE_MS) return
    lastDetailPushAt = now

    // The fragment currently filling the container (active tab, or the top
    // detail already on the stack). Restricted to container-hosted fragments so
    // open dialogs/bottom sheets are never hidden. Hiding it avoids overdraw;
    // the reversed transaction re-shows it on pop.
    val current = fragments.lastOrNull {
        it.id == R.id.fragmentContainer && it.isAdded && !it.isHidden && it.view != null
    }

    val tx = beginTransaction()
        .setReorderingAllowed(true)
        .applySmoothTransitions()
    if (current != null) tx.hide(current)
    tx.add(R.id.fragmentContainer, next).addToBackStack(null)
    if (allowStateLoss) tx.commitAllowingStateLoss() else tx.commit()
}

/** Fragment-scoped convenience for [FragmentManager.pushDetail]. */
fun Fragment.pushDetail(next: Fragment, allowStateLoss: Boolean = false) {
    parentFragmentManager.pushDetail(next, allowStateLoss)
}

