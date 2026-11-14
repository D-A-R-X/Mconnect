package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.widget.Toast

/**
 * Drop-in stand-in for android.widget.Toast. Screens import it as
 * `import com.manjugroups.m_connect.ui.common.AppToast as Toast`, so every
 * existing `Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()` now shows
 * the app's top capsule ([Floater]), with HTTP/network text cleaned up and
 * the colour picked from the message. With no screen showing it falls back
 * to a real toast.
 */
class AppToast private constructor(
    private val context: Context?,
    private var text: CharSequence?,
    private val duration: Int,
) {
    fun show() = Floater.fromToast(context, text, duration)

    fun setText(value: CharSequence?) { text = value }

    /** Capsules hide themselves; kept so existing cancel() calls compile. */
    fun cancel() = Unit

    companion object {
        const val LENGTH_SHORT = Toast.LENGTH_SHORT
        const val LENGTH_LONG = Toast.LENGTH_LONG

        @JvmStatic
        fun makeText(context: Context?, text: CharSequence?, duration: Int): AppToast =
            AppToast(context, text, duration)

        @JvmStatic
        fun makeText(context: Context?, resId: Int, duration: Int): AppToast =
            AppToast(context, context?.getText(resId), duration)
    }
}
