package com.manjugroups.m_connect.ui.marketing

import android.app.AlertDialog
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.JointCpCompleteReviewRequest
import com.manjugroups.m_connect.network.JointCpWorkflow
import com.manjugroups.m_connect.ui.home.jointCpApiErrorMessage
import com.manjugroups.m_connect.ui.home.jointCpReviewRevision
import com.manjugroups.m_connect.ui.home.jointCpReviewerCanReview
import com.manjugroups.m_connect.ui.home.jointCpUserMessage
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The reviewer's "Review outcome" action, usable from any list card.
 *
 * Cards used to open the trip screen and wait for its five-second workflow
 * poll, so tapping Review appeared to do nothing until the staff member
 * refreshed. This reads the workflow from the API on tap and then shows the
 * review dialog, so the action is immediate wherever it is offered.
 */
object JointCpReviewFlow {

    /**
     * @param onCompleted invoked after the server confirms completion, so the
     *   caller can reload its list instead of leaving a stale card.
     */
    fun start(
        fragment: Fragment,
        cpVisitId: String,
        api: GeoTrackApi = GeoTrackApi.create(),
        onCompleted: () -> Unit,
    ) {
        if (!fragment.isAdded) return
        val context = fragment.requireContext()
        val session = SessionManager(context)
        val loading = Toast.makeText(context, "Loading outcome…", Toast.LENGTH_SHORT)
        loading.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching { api.getJointCpWorkflow(session.bearerToken, cpVisitId) }
                .getOrNull()
            loading.cancel()
            if (!fragment.isAdded) return@launch
            val workflow = response?.takeIf { it.success }?.workflow?.let {
                com.manjugroups.m_connect.ui.home.resolvedJointCpWorkflowForActor(
                    it,
                    session.staffId,
                    response.visit?.joint,
                )
            }
            if (workflow == null) {
                Toast.makeText(
                    context,
                    jointCpUserMessage(response?.error, "Couldn't load the outcome. Check your connection and try again."),
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            if (!jointCpReviewerCanReview(workflow)) {
                // Someone else already completed it, or the owner has not
                // submitted yet. Refresh so the card stops offering Review.
                Toast.makeText(context, reviewUnavailableMessage(workflow), Toast.LENGTH_LONG).show()
                onCompleted()
                return@launch
            }
            showSummary(fragment, cpVisitId, workflow, api, onCompleted)
        }
    }

    internal fun reviewUnavailableMessage(workflow: JointCpWorkflow): String {
        val state = workflow.state?.trim()?.lowercase()?.replace('-', '_')
        return when (state) {
            "completed" -> "This Joint CP is already completed."
            "cancelled", "canceled" -> "This Joint CP was cancelled."
            else -> {
                val owner = workflow.outcomeOwnerName?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "the outcome owner"
                "Waiting for $owner to submit the outcome."
            }
        }
    }

    internal fun summaryText(workflow: JointCpWorkflow): String {
        val owner = workflow.outcomeOwnerName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "The outcome owner"
        val summary = workflow.outcomeSummary?.trim()?.takeIf { it.isNotEmpty() }
            ?: workflow.outcome?.replace('_', ' ')?.trim()?.takeIf { it.isNotEmpty() }
            ?: "No outcome details were returned."
        return "$owner submitted:\n\n$summary"
    }

    private fun showSummary(
        fragment: Fragment,
        cpVisitId: String,
        workflow: JointCpWorkflow,
        api: GeoTrackApi,
        onCompleted: () -> Unit,
    ) {
        val context = fragment.requireContext()
        AlertDialog.Builder(context)
            .setTitle("Review Joint CP outcome")
            .setMessage(summaryText(workflow))
            .setPositiveButton("Complete with remarks") { _, _ ->
                promptRemarks(fragment, cpVisitId, workflow, api, onCompleted)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun promptRemarks(
        fragment: Fragment,
        cpVisitId: String,
        workflow: JointCpWorkflow,
        api: GeoTrackApi,
        onCompleted: () -> Unit,
    ) {
        val context = fragment.requireContext()
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val input = EditText(context).apply {
            hint = "Enter review remarks"
            minLines = 3
            maxLines = 5
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Complete Joint CP")
            .setMessage("Add the higher-level staff review remarks. Completion will close the trip for both staff.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Complete", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val remarks = input.text?.toString()?.trim().orEmpty()
                if (remarks.isEmpty()) {
                    input.error = "Review remarks are required"
                    return@setOnClickListener
                }
                dialog.dismiss()
                complete(fragment, cpVisitId, workflow, remarks, api, onCompleted)
            }
        }
        dialog.show()
    }

    private fun complete(
        fragment: Fragment,
        cpVisitId: String,
        workflow: JointCpWorkflow,
        remarks: String,
        api: GeoTrackApi,
        onCompleted: () -> Unit,
    ) {
        val context = fragment.requireContext()
        val session = SessionManager(context)
        val revision = jointCpReviewRevision(workflow)
        if (revision == null) {
            Toast.makeText(context, "Reload the visit and review again.", Toast.LENGTH_LONG).show()
            onCompleted()
            return
        }
        Toast.makeText(context, "Completing Joint CP…", Toast.LENGTH_SHORT).show()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                api.completeJointCpReview(
                    session.bearerToken,
                    UUID.randomUUID().toString(),
                    JointCpCompleteReviewRequest(cpVisitId, revision, remarks),
                )
            }
            if (!fragment.isAdded) return@launch
            val response = result.getOrNull()
            val completed = response?.success == true && response.workflow?.state == "completed"
            val message = when {
                completed -> {
                    val reviewer = response.workflow?.reviewedByTemplateName
                        ?: response.workflow?.reviewedByName
                        ?: "reviewer"
                    "Outcome reviewed by $reviewer"
                }
                response != null -> jointCpApiErrorMessage(
                    response.code,
                    response.error,
                    response.requiredRadiusMeters,
                    response.maximumAccuracyMeters,
                    response.maximumLocationAgeMs,
                    "Could not complete Joint CP review",
                )
                else -> jointCpUserMessage(
                    result.exceptionOrNull()?.message,
                    "Could not complete Joint CP review",
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            // Reload either way: on failure the card must show the real state.
            onCompleted()
        }
    }
}
