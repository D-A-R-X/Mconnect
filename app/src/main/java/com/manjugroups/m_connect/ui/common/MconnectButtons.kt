package com.manjugroups.m_connect.ui.common

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import kotlin.math.roundToInt

/**
 * Mconnect button components for buttons built in code. Same look as
 * `style="@style/Widget.Mconnect.Button*"` in XML (values/buttons.xml):
 * height, corner, font, colours and dark mode all come from the design tokens.
 *
 *     MconnectButtons.create(ctx, MconnectButtons.Kind.SUCCESS, "Approve") { approve() }
 *
 * Sizes by placement (MMS-289): [Size.LARGE] 52dp footer CTA, [Size.MEDIUM]
 * 48dp action inside a card (the "Call Client" size), [Size.COMPACT] 36dp
 * row action. `icon` puts a leading icon before the label (tint, size and
 * gap follow the size). `compact = true` is kept for existing callers.
 */
object MconnectButtons {

    enum class Kind { PRIMARY, SECONDARY, SUCCESS, DANGER, DANGER_OUTLINE }

    /** Height / text / icon by placement. See values/buttons.xml. */
    enum class Size(val heightDp: Int, val textSp: Float, val iconDp: Int, val padDp: Int, val gapDp: Int) {
        LARGE(52, 16f, 20, 16, 8),
        MEDIUM(48, 14f, 18, 16, 8),
        COMPACT(36, 13f, 16, 12, 6),
    }

    fun create(
        ctx: Context,
        kind: Kind,
        label: CharSequence,
        compact: Boolean = false,
        size: Size = if (compact) Size.COMPACT else Size.LARGE,
        @androidx.annotation.DrawableRes icon: Int? = null,
        // Keep onClick LAST: callers pass it as a trailing lambda.
        onClick: (() -> Unit)? = null,
    ): MaterialButton {
        val layout = when (kind) {
            Kind.PRIMARY -> R.layout.mc_button_primary
            Kind.SECONDARY -> R.layout.mc_button_secondary
            Kind.SUCCESS -> R.layout.mc_button_success
            Kind.DANGER -> R.layout.mc_button_danger
            Kind.DANGER_OUTLINE -> R.layout.mc_button_danger_outline
        }
        val button = LayoutInflater.from(ctx).inflate(layout, null, false) as MaterialButton
        button.text = label
        if (size != Size.LARGE) {
            val dp = ctx.resources.displayMetrics.density
            button.minHeight = (size.heightDp * dp).roundToInt()
            button.minimumHeight = (size.heightDp * dp).roundToInt()
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.textSp)
            button.setPadding((size.padDp * dp).roundToInt(), 0, (size.padDp * dp).roundToInt(), 0)
            button.iconSize = (size.iconDp * dp).roundToInt()
            button.iconPadding = (size.gapDp * dp).roundToInt()
        }
        if (icon != null) {
            button.setIconResource(icon)
            button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        }
        if (onClick != null) button.setOnClickListener { onClick() }
        return button
    }

    /** The shared button height (@dimen/button_height) in px. */
    fun height(ctx: Context): Int = ctx.resources.getDimensionPixelSize(R.dimen.button_height)
}
