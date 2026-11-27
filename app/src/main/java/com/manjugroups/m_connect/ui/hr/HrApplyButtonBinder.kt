package com.manjugroups.m_connect.ui.hr

import android.view.View
import android.widget.TextView
import com.manjugroups.m_connect.R
import android.content.Context
import android.util.TypedValue

/**
 * Paints an HR "Apply …" bottom button from an [HrApplyAvailability.State]
 * (MMS-290): the normal green button, or the shared disabled button reading
 * "Unavailable" with the one-line reason under it.
 *
 * A disabled view never fires its click listener, so the screen's existing
 * "open the apply sheet" handler stays exactly as it was.
 */
object HrApplyButtonBinder {

    const val UNAVAILABLE_LABEL = "Unavailable"

    fun bind(
        button: View,
        label: TextView,
        reason: TextView,
        state: HrApplyAvailability.State,
        applyText: String,
    ) {
        val ctx = button.context
        if (state.available) {
            button.setBackgroundResource(R.drawable.bg_leave_submit_button)
            button.isEnabled = true
            label.text = applyText
            label.setTextColor(ctx.getColor(android.R.color.white))
            reason.visibility = View.GONE
        } else {
            button.setBackgroundResource(R.drawable.bg_mc_button_disabled)
            button.isEnabled = false
            label.text = UNAVAILABLE_LABEL
            label.setTextColor(themeColor(ctx, R.attr.colorForegroundPrimary))
            reason.text = state.reason
            reason.setTextColor(themeColor(ctx, R.attr.colorForegroundSecondary))
            reason.visibility = if (state.reason.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        button.contentDescription =
            if (state.available) applyText else listOfNotNull(UNAVAILABLE_LABEL, state.reason).joinToString(". ")
    }

    private fun themeColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
    }
}
