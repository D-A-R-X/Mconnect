package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.SheetCreateFineBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffData
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CreateFineBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCreateFineBinding? = null
    private val binding get() = _binding!!

    private val api by lazy { ApiService.create() }
    private val session by lazy { SessionManager(requireContext()) }
    private val staffList = mutableListOf<StaffData>()
    private var selectedStaff: StaffData? = null

    interface OnFineCreatedListener {
        fun onFineCreated(name: String, department: String, fineType: String, amount: Double, dateStr: String, photo: String?)
    }

    private var listener: OnFineCreatedListener? = null

    fun setOnFineCreatedListener(listener: OnFineCreatedListener) {
        this.listener = listener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                
                // No drop shadow needed, matches screenshot
                bottomSheet.elevation = 0f
            }
        }
        return dialog
    }

    override fun getTheme(): Int {
        return R.style.CustomCameraBottomSheetTheme
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCreateFineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadStaffList()

        binding.btnSelectEmployee.setOnClickListener {
            showEmployeePicker()
        }

        binding.btnCamera.setOnClickListener {
            Toast.makeText(requireContext(), "Camera feature coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnUploadFile.setOnClickListener {
            Toast.makeText(requireContext(), "Upload feature coming soon", Toast.LENGTH_SHORT).show()
        }

        binding.btnSubmitFine.setOnClickListener {
            submitFine()
        }
    }

    private fun loadStaffList() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaff(session.bearerToken, status = "active")
                if (resp.success && !resp.staff.isNullOrEmpty()) {
                    staffList.clear()
                    staffList.addAll(resp.staff)
                }
            } catch (_: Exception) {
                // Fallback to local items if network count fails
            }
        }
    }

    private fun showEmployeePicker() {
        val fallbackStaff = listOf(
            StaffData(id = "1", name = "Mari Muthu.R", phone = null, role = null, designation = null, status = "active", employeeId = null, department = "Sales Department", photo = null),
            StaffData(id = "2", name = "Sudalai Muthu.R", phone = null, role = null, designation = null, status = "active", employeeId = null, department = "Sales Department", photo = null)
        )
        
        val currentList = if (staffList.isNotEmpty()) staffList else fallbackStaff
        val names = currentList.map { it.name ?: "Unknown Employee" }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Employee")
            .setItems(names) { _, which ->
                val selected = currentList[which]
                selectedStaff = selected
                binding.tvSelectedEmployee.text = selected.name
                binding.tvSelectedEmployee.setTextColor(Color.parseColor("#1D2939"))
            }
            .show()
    }

    private fun submitFine() {
        val staffName = selectedStaff?.name ?: binding.tvSelectedEmployee.text.toString()
        if (staffName == "Select Employee" || (selectedStaff == null && staffList.isNotEmpty())) {
            Toast.makeText(requireContext(), "Please select an employee", Toast.LENGTH_SHORT).show()
            return
        }

        val fineType = binding.etFineType.text.toString().trim()
        if (fineType.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter fine type", Toast.LENGTH_SHORT).show()
            return
        }

        val amountStr = binding.etFineAmount.text.toString().trim()
        if (amountStr.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter amount", Toast.LENGTH_SHORT).show()
            return
        }

        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(requireContext(), "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val department = selectedStaff?.department ?: "Sales Department"
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())

        listener?.onFineCreated(
            name = staffName,
            department = department,
            fineType = fineType,
            amount = amount,
            dateStr = dateStr,
            photo = selectedStaff?.photo
        )

        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = CreateFineBottomSheet()
    }
}
