package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentAdminFleetDriversBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetDriverBinding
import java.util.Locale

class AdminFleetDriversFragment : Fragment() {

    private var _binding: FragmentAdminFleetDriversBinding? = null
    private val binding get() = _binding!!

    // Store the master list of drivers
    private val allDrivers = mutableListOf(
        DriverItem("Ramesh Kumar", "+91 98765 43210", "Triplane Mainroad..", "Active"),
        DriverItem("Suresh Yadav", "+91 98765 43211", "Triplane Mainroad..", "Active"),
        DriverItem("Mahesh Singh", "+91 98765 43212", "Triplane Mainroad..", "Inactive"),
        DriverItem("Vikram Rathod", "+91 98765 43213", "DL 04 GH 3456", "Active")
    )

    // Current displayed list (filtered by search)
    private val displayedDrivers = mutableListOf<DriverItem>()
    private lateinit var adapter: DriversAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminFleetDriversBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize display list
        displayedDrivers.addAll(allDrivers)

        // Setup RecyclerView
        binding.rvAdminDrivers.layoutManager = LinearLayoutManager(requireContext())
        adapter = DriversAdapter(displayedDrivers) { driver ->
            // Click card to Edit/Deactivate
            val bottomSheet = CreateDriverBottomSheet.newEditInstance(
                name = driver.name,
                phone = driver.phone,
                address = driver.address,
                status = driver.status,
                onSave = { newName, newPhone, newAddress ->
                    // Update master list
                    val masterIndex = allDrivers.indexOf(driver)
                    if (masterIndex != -1) {
                        allDrivers[masterIndex] = driver.copy(name = newName, phone = newPhone, address = newAddress)
                    }
                    // Refresh search/filtered list
                    filterList(binding.etSearchDrivers.text.toString())
                    Toast.makeText(requireContext(), "Driver updated successfully", Toast.LENGTH_SHORT).show()
                },
                onDeactivate = {
                    // Toggle Active/Inactive status
                    val masterIndex = allDrivers.indexOf(driver)
                    if (masterIndex != -1) {
                        val newStatus = if (allDrivers[masterIndex].status == "Active") "Inactive" else "Active"
                        allDrivers[masterIndex] = allDrivers[masterIndex].copy(status = newStatus)
                        val msg = if (newStatus == "Active") "Driver activated" else "Driver deactivated"
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                    filterList(binding.etSearchDrivers.text.toString())
                }
            )
            bottomSheet.show(parentFragmentManager, "CreateDriverBottomSheet")
        }
        binding.rvAdminDrivers.adapter = adapter

        binding.rvAdminDrivers.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 10) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(false)
                } else if (!recyclerView.canScrollVertically(-1)) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(true)
                }
            }
        })

        // Search text watcher
        binding.etSearchDrivers.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Plus button click
        binding.btnCreateDriver.setOnClickListener {
            val bottomSheet = CreateDriverBottomSheet.newInstance { name, phone, address ->
                // Add new active driver to the top of master list
                allDrivers.add(0, DriverItem(name, phone, address, "Active"))
                filterList(binding.etSearchDrivers.text.toString())
                binding.rvAdminDrivers.scrollToPosition(0)
                Toast.makeText(requireContext(), "Driver created successfully", Toast.LENGTH_SHORT).show()
            }
            bottomSheet.show(parentFragmentManager, "CreateDriverBottomSheet")
        }
    }

    private fun filterList(query: String) {
        displayedDrivers.clear()
        if (query.trim().isEmpty()) {
            displayedDrivers.addAll(allDrivers)
        } else {
            val cleanQuery = query.lowercase(Locale.getDefault())
            displayedDrivers.addAll(allDrivers.filter {
                it.name.lowercase(Locale.getDefault()).contains(cleanQuery) ||
                it.phone.contains(cleanQuery) ||
                it.address.lowercase(Locale.getDefault()).contains(cleanQuery)
            })
        }
        adapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class DriverItem(
        val name: String,
        val phone: String,
        val address: String,
        val status: String // "Active" or "Inactive"
    )

    private class DriversAdapter(
        private val items: List<DriverItem>,
        private val onItemClick: (DriverItem) -> Unit
    ) : RecyclerView.Adapter<DriversAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAdminFleetDriverBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemAdminFleetDriverBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: DriverItem) {
                binding.root.setOnClickListener {
                    onItemClick(item)
                }

                binding.tvDriverName.text = item.name
                binding.tvDriverPhone.text = item.phone
                binding.tvDriverAddress.text = item.address
                binding.tvDriverStatus.text = item.status

                // Dynamically change spec/address icon to matches Vikram's plate vs others
                if (item.address.startsWith("DL", ignoreCase = true) ||
                    item.address.startsWith("KA", ignoreCase = true) ||
                    item.address.startsWith("TN", ignoreCase = true)
                ) {
                    binding.ivDriverAddressIcon.setImageResource(R.drawable.ic_custom_car)
                } else {
                    binding.ivDriverAddressIcon.setImageResource(R.drawable.ic_location_pin)
                }

                if (item.status == "Active") {
                    binding.tvDriverStatus.text = "Active"
                    binding.tvDriverStatus.setBackgroundResource(R.drawable.bg_badge_success)
                    binding.tvDriverStatus.setTextColor(Color.parseColor("#065F46"))
                    binding.vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
                } else {
                    binding.tvDriverStatus.text = "Inactive"
                    binding.tvDriverStatus.setBackgroundResource(R.drawable.bg_badge_error)
                    binding.tvDriverStatus.setTextColor(Color.parseColor("#991B1B")) // Dark Red text
                    binding.vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_inactive)
                }
            }
        }
    }
}
