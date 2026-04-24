package com.manjugroups.m_connect.ui.hr

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentApplyLeaveBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyLeaveRequest
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.*

class ApplyLeaveFragment : Fragment() {

    private data class ApiErrorResponse(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null
    )

    private var _binding: FragmentApplyLeaveBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val gson = Gson()
    private var leaveTypes = listOf("casual", "sick", "earned")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApplyLeaveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.etFromDate.setOnClickListener { showDatePicker(binding.etFromDate) }
        binding.etToDate.setOnClickListener { showDatePicker(binding.etToDate) }

        loadLeaveTypes()

        binding.btnSubmit.setOnClickListener { submitLeave() }
    }

    private fun loadLeaveTypes() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val policy = api.getPolicy(session.bearerToken)
                val types = mutableListOf<String>()
                val lp = policy.policy?.leave
                if ((lp?.casualPerYear ?: 1) > 0) types.add("casual")
                if ((lp?.sickPerYear ?: 1) > 0) types.add("sick")
                if ((lp?.earnedPerYear ?: 1) > 0) types.add("earned")
                lp?.types?.forEach { t ->
                    if (t !in listOf("casual", "sick", "earned") && t !in types) types.add(t)
                }
                if (types.isNotEmpty()) leaveTypes = types
            } catch (_: Exception) { }

            binding.spinnerLeaveType.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_dropdown_item,
                leaveTypes.map { it.replaceFirstChar { c -> c.uppercase() } }
            )
        }
    }

    private fun submitLeave() {
        val type = leaveTypes.getOrElse(binding.spinnerLeaveType.selectedItemPosition) { "casual" }
        val from = binding.etFromDate.text.toString()
        val to = binding.etToDate.text.toString()
        val reason = binding.etReason.text.toString().trim()

        if (from.isBlank() || to.isBlank() || reason.isBlank()) {
            Toast.makeText(requireContext(), "Fill all fields", Toast.LENGTH_SHORT).show()
            return
        }
        if (to < from) {
            Toast.makeText(requireContext(), "To date must be on or after from date", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvSubmit.visibility = View.INVISIBLE
        binding.progressSubmit.visibility = View.VISIBLE
        binding.btnSubmit.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyLeave(session.bearerToken, ApplyLeaveRequest(type, from, to, reason))
                if (resp.success) {
                    Toast.makeText(requireContext(), "Leave applied!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), parseErrorMessage(e), Toast.LENGTH_SHORT).show()
            }
            _binding?.tvSubmit?.visibility = View.VISIBLE
            _binding?.progressSubmit?.visibility = View.GONE
            _binding?.btnSubmit?.isClickable = true
        }
    }

    private fun showDatePicker(target: android.widget.EditText) {
        val cal = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            target.setText(String.format("%04d-%02d-%02d", y, m + 1, d))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun parseErrorMessage(error: Throwable): String {
        if (error is HttpException) {
            val body = error.response()?.errorBody()?.string()
            if (!body.isNullOrBlank()) {
                runCatching {
                    gson.fromJson(body, ApiErrorResponse::class.java)
                }.getOrNull()?.let { parsed ->
                    parsed.error?.takeIf { it.isNotBlank() }?.let { return it }
                    parsed.message?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return error.message ?: "Network error"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
