package com.manjugroups.m_connect.ui.marketing.inventory

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.manjugroups.m_connect.network.InventoryUnit
import kotlin.math.max

/**
 * KOS-52 v1: paints rect-shaped inventory units onto a canvas, auto-scaled
 * to fit. Each unit is colored by status. Polygons are intentionally skipped
 * until the UX subtask locks the layoutCoordinates schema.
 */
class UnitMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val units = mutableListOf<InventoryUnit>()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#475467")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#101828")
        textAlign = Paint.Align.CENTER
        textSize = 22f
    }

    fun setUnits(newUnits: List<InventoryUnit>) {
        units.clear()
        units.addAll(newUnits)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (units.isEmpty() || width == 0 || height == 0) return

        val padding = 16f
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        units.forEach { u ->
            val c = u.layoutCoordinates ?: return@forEach
            val x = c.x ?: return@forEach
            val y = c.y ?: return@forEach
            val w = c.width ?: return@forEach
            val h = c.height ?: return@forEach
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x + w > maxX) maxX = x + w
            if (y + h > maxY) maxY = y + h
        }
        if (minX == Double.MAX_VALUE) return

        val srcW = max(1.0, maxX - minX)
        val srcH = max(1.0, maxY - minY)
        val scale = minOf(
            (width - 2 * padding) / srcW,
            (height - 2 * padding) / srcH,
        ).toFloat()
        val offsetX = padding - (minX.toFloat() * scale)
        val offsetY = padding - (minY.toFloat() * scale)

        units.forEach { u ->
            val c = u.layoutCoordinates ?: return@forEach
            val x = (c.x ?: return@forEach).toFloat()
            val y = (c.y ?: return@forEach).toFloat()
            val w = (c.width ?: return@forEach).toFloat()
            val h = (c.height ?: return@forEach).toFloat()
            val rect = RectF(
                x * scale + offsetX,
                y * scale + offsetY,
                (x + w) * scale + offsetX,
                (y + h) * scale + offsetY,
            )
            fillPaint.color = colorForStatus(u.status)
            canvas.drawRect(rect, fillPaint)
            canvas.drawRect(rect, strokePaint)
            u.unitNumber?.let { label ->
                canvas.drawText(label, rect.centerX(), rect.centerY() + 8f, labelPaint)
            }
        }
    }

    private fun colorForStatus(status: String): Int = when (status) {
        "available" -> Color.parseColor("#D1FADF")
        "held" -> Color.parseColor("#FEDF89")
        "booked" -> Color.parseColor("#B2DDFF")
        "sold" -> Color.parseColor("#FECDCA")
        else -> Color.parseColor("#F2F4F7")
    }
}
