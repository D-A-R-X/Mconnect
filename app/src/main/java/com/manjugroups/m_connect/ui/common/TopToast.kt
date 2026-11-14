package com.manjugroups.m_connect.ui.common

import android.app.Activity

/**
 * Former custom top-of-screen pill. Kept as an entry point so existing calls
 * keep working; it now shows the app's one capsule notice ([Floater]).
 */
object TopToast {

    @Suppress("UNUSED_PARAMETER")
    fun show(activity: Activity, message: String, durationMs: Long = 0L) {
        Floater.show(message)
    }

    fun dismiss() = Unit
}
