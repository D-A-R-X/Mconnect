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
import coil.load
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
        val address: String?,
        val photoId: String?,
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
        val sessionsJson = arguments?.getString(ARG_SESSIONS).orEmpty()
        val hasOpen = arguments?.getBoolean(ARG_HAS_OPEN, false) ?: false
        val totalMinutes = arguments?.getInt(ARG_TOTAL_MIN, 0) ?: 0

        // Sessions are handed in as a Gson JSON blob (carries every field —
        // source, address/machine, photo — without brittle pipe encoding).
        val sessions: List<SessionData> = runCatching {
            com.google.gson.Gson().fromJson<List<SessionData>>(
                sessionsJson,
                object : com.google.gson.reflect.TypeToken<List<SessionData>>() {}.type,
            )
        }.getOrNull() ?: emptyList()

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

            // Where the punch happened — mobile address or biometric machine.
            val addr = row.findViewById<TextView>(R.id.tvPunchEventAddress)
            if (!ev.address.isNullOrBlank()) {
                addr.text = ev.address
                addr.visibility = View.VISIBLE
            } else {
                addr.visibility = View.GONE
            }

            // Captured punch photo (mobile clock in/out). Tapping opens the
            // shared full-screen viewer — the thumbnail is too small to verify
            // who actually punched, which is the whole point of capturing it.
            val photo = row.findViewById<ImageView>(R.id.ivPunchEventPhoto)
            val pid = ev.photoId
            if (!pid.isNullOrBlank()) {
                photo.visibility = View.VISIBLE
                // resolve() handles both a bare storageId and an already-
                // resolved URL, and repairs dev/localhost storage hosts.
                val url = com.manjugroups.m_connect.ui.common.ProfilePhotos
                    .resolve(pid) ?: pid
                photo.load(url)
                photo.isClickable = true
                photo.setOnClickListener {
                    context?.let { ctx ->
                        com.manjugroups.m_connect.ui.common.ImagePreviewDialog.show(ctx, url)
                    }
                }
            } else {
                photo.visibility = View.GONE
                photo.setOnClickListener(null)
                photo.isClickable = false
            }

            list.addView(row)
        }
    }

    /**
     * Walk every session and emit one [Event] per timestamp present.
     * A closed session yields two events (IN then OUT); a still-open
     * session yields just the IN. Sorted by actual parsed time, ascending —
     * oldest at the top, newest at the bottom. (Sorting the raw ISO string
     * mis-orders biometric rows whose timestamps use a different format /
     * offset, which is why the log read newest-first.)
     */
    private fun flatten(sessions: List<SessionData>): List<Event> {
        val out = mutableListOf<Event>()
        sessions.forEach { s ->
            s.punchInTime?.takeIf { it.isNotBlank() }?.let {
                out.add(Event(it, Kind.IN, s.source, s.punchInAddress, s.punchInPhoto))
            }
            s.punchOutTime?.takeIf { it.isNotBlank() }?.let {
                // The OUT event's chip is the punch-out's own source (e.g. a
                // mobile clock-out on a biometric punch-in), falling back to
                // the session source for historical rows without it.
                out.add(Event(it, Kind.OUT, s.punchOutSource ?: s.source, s.punchOutAddress, s.punchOutPhoto))
            }
        }
        return out.sortedBy { parseIsoMillis(it.timeIso) ?: Long.MAX_VALUE }
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
            // SessionData isn't Parcelable — hand the sessions through as a
            // Gson JSON blob so every field (source, address/machine, photo)
            // survives without a brittle hand-rolled encoding.
            val json = com.google.gson.Gson().toJson(record.sessions.orEmpty())
            return AttendancePunchLogSheet().apply {
                arguments = bundleOf(
                    ARG_DATE to record.date.orEmpty(),
                    ARG_SESSIONS to json,
                    ARG_HAS_OPEN to (record.hasOpenSession == true),
                    ARG_TOTAL_MIN to (record.totalMinutes ?: 0),
                )
            }
        }
    }
}
