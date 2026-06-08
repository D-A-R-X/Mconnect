package com.manjugroups.m_connect.ui.library.loans

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.SheetCreateLoanBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyLoanRequest
import com.manjugroups.m_connect.network.StaffData
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CreateLoanBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCreateLoanBinding? = null
    private val binding get() = _binding!!

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private val staffList = mutableListOf<StaffData>()
    
    private var selectedNominee1: StaffData? = null
    private var selectedNominee2: StaffData? = null
    
    private var interestType: String? = null
    private var disbursedDateIso: String? = null
    private var repaymentMonthIso: String? = null
    private var originalDocument: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(it)
                val metrics = resources.displayMetrics
                val peekH = (metrics.heightPixels * 0.55f).toInt()
                behavior.isFitToContents = true
                behavior.peekHeight = peekH
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.skipCollapsed = false
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCreateLoanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        setupSelectors()
        loadStaffList()

        binding.etLoanAmount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateForm()
            }
        })
        binding.etTenure.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateForm()
            }
        })
        binding.etLoanPurpose.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateForm()
            }
        })

        binding.btnSubmitLoan.setOnClickListener {
            submitLoanRequest()
        }
        validateForm()
    }

    private fun setupSelectors() {
        binding.btnSelectNominee1.setOnClickListener {
            showNomineePicker(1)
        }

        binding.btnSelectNominee2.setOnClickListener {
            showNomineePicker(2)
        }

        binding.btnSelectInterestType.setOnClickListener {
            showInterestTypePicker()
        }

        binding.btnSelectDisbursedDate.setOnClickListener {
            showDatePicker { y, m, d ->
                val cal = Calendar.getInstance().apply { set(y, m, d) }
                disbursedDateIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
                binding.tvDisbursedDate.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(cal.time)
                binding.tvDisbursedDate.setTextColor(android.graphics.Color.parseColor("#101828"))
                validateForm()
            }
        }

        binding.btnSelectRepaymentStartMonth.setOnClickListener {
            showDatePicker { y, m, _ ->
                val cal = Calendar.getInstance().apply { set(y, m, 1) }
                repaymentMonthIso = SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
                binding.tvRepaymentStartMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                binding.tvRepaymentStartMonth.setTextColor(android.graphics.Color.parseColor("#101828"))
                validateForm()
            }
        }

        binding.btnSelectOriginalDocument.setOnClickListener {
            showOriginalDocumentPicker()
        }
    }

    private fun validateForm() {
        val n1 = selectedNominee1
        val n2 = selectedNominee2
        val amountStr = binding.etLoanAmount.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        val disDate = disbursedDateIso
        val repMonth = repaymentMonthIso
        val tenureStr = binding.etTenure.text.toString().trim()
        val tenure = tenureStr.toDoubleOrNull()
        val doc = originalDocument
        val purpose = binding.etLoanPurpose.text.toString().trim()

        val isValid = n1 != null && 
                      n2 != null && 
                      n1.id != n2.id && 
                      amount != null && amount > 0 && 
                      !disDate.isNullOrBlank() && 
                      !repMonth.isNullOrBlank() && 
                      tenure != null && tenure > 0 && tenure <= 6.0 && 
                      !doc.isNullOrBlank() && 
                      purpose.isNotBlank()

        if (isValid) {
            binding.btnSubmitLoan.isEnabled = true
            binding.btnSubmitLoan.isClickable = true
            binding.btnSubmitLoan.isFocusable = true
            binding.btnSubmitLoan.setBackgroundResource(R.drawable.bg_leave_submit_button)
            binding.btnSubmitLoan.setTextColor(android.graphics.Color.WHITE)
        } else {
            binding.btnSubmitLoan.isEnabled = false
            binding.btnSubmitLoan.isClickable = false
            binding.btnSubmitLoan.isFocusable = false
            binding.btnSubmitLoan.setBackgroundResource(R.drawable.bg_loan_submit_button_disabled)
            binding.btnSubmitLoan.setTextColor(android.graphics.Color.parseColor("#98A2B3"))
        }
    }

    private fun loadStaffList() {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.getStaff(session.bearerToken) }
                .onSuccess { response ->
                    if (response.success) {
                        staffList.clear()
                        staffList.addAll(response.staff.filter { it.id != session.staffId })
                    }
                }
        }
    }

    private fun showNomineePicker(nomineeNumber: Int) {
        if (staffList.isEmpty()) {
            Toast.makeText(requireContext(), "Loading staff members...", Toast.LENGTH_SHORT).show()
            return
        }

        val names = staffList.map { it.name ?: "Unknown Staff" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Select Nominee $nomineeNumber")
            .setItems(names) { _, which ->
                val selected = staffList[which]
                if (nomineeNumber == 1) {
                    selectedNominee1 = selected
                    binding.tvNominee1.text = selected.name
                    binding.tvNominee1.setTextColor(android.graphics.Color.parseColor("#101828"))
                } else {
                    selectedNominee2 = selected
                    binding.tvNominee2.text = selected.name
                    binding.tvNominee2.setTextColor(android.graphics.Color.parseColor("#101828"))
                }
                validateForm()
            }
            .show()
    }

    private fun showInterestTypePicker() {
        val types = arrayOf("Flat", "Reducing")
        AlertDialog.Builder(requireContext())
            .setTitle("Select Interest Type")
            .setItems(types) { _, which ->
                val type = types[which]
                interestType = type
                binding.tvInterestType.text = type
                binding.tvInterestType.setTextColor(android.graphics.Color.parseColor("#101828"))
                validateForm()
            }
            .show()
    }

    private fun showOriginalDocumentPicker() {
        val docs = arrayOf("Aadhaar Card", "PAN Card", "Salary Slip", "Bond Certificate", "Other")
        AlertDialog.Builder(requireContext())
            .setTitle("Select Original Document")
            .setItems(docs) { _, which ->
                val doc = docs[which]
                originalDocument = doc
                binding.tvOriginalDocument.text = doc
                binding.tvOriginalDocument.setTextColor(android.graphics.Color.parseColor("#101828"))
                validateForm()
            }
            .show()
    }

    private fun showDatePicker(onDateSelected: (year: Int, month: Int, day: Int) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d -> onDateSelected(y, m, d) },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun submitLoanRequest() {
        val n1 = selectedNominee1
        if (n1 == null) {
            Toast.makeText(requireContext(), "Please select Nominee 1", Toast.LENGTH_SHORT).show()
            return
        }

        val n2 = selectedNominee2
        if (n2 == null) {
            Toast.makeText(requireContext(), "Please select Nominee 2", Toast.LENGTH_SHORT).show()
            return
        }

        if (n1.id == n2.id) {
            Toast.makeText(requireContext(), "Nominee 1 and Nominee 2 cannot be the same person", Toast.LENGTH_SHORT).show()
            return
        }

        val amountStr = binding.etLoanAmount.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(requireContext(), "Please enter a valid loan amount", Toast.LENGTH_SHORT).show()
            return
        }

        val disDate = disbursedDateIso
        if (disDate.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Please select a disbursed date", Toast.LENGTH_SHORT).show()
            return
        }

        val repMonth = repaymentMonthIso
        if (repMonth.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Please select a repayment start month", Toast.LENGTH_SHORT).show()
            return
        }

        val tenureStr = binding.etTenure.text.toString().trim()
        val tenure = tenureStr.toDoubleOrNull()
        if (tenure == null || tenure <= 0) {
            Toast.makeText(requireContext(), "Please enter a valid tenure", Toast.LENGTH_SHORT).show()
            return
        }
        if (tenure > 6.0) {
            Toast.makeText(requireContext(), "Tenure cannot exceed 6 months", Toast.LENGTH_SHORT).show()
            return
        }

        val doc = originalDocument
        if (doc.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Please select the original document to submit", Toast.LENGTH_SHORT).show()
            return
        }

        val purpose = binding.etLoanPurpose.text.toString().trim()
        if (purpose.isBlank()) {
            Toast.makeText(requireContext(), "Please enter the purpose of the loan", Toast.LENGTH_SHORT).show()
            return
        }

        val notes = binding.etLoanNotes.text.toString().trim()

        binding.btnSubmitLoan.isEnabled = false
        binding.btnSubmitLoan.alpha = 0.5f

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyLoan(
                    session.bearerToken,
                    ApplyLoanRequest(
                        nominee1Id = n1.id,
                        nominee1Name = n1.name,
                        nominee2Id = n2.id,
                        nominee2Name = n2.name,
                        loanAmount = amount,
                        interestType = interestType ?: "Flat",
                        disbursedDate = disDate,
                        repaymentStartMonth = repMonth,
                        tenureMonths = tenure,
                        originalDocument = doc,
                        purpose = purpose,
                        notes = notes.ifBlank { null }
                    )
                )
                if (resp.success) {
                    setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                    Toast.makeText(requireContext(), "Loan requested successfully", Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed to request loan", Toast.LENGTH_LONG).show()
                    binding.btnSubmitLoan.isEnabled = true
                    binding.btnSubmitLoan.alpha = 1f
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG).show()
                binding.btnSubmitLoan.isEnabled = true
                binding.btnSubmitLoan.alpha = 1f
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val RESULT_KEY = "LoanCreated"

        fun newInstance() = CreateLoanBottomSheet()
    }
}
