package com.manjugroups.m_connect.ui.hr

/**
 * Bridge between the design's seven-category leave picker and the
 * Convex backend's five `leaves.leaveType` codes
 * (`casual / sick / earned / unpaid / compensatory`).
 *
 * The picker shows the design's labels; on submit we send `backendCode`
 * as `leaveType` and the UI flow prefixes the chosen label onto the
 * `reason` payload so approvers still see the user's intent — e.g. a
 * "Bereavement Leave" request reaches the backend as `leaveType=unpaid`
 * with `reason="Bereavement Leave: …"`.
 *
 * Once the backend grows a richer enum this whole file can collapse
 * into a 1:1 mapping or be deleted.
 */
data class LeaveCategory(
    val label: String,
    val backendCode: String,
    /**
     * Whether this code consumes a balance bucket on the backend.
     * Affects whether we surface a "balance left" hint anywhere.
     */
    val balanceTracked: Boolean,
)

object LeaveCategoryCatalog {

    /** Order matches the design's bottom-sheet radio list, top → bottom. */
    val all: List<LeaveCategory> = listOf(
        LeaveCategory("Sick Leave", "sick", balanceTracked = true),
        LeaveCategory("Annual Leave/Vacation Leave", "earned", balanceTracked = true),
        LeaveCategory("Maternity/Paternity Leave", "unpaid", balanceTracked = false),
        LeaveCategory("Bereavement Leave", "unpaid", balanceTracked = false),
        LeaveCategory("Personal Leave", "casual", balanceTracked = true),
        LeaveCategory("Jury Duty Leave", "unpaid", balanceTracked = false),
        LeaveCategory("Compassionate Leave", "compassionate", balanceTracked = false),
    )

    /**
     * Pretty label for a saved backend code (used when rendering an
     * existing leave card). If the same code maps to multiple labels we
     * return the first hit, which corresponds to the design's "default"
     * choice for that bucket (e.g. `unpaid` → "Maternity/Paternity Leave").
     */
    fun labelFor(backendCode: String?): String {
        val key = backendCode?.trim()?.lowercase() ?: return "Leave"
        return all.firstOrNull { it.backendCode == key }?.label ?: when (key) {
            "casual" -> "Personal Leave"
            "compensatory" -> "Compensatory Off"
            "unpaid" -> "Unpaid Leave"
            else -> key.replaceFirstChar { it.titlecase() } + " Leave"
        }
    }

    /** Lookup by position (used by the bottom-sheet radio list). */
    fun at(index: Int): LeaveCategory = all[index.coerceIn(all.indices)]

    /** Convenience: the index of a saved code, defaulting to 0. */
    fun indexOfCode(backendCode: String?): Int {
        val key = backendCode?.trim()?.lowercase() ?: return 0
        return all.indexOfFirst { it.backendCode == key }.coerceAtLeast(0)
    }
}
