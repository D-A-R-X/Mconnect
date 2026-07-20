package com.manjugroups.m_connect.ui.common

import android.os.SystemClock
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction

/**
 * Duplicate-proof replacement for [DialogFragment.show].
 *
 * A sheet that takes a moment to appear invites the user to tap again, and
 * every one of those taps used to open another copy — the same form stacked
 * three or four deep, each needing its own dismiss. Debouncing the click alone
 * doesn't fix it: the problem is on the show side, so that is where the guard
 * belongs. Any caller, any number of taps, at most one instance.
 *
 * Two checks, because they cover different windows:
 *
 *  - [FragmentManager.findFragmentByTag] catches the case where the sheet is
 *    already on screen. It can't catch rapid taps, because `show()` commits
 *    asynchronously and the tag isn't queryable until that commit runs.
 *  - The per-tag timestamp covers exactly that gap, absorbing repeat taps in
 *    the frames before the first transaction executes.
 *
 * Also a no-op once state is saved, where `show()` would throw
 * IllegalStateException.
 */
private const val DUPLICATE_WINDOW_MS = 700L

/** Tag → last show time. Bounded by the number of distinct sheet tags. */
private val lastShownAt = mutableMapOf<String, Long>()

fun DialogFragment.showOnce(manager: FragmentManager, tag: String) {
    if (manager.isStateSaved || manager.isDestroyed) return
    if (manager.findFragmentByTag(tag) != null) return

    val now = SystemClock.uptimeMillis()
    if (now - (lastShownAt[tag] ?: 0L) < DUPLICATE_WINDOW_MS) return
    lastShownAt[tag] = now

    show(manager, tag)
}

/** Last back-stack push, for [commitOnce]. */
private var lastPushAt = 0L

/**
 * [FragmentTransaction.commit] for screens pushed onto the back stack, with the
 * same duplicate protection [showOnce] gives sheets.
 *
 * Full-screen forms have the same failure as sheets: the push commits
 * asynchronously, so a second tap landing before the new screen draws hits the
 * old one and pushes a second copy. The user then has to press back once per
 * accidental tap.
 *
 * The window is global rather than per-destination because that is the actual
 * signal — two *different* screens opening within a few hundred milliseconds is
 * not something a person does, while double-tapping one slow button is. An
 * uncommitted transaction is just a discarded builder, so dropping it is free.
 */
fun FragmentTransaction.commitOnce() {
    val now = SystemClock.uptimeMillis()
    if (now - lastPushAt < DUPLICATE_WINDOW_MS) return
    lastPushAt = now
    commit()
}
