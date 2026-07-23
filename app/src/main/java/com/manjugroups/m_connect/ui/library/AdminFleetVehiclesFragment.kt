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
import com.manjugroups.m_connect.databinding.FragmentAdminFleetVehiclesBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetVehicleBinding
import com.manjugroups.m_connect.network.CreateVehicleRequest
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskVehicle
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.manjugroups.m_connect.ui.common.showOnce

class AdminFleetVehiclesFragment : Fragment() {

    private var _binding: FragmentAdminFleetVehiclesBinding? = null
    private val binding get() = _binding!!

    private val api = TravelDeskApi.create()
    private lateinit var session: SessionManager
    private val vehicles = mutableListOf<VehicleItem>()
    private lateinit var adapter: VehiclesAdapter
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
        // Connectivity first: a DNS/socket failure surfaces as
        // "Unable to resolve host ...", which reads like a server fault when
        // the phone simply has no working internet.
        if (e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e is java.net.SocketTimeoutException
        ) {
            return "No internet connection. Check the network and pull to refresh."
        }
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
        _binding = FragmentAdminFleetVehiclesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.rvAdminVehicles.layoutManager = LinearLayoutManager(requireContext())
        adapter = VehiclesAdapter(vehicles) { item -> openEditVehicle(item) }
        binding.rvAdminVehicles.adapter = adapter

        binding.rvAdminVehicles.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 10) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(false)
                } else if (!recyclerView.canScrollVertically(-1)) {
                    (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(true)
                }
            }
        })

        binding.btnCreateVehicle.setOnClickListener {
            // On mobile the caller IS the agency, so we lock the Agency field
            // to the session's userName (the agency name) — the backend will
            // anyway default to the caller's own agency when travelAgencyId
            // is omitted, the lock is just to make that explicit in the UI.
            val agencyName = session.userName?.takeIf { it.isNotBlank() } ?: "Your Agency"
            val bottomSheet = CreateVehicleBottomSheet.newInstanceLockedToAgency(agencyName) { form ->
                submitCreate(form)
            }
            bottomSheet.showOnce(parentFragmentManager, "CreateVehicleBottomSheet")
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
                val resp = if (useMmsFleet) api.listMmsVehicles(token) else api.listVehicles(token)
                if (_binding == null) return@launch
                vehicles.clear()
                vehicles.addAll(resp.rows.map { mapVehicle(it) })
                adapter.notifyDataSetChanged()
                binding.tvVehiclesEmpty.visibility = if (vehicles.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                binding.tvVehiclesEmpty.visibility = View.VISIBLE
                binding.tvVehiclesEmpty.text = loadErrorMessage(e)
                Toast.makeText(
                    requireContext(),
                    "Couldn't load vehicles: ${loadErrorMessage(e)}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun openEditVehicle(item: VehicleItem) {
        CreateVehicleBottomSheet.newEditInstance(
            plate = item.plateNumber.takeIf { it != "—" }.orEmpty(),
            make = item.make,
            model = item.model,
            modelYear = item.modelYear,
            type = item.name.takeIf { it != "Vehicle" }.orEmpty(),
            capacity = item.capacity.takeIf { it != "—" }.orEmpty(),
            name = item.driverName.takeIf { it != "—" }.orEmpty(),
            phone = item.driverPhone.takeIf { it != "—" }.orEmpty(),
            whatsapp = item.whatsapp,
            agency = item.agency,
        ) { form ->
            submitUpdate(item.id, form)
        }.showOnce(parentFragmentManager, "EditVehicleBottomSheet")
    }

    private fun submitUpdate(id: String, form: VehicleFormResult) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        actionJob?.cancel()
        actionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.updateVehicle(
                    token,
                    com.manjugroups.m_connect.network.UpdateVehicleRequest(
                        id = id,
                        vehicleNumber = form.vehicleNumber.trim().takeIf { it.isNotBlank() },
                        make = form.make.trim().takeIf { it.isNotBlank() },
                        model = form.model.trim().takeIf { it.isNotBlank() },
                        modelYear = form.modelYear.trim().toIntOrNull(),
                        type = form.type.trim().takeIf { it.isNotBlank() },
                        capacity = form.capacity.trim().toIntOrNull(),
                        defaultDriverName = form.driverName.trim().takeIf { it.isNotBlank() },
                        defaultDriverPhone = form.driverPhone.trim().takeIf { it.isNotBlank() },
                        defaultDriverWhatsapp = form.driverWhatsapp.trim().takeIf { it.isNotBlank() },
                    ),
                )
                if (_binding == null) return@launch
                if (!resp.success) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Couldn't update vehicle.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "Vehicle updated", Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    "Couldn't update vehicle: ${loadErrorMessage(e)}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun submitCreate(form: VehicleFormResult) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        actionJob?.cancel()
        actionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.createVehicle(
                    token,
                    CreateVehicleRequest(
                        vehicleNumber = form.vehicleNumber.trim(),
                        make = form.make.trim().takeIf { it.isNotBlank() },
                        model = form.model.trim().takeIf { it.isNotBlank() },
                        modelYear = form.modelYear.trim().toIntOrNull(),
                        type = form.type.trim().takeIf { it.isNotBlank() },
                        capacity = form.capacity.trim().toIntOrNull(),
                        defaultDriverName = form.driverName.trim().takeIf { it.isNotBlank() },
                        defaultDriverPhone = form.driverPhone.trim().takeIf { it.isNotBlank() },
                        defaultDriverWhatsapp = form.driverWhatsapp.trim().takeIf { it.isNotBlank() },
                    ),
                )
                if (_binding == null) return@launch
                if (!resp.success) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Couldn't create vehicle.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                Toast.makeText(requireContext(), "Vehicle created successfully", Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                Toast.makeText(
                    requireContext(),
                    "Couldn't create vehicle: ${loadErrorMessage(e)}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun mapVehicle(v: TravelDeskVehicle): VehicleItem {
        val statusLabel = if ((v.status ?: "active").equals("active", ignoreCase = true)) "Available" else "Inactive"
        return VehicleItem(
            id = v.id,
            make = v.make ?: "",
            model = v.model ?: "",
            modelYear = v.modelYear?.toString() ?: "",
            whatsapp = v.defaultDriverWhatsapp ?: "",
            name = v.type ?: "Vehicle",
            plateNumber = v.vehicleNumber ?: "—",
            status = statusLabel,
            capacity = v.capacity?.toString() ?: "—",
            fuelType = "—",
            driverName = v.defaultDriverName ?: "—",
            driverPhone = v.defaultDriverPhone ?: "—",
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        actionJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    data class VehicleItem(
        val id: String,
        val make: String,
        val model: String,
        val modelYear: String,
        val whatsapp: String,
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
                binding.root.setOnClickListener { onItemClick(item) }
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
