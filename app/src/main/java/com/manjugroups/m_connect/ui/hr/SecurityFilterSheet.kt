package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Designation / department filter for the Security staff list, matching the web
 * Security tab's filters.
 *
 * Options come from the loaded staff themselves rather than a fixed list, so
 * the sheet can never offer a designation nobody holds — and never miss one the
 * org has added.
 */
class SecurityFilterSheet : BottomSheetDialogFragment() {

    private var designations: List<String> = emptyList()
    private var departments: List<String> = emptyList()
    private var selectedDesignation: String? = null
    private var selectedDepartment: String? = null
    private var onApply: ((String?, String?) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }

        root.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(Color.parseColor("#E4E7EC"))
            }
        })

        root.addView(TextView(requireContext()).apply {
            text = "Filter staff"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#101828"))
        })

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        if (designations.isNotEmpty()) {
            content.addView(sectionTitle("Designation"))
            content.addView(
                chipGroup(designations, selectedDesignation) { selectedDesignation = it },
            )
        }
        if (departments.isNotEmpty()) {
            content.addView(sectionTitle("Department"))
            content.addView(
                chipGroup(departments, selectedDepartment) { selectedDepartment = it },
            )
        }
        if (designations.isEmpty() && departments.isEmpty()) {
            content.addView(TextView(requireContext()).apply {
                text = "No designations or departments are set on the staff records."
                textSize = 13f
                setTextColor(Color.parseColor("#667085"))
                setPadding(0, dp(16), 0, dp(8))
            })
        }
        root.addView(
            ScrollView(requireContext()).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f }
            },
        )

        val actions = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        actions.addView(pillButton("Clear all", filled = false) {
            selectedDesignation = null
            selectedDepartment = null
            onApply?.invoke(null, null)
            dismiss()
        })
        actions.addView(pillButton("Apply", filled = true) {
            onApply?.invoke(selectedDesignation, selectedDepartment)
            dismiss()
        })
        root.addView(actions)

        return root
    }

    private fun sectionTitle(text: String) = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor("#344054"))
        setPadding(0, dp(18), 0, dp(8))
    }

    /**
     * Single-select list. Tapping the active value clears it, so a filter can
     * always be undone without hunting for a "None" option.
     *
     * A vertical list rather than wrapped chips: chip wrapping would need a
     * flexbox dependency this app does not carry, and designation lists are
     * long enough that a scrollable list reads better anyway — the same shape
     * the app's other value pickers use.
     */
    private fun chipGroup(
        options: List<String>,
        selected: String?,
        onPick: (String?) -> Unit,
    ): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        var current = selected
        val rows = mutableListOf<Pair<String, LinearLayout>>()
        fun paint() {
            rows.forEach { (value, row) ->
                val active = value == current
                row.background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.parseColor(if (active) "#EAF4FF" else "#FFFFFF"))
                    setStroke(
                        dp(1),
                        Color.parseColor(if (active) "#0B61CA" else "#EAECF0"),
                    )
                }
                (row.getChildAt(0) as TextView).setTextColor(
                    Color.parseColor(if (active) "#0B61CA" else "#344054"),
                )
                row.getChildAt(1).visibility = if (active) View.VISIBLE else View.INVISIBLE
            }
        }
        options.forEach { option ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                isClickable = true
                addView(TextView(context).apply {
                    text = option
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                })
                addView(TextView(context).apply {
                    text = "✓"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.parseColor("#0B61CA"))
                })
                setOnClickListener {
                    current = if (current == option) null else option
                    onPick(current)
                    paint()
                }
            }
            rows.add(option to row)
            wrap.addView(row)
        }
        paint()
        return wrap
    }

    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit) =
        TextView(requireContext()).apply {
            text = label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor(if (filled) "#FFFFFF" else "#475467"))
            background = GradientDrawable().apply {
                cornerRadius = dp(27).toFloat()
                setColor(Color.parseColor(if (filled) "#0B61CA" else "#FFFFFF"))
                if (!filled) setStroke(dp(1), Color.parseColor("#D0D5DD"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(50)).apply {
                weight = 1f
                marginStart = if (filled) dp(8) else 0
                marginEnd = if (filled) 0 else dp(8)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun show(
            fm: FragmentManager,
            designations: List<String>,
            departments: List<String>,
            selectedDesignation: String?,
            selectedDepartment: String?,
            onApply: (String?, String?) -> Unit,
        ) {
            if (fm.findFragmentByTag("security_filter") != null) return
            SecurityFilterSheet().apply {
                this.designations = designations
                this.departments = departments
                this.selectedDesignation = selectedDesignation
                this.selectedDepartment = selectedDepartment
                this.onApply = onApply
            }.show(fm, "security_filter")
        }
    }
}
