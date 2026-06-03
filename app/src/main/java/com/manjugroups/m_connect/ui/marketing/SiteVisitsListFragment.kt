package com.manjugroups.m_connect.ui.marketing

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.home.TripNavigationFragment
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Lists every site visit assigned to the authenticated staff (any status,
 * any date). Cards mirror the Home page "Today Visits" style:
 * - Tap card → opens trip navigation / detail screen.
 * - Tap "Start Trip" pill → opens trip navigation (where the trip is started).
 * - "In progress" / "Completed" pills are status badges (non-clickable).
 */
class SiteVisitsListFragment : Fragment() {

    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_site_visits_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<View>(R.id.btnSiteVisitsBack).setOnClickListener {
            navigateUp()
        }

        loadVisits(view)
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTopBarAppearance(Color.WHITE, true)
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTopBarAppearance(Color.parseColor("#FEFEFE"), true)
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
    }

    private fun loadVisits(root: View) {
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        val empty = root.findViewById<TextView>(R.id.tvSiteVisitsEmpty)
        val list = root.findViewById<LinearLayout>(R.id.siteVisitsList)
        val countText = root.findViewById<TextView>(R.id.tvSiteVisitsCount)

        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        empty.visibility = View.GONE
        list.removeAllViews()

        // Default range: last 30 days through end of next 30 days, so the
        // staff sees recently-completed and upcoming-scheduled visits.
        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val from = ymd.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 60)
        val to = ymd.format(cal.time)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.getMySiteVisits(session.bearerToken, from, to)
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                if (!resp.success) {
                    empty.text = resp.error ?: "Failed to load site visits"
                    empty.visibility = View.VISIBLE
                    countText.text = ""
                    return@launch
                }
                val visits = resp.visits.sortedByDescending { it.scheduledDate ?: "" }
                countText.text = "${visits.size}"
                if (visits.isEmpty()) {
                    empty.text = "No site visits assigned to you yet."
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                visits.forEach { v ->
                    val row = createVisitItem(v, list)
                    list.addView(row)
                }
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                empty.text = "Network error: ${e.message ?: "unknown"}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun createVisitItem(visit: TodayVisit, parent: ViewGroup): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, parent, false)
        val title = itemView.findViewById<TextView>(R.id.tvVisitItemTitle)
        val time = itemView.findViewById<TextView>(R.id.tvVisitItemTime)
        val actionBtn = itemView.findViewById<LinearLayout>(R.id.btnVisitItemAction)
        val action = itemView.findViewById<TextView>(R.id.tvVisitItemActionLabel)

        title.text = visit.placeName?.takeIf { it.isNotBlank() } ?: "Site visit"
        time.text = formatWhen(visit)

        val status = visit.status.lowercase(Locale.getDefault())
        val isCompleted = status in setOf("completed", "complete", "done", "closed")
        val isInProgress = status in setOf(
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
        )
        val isCancelled = status == "cancelled" || status == "canceled"

        when {
            isInProgress -> {
                action.text = "In progress"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action_progress)
                action.setTextColor(Color.parseColor("#B54708"))
            }
            isCompleted -> {
                action.text = "Completed"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action_disabled)
                action.setTextColor(Color.parseColor("#475467"))
            }
            isCancelled -> {
                action.text = "Cancelled"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action_disabled)
                action.setTextColor(Color.parseColor("#475467"))
            }
            else -> {
                action.text = "Start Trip"
                actionBtn.background = requireContext().getDrawable(R.drawable.bg_home_today_visit_action)
                action.setTextColor(Color.WHITE)
            }
        }

        val canOpen = !isCancelled
        if (canOpen) {
            val openNav: (View) -> Unit = { openVisit(visit) }
            itemView.isClickable = true
            itemView.isFocusable = true
            itemView.setOnClickListener(openNav)
            if (isInProgress || isCompleted) {
                actionBtn.isClickable = false
                actionBtn.setOnClickListener(null)
            } else {
                actionBtn.isClickable = true
                actionBtn.setOnClickListener(openNav)
            }
        } else {
            itemView.isClickable = false
            itemView.isFocusable = false
            itemView.setOnClickListener(null)
            actionBtn.isClickable = false
            actionBtn.setOnClickListener(null)
        }

        return itemView
    }

    private fun formatWhen(visit: TodayVisit): String {
        val rawDate = visit.scheduledDate
        val rawStart = visit.scheduledStartTime

        // Today's local date in yyyy-MM-dd for comparison.
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        val datePart = rawDate?.take(10)
        val isToday = datePart == today

        val timeLabel = rawStart?.takeIf { it.isNotBlank() }?.let { extractTime(it) }
            ?: rawDate?.let { extractTime(it) }

        val dateLabel = when {
            isToday -> "Available Today"
            datePart != null -> formatDateLabel(datePart)
            else -> "Date TBD"
        }

        return if (timeLabel != null) "$dateLabel · $timeLabel" else dateLabel
    }

    private fun formatDateLabel(yyyyMmDd: String): String {
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyyMmDd)
        }.getOrNull() ?: return yyyyMmDd
        return SimpleDateFormat("d MMM", Locale.getDefault()).format(parsed)
    }

    private fun extractTime(raw: String): String? {
        // ISO 8601 datetime → grab HH:mm after 'T'.
        val iso = Regex("""T(\d{2}):(\d{2})""").find(raw)
        if (iso != null) return formatHm(iso.groupValues[1].toInt(), iso.groupValues[2].toInt())
        // Plain HH:mm or HH:mm:ss
        val plain = Regex("""^(\d{1,2}):(\d{2})""").find(raw.trim())
        if (plain != null) return formatHm(plain.groupValues[1].toInt(), plain.groupValues[2].toInt())
        return null
    }

    private fun formatHm(h: Int, m: Int): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(cal.time)
    }

    private fun openVisit(v: TodayVisit) {
        val fragment = TripNavigationFragment.forVisit(
            visitId = v.id,
            placeName = v.placeName,
            placeAddress = v.placeAddress,
            destLat = v.placeLat,
            destLng = v.placeLng,
            status = v.status,
            visitCategory = v.visitCategory ?: "site_visit",
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }
}
