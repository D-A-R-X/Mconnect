package com.manjugroups.m_connect.util

import android.content.Context
import android.content.res.Configuration

/**
 * Caps the system font size the app renders at.
 *
 * Staff phones set to the largest font sizes (and large display size) broke
 * fixed layouts: "You're Clocked In" ran into the header illustration, tile
 * values wrapped onto two lines, and the tab bar read "Attendan / ce". Text
 * still grows with the user's setting up to [MAX_FONT_SCALE]; beyond that the
 * app keeps its layout readable instead of overlapping.
 */
object FontScale {
    const val MAX_FONT_SCALE = 1.15f

    fun cap(base: Context): Context {
        val current = base.resources.configuration
        if (current.fontScale <= MAX_FONT_SCALE) return base
        val capped = Configuration(current).apply { fontScale = MAX_FONT_SCALE }
        return base.createConfigurationContext(capped)
    }
}
