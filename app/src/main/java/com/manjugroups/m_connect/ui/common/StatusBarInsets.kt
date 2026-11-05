package com.manjugroups.m_connect.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * For a screen that asks MainActivity for `fullBleed = true` but has a plain
 * (white) header: draws the header under the status bar and pads it by the
 * status-bar / cutout inset.
 *
 * Why: the root tabs are full-bleed. A screen that used MainActivity's status
 * strip instead (`fullBleed = false`) made the strip change height whenever it
 * was entered or left, and that resize landed mid-slide, so the page visibly
 * jumped ("header glitch"). Full-bleed everywhere means nothing resizes.
 */
object StatusBarInsets {

    /**
     * @param fixedHeight true for a header with a fixed layout height (e.g.
     *   56dp): its height grows by the inset too, so the content keeps its room.
     */
    fun padHeader(header: View, fixedHeight: Boolean = false) {
        val baseTop = header.paddingTop
        val baseHeight = header.layoutParams?.height ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            val top = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top,
                insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top,
            )
            v.setPadding(v.paddingLeft, baseTop + top, v.paddingRight, v.paddingBottom)
            if (fixedHeight && baseHeight > 0) {
                v.layoutParams = v.layoutParams.apply { height = baseHeight + top }
            }
            insets
        }
        ViewCompat.requestApplyInsets(header)
    }
}
