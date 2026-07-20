package com.manjugroups.m_connect.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAgencyDriverTripsBinding
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskDriverTrip
import com.manjugroups.m_connect.network.TravelDeskDriverTripRequest
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

    private var busy = false

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

        binding.swipeDriverTrips.setOnRefreshListener { loadTrips() }
        loadTrips()
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
            binding.swipeDriverTrips.isRefreshing = false
            if (_binding == null) return@launch
            render(trips?.rows.orEmpty().filter { !it.id.isNullOrBlank() })
        }
    }

    private fun render(trips: List<TravelDeskDriverTrip>) {
        val container = binding.driverTripsContent
        container.removeAllViews()
        binding.tvDriverTripsEmpty.visibility =
            if (trips.isEmpty()) View.VISIBLE else View.GONE

        for (trip in trips) {
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

        card.findViewById<TextView>(R.id.tvTripPhase).text = phaseLabel(phase)

        val actionLabel = card.findViewById<TextView>(R.id.tvTripActionLabel)
        val actionBtn = card.findViewById<View>(R.id.btnTripAction)
        val next = nextAction(phase)
        if (next == null) {
            actionLabel.text = "Completed"
            actionBtn.isEnabled = false
            actionBtn.alpha = 0.5f
        } else {
            actionLabel.text = next.label
            actionBtn.isEnabled = trip.canOperateToday != false
            actionBtn.alpha = if (trip.canOperateToday != false) 1f else 0.5f
            actionBtn.setOnClickListener { advance(trip, next) }
        }
        return card
    }

    private fun phaseLabel(phase: String): String = when (phase) {
        "at_client" -> "At client"
        "in_progress" -> "Going to site"
        "on_site" -> "On site"
        "picked_from_site" -> "Picked from site"
        "completed" -> "Completed"
        else -> "Waiting"
    }

    /** The trip lifecycle a driver drives forward, one tap per stage. */
    private data class NextAction(val label: String, val key: String)

    private fun nextAction(phase: String): NextAction? = when (phase) {
        "waiting" -> NextAction("Reached client", "arrive")
        "at_client" -> NextAction("Start trip", "start")
        "in_progress" -> NextAction("On-site reached", "on-site")
        // Ending a trip needs odometer photos + km, which this stripped
        // screen doesn't collect — the agency closes it out on the portal.
        else -> null
    }

    private fun advance(trip: TravelDeskDriverTrip, action: NextAction) {
        val id = trip.id ?: return
        if (busy) return
        busy = true
        viewLifecycleOwner.lifecycleScope.launch {
            val body = TravelDeskDriverTripRequest(id)
            val result = runCatching {
                when (action.key) {
                    "arrive" -> api.driverMarkArrived(session.bearerToken, body)
                    "start" -> api.driverStartTrip(session.bearerToken, body)
                    else -> api.driverMarkOnSite(session.bearerToken, body)
                }
            }
            busy = false
            if (_binding == null) return@launch
            val ok = result.getOrNull()?.success == true
            if (!ok) {
                Toast.makeText(
                    requireContext(),
                    result.getOrNull()?.error
                        ?: result.exceptionOrNull()?.message
                        ?: "Could not update the trip",
                    Toast.LENGTH_LONG,
                ).show()
            }
            loadTrips()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
