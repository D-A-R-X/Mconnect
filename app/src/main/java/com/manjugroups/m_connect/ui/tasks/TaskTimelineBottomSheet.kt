package com.manjugroups.m_connect.ui.tasks

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.TaskTimelineEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import coil.load

/**
 * "Time Line" bottom sheet — opened from the clock-history icon on
 * Task Overview. Lists every daily update on a task, newest first,
 * with date headers grouped by day, the author + time, progress
 * snapshot, notes, photo strip, and blocker / tomorrow-plan rows.
 *
 * Data comes from GET /api/projects/tasks/updates?taskId=... which
 * wraps taskUpdates.listByTask (permissive read).
 */
class TaskTimelineBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private val taskId: String by lazy {
        requireArguments().getString(ARG_TASK_ID).orEmpty()
    }

    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val headerFormatter = SimpleDateFormat("d MMM yyyy", Locale.US)
    private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
    // ISO-8601 instants written by `new Date().toISOString()` — e.g.
    // "2026-05-23T20:04:07.161Z". Used to parse the createdAt string
    // stored on each taskUpdates row.
    private val createdAtIsoFormatter = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        Locale.US,
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_task_timeline, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val recycler = view.findViewById<RecyclerView>(R.id.rvTimeline)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getTaskTimeline(session.bearerToken, taskId)
                if (!resp.success) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Failed to load timeline",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                // Newest first — backend returns desc, but defend
                // against future changes by sorting on creationTime
                // (epoch ms from Convex's _creationTime system field).
                val sorted = resp.updates.sortedByDescending {
                    it.creationTime ?: 0.0
                }
                recycler.adapter = TimelineAdapter(sorted)
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private inner class TimelineAdapter(
        private val items: List<TaskTimelineEntry>,
    ) : RecyclerView.Adapter<TimelineAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_task_timeline, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            // Hide the date header when this entry's date matches the
            // previous one's, so the date appears once per day group.
            val previous = if (position > 0) items[position - 1] else null
            val showDate = previous == null || previous.date != entry.date
            holder.bind(entry, showDate)
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDate: TextView = itemView.findViewById(R.id.tvTimelineDate)
            private val tvAuthor: TextView = itemView.findViewById(R.id.tvTimelineAuthor)
            private val tvTime: TextView = itemView.findViewById(R.id.tvTimelineTime)
            private val tvProgress: TextView = itemView.findViewById(R.id.tvTimelineProgress)
            private val tvNotes: TextView = itemView.findViewById(R.id.tvTimelineNotes)
            private val tvIssues: TextView = itemView.findViewById(R.id.tvTimelineIssues)
            private val tvTomorrow: TextView = itemView.findViewById(R.id.tvTimelineTomorrow)
            private val photosScroller: View = itemView.findViewById(R.id.timelinePhotosScroller)
            private val photosStrip: LinearLayout = itemView.findViewById(R.id.timelinePhotos)

            fun bind(entry: TaskTimelineEntry, showDate: Boolean) {
                // Date header
                if (showDate) {
                    tvDate.visibility = View.VISIBLE
                    tvDate.text = formatDateHeader(entry.date)
                } else {
                    tvDate.visibility = View.GONE
                }

                tvAuthor.text = entry.createdBy?.takeIf { it.isNotBlank() } ?: "Site Update"
                // Prefer the Convex-injected _creationTime epoch ms; if
                // it's missing (older rows), parse the createdAt ISO
                // string. Either way, fall back to empty rather than
                // surfacing a parse error.
                tvTime.text = entry.creationTime?.let { ms ->
                    runCatching { timeFormatter.format(Date(ms.toLong())) }.getOrNull()
                } ?: entry.createdAt?.takeIf { it.isNotBlank() }?.let { iso ->
                    runCatching {
                        createdAtIsoFormatter.parse(iso)?.let(timeFormatter::format)
                    }.getOrNull()
                } ?: ""

                val progressTxt = entry.progressSnapshot?.let { "Progress: $it%" }
                tvProgress.visibility = if (progressTxt != null) View.VISIBLE else View.GONE
                tvProgress.text = progressTxt.orEmpty()

                val notes = entry.todaysUpdate?.takeIf { it.isNotBlank() }
                tvNotes.visibility = if (notes != null) View.VISIBLE else View.GONE
                tvNotes.text = notes.orEmpty()

                // "Issues" row — show the blocker text in red if present;
                // otherwise show "No issues" in muted gray (matches the
                // Figma where both states use the same row).
                val blocker = entry.blocker?.takeIf { it.isNotBlank() }
                if (blocker != null) {
                    tvIssues.text = blocker
                    tvIssues.setTextColor(android.graphics.Color.parseColor("#B42318"))
                } else {
                    tvIssues.text = "No issues"
                    tvIssues.setTextColor(android.graphics.Color.parseColor("#667085"))
                }

                val tomorrow = entry.tomorrowsPlan?.takeIf { it.isNotBlank() }
                tvTomorrow.visibility = if (tomorrow != null) View.VISIBLE else View.GONE
                tvTomorrow.text = tomorrow.orEmpty()

                // Photo strip — rebuild from scratch each bind, with the
                // first thumbnail dictating visibility.
                photosStrip.removeAllViews()
                val images = entry.images.orEmpty()
                if (images.isEmpty()) {
                    photosScroller.visibility = View.GONE
                } else {
                    photosScroller.visibility = View.VISIBLE
                    val ctx = itemView.context
                    val density = ctx.resources.displayMetrics.density
                    val side = (64 * density).toInt()
                    val gap = (8 * density).toInt()
                    for (img in images) {
                        val iv = ImageView(ctx)
                        iv.layoutParams = LinearLayout.LayoutParams(side, side).apply {
                            marginEnd = gap
                        }
                        iv.scaleType = ImageView.ScaleType.CENTER_CROP
                        iv.background = ctx.getDrawable(R.drawable.bg_task_inner_card)
                        iv.clipToOutline = true
                        photosStrip.addView(iv)
                        // `url` is optional on TaskUpdateImage while storageId is
                        // required — the backend often sends only the id, and
                        // keying off url alone left an empty grey tile. resolve()
                        // turns either form into a servable URL.
                        loadInto(iv, img.url?.takeIf { it.isNotBlank() } ?: img.storageId)
                    }
                }
            }

            private fun formatDateHeader(iso: String?): String {
                if (iso.isNullOrBlank()) return ""
                return runCatching {
                    headerFormatter.format(isoFormatter.parse(iso)!!)
                }.getOrDefault(iso)
            }

            /**
             * Load through Coil like the rest of the app.
             *
             * This used to hand-roll `java.net.URL(...).openStream()` on an IO
             * dispatcher: no disk/memory cache, no redirect or content-type
             * handling, no cancellation when the row is recycled (so a slow
             * response could paint onto whichever entry the view now holds),
             * and every failure swallowed by getOrNull() — which is why a
             * broken image was indistinguishable from an empty one.
             */
            private fun loadInto(target: ImageView, rawUrl: String?) {
                val resolved = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(rawUrl)
                target.load(resolved) {
                    crossfade(true)
                    placeholder(R.drawable.bg_task_inner_card)
                    error(R.drawable.bg_task_inner_card)
                }
            }
        }
    }

    companion object {
        private const val ARG_TASK_ID = "arg_task_id"

        fun newInstance(taskId: String): TaskTimelineBottomSheet =
            TaskTimelineBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_TASK_ID, taskId) }
            }
    }
}
