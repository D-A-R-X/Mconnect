package com.manjugroups.m_connect.ui.library

import android.app.AlertDialog
import android.content.Context

/**
 * The server refuses to assign a vehicle when the party size is unknown or
 * bigger than the vehicle, unless the dispatcher confirms
 * (`allowOvercapacity: true`). The web asks "assign anyway?"; the app used to
 * show the raw error and could never dispatch such a visit.
 */
object FleetCapacityConfirm {

    private val overcapacity = Regex("""Vehicle capacity (\d+) is below expected attendee count (\d+)""")

    /** The question to ask for a capacity error, or null for any other error. */
    fun question(error: String?): String? {
        if (error == null) return null
        overcapacity.find(error)?.let { m ->
            val (capacity, expected) = m.destructured
            return "This vehicle seats $capacity, but $expected people are expected. Assign it anyway?"
        }
        if (error.contains("Cannot verify capacity", ignoreCase = true)) {
            return "This site visit has no expected party size, so the vehicle's capacity can't be checked. Assign it anyway?"
        }
        return null
    }

    fun confirm(context: Context, question: String, onAssign: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Check vehicle capacity")
            .setMessage(question)
            .setPositiveButton("Assign anyway") { _, _ -> onAssign() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
