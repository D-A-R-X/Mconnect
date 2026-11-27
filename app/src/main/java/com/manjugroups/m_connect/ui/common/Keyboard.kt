package com.manjugroups.m_connect.ui.common

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView

/**
 * One place to close the soft keyboard (MMS-288).
 *
 * The bug: after searching and picking a result, the keyboard stayed up on the
 * next screen. pushDetail() hides (not removes) the search screen, so its
 * focused search box kept focus and the IME stayed attached; picker sheets
 * dismissed with the keyboard still open over the form behind them.
 *
 * Rule of thumb for callers: close the keyboard on SELECT, NAVIGATION or
 * SUBMIT only. Never while the user is typing (search-as-you-type keeps it),
 * and never on screens that open with the keyboard on purpose (chat composer,
 * OTP, a form's next field).
 */
object Keyboard {

    /** Hides the IME attached to [view]'s window and drops focus inside [view]. */
    fun hide(view: View?) {
        view ?: return
        view.findFocus()?.clearFocus()
        val token = view.windowToken ?: view.rootView?.windowToken ?: return
        imm(view.context)?.hideSoftInputFromWindow(token, 0)
    }

    /** Hides the IME for the whole activity window and clears its focused view. */
    fun hide(activity: Activity?) {
        activity ?: return
        val focused = activity.currentFocus
        focused?.clearFocus()
        val token = focused?.windowToken ?: activity.window?.decorView?.windowToken ?: return
        imm(activity)?.hideSoftInputFromWindow(token, 0)
    }

    private fun imm(context: Context): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
}

/** Closes the keyboard and clears focus inside this view's window. */
fun View.hideKeyboard() = Keyboard.hide(this)

/** Closes the keyboard for this screen (its view first, then the activity window). */
fun Fragment.hideKeyboard() {
    val v = view
    if (v != null) Keyboard.hide(v) else Keyboard.hide(activity)
}

/**
 * Closes the keyboard when the user starts dragging this list, so search
 * results can be scrolled with the full screen. Typing is unaffected.
 */
fun RecyclerView.hideKeyboardOnDrag() {
    addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) Keyboard.hide(recyclerView)
        }
    })
}
