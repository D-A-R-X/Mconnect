package com.manjugroups.m_connect.ui.dashboard

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DashboardCallRow
import kotlinx.coroutines.launch

/** Today's calls (optionally filtered to a direction) — a VP dashboard drill-down. */
class CallsReportFragment : Fragment() {

    private val api = ApiService.create()
    // Full fetched set; `rows` holds only the current scroll window the adapter renders.
    private var allRows: List<DashboardCallRow> = emptyList()
    private val rows = mutableListOf<DashboardCallRow>()
    private val adapter = Adapter()
    private var progress: ProgressBar? = null
    private var empty: View? = null
    private var recycler: RecyclerView? = null
    // Infinite scroll: render 20 rows, extend by 20 as the list nears its end.
    private var callsWindowCtx: String? = null
    private var callsFilteredCount: Int = 0
    private val callsPager = com.manjugroups.m_connect.ui.common.InfiniteScrollPager(
        onLoadMore = { renderCalls() },
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val direction = arguments?.getString(ARG_DIRECTION)
        val title = arguments?.getString(ARG_TITLE) ?: "Calls"

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F3F8"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(DashboardListUi.header(this, title))

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val lm = LinearLayoutManager(ctx)
        val rv = RecyclerView(ctx).apply {
            layoutManager = lm
            setPadding(dp(16), dp(8), dp(16), dp(24))
            clipToPadding = false
            adapter = this@CallsReportFragment.adapter
        }
        recycler = rv
        callsPager.bindRecyclerView(rv, lm) { callsFilteredCount }
        frame.addView(rv)
        progress = ProgressBar(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
        }
        frame.addView(progress)
        empty = DashboardListUi.emptyState(ctx, "📞", "No calls today").also {
            it.visibility = View.GONE
            frame.addView(it)
        }
        root.addView(frame)

        load(direction)
        return root
    }

    private fun load(direction: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val session = SessionManager(requireContext())
            val resp = runCatching { api.getDashboardCalls(session.bearerToken, null, direction) }.getOrNull()
            if (view == null) return@launch
            progress?.visibility = View.GONE
            allRows = resp?.calls.orEmpty()
            renderCalls()
        }
    }

    /** Render only the current scroll window; on extend re-slice and notify. */
    private fun renderCalls() {
        val full = allRows
        callsFilteredCount = full.size
        val windowCtx = System.identityHashCode(full).toString()
        if (windowCtx != callsWindowCtx) {
            callsWindowCtx = windowCtx
            callsPager.reset()
            recycler?.scrollToPosition(0)
        }
        rows.clear()
        rows.addAll(full.take(callsPager.limit))
        adapter.notifyDataSetChanged()
        // Empty state keyed off the FULL fetched size, not the window.
        empty?.visibility = if (full.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
            val title = card.getChildAt(0) as TextView
            val sub = card.getChildAt(1) as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(DashboardListUi.rowCard(parent.context))

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val c = rows[position]
            val dir = c.callType?.uppercase()
            val arrow = if (dir == "INBOUND") "↓ Incoming" else if (dir == "OUTBOUND") "↑ Outbound" else (dir ?: "Call")
            holder.title.text = c.phoneNumber?.takeIf { it.isNotBlank() } ?: "Unknown number"
            holder.title.setTextColor(Color.parseColor("#101828"))
            val bits = listOfNotNull(
                arrow,
                c.status?.takeIf { it.isNotBlank() },
                c.agent?.takeIf { it.isNotBlank() }?.let { "Agent $it" },
                c.talkTime?.takeIf { it.isNotBlank() && it != "0" }?.let { "Talk ${it}s" },
                c.time?.takeIf { it.isNotBlank() },
            )
            holder.sub.text = bits.joinToString("  ·  ")
        }
    }

    companion object {
        private const val ARG_DIRECTION = "direction"
        private const val ARG_TITLE = "title"
        fun newInstance(direction: String?, title: String) = CallsReportFragment().apply {
            arguments = bundleOf(ARG_DIRECTION to direction, ARG_TITLE to title)
        }
    }
}
