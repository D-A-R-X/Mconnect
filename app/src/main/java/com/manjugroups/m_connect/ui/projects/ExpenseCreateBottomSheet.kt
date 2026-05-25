package com.manjugroups.m_connect.ui.projects

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
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
 * Bottom sheet for logging a new project expense — the "Expense Creation"
 * sheet from the mobile Figma. Five labeled inputs:
 *   - Expense Category (dropdown: Labour / Materials / Equipment / Other)
 *   - Date (opens DateFilterBottomSheet)
 *   - Money (numeric amount)
 *   - Notes (optional multi-line text)
 *   - Payment Method (dropdown: Cash / Bank Transfer / UPI / Cheque / Card)
 *
 * Posts to `/api/projects/expenses/create` and emits a result so the
 * Expenses list can refresh.
 */
class ExpenseCreateBottomSheet : BottomSheetDialogFragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private val projectId: String by lazy {
        requireArguments().getString(ARG_PROJECT_ID).orEmpty()
    }
    private val initialCategory: String? by lazy {
        arguments?.getString(ARG_INITIAL_CATEGORY)
    }

    /** Server slug -> display label, in the same order the picker shows. */
    private val categories = listOf(
        "labour" to "Labour",
        "materials" to "Materials",
        "equipment" to "Equipments",
        "other" to "Other",
    )
    private val paymentMethods = listOf(
        "cash" to "Cash",
        "bank_transfer" to "Bank Transfer",
        "upi" to "UPI",
        "cheque" to "Cheque",
        "card" to "Card",
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // Default BottomSheetDialog starts at STATE_HALF_EXPANDED, which
        // leaves the Save It button below the fold once the keyboard
        // opens. Force STATE_EXPANDED + skipCollapsed so the sheet takes
        // the full available height and the scroll view inside can scroll
        // up under the keyboard.
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // Resize the dialog window when the IME shows so the Save It CTA
        // stays above the keyboard instead of being clipped off-screen.
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_expense_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        val etCategory = view.findViewById<AutoCompleteTextView>(R.id.etCategory)
        val etDate = view.findViewById<TextInputEditText>(R.id.etDate)
        val etAmount = view.findViewById<TextInputEditText>(R.id.etAmount)
        val etPaymentMethod = view.findViewById<AutoCompleteTextView>(R.id.etPaymentMethod)
        val etNotes = view.findViewById<TextInputEditText>(R.id.etNotes)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSave)

        // ── Category dropdown ───────────────────────────────────────────
        etCategory.setAdapter(
            ArrayAdapter(
                requireContext(),
                android.R.layout.simple_list_item_1,
                categories.map { it.second },
            ),
        )
        var pickedCategory: String =
            initialCategory?.takeIf { initial -> categories.any { it.first == initial } }
                ?: "labour"
        etCategory.setText(
            categories.firstOrNull { it.first == pickedCategory }?.second.orEmpty(),
            /* filter = */ false,
        )
        etCategory.setOnClickListener { etCategory.showDropDown() }
        etCategory.setOnItemClickListener { _, _, position, _ ->
            pickedCategory = categories.getOrNull(position)?.first ?: pickedCategory
        }

        // ── Date — defaults to today, taps open the DateFilter sheet ────
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var pickedDateIso = fmt.format(Calendar.getInstance().time)
        etDate.setText(pickedDateIso)
        etDate.setOnClickListener {
            DateFilterBottomSheet.newInstance()
                .show(parentFragmentManager, "date_filter")
            parentFragmentManager.setFragmentResultListener(
                DateFilterBottomSheet.REQUEST_KEY,
                viewLifecycleOwner,
            ) { _, bundle ->
                pickedDateIso = bundle.getString(DateFilterBottomSheet.RESULT_FROM)
                    ?: pickedDateIso
                etDate.setText(pickedDateIso)
            }
        }

        // ── Payment method dropdown ─────────────────────────────────────
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

        // ── Submit ──────────────────────────────────────────────────────
        btnSave.setOnClickListener {
            val amount = etAmount.text?.toString()?.toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(
                    requireContext(),
                    "Enter a valid amount",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            if (projectId.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "Missing project context",
                    Toast.LENGTH_SHORT,
                ).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = api.createProjectExpense(
                        session.bearerToken,
                        CreateProjectExpenseRequest(
                            projectId = projectId,
                            category = pickedCategory,
                            amount = amount,
                            date = pickedDateIso,
                            paymentMethod = pickedPaymentMethod,
                            notes = etNotes.text?.toString()?.takeIf { it.isNotBlank() },
                            // approver-only; backend rejects paid=true
                            // without the projects.expenses.approve key.
                            paid = false,
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

        playEntryAnimations(view)
    }

    private fun playEntryAnimations(view: View) {
        // Walk up from each input's EditText to its TextInputLayout
        // parent (the .parent.parent hop) and stagger a slide-up fade.
        val formItems = listOfNotNull(
            view.findViewById<View>(R.id.etCategory)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etDate)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etAmount)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etNotes)?.parent?.parent as? View,
            view.findViewById<View>(R.id.etPaymentMethod)?.parent?.parent as? View,
            view.findViewById<View>(R.id.btnSave),
        )
        formItems.forEachIndexed { i, item ->
            item.alpha = 0f
            item.translationY = 20f
            item.animate().alpha(1f).translationY(0f)
                .setDuration(350L)
                .setStartDelay(100L + i * 50L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
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
