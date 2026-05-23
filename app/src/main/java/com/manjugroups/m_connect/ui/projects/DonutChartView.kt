package com.manjugroups.m_connect.ui.projects

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.manjugroups.m_connect.R
import kotlin.math.min

/**
 * Tiny custom donut chart used in the Expenses totals card. Renders
 * up to 4 arcs (labour / materials / equipment / other) sized
 * proportionally to the value array. Falls back to a grey ring when
 * everything is zero so the card never collapses while data loads.
 *
 * Usage:
 *   donut.setValues(labour, materials, equipment, other)
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E5E7EB") // neutral grey for empty state
    }
    private val rect = RectF()

    private var values = floatArrayOf(0f, 0f, 0f, 0f)
    private var colors = intArrayOf(
        ContextCompat.getColor(context, R.color.category_labour),
        ContextCompat.getColor(context, R.color.category_materials),
        ContextCompat.getColor(context, R.color.category_equipment),
        ContextCompat.getColor(context, R.color.category_other),
    )

    fun setValues(labour: Double, materials: Double, equipment: Double, other: Double) {
        values = floatArrayOf(
            labour.toFloat().coerceAtLeast(0f),
            materials.toFloat().coerceAtLeast(0f),
            equipment.toFloat().coerceAtLeast(0f),
            other.toFloat().coerceAtLeast(0f),
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = min(width, height).toFloat()
        // Thickness ~14% of the diameter so the donut reads at small
        // sizes. Stroke is centred on the path so we inset by half-
        // stroke.
        val stroke = side * 0.14f
        arcPaint.strokeWidth = stroke
        ringPaint.strokeWidth = stroke
        val inset = stroke / 2f
        rect.set(
            (width - side) / 2f + inset,
            (height - side) / 2f + inset,
            (width + side) / 2f - inset,
            (height + side) / 2f - inset,
        )

        val total = values.sum()
        if (total <= 0f) {
            // Empty-state ring — neutral grey so the card doesn't look broken.
            canvas.drawArc(rect, 0f, 360f, false, ringPaint)
            return
        }

        // Tiny gap between arcs for visual separation. 2° looks tight
        // at small sizes; scale slightly with size for legibility.
        val gap = if (side < dpToPx(80f)) 0f else 2f
        var start = -90f // start at 12 o'clock
        for (i in values.indices) {
            val v = values[i]
            if (v <= 0f) continue
            val sweep = (v / total) * 360f - gap
            if (sweep > 0f) {
                arcPaint.color = colors[i]
                canvas.drawArc(rect, start, sweep, false, arcPaint)
            }
            start += sweep + gap
        }
    }

    private fun dpToPx(dp: Float): Float =
        dp * Resources.getSystem().displayMetrics.density
}
