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

        // Hard cap the Tenure field at 6 (matching web: LOAN_TENURE_MAX_MONTHS).
        // Filter rejects any keystroke or paste that would produce a value > 6,
        // so the user can never type or paste "70", "7", "12", etc. The
        // submit-time check in submitLoanRequest() stays as defence in depth.
        // (Field watchers were dropped in the darx merge — the submit button
        // is always active now and persists a draft instead of gating on a
        // validateForm pass.)
        binding.etTenure.filters = arrayOf(
            android.text.InputFilter { source, start, end, dest, dstart, dend ->
                val resulting = StringBuilder(dest)
                    .replace(dstart, dend, source.subSequence(start, end).toString())
                    .toString()
                if (resulting.isEmpty()) return@InputFilter null
                val n = resulting.toIntOrNull() ?: return@InputFilter ""
                if (n in 0..6) null else ""
            }
        )

        binding.btnSubmitLoan.setOnClickListener {
            submitLoanRequest()
        }
        
        restoreDraft()
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
            }
        }

        binding.btnSelectRepaymentStartMonth.setOnClickListener {
            showDatePicker { y, m, _ ->
                val cal = Calendar.getInstance().apply { set(y, m, 1) }
                repaymentMonthIso = SimpleDateFormat("yyyy-MM", Locale.US).format(cal.time)
                binding.tvRepaymentStartMonth.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
                binding.tvRepaymentStartMonth.setTextColor(android.graphics.Color.parseColor("#101828"))
            }
        }

        binding.btnSelectOriginalDocument.setOnClickListener {
            showOriginalDocumentPicker()
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
                        tenureMonths = tenure.toInt(),
                        originalDocument = doc,
                        purpose = purpose,
                        notes = notes.ifBlank { null }
                    )
                )
                if (resp.success) {
                    clearDraft()
                    setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                    Toast.makeText(requireContext(), "Loan requested successfully", Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed to request loan", Toast.LENGTH_LONG).show()
                    binding.btnSubmitLoan.isEnabled = true
                    binding.btnSubmitLoan.alpha = 1f
                }
            } catch (e: retrofit2.HttpException) {
                // Toast the parsed error instead of the raw JSON +
                // convex stack trace. Matches CreateSalaryAdvanceSheet.
                Toast.makeText(
                    requireContext(),
                    LoanErrorParser.friendlyMessage(e),
                    Toast.LENGTH_LONG,
                ).show()
                binding.btnSubmitLoan.isEnabled = true
                binding.btnSubmitLoan.alpha = 1f
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

    override fun onPause() {
        super.onPause()
        saveDraft()
    }

    private fun saveDraft() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("loan_draft", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("n1_id", selectedNominee1?.id)
            putString("n1_name", selectedNominee1?.name)
            putString("n2_id", selectedNominee2?.id)
            putString("n2_name", selectedNominee2?.name)
            putString("amount", binding.etLoanAmount.text.toString())
            putString("interestType", interestType)
            putString("disbursedDateIso", disbursedDateIso)
            putString("disbursedDateText", binding.tvDisbursedDate.text.toString())
            putString("repaymentMonthIso", repaymentMonthIso)
            putString("repaymentMonthText", binding.tvRepaymentStartMonth.text.toString())
            putString("tenure", binding.etTenure.text.toString())
            putString("document", originalDocument)
            putString("purpose", binding.etLoanPurpose.text.toString())
            putString("notes", binding.etLoanNotes.text.toString())
        }.apply()
    }

    private fun restoreDraft() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("loan_draft", android.content.Context.MODE_PRIVATE)
        val n1Id = prefs.getString("n1_id", null)
        val n1Name = prefs.getString("n1_name", null)
        if (n1Id != null && n1Name != null) {
            selectedNominee1 = StaffData(id = n1Id, name = n1Name, phone = null, role = null, designation = null, status = null, employeeId = null, department = null)
            binding.tvNominee1.text = n1Name
            binding.tvNominee1.setTextColor(android.graphics.Color.parseColor("#101828"))
        }

        val n2Id = prefs.getString("n2_id", null)
        val n2Name = prefs.getString("n2_name", null)
        if (n2Id != null && n2Name != null) {
            selectedNominee2 = StaffData(id = n2Id, name = n2Name, phone = null, role = null, designation = null, status = null, employeeId = null, department = null)
            binding.tvNominee2.text = n2Name
            binding.tvNominee2.setTextColor(android.graphics.Color.parseColor("#101828"))
        }

        binding.etLoanAmount.setText(prefs.getString("amount", ""))
        
        interestType = prefs.getString("interestType", null)
        if (interestType != null && interestType != "Select Type") {
            binding.tvInterestType.text = interestType
            binding.tvInterestType.setTextColor(android.graphics.Color.parseColor("#101828"))
        }

        disbursedDateIso = prefs.getString("disbursedDateIso", null)
        val dDateText = prefs.getString("disbursedDateText", null)
        if (disbursedDateIso != null && dDateText != null && dDateText != "Select Date") {
            binding.tvDisbursedDate.text = dDateText
            binding.tvDisbursedDate.setTextColor(android.graphics.Color.parseColor("#101828"))
        }

        repaymentMonthIso = prefs.getString("repaymentMonthIso", null)
        val rMonthText = prefs.getString("repaymentMonthText", null)
        if (repaymentMonthIso != null && rMonthText != null && rMonthText != "Select Month") {
            binding.tvRepaymentStartMonth.text = rMonthText
            binding.tvRepaymentStartMonth.setTextColor(android.graphics.Color.parseColor("#101828"))
        }

        binding.etTenure.setText(prefs.getString("tenure", ""))
        
        originalDocument = prefs.getString("document", null)
        if (originalDocument != null && originalDocument != "Select the document") {
            binding.tvOriginalDocument.text = originalDocument
            binding.tvOriginalDocument.setTextColor(android.graphics.Color.parseColor("#101828"))
        }

        binding.etLoanPurpose.setText(prefs.getString("purpose", ""))
        binding.etLoanNotes.setText(prefs.getString("notes", ""))
    }

    private fun clearDraft() {
        requireContext().getSharedPreferences("loan_draft", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    companion object {
        const val RESULT_KEY = "LoanCreated"

        fun newInstance() = CreateLoanBottomSheet()
    }
}
