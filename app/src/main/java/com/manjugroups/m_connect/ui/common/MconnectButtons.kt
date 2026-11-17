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
 * `compact = true` is for small inline row actions (36dp, 13sp) where a full
 * 52dp button would not fit.
 */
object MconnectButtons {

    enum class Kind { PRIMARY, SECONDARY, SUCCESS, DANGER, DANGER_OUTLINE }

    fun create(
        ctx: Context,
        kind: Kind,
        label: CharSequence,
        compact: Boolean = false,
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
        if (compact) {
            val dp = ctx.resources.displayMetrics.density
            button.minHeight = (36 * dp).roundToInt()
            button.minimumHeight = (36 * dp).roundToInt()
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            button.setPadding((14 * dp).roundToInt(), 0, (14 * dp).roundToInt(), 0)
        }
        if (onClick != null) button.setOnClickListener { onClick() }
        return button
    }

    /** The shared button height (@dimen/button_height) in px. */
    fun height(ctx: Context): Int = ctx.resources.getDimensionPixelSize(R.dimen.button_height)
}
