package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAdminFleetStaffBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetStaffBinding
import com.manjugroups.m_connect.network.CreateAgencyStaffRequest
import com.manjugroups.m_connect.network.TravelDeskAgencyStaff
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.UpdateAgencyStaffRequest
import com.manjugroups.m_connect.ui.common.showOnce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class AdminFleetStaffFragment : Fragment() {

    private var _binding: FragmentAdminFleetStaffBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = TravelDeskApi.create()
    private var loadJob: Job? = null
    private var actionJob: Job? = null
    private var staff: List<TravelDeskAgencyStaff> = emptyList()
    private val displayed = mutableListOf<TravelDeskAgencyStaff>()
    private lateinit var adapter: StaffAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAdminFleetStaffBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        if (!session.isExternalFleetAgency) {
            (parentFragment as? AdminFleetContainerFragment)?.openTab(0)
            return
        }

        adapter = StaffAdapter(displayed) { openEdit(it) }
        binding.rvAgencyStaff.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAgencyStaff.adapter = adapter
        binding.etSearchStaff.addTextChangedListener { filter(it?.toString().orEmpty()) }
        binding.btnCreateStaff.setOnClickListener {
            CreateAgencyStaffBottomSheet.newInstance(::submitCreate)
                .showOnce(parentFragmentManager, "CreateAgencyStaffBottomSheet")
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::session.isInitialized && session.isExternalFleetAgency) refresh()
    }

    private fun refresh() {
        val token = session.bearerToken
        if (token.isBlank() || _binding == null) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = api.listAgencyStaff(token)
                if (_binding == null) return@launch
                if (!response.success) {
                    showLoadError(response.error ?: "Couldn't load agency staff.")
                    return@launch
                }
                staff = response.rows
                filter(binding.etSearchStaff.text.toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding != null) {
                    showLoadError(e.localizedMessage ?: "Couldn't load agency staff.")
                }
            }
        }
    }

    private fun showLoadError(message: String) {
        binding.tvStaffEmpty.visibility = View.VISIBLE
        binding.tvStaffEmpty.text = message
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun filter(query: String) {
        val clean = query.trim().lowercase(Locale.getDefault())
        displayed.clear()
        displayed.addAll(
            if (clean.isBlank()) staff else staff.filter {
                it.name.lowercase(Locale.getDefault()).contains(clean) ||
                    it.phone.contains(clean) ||
                    it.whatsapp.orEmpty().contains(clean)
            },
        )
        adapter.notifyDataSetChanged()
        binding.tvStaffEmpty.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        binding.tvStaffEmpty.text = "No agency staff yet.\nTap + to add one."
    }

    private fun openEdit(item: TravelDeskAgencyStaff) {
        CreateAgencyStaffBottomSheet.newEditInstance(
            staff = item,
            onSave = { submitUpdate(item.id, it) },
            onStatusChange = { submitStatus(item.id, it) },
        ).showOnce(parentFragmentManager, "EditAgencyStaffBottomSheet")
    }

    private fun submitCreate(form: AgencyStaffFormResult) {
        runAction(
            successMessage = "Staff member created",
            request = {
                api.createAgencyStaff(
                    session.bearerToken,
                    CreateAgencyStaffRequest(
                        name = form.name,
                        phone = form.phone,
                        whatsapp = form.whatsapp.takeIf(String::isNotBlank),
                    ),
                ).let { it.success to it.error }
            },
        )
    }

    private fun submitUpdate(id: String, form: AgencyStaffFormResult) {
        runAction(
            successMessage = "Staff member updated",
            request = {
                api.updateAgencyStaff(
                    session.bearerToken,
                    UpdateAgencyStaffRequest(
                        id = id,
                        name = form.name,
                        phone = form.phone,
                        whatsapp = form.whatsapp,
                    ),
                ).let { it.success to it.error }
            },
        )
    }

    private fun submitStatus(id: String, status: String) {
        runAction(
            successMessage = if (status == "active") "Staff member activated" else "Staff member deactivated",
            request = {
                api.updateAgencyStaff(
                    session.bearerToken,
                    UpdateAgencyStaffRequest(id = id, status = status),
                ).let { it.success to it.error }
            },
        )
    }

    private fun runAction(
        successMessage: String,
        request: suspend () -> Pair<Boolean, String?>,
    ) {
        actionJob?.cancel()
        actionJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (success, error) = request()
                if (_binding == null) return@launch
                if (!success) {
                    Toast.makeText(
                        requireContext(),
                        error ?: "Staff action failed.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                Toast.makeText(requireContext(), successMessage, Toast.LENGTH_SHORT).show()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding != null) {
                    Toast.makeText(
                        requireContext(),
                        e.localizedMessage ?: "Staff action failed.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        actionJob?.cancel()
        super.onDestroyView()
        _binding = null
    }

    private class StaffAdapter(
        private val items: List<TravelDeskAgencyStaff>,
        private val onClick: (TravelDeskAgencyStaff) -> Unit,
    ) : RecyclerView.Adapter<StaffAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                ItemAdminFleetStaffBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(
            private val binding: ItemAdminFleetStaffBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(item: TravelDeskAgencyStaff) {
                binding.root.setOnClickListener { onClick(item) }
                binding.tvStaffName.text = item.name
                binding.tvStaffPhone.text = item.phone
                binding.tvStaffWhatsapp.text = item.whatsapp?.takeIf(String::isNotBlank) ?: "—"
                val active = item.status.equals("active", ignoreCase = true)
                binding.tvStaffStatus.text = if (active) "Active" else "Inactive"
                binding.tvStaffStatus.setBackgroundResource(
                    if (active) R.drawable.bg_badge_success else R.drawable.bg_badge_error,
                )
                binding.tvStaffStatus.setTextColor(
                    Color.parseColor(if (active) "#065F46" else "#991B1B"),
                )
            }
        }
    }
}
