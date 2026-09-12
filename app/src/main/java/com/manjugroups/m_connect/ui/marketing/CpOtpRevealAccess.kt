package com.manjugroups.m_connect.ui.marketing

import java.util.Locale

/**
 * IAM key that grants CP arrival-OTP reveal. Granted to AVP by designation on
 * the backend (it survives a staff IAM template replacing the configurable
 * designation grants), and assignable to anyone else from the IAM screen.
 */
const val CP_REVEAL_OTP_PERMISSION = "marketing.cpVisits.revealOtp"

/**
 * Who may read a team member's live CP arrival OTP back to them.
 *
 * A staff member stuck on a client's doorstep — client refuses the OTP, the SMS
 * never lands — used to have no route forward but the tech team. This is the
 * supported route: their own AVP/GM reveals the active code.
 *
 * Every rule here mirrors `requireOtpRevealAccess` in the backend's
 * `convex/hr/fieldVisitOtp.ts`, which stays authoritative — reveal is audited
 * server-side on both view and copy, and the scope is re-checked on each call
 * so a previously returned field-visit id cannot widen it. This object exists
 * only so the app does not offer an action the server is going to refuse, and
 * so an unprivileged viewer is never shown that the capability exists.
 *
 * Deliberately NOT built on `SessionManager.hasPermission`: that folds in the
 * blanket `isAdmin` flag, which this app sets for several non-admin roles. An
 * OTP is a client's own verification code, so the grant must be explicit.
 */
internal object CpOtpRevealAccess {

    /**
     * General Manager, excluding Assistant General Manager — "AGM" contains
     * "GM", and an AGM is not a GM.
     */
    fun isGmDesignation(value: String?): Boolean {
        val designation = (value ?: "").trim().lowercase(Locale.US)
        if (designation.isEmpty()) return false
        if (Regex("""\b(?:assistant|asst\.?)\s+general\s+manager\b""").containsMatchIn(designation)) {
            return false
        }
        return Regex("""\bgm\b|\b(?:senior\s+)?general\s+manager\b""").containsMatchIn(designation)
    }

    /** Assistant/Associate Vice President, in the spellings HR actually uses. */
    fun isAvpDesignation(value: String?): Boolean {
        val designation = (value ?: "")
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("""[._/-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
        if (designation.isEmpty()) return false
        return Regex("""\bavp\b""").containsMatchIn(designation) ||
            Regex("""\bavvp\b""").containsMatchIn(designation) ||
            Regex("""\b(?:assistant|asst|associate)\s+vice\s+president\b""")
                .containsMatchIn(designation) ||
            Regex("""\b(?:assistant|asst|associate)\s+vp\b""").containsMatchIn(designation)
    }

    fun isGmOrAvpDesignation(value: String?): Boolean =
        isGmDesignation(value) || isAvpDesignation(value)

    /**
     * Does this viewer hold the capability at all, before any target is known?
     *
     * Super Admin bypasses both the key and the designation gate, exactly as
     * the server does (`staff.isAdmin === true || role === "super-admin"`).
     * Everyone else needs the explicit key AND a GM/AVP designation: the
     * permission alone is not enough, so granting it to a coordinator by
     * mistake does not hand out client OTPs.
     */
    fun holdsCapability(
        isSuperAdmin: Boolean,
        designation: String?,
        permissions: Set<String>,
    ): Boolean {
        if (isSuperAdmin) return true
        if (!permissions.contains(CP_REVEAL_OTP_PERMISSION)) return false
        return isGmOrAvpDesignation(designation)
    }

    /**
     * Reveal is limited to staff strictly BELOW the viewer in the reporting
     * hierarchy. [reportingTeamStaffIds] is the team the server itself just
     * returned for this viewer, so the app never infers the hierarchy locally;
     * the viewer is removed from it because nobody reveals their own OTP.
     */
    fun canRevealFor(
        isSuperAdmin: Boolean,
        designation: String?,
        permissions: Set<String>,
        viewerStaffId: String?,
        targetStaffId: String?,
        reportingTeamStaffIds: Set<String>,
    ): Boolean {
        if (!holdsCapability(isSuperAdmin, designation, permissions)) return false
        val target = targetStaffId?.trim().orEmpty()
        if (target.isEmpty()) return false
        if (isSuperAdmin) return true
        val viewer = viewerStaffId?.trim().orEmpty()
        if (viewer.isNotEmpty() && target == viewer) return false
        return reportingTeamStaffIds.any { it.trim() == target }
    }

    /**
     * CP states where an active arrival OTP can still exist.
     *
     * The server refuses a reveal once arrival is verified or the visit is
     * closed; offering the action there would only produce an error toast. A
     * blank/unknown status is allowed through so a deployment that stops
     * sending one degrades to the server's own answer rather than hiding a
     * capability the viewer legitimately has.
     */
    fun isRevealableStatus(status: String?): Boolean {
        val normalized = (status ?: "").trim().lowercase(Locale.US)
        if (normalized.isEmpty()) return true
        return normalized !in TERMINAL_REVEAL_STATUSES
    }
}

/**
 * Arrival is done, or the visit is closed. `arrived` is included because the
 * server treats it as verified; the approval/outcome states are terminal for
 * the trip even though the CP row stays open.
 */
private val TERMINAL_REVEAL_STATUSES = setOf(
    "arrived",
    "completed",
    "complete",
    "done",
    "closed",
    "cancelled",
    "canceled",
    "postponed",
    "pending_gm_approval",
)
