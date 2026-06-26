package com.manjugroups.m_connect.ui.library

import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.BottomSheetAllocateVehicleBinding
import java.util.Calendar
import java.util.Locale

class AllocateVehicleBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAllocateVehicleBinding? = null
    private val binding get() = _binding!!

    private var onAllocateCallback: ((String, String, String) -> Unit)? = null
    private var pricingType = "Per Km"

    companion object {
        fun newInstance(onAllocate: (String, String, String) -> Unit): AllocateVehicleBottomSheet {
            val sheet = AllocateVehicleBottomSheet()
            sheet.onAllocateCallback = onAllocate
            return sheet
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Semi-transparent status bar for floating modal effect
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAllocateVehicleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Remove standard BottomSheet dropshadow and elevation to match the mockup exactly
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        val bottomSheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            it.elevation = 0f
            it.background = null // Remove default background card shadow
            it.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Add red asterisks to required fields
        binding.tvLabelVehicle.text = Html.fromHtml("Vehicle <font color='#EF4444'>*</font>")
        binding.tvLabelDriverName.text = Html.fromHtml("Driver Name <font color='#EF4444'>*</font>")
        binding.tvLabelDriverPhone.text = Html.fromHtml("Driver Phone Number <font color='#EF4444'>*</font>")
        binding.tvLabelPickupTime.text = Html.fromHtml("Pickup Time <font color='#EF4444'>*</font>")
        binding.tvAmountLabel.text = Html.fromHtml("Per Km amount (Rs) <font color='#EF4444'>*</font>")

        setupVehicleSpinner()
        setupTimePicker()
        setupPricingToggle()

        // Default prefilled details to match the screenshot sample
        binding.etDriverName.setText("Divakar")
        binding.etDriverPhone.setText("9214782193")
        binding.etPickupTime.setText("02:30 AM")
        binding.etAmount.setText("1500")

        binding.btnSubmitAllocate.setOnClickListener {
            validateAndSubmit()
        }
    }

    private fun setupVehicleSpinner() {
        val vehicles = listOf(
            "Toyota Innova (TN01 AB 1234)",
            "Maruti Swift (TN01 CD 5678)",
            "Mahindra XUV (TN01 EF 9012)",
            "Force Traveller (TN01 GH 3456)"
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, vehicles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerVehicle.adapter = adapter
    }

    private fun setupTimePicker() {
        binding.etPickupTime.setOnClickListener {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)

            TimePickerDialog(requireContext(), { _, h, m ->
                val format = if (h >= 12) "PM" else "AM"
                val displayHour = when {
                    h == 0 -> 12
                    h > 12 -> h - 12
                    else -> h
                }
                binding.etPickupTime.setText(
                    String.format(Locale.getDefault(), "%02d:%02d %s", displayHour, m, format)
                )
            }, hour, minute, false).show()
        }
    }

    private fun setupPricingToggle() {
        binding.btnPricePerKm.setOnClickListener {
            pricingType = "Per Km"
            binding.btnPricePerKm.setBackgroundResource(R.drawable.bg_my_trips_tab_active)
            binding.btnPricePerKm.setTextColor(Color.WHITE)

            binding.btnPricePackage.background = null
            binding.btnPricePackage.setTextColor(Color.parseColor("#475467"))

            binding.tvAmountLabel.text = Html.fromHtml("Per Km amount (Rs) <font color='#EF4444'>*</font>")
        }

        binding.btnPricePackage.setOnClickListener {
            pricingType = "Package"
            binding.btnPricePackage.setBackgroundResource(R.drawable.bg_my_trips_tab_active)
            binding.btnPricePackage.setTextColor(Color.WHITE)

            binding.btnPricePerKm.background = null
            binding.btnPricePerKm.setTextColor(Color.parseColor("#475467"))

            binding.tvAmountLabel.text = Html.fromHtml("Package amount (Rs) <font color='#EF4444'>*</font>")
        }
    }

    private fun validateAndSubmit() {
        val vehicle = binding.spinnerVehicle.selectedItem?.toString() ?: ""
        val driverName = binding.etDriverName.text.toString().trim()
        val driverPhone = binding.etDriverPhone.text.toString().trim()
        val pickupTime = binding.etPickupTime.text.toString().trim()
        val amount = binding.etAmount.text.toString().trim()

        if (driverName.isEmpty() || driverPhone.isEmpty() || pickupTime.isEmpty() || amount.isEmpty()) {
            Toast.makeText(requireContext(), "Please fill all mandatory fields", Toast.LENGTH_SHORT).show()
            return
        }

        onAllocateCallback?.invoke(driverName, driverPhone, vehicle)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
