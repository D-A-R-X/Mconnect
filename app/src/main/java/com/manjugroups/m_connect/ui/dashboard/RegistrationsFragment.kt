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
import kotlinx.coroutines.launch

/** Today's completed registrations — a VP dashboard drill-down. */
class RegistrationsFragment : Fragment() {

    private val api = ApiService.create()
    private val rows = mutableListOf<DashboardRegistrationRow>()
    private val adapter = Adapter()
    private var progress: ProgressBar? = null

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
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            setPadding(dp(16), dp(8), dp(16), dp(24))
            clipToPadding = false
            adapter = this@RegistrationsFragment.adapter
        }
        frame.addView(rv)
        progress = ProgressBar(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
        }
        frame.addView(progress)
        val emptyView = DashboardListUi.emptyState(ctx, "📝", "No registrations completed today").also {
            it.visibility = View.GONE
            frame.addView(it)
        }

        root.addView(frame)
        load(emptyView)
        return root
    }

    private fun load(emptyView: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val session = SessionManager(requireContext())
            val resp = runCatching { api.getDashboardRegistrations(session.bearerToken, null) }.getOrNull()
            if (view == null) return@launch
            progress?.visibility = View.GONE
            rows.clear()
            rows.addAll(resp?.registrations.orEmpty())
            adapter.notifyDataSetChanged()
            emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
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
