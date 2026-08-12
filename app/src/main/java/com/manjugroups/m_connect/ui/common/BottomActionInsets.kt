package com.manjugroups.m_connect.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.manjugroups.m_connect.R
import kotlin.math.roundToInt

object BottomActionInsets {
    /**
     * Push a fixed header (top bar) below the status bar on this edge-to-edge
     * screen. The activity keeps `fragmentContainer` at top padding 0 and paints
     * only a colour strip behind the status bar — it does NOT offset content — so
     * each detail fragment must add the status-bar inset to its own header, or the
     * back button ends up under the notch / status icons.
     *
     * The header's XML paddingTop is preserved and the inset is added on top, so
     * repeated inset passes stay idempotent (never additive).
     */
    fun applyStatusBarTop(headerView: View) {
        val basePaddingTop = headerView.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(headerView) { view, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.updatePadding(top = basePaddingTop + statusTop)
            insets
        }
        headerView.post { ViewCompat.requestApplyInsets(headerView) }
    }

    fun applyAboveSystemNavAndTabs(actionView: View, breathingRoomDp: Int = 12) {
        val baseBottomMargin = (actionView.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        val breathingRoomPx = (breathingRoomDp * actionView.resources.displayMetrics.density).roundToInt()

        ViewCompat.setOnApplyWindowInsetsListener(actionView) { view, insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val tabBarContainer = view.rootView.findViewById<View?>(R.id.tabBarContainer)
            val visibleTabReserve = if (
                tabBarContainer?.visibility == View.VISIBLE &&
                tabBarContainer.height > 0
            ) {
                tabBarContainer.height + breathingRoomPx
            } else {
                navigationBottom + breathingRoomPx
            }

            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.bottomMargin = baseBottomMargin + visibleTabReserve
                view.layoutParams = params
            }
            insets
        }

        actionView.post { ViewCompat.requestApplyInsets(actionView) }
    }
}
