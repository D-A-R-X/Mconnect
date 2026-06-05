package com.manjugroups.m_connect.ui.library.loans

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
import com.manjugroups.m_connect.databinding.SheetCreateSalaryAdvanceBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApplyLoanRequest
import kotlinx.coroutines.launch

class CreateSalaryAdvanceBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetCreateSalaryAdvanceBinding? = null
    private val binding get() = _binding!!

    private val api = ApiService.create()
    private lateinit var session: SessionManager

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
        _binding = SheetCreateSalaryAdvanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        binding.etSalaryRequirement.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                validateForm()
            }
        })

        binding.btnSubmitAdvance.setOnClickListener {
            submitAdvance()
        }
        validateForm()
    }

    private fun validateForm() {
        val amountStr = binding.etSalaryRequirement.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        val isValid = amount != null && amount > 0

        if (isValid) {
            binding.btnSubmitAdvance.isEnabled = true
            binding.btnSubmitAdvance.isClickable = true
            binding.btnSubmitAdvance.isFocusable = true
            binding.btnSubmitAdvance.setBackgroundResource(R.drawable.bg_leave_submit_button)
            binding.btnSubmitAdvance.setTextColor(android.graphics.Color.WHITE)
        } else {
            binding.btnSubmitAdvance.isEnabled = false
            binding.btnSubmitAdvance.isClickable = false
            binding.btnSubmitAdvance.isFocusable = false
            binding.btnSubmitAdvance.setBackgroundResource(R.drawable.bg_loan_submit_button_disabled)
            binding.btnSubmitAdvance.setTextColor(android.graphics.Color.parseColor("#98A2B3"))
        }
    }

    private fun submitAdvance() {
        val amountStr = binding.etSalaryRequirement.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(requireContext(), "Please enter a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        val purpose = binding.etPurpose.text.toString().trim()

        binding.btnSubmitAdvance.isEnabled = false
        binding.btnSubmitAdvance.alpha = 0.5f

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.applyLoan(
                    session.bearerToken,
                    ApplyLoanRequest(
                        loanAmount = amount,
                        purpose = purpose.ifBlank { "Salary Advance" },
                        interestType = "Salary Advance"
                    )
                )
                if (resp.success) {
                    setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                    Toast.makeText(requireContext(), "Salary advance requested successfully", Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed to request advance", Toast.LENGTH_LONG).show()
                    binding.btnSubmitAdvance.isEnabled = true
                    binding.btnSubmitAdvance.alpha = 1f
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG).show()
                binding.btnSubmitAdvance.isEnabled = true
                binding.btnSubmitAdvance.alpha = 1f
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val RESULT_KEY = "AdvanceCreated"

        fun newInstance() = CreateSalaryAdvanceBottomSheet()
    }
}
