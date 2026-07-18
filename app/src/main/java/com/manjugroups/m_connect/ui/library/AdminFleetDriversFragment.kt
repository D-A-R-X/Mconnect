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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAdminFleetDriversBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetDriverBinding
import com.manjugroups.m_connect.network.CreateDriverRequest
import com.manjugroups.m_connect.network.SetDriverStatusRequest
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskDriver
import com.manjugroups.m_connect.network.UpdateDriverRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

class AdminFleetDriversFragment : Fragment() {

    private var _binding: FragmentAdminFleetDriversBinding? = null
    private val binding get() = _binding!!

    private val api = TravelDeskApi.create()
    private lateinit var session: SessionManager

    // Source of truth (server snapshot) vs. what's currently rendered (after
    // local client-side search filtering). Server is consulted on refresh and
    // after any create/update/setStatus call so the list never lies about
    // post-action state.
    private val allDrivers = mutableListOf<DriverItem>()
    private val displayedDrivers = mutableListOf<DriverItem>()
    private lateinit var adapter: DriversAdapter
    private var loadJob: Job? = null
    private var actionJob: Job? = null

    /**
     * True when the signed-in principal is in-house fleet staff rather than an
     * external agency. Chooses which backend the portal talks to: agencies use
     * the travel-desk routes, staff use the MMS dispatch routes (their token
     * would 401 on the agency ones, which the watchdog reads as a dead session).
     */
    private val useMmsFleet: Boolean
        get() = !session.designation.orEmpty()
            .trim().equals("External Fleet", ignoreCase = true)

    /**
     * Human-readable load failure.
     *
     * A 404 here has one meaning: the MMS dispatch routes aren't on the server
     * this build points at. Surfacing a bare "HTTP 404" makes that look like a
     * bug in the screen rather than a backend that hasn't been deployed yet.
     */
    private fun loadErrorMessage(e: Exception): String {
        val code = (e as? retrofit2.HttpException)?.code()
        return when (code) {
            404 -> "Fleet dispatch isn't available on this server yet — it needs the latest backend deploy."
            403 -> "You don't have fleet permissions (marketing.fleet.view)."
            else -> e.message ?: "network error"
        }
    }

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
        session = SessionManager(requireContext())

        binding.rvAdminDrivers.layoutManager = LinearLayoutManager(requireContext())
        adapter = DriversAdapter(displayedDrivers) { driver ->
            val bottomSheet = CreateDriverBottomSheet.newEditInstance(
                name = driver.name,
                phone = driver.phone,
                address = driver.address,
                status = driver.status,
                onSave = { newName, newPhone, newAddress ->
                    submitUpdate(driver.id, newName, newPhone, newAddress)
                },
                onDeactivate = {
                    val nextStatus = if (driver.status == "Active") "inactive" else "active"
                    submitSetStatus(driver.id, nextStatus)
                }
            )
            bottomSheet.showOnce(parentFragmentManager, "CreateDriverBottomSheet")
        }
        binding.rvAdminDrivers.adapter = adapter

        binding.rvAdminDrivers.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 10) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(false)
                } else if (!recyclerView.canScrollVertically(-1)) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(true)
                }
            }
        })

        binding.etSearchDrivers.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnCreateDriver.setOnClickListener {
            val bottomSheet = CreateDriverBottomSheet.newInstance { name, phone, address ->
                submitCreate(name, phone, address)
            }
            bottomSheet.showOnce(parentFragmentManager, "CreateDriverBottomSheet")
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (_binding == null) return
        val token = session.bearerToken
        if (token.isBlank()) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = if (useMmsFleet) api.listMmsDrivers(token) else api.listDrivers(token)
                if (_binding == null) return@launch
                allDrivers.clear()
                allDrivers.addAll(resp.rows.map { mapDriver(it) })
                filterList(binding.etSearchDrivers.text.toString())
                binding.tvDriversEmpty.visibility =
                    if (allDrivers.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    "Couldn't load drivers: ${loadErrorMessage(e)}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun submitCreate(name: String, phone: String, address: String) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        actionJob?.cancel()
        actionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = CreateDriverRequest(
                    name = name.trim(),
                    phone = phone.trim(),
                    address = address.trim().takeIf { it.isNotBlank() },
                )
                val resp = if (useMmsFleet) api.createMmsDriver(token, request)
                    else api.createDriver(token, request)
                if (_binding == null) return@launch
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Couldn't create driver.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "Driver created successfully", Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(requireContext(), "Couldn't create driver: ${loadErrorMessage(e)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitUpdate(id: String, name: String, phone: String, address: String) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        actionJob?.cancel()
        actionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = UpdateDriverRequest(
                    id = id,
                    name = name.trim().takeIf { it.isNotBlank() },
                    phone = phone.trim().takeIf { it.isNotBlank() },
                    address = address.trim().takeIf { it.isNotBlank() },
                )
                val resp = if (useMmsFleet) api.updateMmsDriver(token, request)
                    else api.updateDriver(token, request)
                if (_binding == null) return@launch
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Couldn't update driver.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "Driver updated successfully", Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(requireContext(), "Couldn't update driver: ${loadErrorMessage(e)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun submitSetStatus(id: String, status: String) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        actionJob?.cancel()
        actionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val req = SetDriverStatusRequest(id = id, status = status)
                val resp = if (useMmsFleet) api.setMmsDriverStatus(token, req)
                    else api.setDriverStatus(token, req)
                if (_binding == null) return@launch
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Couldn't change status.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val msg = if (status.equals("active", ignoreCase = true)) "Driver activated" else "Driver deactivated"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(requireContext(), "Couldn't change status: ${loadErrorMessage(e)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun mapDriver(d: TravelDeskDriver): DriverItem {
        val statusLabel = if (d.status.equals("active", ignoreCase = true)) "Active" else "Inactive"
        return DriverItem(
            id = d.id,
            name = d.name,
            // Internal fleet drivers may have no phone on record.
            phone = d.phone.orEmpty(),
            address = d.address ?: "",
            status = statusLabel,
        )
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
        loadJob?.cancel()
        actionJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    data class DriverItem(
        val id: String,
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
                binding.root.setOnClickListener { onItemClick(item) }

                binding.tvDriverName.text = item.name
                binding.tvDriverPhone.text = item.phone
                binding.tvDriverAddress.text = item.address.ifBlank { "—" }
                binding.tvDriverStatus.text = item.status

                if (item.address.startsWith("DL", ignoreCase = true) ||
                    item.address.startsWith("KA", ignoreCase = true) ||
                    item.address.startsWith("TN", ignoreCase = true)
                ) {
                    binding.ivDriverAddressIcon.setImageResource(R.drawable.ic_custom_car)
                } else {
                    binding.ivDriverAddressIcon.setImageResource(R.drawable.ic_location_pin)
                }

                if (item.status == "Active") {
                    binding.tvDriverStatus.setBackgroundResource(R.drawable.bg_badge_success)
                    binding.tvDriverStatus.setTextColor(Color.parseColor("#065F46"))
                    binding.vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_active)
                } else {
                    binding.tvDriverStatus.setBackgroundResource(R.drawable.bg_badge_error)
                    binding.tvDriverStatus.setTextColor(Color.parseColor("#991B1B"))
                    binding.vStatusDot.setBackgroundResource(R.drawable.bg_status_dot_inactive)
                }
            }
        }
    }
}
