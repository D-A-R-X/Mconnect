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

        binding.btnSubmitAdvance.setOnClickListener {
            submitAdvance()
        }
        
        restoreDraft()
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
                    clearDraft()
                    setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                    Toast.makeText(requireContext(), "Salary advance requested successfully", Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                } else {
                    Toast.makeText(requireContext(), resp.error ?: "Failed to request advance", Toast.LENGTH_LONG).show()
                    binding.btnSubmitAdvance.isEnabled = true
                    binding.btnSubmitAdvance.alpha = 1f
                }
            } catch (e: retrofit2.HttpException) {
                // Surface a clean human-readable message instead of the
                // raw JSON + convex stack trace that used to land in an
                // AlertDialog (`{"success":false,"error":"Uncaught
                // Error: ...\n    at assertNoBlocking"}`).
                Toast.makeText(
                    requireContext(),
                    LoanErrorParser.friendlyMessage(e),
                    Toast.LENGTH_LONG,
                ).show()
                binding.btnSubmitAdvance.isEnabled = true
                binding.btnSubmitAdvance.alpha = 1f
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

    override fun onPause() {
        super.onPause()
        saveDraft()
    }

    private fun saveDraft() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("advance_draft", android.content.Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("amount", binding.etSalaryRequirement.text.toString())
            putString("purpose", binding.etPurpose.text.toString())
        }.apply()
    }

    private fun restoreDraft() {
        if (_binding == null) return
        val prefs = requireContext().getSharedPreferences("advance_draft", android.content.Context.MODE_PRIVATE)
        binding.etSalaryRequirement.setText(prefs.getString("amount", ""))
        binding.etPurpose.setText(prefs.getString("purpose", ""))
    }

    private fun clearDraft() {
        requireContext().getSharedPreferences("advance_draft", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    companion object {
        const val RESULT_KEY = "AdvanceCreated"

        fun newInstance() = CreateSalaryAdvanceBottomSheet()
    }
}
