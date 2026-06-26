package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentAdminFleetVehiclesBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetVehicleBinding

class AdminFleetVehiclesFragment : Fragment() {

    private var _binding: FragmentAdminFleetVehiclesBinding? = null
    private val binding get() = _binding!!

    private val vehicles = mutableListOf(
        VehicleItem("Innova", "KA-01-AA-1234", "Available", "Sedan", "Tempo", "Arun Raj", "9090909090"),
        VehicleItem("Innova", "KA-01-AA-1234", "Available", "Sedan", "Tempo", "Arun Raj", "9090909090"),
        VehicleItem("Innova", "KA-01-AA-1234", "Available", "Sedan", "Tempo", "Arun Raj", "9090909090"),
        VehicleItem("Innova", "KA-01-AA-1234", "Available", "Sedan", "Tempo", "Arun Raj", "9090909090")
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminFleetVehiclesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvAdminVehicles.layoutManager = LinearLayoutManager(requireContext())
        val adapter = VehiclesAdapter(vehicles) { item ->
            val bottomSheet = CreateVehicleBottomSheet.newEditInstance(
                plate = item.plateNumber,
                type = item.name,
                capacity = item.capacity,
                name = item.driverName,
                phone = item.driverPhone,
                agency = item.agency,
                onSave = { newPlate, newType, newCapacity, newName, newPhone, newAgency ->
                    val index = vehicles.indexOf(item)
                    if (index != -1) {
                        vehicles[index] = item.copy(
                            plateNumber = newPlate,
                            name = newType,
                            capacity = newCapacity,
                            driverName = newName,
                            driverPhone = newPhone,
                            agency = newAgency
                        )
                        binding.rvAdminVehicles.adapter?.notifyItemChanged(index)
                        Toast.makeText(requireContext(), "Vehicle updated successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            bottomSheet.show(parentFragmentManager, "CreateVehicleBottomSheet")
        }
        binding.rvAdminVehicles.adapter = adapter

        binding.rvAdminVehicles.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 10) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(false)
                } else if (!recyclerView.canScrollVertically(-1)) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(true)
                }
            }
        })

        binding.btnCreateVehicle.setOnClickListener {
            val bottomSheet = CreateVehicleBottomSheet.newInstance { plate, type, capacity, name, phone, agency ->
                // Add the newly created vehicle to the top of the list
                vehicles.add(0, VehicleItem(type, plate, "Available", capacity, "Diesel", name, phone, agency))
                adapter.notifyItemInserted(0)
                binding.rvAdminVehicles.scrollToPosition(0)
                Toast.makeText(requireContext(), "Vehicle created successfully", Toast.LENGTH_SHORT).show()
            }
            bottomSheet.show(parentFragmentManager, "CreateVehicleBottomSheet")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class VehicleItem(
        val name: String,
        val plateNumber: String,
        val status: String,
        val capacity: String,
        val fuelType: String,
        val driverName: String,
        val driverPhone: String,
        val agency: String = "Default Selected"
    )

    private class VehiclesAdapter(
        private val items: List<VehicleItem>,
        private val onItemClick: (VehicleItem) -> Unit
    ) : RecyclerView.Adapter<VehiclesAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAdminFleetVehicleBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemAdminFleetVehicleBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: VehicleItem) {
                binding.root.setOnClickListener {
                    onItemClick(item)
                }
                binding.tvVehiclePlate.text = item.plateNumber
                binding.tvVehicleName.text = "${item.name} / ${item.capacity} / ${item.fuelType}"
                binding.tvDriverName.text = item.driverName
                binding.tvDriverPhone.text = item.driverPhone
                binding.tvVehicleStatus.text = item.status
                binding.tvVehicleCapacity.text = item.capacity
                binding.tvVehicleFuel.text = item.fuelType

                if (item.status == "Available") {
                    binding.tvVehicleStatus.setBackgroundResource(R.drawable.bg_badge_success)
                    binding.tvVehicleStatus.setTextColor(Color.parseColor("#065F46"))
                } else {
                    binding.tvVehicleStatus.setBackgroundResource(R.drawable.bg_badge_info)
                    binding.tvVehicleStatus.setTextColor(Color.parseColor("#1D4ED8"))
                }
            }
        }
    }
}
