package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAdminFleetTripsBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetTripBinding
import com.manjugroups.m_connect.ui.common.ProfilePhotos

class AdminFleetTripsFragment : Fragment() {

    private var _binding: FragmentAdminFleetTripsBinding? = null
    private val binding get() = _binding!!

    private var activeFilter = "Pending"
    private lateinit var tripsAdapter: AdminTripsAdapter
    private lateinit var session: SessionManager

    // Shared list of trips to preserve allocation state during the fragment lifetime
    companion object {
        val tripsList = mutableListOf(
            AdminTrip(
                id = "1",
                time = "2026-06-13 • 13:00",
                address = "1st Avenue, 83rd St, Ashok Nagar, Chennai, Tamil Nadu 600083",
                attendees = "2 Attendees",
                vehicleType = "Company Vehicle",
                status = "Pending"
            ),
            AdminTrip(
                id = "2",
                time = "2026-06-13 • 13:00",
                address = "1st Avenue, 83rd St, Ashok Nagar, Chennai, Tamil Nadu 600083",
                attendees = "2 Attendees",
                vehicleType = "Company Vehicle",
                status = "Pending"
            ),
            AdminTrip(
                id = "3",
                time = "2026-06-13 • 13:00",
                address = "1st Avenue, 83rd St, Ashok Nagar, Chennai, Tamil Nadu 600083",
                attendees = "2 Attendees",
                vehicleType = "Company Vehicle",
                status = "Pending"
            )
        )
    }

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

        applyFilter("Pending")
    }

    private fun setupRecyclerView() {
        tripsAdapter = AdminTripsAdapter(
            onAllocateClick = { trip ->
                val bottomSheet = AllocateVehicleBottomSheet.newInstance { driverName, driverPhone, vehicle ->
                    trip.status = "Assigned"
                    trip.driverName = driverName
                    trip.driverPhone = driverPhone
                    trip.allocatedVehicle = vehicle
                    // Move to Assigned tab upon successful allocation
                    applyFilter("Assigned")
                    Toast.makeText(requireContext(), "Vehicle allocated successfully", Toast.LENGTH_SHORT).show()
                }
                bottomSheet.show(parentFragmentManager, "AllocateVehicleBottomSheet")
            },
            onCompleteClick = { trip ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Complete Trip")
                    .setMessage("Are you sure you want to mark this trip as completed?")
                    .setPositiveButton("Yes") { _, _ ->
                        trip.status = "Completed"
                        // Move to Completed tab
                        applyFilter("Completed")
                        Toast.makeText(requireContext(), "Trip completed successfully", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        )
        binding.rvAdminTrips.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAdminTrips.adapter = tripsAdapter
        binding.rvAdminTrips.isNestedScrollingEnabled = false
    }

    private fun setupFilters() {
        binding.btnTabPending.setOnClickListener { applyFilter("Pending") }
        binding.btnTabAssigned.setOnClickListener { applyFilter("Assigned") }
        binding.btnTabCompleted.setOnClickListener { applyFilter("Completed") }
    }

    private fun applyFilter(filter: String) {
        activeFilter = filter

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

        // Filter list items
        val filtered = tripsList.filter { it.status == filter }
        tripsAdapter.submitList(filtered)
    }

    override fun onDestroyView() {
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
                // Map attendees text to short format (e.g., "2 Attendees" -> "2")
                val attendeesShort = item.attendees.replace(" Attendees", "").trim()
                binding.tvAttendeesTag.text = attendeesShort

                // Map vehicle type to short format (e.g., "Company Vehicle" -> "CV")
                val vehicleShort = if (item.vehicleType.equals("Company Vehicle", ignoreCase = true)) "CV" else item.vehicleType
                binding.tvVehicleTag.text = vehicleShort
                binding.tvTripStatus.text = item.status

                // Configure status badge colors to match reference design
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

                // Clicking the card itself when assigned triggers completion
                binding.root.setOnClickListener {
                    if (item.status == "Assigned") {
                        onCompleteClick(item)
                    }
                }
            }
        }
    }
}
