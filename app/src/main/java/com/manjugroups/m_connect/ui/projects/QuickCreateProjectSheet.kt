package com.manjugroups.m_connect.ui.projects

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateProjectRequest
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Lightweight "quick create project" reached from the daily-log project picker
 * when the searched project doesn't exist yet. Captures only the basics
 * (name, dates, status, budget) via the shared form components; the project
 * then opens on the web for full editing. On success the created project is
 * handed back so the caller can select it immediately.
 */
object QuickCreateProjectSheet {

    fun show(
        context: Context,
        owner: LifecycleOwner,
        session: SessionManager,
        prefillName: String,
        onCreated: (MarketingProject) -> Unit,
    ) {
        val api = ApiService.create()
        val apiDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val labelDate = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

        val view = View.inflate(context, R.layout.bottom_sheet_create_project, null)
        val etName = view.findViewById<EditText>(R.id.etProjectName)
        val etDesc = view.findViewById<EditText>(R.id.etDescription)
        val fieldStartDate = view.findViewById<View>(R.id.fieldStartDate)
        val tvStartDate = view.findViewById<TextView>(R.id.tvStartDateValue)
        val etDuration = view.findViewById<EditText>(R.id.etDuration)
        val fieldStatus = view.findViewById<View>(R.id.fieldStatus)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatusValue)
        val etBudget = view.findViewById<EditText>(R.id.etBudget)
        val btnCreate = view.findViewById<android.widget.Button>(R.id.btnCreateProject)

        // Pre-fill the name the user searched for but couldn't find.
        etName.setText(prefillName)
        etName.setSelection(etName.text?.length ?: 0)

        var startMillis: Long? = null
        var status = "proposed"

        fieldStartDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { startMillis?.let { timeInMillis = it } }
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    val c = Calendar.getInstance()
                    c.set(y, m, d, 0, 0, 0); c.set(Calendar.MILLISECOND, 0)
                    startMillis = c.timeInMillis
                    tvStartDate.text = labelDate.format(c.time)
                    tvStartDate.setTextColor(Color.parseColor("#101828"))
                },
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }

        fieldStatus.setOnClickListener {
            val opts = listOf("proposed" to "Proposed", "ongoing" to "Ongoing", "completed" to "Completed")
            SearchableSelectionDialog.show(
                context, "Status",
                opts.map { SearchableOption(it.first, it.second) },
            ) { picked ->
                status = picked
                tvStatus.text = opts.first { it.first == picked }.second
            }
        }

        val dialog = BottomSheetDialog(context)
        dialog.setContentView(view)
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        btnCreate.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            val start = startMillis
            val days = etDuration.text?.toString()?.trim()?.toIntOrNull()
            if (name.isEmpty()) { Toast.makeText(context, "Enter a project name", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (start == null) { Toast.makeText(context, "Select a start date", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (days == null || days <= 0) { Toast.makeText(context, "Enter a valid duration in days", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val endCal = Calendar.getInstance().apply { timeInMillis = start; add(Calendar.DAY_OF_MONTH, days) }
            val budget = etBudget.text?.toString()?.trim()?.toDoubleOrNull()

            btnCreate.isEnabled = false
            btnCreate.text = "Creating…"
            owner.lifecycleScope.launch {
                val resp = runCatching {
                    api.createProject(
                        session.bearerToken,
                        CreateProjectRequest(
                            name = name,
                            description = etDesc.text?.toString()?.trim().takeUnless { it.isNullOrBlank() },
                            status = status,
                            startDate = apiDate.format(start),
                            endDate = apiDate.format(endCal.time),
                            budget = budget,
                        ),
                    )
                }.getOrNull()
                btnCreate.isEnabled = true
                btnCreate.text = "Create Project"
                val project = resp?.project
                if (resp?.success == true && project != null) {
                    Toast.makeText(context, "Project created", Toast.LENGTH_SHORT).show()
                    onCreated(project)
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, resp?.error ?: "Couldn't create project", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }
}
