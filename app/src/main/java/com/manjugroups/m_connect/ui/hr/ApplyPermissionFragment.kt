package com.manjugroups.m_connect.ui.hr

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentApplyPermissionBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyPermissionRequest
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.util.*

class ApplyPermissionFragment : Fragment() {

    private data class ApiErrorResponse(
        val success: Boolean? = null,
        val error: String? = null,
        val message: String? = null
    )

    private var _binding: FragmentApplyPermissionBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private val gson = Gson()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentApplyPermissionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.etDate.setOnClickListener { showDatePicker() }
        binding.etFromTime.setOnClickListener { showTimePicker(binding.etFromTime) }
        binding.etToTime.setOnClickListener { showTimePicker(binding.etToTime) }

        binding.btnSubmit.setOnClickListener { submitPermission() }
    }

    private fun submitPermission() {
        val date = binding.etDate.text.toString()
        val from = binding.etFromTime.text.toString()
        val to = binding.etToTime.text.toString()
        val reason = binding.etReason.text.toString().trim()

        if (date.isBlank() || from.isBlank() || to.isBlank() || reason.isBlank()) {
            Toast.makeText(requireContext(), "Fill all fields", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isValidTimeRange(from, to)) {
            Toast.makeText(requireContext(), "To time must be after from time", Toast.LENGTH_SHORT).show()
            return
        }

        binding.tvSubmit.visibility = View.INVISIBLE
        binding.skeletonSubmit.visibility = View.VISIBLE
        SkeletonUtils.startSkeletonPulse(binding.skeletonSubmit)
        binding.btnSubmit.isClickable = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyPermission(session.bearerToken, ApplyPermissionRequest(date, from, to, reason))
                if (resp.success) {
                    Toast.makeText(requireContext(), "Permission applied!", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), parseErrorMessage(e), Toast.LENGTH_SHORT).show()
            }
            _binding?.tvSubmit?.visibility = View.VISIBLE
            _binding?.skeletonSubmit?.let { SkeletonUtils.stopSkeletonPulse(it) }
            _binding?.skeletonSubmit?.visibility = View.GONE
            _binding?.btnSubmit?.isClickable = true
        }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(requireContext(), { _, y, m, d ->
            binding.etDate.setText(String.format("%04d-%02d-%02d", y, m + 1, d))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showTimePicker(target: android.widget.EditText) {
        val cal = Calendar.getInstance()
        TimePickerDialog(requireContext(), { _, h, m ->
            target.setText(String.format("%02d:%02d", h, m))
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }

    private fun isValidTimeRange(fromTime: String, toTime: String): Boolean {
        val fromParts = fromTime.split(":").mapNotNull { it.toIntOrNull() }
        val toParts = toTime.split(":").mapNotNull { it.toIntOrNull() }
        if (fromParts.size != 2 || toParts.size != 2) return false

        val fromMinutes = fromParts[0] * 60 + fromParts[1]
        val toMinutes = toParts[0] * 60 + toParts[1]
        return toMinutes > fromMinutes
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
