package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.AttendanceRecord
import com.manjugroups.m_connect.network.SessionData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Per-day punch / clock event log. Mirrors the web "9 punches · 0h 32m"
 * popup so the mobile UI surfaces every individual IN/OUT touch — not
 * just the first-in / last-out range — with its source chip (Mobile,
 * Biometric, Manual…).
 *
 * Payload is the [AttendanceRecord] for the tapped row; sessions[] is
 * flattened into a chronological list of events.
 */
class AttendancePunchLogSheet : BottomSheetDialogFragment() {

    private data class Event(
        val timeIso: String,
        val kind: Kind,
        val source: String?,
    )

    private enum class Kind { IN, OUT }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return@setOnShowListener
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
            sheet.setBackgroundColor(Color.TRANSPARENT)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_attendance_punch_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val dateIso = arguments?.getString(ARG_DATE).orEmpty()
        val sessionsJson = arguments?.getStringArrayList(ARG_SESSIONS) ?: arrayListOf()
        val hasOpen = arguments?.getBoolean(ARG_HAS_OPEN, false) ?: false
        val totalMinutes = arguments?.getInt(ARG_TOTAL_MIN, 0) ?: 0

        // Reassemble the sessions list from the serialized strings the
        // caller pushed in (Bundles can't carry arbitrary objects between
        // fragments safely without Parcelable boilerplate, so we encode
        // each session as "in|out|source").
        val sessions: List<SessionData> = sessionsJson.map { raw ->
            val parts = raw.split("|", limit = 3)
            SessionData(
                punchInTime = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                punchOutTime = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
                source = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
                totalMinutes = null,
            )
        }

        view.findViewById<View>(R.id.btnPunchLogClose).setOnClickListener {
            dismissAllowingStateLoss()
        }

        renderHeader(view, dateIso, sessions, hasOpen, totalMinutes)
        renderEvents(view, sessions)
    }

    private fun renderHeader(
        root: View,
        dateIso: String,
        sessions: List<SessionData>,
        hasOpen: Boolean,
        totalMinutes: Int,
    ) {
        val title = formatDate(dateIso) ?: dateIso
        root.findViewById<TextView>(R.id.tvPunchLogTitle).text = title

        val events = flatten(sessions)
        val firstTime = events.firstOrNull()?.let { formatIsoTime(it.timeIso) }
        val lastTime = events.lastOrNull()?.let { formatIsoTime(it.timeIso) }
        val durationLabel = if (totalMinutes > 0) {
            "${totalMinutes / 60}h ${totalMinutes % 60}m"
        } else null
        // "9 punches · 0h 32m (04:04 pm → 04:36 pm)" when both ends are
        // known; collapses gracefully when not.
        val parts = mutableListOf("${events.size} punch${if (events.size == 1) "" else "es"}")
        if (durationLabel != null) parts.add(durationLabel)
        if (firstTime != null && lastTime != null && firstTime != lastTime) {
            parts.add("$firstTime → $lastTime")
        } else if (hasOpen && events.size == 1 && firstTime != null) {
            parts.add("$firstTime · Not Punched Out")
        }
        root.findViewById<TextView>(R.id.tvPunchLogSubtitle).text =
            parts.joinToString(" · ")
    }

    private fun renderEvents(root: View, sessions: List<SessionData>) {
        val list = root.findViewById<LinearLayout>(R.id.punchLogList)
        val empty = root.findViewById<TextView>(R.id.tvPunchLogEmpty)
        list.removeAllViews()

        val events = flatten(sessions)
        if (events.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE

        val inflater = LayoutInflater.from(requireContext())
        events.forEach { ev ->
            val row = inflater.inflate(R.layout.item_attendance_punch_event, list, false)
            row.findViewById<TextView>(R.id.tvPunchEventTime).text =
                formatIsoTime(ev.timeIso) ?: "--"
            val kindPill = row.findViewById<TextView>(R.id.tvPunchEventKind)
            val dot = row.findViewById<View>(R.id.punchEventDot)
            if (ev.kind == Kind.IN) {
                kindPill.text = "Punch In"
                kindPill.setBackgroundResource(R.drawable.bg_punch_event_in)
                kindPill.setTextColor(Color.parseColor("#15803D"))
                dot.setBackgroundColor(Color.parseColor("#22C55E"))
            } else {
                kindPill.text = "Punch Out"
                kindPill.setBackgroundResource(R.drawable.bg_punch_event_out)
                kindPill.setTextColor(Color.parseColor("#B91C1C"))
                dot.setBackgroundColor(Color.parseColor("#EF4444"))
            }
            row.findViewById<TextView>(R.id.tvPunchEventSource).text =
                (ev.source ?: "mobile").replaceFirstChar { c -> c.uppercase() }
            list.addView(row)
        }
    }

    /**
     * Walk every session and emit one [Event] per timestamp present.
     * A closed session yields two events (IN then OUT); a still-open
     * session yields just the IN. The list is sorted by the ISO string,
     * which is lexicographically equivalent to chronological order for
     * Convex's normalised ISO-8601 outputs.
     */
    private fun flatten(sessions: List<SessionData>): List<Event> {
        val out = mutableListOf<Event>()
        sessions.forEach { s ->
            s.punchInTime?.takeIf { it.isNotBlank() }?.let { out.add(Event(it, Kind.IN, s.source)) }
            s.punchOutTime?.takeIf { it.isNotBlank() }?.let { out.add(Event(it, Kind.OUT, s.source)) }
        }
        return out.sortedBy { it.timeIso }
    }

    private fun formatDate(iso: String): String? {
        if (iso.isBlank()) return null
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val out = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
            out.format(parser.parse(iso)!!)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatIsoTime(iso: String): String? {
        val millis = parseIsoMillis(iso) ?: return null
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun parseIsoMillis(iso: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in patterns) {
            try {
                val fmt = SimpleDateFormat(pattern, Locale.US)
                if (pattern.endsWith("'Z'")) fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(iso)?.time
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    companion object {
        private const val ARG_DATE = "date"
        private const val ARG_SESSIONS = "sessions"
        private const val ARG_HAS_OPEN = "has_open"
        private const val ARG_TOTAL_MIN = "total_min"

        fun newInstance(record: AttendanceRecord): AttendancePunchLogSheet {
            // SessionData isn't Parcelable / Serializable — encode each
            // row as "in|out|source" so we can hand it through the
            // standard Bundle without dragging in a new dependency or a
            // brittle hand-rolled Parcelable.
            val encoded = ArrayList(record.sessions.orEmpty().map { s ->
                listOf(s.punchInTime.orEmpty(), s.punchOutTime.orEmpty(), s.source.orEmpty())
                    .joinToString("|")
            })
            return AttendancePunchLogSheet().apply {
                arguments = bundleOf(
                    ARG_DATE to record.date.orEmpty(),
                    ARG_SESSIONS to encoded,
                    ARG_HAS_OPEN to (record.hasOpenSession == true),
                    ARG_TOTAL_MIN to (record.totalMinutes ?: 0),
                )
            }
        }
    }
}
