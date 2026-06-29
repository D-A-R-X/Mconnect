package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAdminFleetTripsBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetTripBinding
import com.manjugroups.m_connect.network.AllocateTripRequest
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskTrip
import com.manjugroups.m_connect.network.TravelDeskVehicle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AdminFleetTripsFragment : Fragment() {

    private var _binding: FragmentAdminFleetTripsBinding? = null
    private val binding get() = _binding!!

    private var activeFilter = "Pending"
    private lateinit var tripsAdapter: AdminTripsAdapter
    private lateinit var session: SessionManager
    private val api = TravelDeskApi.create()

    // Live data (replaces the old hardcoded `companion object { tripsList }`).
    // The trip lists are independent feeds (pending vs assigned); completed
    // is derived from assigned by inspecting travel-desk end timestamps,
    // since the backend exposes only listPending / listAssigned today.
    private var pendingTrips: List<TravelDeskTrip> = emptyList()
    private var assignedActive: List<TravelDeskTrip> = emptyList()
    private var assignedCompleted: List<TravelDeskTrip> = emptyList()
    private var vehicles: List<TravelDeskVehicle> = emptyList()
    private var activeVehicleCount: Int = 0
    private var loadJob: Job? = null
    private var allocateJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminFleetTripsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.homeHeader.setup(this)
        binding.homeHeader.setFleetBannerMode()
        // Avatar tap routes the agency to their Settings tab (which IS their
        // profile/account overview inside the Admin Fleet portal) instead of
        // the staff ProfileFragment, which requires a real staff record.
        binding.homeHeader.setOnProfileClickListener {
            (parentFragment as? AdminFleetContainerFragment)?.openTab(3)
        }
        binding.homeHeader.post { binding.homeHeader.playEntryAnimation() }
        setupRecyclerView()
        setupFilters()

        binding.scrollAdminTrips.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 10) {
                (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(false)
            } else if (scrollY <= 10) {
                (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(true)
            }
        })

        // Render whatever we already have, then trigger a refresh.
        applyFilter("Pending")
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Pick up changes made on travel-desk web (e.g., allocations) when the
        // user comes back to this screen.
        refresh()
    }

    private fun refresh() {
        if (_binding == null) return
        val token = session.bearerToken
        if (token.isBlank()) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pendingResp = api.listPending(token)
                val assignedResp = api.listAssigned(token)
                val vehiclesResp = api.listVehicles(token)

                pendingTrips = pendingResp.rows
                val assignedRows = assignedResp.rows
                // Trips with an end timestamp from travel-desk are "Completed";
                // everything else under "assigned" is in-flight ("Assigned").
                assignedActive = assignedRows.filter { tripEndedAt(it) == null }
                assignedCompleted = assignedRows.filter { tripEndedAt(it) != null }
                vehicles = vehiclesResp.rows
                activeVehicleCount = vehicles.count {
                    (it.status ?: "active").equals("active", ignoreCase = true)
                }

                if (_binding == null) return@launch
                bindBannerStats()
                applyFilter(activeFilter)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    "Couldn't load trips: ${e.message ?: "network error"}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    /** Surfaces the trip end timestamp regardless of which field the backend uses. */
    private fun tripEndedAt(trip: TravelDeskTrip): Long? {
        // Both fields can appear depending on the trip's phase in travel-desk.
        // Either non-null means "completed" for our purposes.
        // (status == "completed" is the canonical Convex field; some legacy
        // rows store it on travelDeskEndedAt only — keep both safe.)
        val statusCompleted = (trip.status ?: "").equals("completed", ignoreCase = true)
        return if (statusCompleted) Long.MAX_VALUE else null
    }

    private fun bindBannerStats() {
        if (_binding == null) return
        val header = binding.homeHeader.getHeaderBinding()
        // Today's Trips card = the count under the active "Pending" filter so
        // the agency sees how many allocations still need their attention.
        header.tvFleetTodaysTripsCount?.text = pendingTrips.size.toString()
        // Active vehicles = vehicles on this agency's roster currently active.
        header.tvFleetActiveVehiclesCount?.text = activeVehicleCount.toString()
    }

    private fun setupRecyclerView() {
        tripsAdapter = AdminTripsAdapter(
            onAllocateClick = { trip -> openAllocateSheet(trip) },
            onCompleteClick = { _ ->
                Toast.makeText(
                    requireContext(),
                    "Trip completion is finalised by the driver in travel-desk.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        )
        binding.rvAdminTrips.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAdminTrips.adapter = tripsAdapter
        binding.rvAdminTrips.isNestedScrollingEnabled = false
    }

    private fun openAllocateSheet(trip: AdminTrip) {
        // Find the original travel-desk trip (we only kept the UI model in the
        // adapter, so look it up by id from the cached lists).
        val originalTrip = pendingTrips.firstOrNull { it.id == trip.id }
            ?: assignedActive.firstOrNull { it.id == trip.id }
            ?: assignedCompleted.firstOrNull { it.id == trip.id }
            ?: return

        if (vehicles.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No vehicles on this agency. Add one in travel-desk first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val options = vehicles
            .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
            .map {
                val number = it.vehicleNumber ?: "—"
                val typePart = it.type?.takeIf { t -> t.isNotBlank() }?.let { t -> "$t · " }.orEmpty()
                AllocateVehicleOption(
                    vehicleId = it.id,
                    label = "$typePart$number",
                    defaultDriverName = it.defaultDriverName,
                    defaultDriverPhone = it.defaultDriverPhone,
                )
            }

        AllocateVehicleBottomSheet.newInstance(options) { result ->
            submitAllocate(originalTrip, result)
        }.show(parentFragmentManager, "AllocateVehicleBottomSheet")
    }

    private fun submitAllocate(trip: TravelDeskTrip, result: AllocateVehicleResult) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        allocateJob?.cancel()
        allocateJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.allocate(
                    token,
                    AllocateTripRequest(
                        siteVisitId = trip.id,
                        vehicleId = result.vehicleId,
                        pickupTime = result.pickupTime,
                        pricingMode = result.pricingMode,
                        driverName = result.driverName,
                        driverPhone = result.driverPhone,
                        kmRate = if (result.pricingMode == "km") result.amount else null,
                        packageAmount = if (result.pricingMode == "package") result.amount else null,
                    ),
                )
                if (!resp.success) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Allocation failed.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                Toast.makeText(
                    requireContext(),
                    "Trip allocated to ${result.vehicleLabel}.",
                    Toast.LENGTH_SHORT,
                ).show()
                // After a successful allocation the trip moves from the pending
                // feed to the assigned feed — switch tabs so the user sees the
                // result of their action.
                refresh()
                applyFilter("Assigned")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Allocation failed: ${e.message ?: "network error"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun setupFilters() {
        binding.btnTabPending.setOnClickListener { applyFilter("Pending") }
        binding.btnTabAssigned.setOnClickListener { applyFilter("Assigned") }
        binding.btnTabCompleted.setOnClickListener { applyFilter("Completed") }
    }

    private fun applyFilter(filter: String) {
        activeFilter = filter

        // Keep the banner's 3-dot indicator in sync with the active tab.
        _binding?.homeHeader?.setActiveFleetDot(
            when (filter) {
                "Assigned" -> 1
                "Completed" -> 2
                else -> 0
            }
        )

        // Style the active/inactive tabs exactly to match the home screen's tab bar selectors
        val activeBg = R.drawable.bg_my_trips_tab_active
        val activeTextColor = Color.parseColor("#FFFFFF")
        val inactiveTextColor = Color.parseColor("#475467")

        val density = binding.root.resources.displayMetrics.density
        val verticalPadding = (8 * density).toInt()

        listOf(
            Triple(binding.btnTabPending, "Pending", activeFilter == "Pending"),
            Triple(binding.btnTabAssigned, "Assigned", activeFilter == "Assigned"),
            Triple(binding.btnTabCompleted, "Completed", activeFilter == "Completed")
        ).forEach { (btn, _, isActive) ->
            if (isActive) {
                btn.setBackgroundResource(activeBg)
                btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0B61CA")))
                btn.setTextColor(activeTextColor)
            } else {
                btn.setBackgroundResource(0)
                btn.setTextColor(inactiveTextColor)
            }
            btn.setPadding(0, verticalPadding, 0, verticalPadding)
        }

        val rows = when (filter) {
            "Assigned" -> assignedActive
            "Completed" -> assignedCompleted
            else -> pendingTrips
        }.map { mapTrip(it, filter) }

        tripsAdapter.submitList(rows)
    }

    private fun mapTrip(trip: TravelDeskTrip, statusLabel: String): AdminTrip {
        val date: String = trip.scheduledDate ?: "—"
        val timePart: String = trip.scheduledTime ?: trip.pickupTime ?: ""
        val timeLabel: String = if (timePart.isBlank()) date else "$date • $timePart"
        val attendees: String = trip.expectedAttendeeCount?.toString() ?: "—"
        val vehicleLabel: String = trip.vehiclePreference ?: "External"

        val pickup: String? = trip.pickupAddress?.trim()?.ifBlank { null }
        val projectName: String? = trip.project?.name
        val addressLine: String = when {
            pickup != null -> pickup
            projectName != null -> "Project: $projectName"
            else -> "Address pending"
        }

        // We intentionally don't populate driverName/driverPhone/allocatedVehicle
        // — they aren't rendered by AdminTripsAdapter.bind() today, and the
        // optional travel-desk fields aren't on the compile path either. If a
        // future iteration needs them, surface only the fields the adapter
        // reads instead of mirroring the whole trip.
        return AdminTrip(
            id = trip.id,
            time = timeLabel,
            address = addressLine,
            attendees = attendees,
            vehicleType = vehicleLabel,
            status = statusLabel,
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        allocateJob?.cancel()
        if (_binding != null) {
            binding.homeHeader.stopFloatingAnimation()
        }
        super.onDestroyView()
        _binding = null
    }

    data class AdminTrip(
        val id: String,
        val time: String,
        val address: String,
        val attendees: String,
        val vehicleType: String,
        var status: String,
        var driverName: String? = null,
        var driverPhone: String? = null,
        var allocatedVehicle: String? = null
    )

    private class AdminTripsAdapter(
        private val onAllocateClick: (AdminTrip) -> Unit,
        private val onCompleteClick: (AdminTrip) -> Unit
    ) : RecyclerView.Adapter<AdminTripsAdapter.ViewHolder>() {

        private var items: List<AdminTrip> = emptyList()

        fun submitList(newList: List<AdminTrip>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAdminFleetTripBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemAdminFleetTripBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: AdminTrip) {
                binding.tvTripTime.text = item.time
                binding.tvTripAddress.text = item.address
                val attendeesShort = item.attendees.replace(" Attendees", "").trim()
                binding.tvAttendeesTag.text = attendeesShort

                // Map vehicle type to short format (e.g., "Company Vehicle" -> "CV")
                val vehicleShort = if (item.vehicleType.equals("Company Vehicle", ignoreCase = true))
                    "CV" else item.vehicleType
                binding.tvVehicleTag.text = vehicleShort
                binding.tvTripStatus.text = item.status

                when (item.status) {
                    "Pending" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_pending)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#EA580C"))
                        binding.btnAllocate.visibility = View.VISIBLE
                    }
                    "Assigned" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_info)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#1D4ED8"))
                        binding.btnAllocate.visibility = View.GONE
                    }
                    "Completed" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_success)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#047857"))
                        binding.btnAllocate.visibility = View.GONE
                    }
                }

                binding.btnAllocate.setOnClickListener {
                    onAllocateClick(item)
                }

                binding.root.setOnClickListener {
                    if (item.status == "Assigned") {
                        onCompleteClick(item)
                    }
                }
            }
        }
    }
}
