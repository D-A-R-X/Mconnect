package com.manjugroups.m_connect.ui.common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * Builds and applies a brand-blue gradient header background whose
 * bottom-corner radius can be mutated on the fly. Used by the
 * collapsing-header pages (Home, Attendance, App Library, Inspection)
 * so the rounded "shoulder" on the header smoothly straightens into a
 * flat edge as the user scrolls — what the user described as
 * "shrinked header roundness into sharp edges".
 *
 * The gradient matches `bg_attendance_header_gradient.xml` exactly so
 * existing static instances remain a visual match.
 *
 * Usage:
 * ```
 * val bg = view.applyShrinkableBlueHeaderBackground()
 * // later, on scroll:
 * bg.setBottomCornerRadius(radiusPx)
 * ```
 */
fun View.applyShrinkableBlueHeaderBackground(): GradientDrawable {
    val drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        gradientType = GradientDrawable.LINEAR_GRADIENT
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        colors = intArrayOf(
            Color.parseColor("#0B61CA"),
            Color.parseColor("#02499D"),
        )
    }
    background = drawable
    return drawable
}

/**
 * Sets only the BOTTOM-left and BOTTOM-right corner radii on a
 * [GradientDrawable] — top stays sharp regardless. `cornerRadii` takes
 * eight floats (x/y for each of the four corners in TL, TR, BR, BL
 * order), so this packs the four shared radii into that array.
 */
fun GradientDrawable.setBottomCornerRadius(radiusPx: Float) {
    cornerRadii = floatArrayOf(
        0f, 0f,            // top-left
        0f, 0f,            // top-right
        radiusPx, radiusPx, // bottom-right
        radiusPx, radiusPx, // bottom-left
    )
}
