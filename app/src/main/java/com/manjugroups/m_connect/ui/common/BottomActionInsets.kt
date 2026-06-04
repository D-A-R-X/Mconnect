package com.manjugroups.m_connect.ui.common

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.manjugroups.m_connect.R
import kotlin.math.roundToInt

object BottomActionInsets {
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
