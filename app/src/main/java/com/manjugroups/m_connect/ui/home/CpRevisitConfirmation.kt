package com.manjugroups.m_connect.ui.home

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.manjugroups.m_connect.network.CpRevisitInfo
import java.text.SimpleDateFormat
import java.util.Locale

object CpRevisitConfirmation {
    fun fromResult(bundle: Bundle): CpRevisitInfo? {
        val date = bundle.getString(CompleteCpVisitBottomSheet.KEY_REVISIT_DATE)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return CpRevisitInfo(
            creationStatus = bundle.getString(
                CompleteCpVisitBottomSheet.KEY_REVISIT_STATUS,
            ).orEmpty(),
            reason = bundle.getString(
                CompleteCpVisitBottomSheet.KEY_REVISIT_REASON,
            ).orEmpty(),
            scheduledDate = date,
            scheduledTime = bundle.getString(
                CompleteCpVisitBottomSheet.KEY_REVISIT_TIME,
            ),
            visitId = bundle.getString(
                CompleteCpVisitBottomSheet.KEY_REVISIT_VISIT_ID,
            ),
            error = bundle.getString(
                CompleteCpVisitBottomSheet.KEY_REVISIT_ERROR,
            ),
        )
    }

    fun show(fragment: Fragment, revisit: CpRevisitInfo, onDone: () -> Unit) {
        if (!fragment.isAdded) return
        val displayDate = runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { isLenient = false }
                .parse(revisit.scheduledDate)
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(parsed!!)
        }.getOrDefault(revisit.scheduledDate)
        val time = revisit.scheduledTime
            ?.takeIf { it.isNotBlank() }
            ?.let { " at $it" }
            .orEmpty()
        val failed = revisit.creationStatus == "failed"
        val message = when {
            failed -> buildString {
                append("This CP is closed, but the revisit could not be created for ")
                append(displayDate).append(time).append(".")
                revisit.error?.takeIf { it.isNotBlank() }?.let {
                    append("\n\nReason: ").append(it.lineSequence().first())
                }
            }
            revisit.creationStatus == "queued" ->
                "This CP is closed as Not Met. A revisit is scheduled for " +
                    "$displayDate$time and will be created automatically after 2 days."
            revisit.reason == "collection_follow_up" ->
                "This collection CP is closed. A follow-up collection CP has been created for $displayDate$time."
            else ->
                "This CP is closed. A follow-up CP has been created for $displayDate$time."
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(if (failed) "Revisit not created" else "Revisit scheduled")
            .setMessage(message)
            .setPositiveButton("Done") { _, _ -> onDone() }
            .setCancelable(false)
            .show()
    }
}
