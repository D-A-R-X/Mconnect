package com.manjugroups.m_connect.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DashboardRegistrationRow
import com.manjugroups.m_connect.ui.common.InfiniteScrollPager
import kotlinx.coroutines.launch

/** Today's completed registrations — a VP dashboard drill-down. */
class RegistrationsFragment : Fragment() {

    private val api = ApiService.create()
    // Full fetched result set. Its identity changes on each load so the pager
    // knows when to reset; `rows` is the windowed slice the adapter renders.
    private var allRegistrations: List<DashboardRegistrationRow> = emptyList()
    private val rows = mutableListOf<DashboardRegistrationRow>()
    private val adapter = Adapter()
    private var progress: ProgressBar? = null
    private var empty: View? = null
    private var recycler: RecyclerView? = null
    // Infinite scroll: render 20 rows, extend by 20 as the list nears its end.
    private var regWindowCtx: String? = null
    private var regFilteredCount: Int = 0
    private val regPager = InfiniteScrollPager(onLoadMore = { renderRows() })

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F1F3F8"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(DashboardListUi.header(this, "Registrations Today"))

        val frame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val lm = LinearLayoutManager(ctx)
        val rv = RecyclerView(ctx).apply {
            layoutManager = lm
            setPadding(dp(16), dp(8), dp(16), dp(24))
            clipToPadding = false
            adapter = this@RegistrationsFragment.adapter
        }
        recycler = rv
        // Infinite scroll: render the next 20 rows as the user nears the end.
        regPager.bindRecyclerView(rv, lm) { regFilteredCount }
        frame.addView(rv)
        progress = ProgressBar(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
        }
        frame.addView(progress)
        empty = DashboardListUi.emptyState(ctx, "📝", "No registrations completed today").also {
            it.visibility = View.GONE
            frame.addView(it)
        }

        root.addView(frame)
        load()
        return root
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val session = SessionManager(requireContext())
            val resp = runCatching { api.getDashboardRegistrations(session.bearerToken, null) }.getOrNull()
            if (view == null) return@launch
            progress?.visibility = View.GONE
            allRegistrations = resp?.registrations.orEmpty()
            renderRows()
        }
    }

    /** No filters/search here — render only the current window (20, +20 on
     *  scroll) of the fetched list; empty state keyed off the full size. */
    private fun renderRows() {
        val full = allRegistrations
        regFilteredCount = full.size
        // Reset the scroll window whenever the fetched list is replaced.
        val windowCtx = System.identityHashCode(full).toString()
        if (windowCtx != regWindowCtx) {
            regWindowCtx = windowCtx
            regPager.reset()
            recycler?.scrollToPosition(0)
        }
        rows.clear()
        rows.addAll(full.take(regPager.limit))
        adapter.notifyDataSetChanged()
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
            val r = rows[position]
            holder.title.text = r.clientName?.takeIf { it.isNotBlank() } ?: "Registration"
            val bits = listOfNotNull(
                r.status?.takeIf { it.isNotBlank() }?.replaceFirstChar(Char::uppercase),
                r.ownerName?.takeIf { it.isNotBlank() }?.let { "By $it" },
                r.completedDate?.takeIf { it.isNotBlank() },
            )
            holder.sub.text = bits.joinToString("  ·  ")
        }
    }

    companion object {
        fun newInstance() = RegistrationsFragment()
    }
}
