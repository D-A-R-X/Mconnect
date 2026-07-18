package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.BottomSheetManageRatesBinding

class ManageRatesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetManageRatesBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager

    companion object {
        fun newInstance(): ManageRatesBottomSheet {
            return ManageRatesBottomSheet()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-use standard bottom sheet theme that enables full width and rounded corner transparent hosts
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetManageRatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // ADJUST_RESIZE keeps the focused input above the soft keyboard;
        // without it the keyboard covers the rate fields and the save button
        // with no way to scroll them back into view.
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        // Make the outer sheet container transparent so the drawable's rounded corners and drop shadow show up correctly
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            it.setBackgroundColor(Color.TRANSPARENT)
            // Note: We deliberately do NOT set elevation to 0f here so that the default drop shadow remains visible
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Add red asterisks to the labels
        binding.tvLabelPerKm.text = Html.fromHtml("Per km amount (₹) <font color='#EF4444'>*</font>")
        binding.tvLabelPackage.text = Html.fromHtml("Package amount (₹) <font color='#EF4444'>*</font>")

        // Populate existing rates from session cache
        binding.etPerKmAmount.setText(session.ratePerKm)
        binding.etPackageAmount.setText(session.ratePackage)

        binding.btnSaveRates.setOnClickListener {
            validateAndSave()
        }
    }

    private fun validateAndSave() {
        val perKm = binding.etPerKmAmount.text.toString().trim()
        val packageAmt = binding.etPackageAmount.text.toString().trim()

        if (perKm.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter per km amount", Toast.LENGTH_SHORT).show()
            return
        }
        if (packageAmt.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter package amount", Toast.LENGTH_SHORT).show()
            return
        }

        // Save rates in session cache
        session.ratePerKm = perKm
        session.ratePackage = packageAmt

        Toast.makeText(requireContext(), "Rates saved successfully", Toast.LENGTH_SHORT).show()
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
