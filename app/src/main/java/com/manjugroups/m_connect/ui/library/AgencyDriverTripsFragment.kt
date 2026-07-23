package com.manjugroups.m_connect.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAgencyDriverTripsBinding
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskDriverTrip
import com.manjugroups.m_connect.ui.profile.ProfileFragment
import kotlinx.coroutines.launch

/**
 * The whole app for a driver created by an external travel agency.
 *
 * These users have no staff record, no attendance and no permissions — they
 * exist only to run the trips their agency allocates to them. So this screen
 * is the root: no bottom navigation, no notification bell and no clock-in.
 * The only way out is the profile avatar, which reaches settings and logout.
 *
 * Data comes from the agency-side travel-desk routes (the bearer token
 * resolves to a *driver* principal), not the MMS fleet-driver routes — those
 * deliberately exclude external-agency trips.
 */
class AgencyDriverTripsFragment : Fragment() {

    private var _binding: FragmentAgencyDriverTripsBinding? = null
    private val binding get() = _binding!!

    private lateinit var session: SessionManager
    private val api by lazy { TravelDeskApi.create() }

    private enum class Tab { ASSIGNED, COMPLETED, EXPIRED }
    private var selectedTab = Tab.ASSIGNED
    private var allTrips: List<TravelDeskDriverTrip> = emptyList()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAgencyDriverTripsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // The app draws edge-to-edge, so pad the header down by the status-bar
        // inset — otherwise the avatar / title slide up under the system bar
        // when this fragment is (re)shown (e.g. returning from Profile).
        val basePaddingTop = binding.driverHeaderBar.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            binding.driverHeaderBar,
        ) { v, insets ->
            val top = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars(),
            ).top
            v.setPadding(v.paddingLeft, basePaddingTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.driverHeaderBar)

        binding.tvDriverName.text = session.userName?.trim().orEmpty().ifBlank {
            "Assigned to you"
        }

        binding.btnDriverProfile.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.fragmentContainer, ProfileFragment())
                .addToBackStack(null)
                .commit()
        }

        binding.tabAssigned.setOnClickListener { selectTab(Tab.ASSIGNED) }
        binding.tabCompleted.setOnClickListener { selectTab(Tab.COMPLETED) }
        binding.tabExpired.setOnClickListener { selectTab(Tab.EXPIRED) }
        applyTabStyles()

        binding.swipeDriverTrips.setOnRefreshListener { loadTrips() }
        loadTrips()
    }

    private fun selectTab(tab: Tab) {
        if (selectedTab == tab) return
        selectedTab = tab
        applyTabStyles()
        render()
    }

    private fun applyTabStyles() {
        // Segmented control: the active segment is a blue-tinted pill inside
        // the container; inactive segments are transparent (show the container).
        val activeText = android.graphics.Color.WHITE
        val inactiveText = android.graphics.Color.parseColor("#475467")
        val activeTint = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.parseColor("#0B61CA")
        )
        val segments = listOf(
            binding.tabAssigned to Tab.ASSIGNED,
            binding.tabCompleted to Tab.COMPLETED,
            binding.tabExpired to Tab.EXPIRED,
        )
        for ((view, tab) in segments) {
            val isActive = selectedTab == tab
            if (isActive) {
                view.setBackgroundResource(R.drawable.bg_my_trips_tab_active)
                view.backgroundTintList = activeTint
                view.setTextColor(activeText)
            } else {
                view.setBackgroundResource(0)
                view.backgroundTintList = null
                view.setTextColor(inactiveText)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Belt-and-braces: MainActivity latches the nav off for this
        // principal, but a fragment popped off the back stack re-runs the
        // back-stack listener, so assert it here too.
        (activity as? MainActivity)?.setTabBarVisible(false)
        loadTrips()
    }

    private fun loadTrips() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.swipeDriverTrips.isRefreshing = true
            val trips = runCatching {
                api.listDriverTrips(session.bearerToken)
            }.getOrNull()
            // The fetch suspends; the view can be torn down while it's in flight
            // — e.g. the driver finishes ending a trip, the end sheet dismisses
            // and pops this fragment. Bail BEFORE touching the binding: reading
            // it (_binding!!) after the view is destroyed is an NPE crash. The
            // guard used to sit one line below this access, so it never helped.
            if (_binding == null) return@launch
            binding.swipeDriverTrips.isRefreshing = false
            allTrips = trips?.rows.orEmpty().filter { !it.id.isNullOrBlank() }
            render()
        }
    }

    private fun isCompleted(trip: TravelDeskDriverTrip) =
        (trip.phase ?: "").equals("completed", ignoreCase = true) ||
            trip.travelDeskEndedAt != null

    /** Not finished, but its scheduled slot is already in the past. */
    private fun isExpired(trip: TravelDeskDriverTrip) =
        com.manjugroups.m_connect.util.VisitExpiry.isExpired(
            scheduledDate = trip.scheduledDate,
            scheduledTime = trip.pickupTime ?: trip.scheduledTime,
            isDone = isCompleted(trip),
        )

    private fun render() {
        if (_binding == null) return
        val rows = when (selectedTab) {
            Tab.COMPLETED -> allTrips.filter { isCompleted(it) }
            Tab.EXPIRED -> allTrips.filter { !isCompleted(it) && isExpired(it) }
            // Assigned = live work: not finished, not past its day.
            Tab.ASSIGNED -> allTrips.filter { !isCompleted(it) && !isExpired(it) }
        }
        val container = binding.driverTripsContent
        container.removeAllViews()
        binding.tvDriverTripsEmpty.visibility =
            if (rows.isEmpty()) View.VISIBLE else View.GONE
        binding.tvDriverTripsEmpty.text = when (selectedTab) {
            Tab.COMPLETED -> "No completed trips yet."
            Tab.EXPIRED -> "No expired trips."
            Tab.ASSIGNED -> "No trips assigned to you yet."
        }
        for (trip in rows) {
            container.addView(buildCard(trip, container))
        }
    }

    private fun buildCard(trip: TravelDeskDriverTrip, parent: ViewGroup): View {
        val card = layoutInflater.inflate(R.layout.item_agency_driver_trip, parent, false)
        val phase = (trip.phase ?: "waiting").lowercase()

        card.findViewById<TextView>(R.id.tvTripProject).text =
            trip.project?.name?.takeIf { it.isNotBlank() } ?: "Site visit"
        card.findViewById<TextView>(R.id.tvTripWhen).text = listOfNotNull(
            trip.scheduledDate?.takeIf { it.isNotBlank() },
            (trip.pickupTime ?: trip.scheduledTime)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "—" }
        card.findViewById<TextView>(R.id.tvTripPickup).text =
            trip.pickupAddress?.takeIf { it.isNotBlank() } ?: "No pickup address"
        card.findViewById<TextView>(R.id.tvTripVehicle).text =
            trip.vehicle?.vehicleNumber?.takeIf { it.isNotBlank() }
                ?.let { "Vehicle: $it" } ?: "No vehicle"

        val completed = isCompleted(trip)
        val expired = !completed && isExpired(trip)
        card.findViewById<TextView>(R.id.tvTripPhase).text = when {
            completed -> "Completed"
            expired -> "Expired"
            else -> phaseLabel(phase)
        }

        val actionLabel = card.findViewById<TextView>(R.id.tvTripActionLabel)
        val actionBtn = card.findViewById<View>(R.id.btnTripAction)
        // The card and its button both open the CP-style detail screen, which
        // shows the client location on a map and drives the Start/End capture
        // flow. Completed and expired trips open the same screen read-only.
        actionLabel.text = when {
            completed || expired -> "View trip"
            phase == "in_progress" || phase == "on_site" || phase == "picked_from_site" -> "End trip"
            else -> "Start trip"
        }
        actionBtn.isEnabled = true
        actionBtn.alpha = 1f
        val open: (View) -> Unit = { openDetail(trip, phase) }
        actionBtn.setOnClickListener(open)
        card.setOnClickListener(open)
        return card
    }

    private fun openDetail(trip: TravelDeskDriverTrip, phase: String) {
        val id = trip.id ?: return
        val whenText = listOfNotNull(
            trip.scheduledDate?.takeIf { it.isNotBlank() },
            (trip.pickupTime ?: trip.scheduledTime)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        parentFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(
                R.id.fragmentContainer,
                AgencyDriverTripDetailFragment.newInstance(
                    id = id,
                    title = trip.project?.name?.takeIf { it.isNotBlank() } ?: "Site visit",
                    whenText = whenText,
                    address = trip.pickupAddress.orEmpty(),
                    vehicle = trip.vehicle?.vehicleNumber.orEmpty(),
                    phase = phase,
                    canOperateToday = trip.canOperateToday != false,
                    onSiteAtMs = trip.travelDeskOnSiteAt ?: 0L,
                ),
            )
            .addToBackStack(null)
            .commit()
    }

    private fun phaseLabel(phase: String): String = when (phase) {
        "at_client" -> "At client"
        "in_progress" -> "Going to site"
        "on_site" -> "On site"
        "picked_from_site" -> "Picked from site"
        "completed" -> "Completed"
        else -> "Waiting"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
