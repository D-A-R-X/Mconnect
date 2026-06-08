package com.manjugroups.m_connect.ui.common

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import java.text.DateFormatSymbols
import java.util.Calendar

object MonthYearPicker {
    fun show(
        context: Context,
        initial: Calendar,
        minDate: Calendar? = null,
        maxDate: Calendar? = null,
        onPicked: (year: Int, month: Int) -> Unit,
    ) {
        val today = Calendar.getInstance()
        val minYear = minDate?.get(Calendar.YEAR) ?: today.get(Calendar.YEAR) - 30
        val maxYear = maxDate?.get(Calendar.YEAR) ?: today.get(Calendar.YEAR) + 30
        val monthNames = DateFormatSymbols.getInstance().months.take(12).toTypedArray()

        val yearPicker = NumberPicker(context).apply {
            minValue = minYear
            maxValue = maxYear
            value = initial.get(Calendar.YEAR).coerceIn(minYear, maxYear)
            wrapSelectorWheel = false
        }
        val monthPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 11
            displayedValues = monthNames
            value = initial.get(Calendar.MONTH)
            wrapSelectorWheel = true
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 4))
            addView(monthPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(yearPicker, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        AlertDialog.Builder(context)
            .setTitle("Select month and year")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val clamped = Calendar.getInstance().apply {
                    set(Calendar.YEAR, yearPicker.value)
                    set(Calendar.MONTH, monthPicker.value)
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.let { candidate ->
                    when {
                        minDate != null && isBeforeMonth(candidate, minDate) -> minDate
                        maxDate != null && isAfterMonth(candidate, maxDate) -> maxDate
                        else -> candidate
                    }
                }
                onPicked(clamped.get(Calendar.YEAR), clamped.get(Calendar.MONTH))
            }
            .show()
    }

    private fun isBeforeMonth(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) < b.get(Calendar.YEAR) ||
            (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) < b.get(Calendar.MONTH))

    private fun isAfterMonth(a: Calendar, b: Calendar): Boolean =
        a.get(Calendar.YEAR) > b.get(Calendar.YEAR) ||
            (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.MONTH) > b.get(Calendar.MONTH))

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
