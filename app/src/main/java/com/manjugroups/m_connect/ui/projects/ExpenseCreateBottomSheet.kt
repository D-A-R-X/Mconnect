package com.manjugroups.m_connect.ui.projects

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateProjectExpenseRequest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Bottom sheet for logging a new project expense — the "Expense
 * Creation" screen in the mobile mockup. Picks category via chip
 * group, date via DatePicker, amount/notes via plain inputs. Posts
 * to `/api/projects/expenses/create` and emits a result so the
 * caller can refresh the list.
 *
 * Reuses for editing later — wire pre-fill via [newInstance] args.
 */
class ExpenseCreateBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private val projectId: String by lazy { requireArguments().getString(ARG_PROJECT_ID).orEmpty() }
    private val initialCategory: String? by lazy { arguments?.getString(ARG_INITIAL_CATEGORY) }

    private val paymentMethods = listOf(
        "cash" to "Cash",
        "bank_transfer" to "Bank Transfer",
        "upi" to "UPI",
        "cheque" to "Cheque",
        "card" to "Card",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_expense_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val categoryChips = view.findViewById<ChipGroup>(R.id.categoryChips)
        val etDate = view.findViewById<TextInputEditText>(R.id.etDate)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etAmount)
        val etPaymentMethod = view.findViewById<AutoCompleteTextView>(R.id.etPaymentMethod)
        val etNotes = view.findViewById<TextInputEditText>(R.id.etNotes)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)

        // Seed today's date so the picker has a sensible default.
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var pickedDateIso = fmt.format(Calendar.getInstance().time)
        etDate.setText(pickedDateIso)
        etDate.setOnClickListener {
            val cal = Calendar.getInstance().apply {
                runCatching { time = fmt.parse(pickedDateIso)!! }
            }
            DatePickerDialog(
                requireContext(),
                { _, y, m, d ->
                    cal.set(y, m, d)
                    pickedDateIso = fmt.format(cal.time)
                    etDate.setText(pickedDateIso)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ).show()
        }

        // Payment method dropdown.
        etPaymentMethod.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                paymentMethods.map { it.second },
            ),
        )
        var pickedPaymentMethod: String? = null
        etPaymentMethod.setOnClickListener { etPaymentMethod.showDropDown() }
        etPaymentMethod.setOnItemClickListener { _, _, position, _ ->
            pickedPaymentMethod = paymentMethods.getOrNull(position)?.first
        }

        // Pre-select category from args.
        val chipLabour = view.findViewById<Chip>(R.id.createChipLabour)
        val chipMaterials = view.findViewById<Chip>(R.id.createChipMaterials)
        val chipEquipment = view.findViewById<Chip>(R.id.createChipEquipment)
        val chipOther = view.findViewById<Chip>(R.id.createChipOther)
        when (initialCategory) {
            "labour" -> chipLabour.isChecked = true
            "materials" -> chipMaterials.isChecked = true
            "equipment" -> chipEquipment.isChecked = true
            "other" -> chipOther.isChecked = true
            else -> chipLabour.isChecked = true
        }

        btnCancel.setOnClickListener { dismissAllowingStateLoss() }

        btnSave.setOnClickListener {
            val category = when (categoryChips.checkedChipId) {
                R.id.createChipMaterials -> "materials"
                R.id.createChipEquipment -> "equipment"
                R.id.createChipOther -> "other"
                else -> "labour"
            }
            val amount = etAmount.text?.toString()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(requireContext(), "Enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (projectId.isBlank()) {
                Toast.makeText(requireContext(), "Missing project context", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = api.createProjectExpense(
                        session.bearerToken,
                        CreateProjectExpenseRequest(
                            projectId = projectId,
                            category = category,
                            amount = amount,
                            date = pickedDateIso,
                            paymentMethod = pickedPaymentMethod,
                            notes = etNotes.text?.toString()?.takeIf { it.isNotBlank() },
                            paid = false, // approver-only; backend rejects paid=true without approve perm
                        ),
                    )
                    if (resp.success) {
                        setFragmentResult(RESULT_KEY, Bundle.EMPTY)
                        Toast.makeText(
                            requireContext(),
                            "Expense saved",
                            Toast.LENGTH_SHORT,
                        ).show()
                        dismissAllowingStateLoss()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            resp.error ?: "Failed to save expense",
                            Toast.LENGTH_LONG,
                        ).show()
                        btnSave.isEnabled = true
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        e.message ?: "Network error",
                        Toast.LENGTH_LONG,
                    ).show()
                    btnSave.isEnabled = true
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "ExpenseCreated"
        private const val ARG_PROJECT_ID = "projectId"
        private const val ARG_INITIAL_CATEGORY = "initialCategory"

        fun newInstance(projectId: String, initialCategory: String? = null) =
            ExpenseCreateBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROJECT_ID, projectId)
                    initialCategory?.let { putString(ARG_INITIAL_CATEGORY, it) }
                }
            }
    }
}
