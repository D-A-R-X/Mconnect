package com.manjugroups.m_connect.ui.common

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import kotlin.math.abs

/**
 * Shared user-avatar rendering: show the profile photo when available, else a
 * circular avatar with the first letter of the name (colour derived from the
 * name so it's stable per person). Use everywhere a log/list shows a user.
 */
object AvatarUtils {

    // (background, letter) colour pairs — soft tint + readable letter.
    private val PALETTE = listOf(
        0xFFE0EAFF.toInt() to 0xFF3538CD.toInt(),
        0xFFD1FADF.toInt() to 0xFF067647.toInt(),
        0xFFFEF0C7.toInt() to 0xFFB54708.toInt(),
        0xFFFCE7F6.toInt() to 0xFFC11574.toInt(),
        0xFFE0F2FE.toInt() to 0xFF026AA2.toInt(),
        0xFFEDE9FE.toInt() to 0xFF6941C6.toInt(),
        0xFFFFE6D5.toInt() to 0xFFC4320A.toInt(),
    )

    /** A circular drawable with the first letter of [name] on a stable colour. */
    fun initialDrawable(res: Resources, name: String?): Drawable {
        val letter = name?.trim()?.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "?"
        val (bg, fg) = PALETTE[abs((name ?: "").trim().lowercase().hashCode()) % PALETTE.size]

        val size = 120
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            textSize = size * 0.42f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val baselineY = r - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(letter, r, baselineY, textPaint)
        return BitmapDrawable(res, bmp)
    }

    /**
     * Load [photoUrl] (circle-cropped) into this [ImageView]; if it's blank or
     * fails to load, fall back to an initial-letter avatar built from [name].
     */
    fun ImageView.loadUserAvatar(photoUrl: String?, name: String?) {
        val fallback = initialDrawable(resources, name)
        val url = photoUrl?.takeIf { it.isNotBlank() }
        // Always route through Coil's load() — even when url is null — so any
        // in-flight request on this (recycled) ImageView is cancelled first.
        // The old early-return setImageDrawable(fallback) did NOT cancel a
        // pending request, so when a RecyclerView row that had loaded someone's
        // photo got rebound to a person with NO photo, the stale request would
        // still land and paint the wrong face over the initials. fallback()
        // renders the initials when the data (url) is null.
        load(url) {
            transformations(CircleCropTransformation())
            placeholder(fallback)
            error(fallback)
            fallback(fallback)
        }
    }
}
