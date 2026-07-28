package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.BottomSheetManageRatesBinding
import com.manjugroups.m_connect.network.TravelDeskAgencySettings
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.UpdateTravelDeskSettingsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ManageRatesBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetManageRatesBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = TravelDeskApi.create()
    private var requestJob: Job? = null

    companion object {
        fun newInstance() = ManageRatesBottomSheet()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.CustomCameraBottomSheetTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetManageRatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let {
            it.setBackgroundColor(Color.TRANSPARENT)
            it.layoutParams = it.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * 0.92f).toInt()
            }
            com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).apply {
                state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        binding.tvLabelPerKm.text =
            Html.fromHtml("Per km amount (₹) <font color='#EF4444'>*</font>")
        binding.tvLabelPackage.text =
            Html.fromHtml("Package amount (₹) <font color='#EF4444'>*</font>")
        binding.btnSaveRates.setOnClickListener { validateAndSave() }
        loadSettings()
    }

    private fun loadSettings() {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            dismiss()
            return
        }
        setLoading(true, "Loading…")
        requestJob?.cancel()
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = api.getAgencySettings(token)
                if (_binding == null) return@launch
                val settings = response.settings
                if (!response.success || settings == null) {
                    Toast.makeText(
                        requireContext(),
                        response.error ?: "Couldn't load agency rates.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    bindSettings(settings)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding != null) {
                    Toast.makeText(
                        requireContext(),
                        "Couldn't load agency rates: ${e.localizedMessage ?: "network error"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                if (_binding != null) setLoading(false, "Save")
            }
        }
    }

    private fun bindSettings(settings: TravelDeskAgencySettings) = with(binding) {
        etPerKmAmount.setText(settings.kmRate.toInputText())
        etPackageAmount.setText(settings.packageAmount.toInputText())
        etBettaAmount.setText(settings.bettaAmount.toInputText())
        etPermitCharge.setText(settings.permitCharge.toInputText())
        etPermitTax.setText(settings.permitTax.toInputText())
        etStandingChargeWithAc.setText(settings.standingChargeWithAc.toInputText())
        etStandingChargeDurationMinutes.setText(
            settings.standingChargeDurationMinutes.toInputText(),
        )
        etWaitingAllowance.setText(settings.waitingAllowance.toInputText())
        etCancellationAllowance.setText(settings.cancellationAllowance.toInputText())
        etTollCharge.setText(settings.tollCharge.toInputText())
    }

    private fun validateAndSave() {
        val request = try {
            UpdateTravelDeskSettingsRequest(
                kmRate = requiredNumber(binding.etPerKmAmount, "Per km amount"),
                packageAmount = requiredNumber(binding.etPackageAmount, "Package amount"),
                bettaAmount = optionalNumber(binding.etBettaAmount, "Betta amount"),
                permitCharge = optionalNumber(binding.etPermitCharge, "Permit charge"),
                permitTax = optionalNumber(binding.etPermitTax, "Permit tax"),
                standingChargeWithAc = optionalNumber(
                    binding.etStandingChargeWithAc,
                    "Standing charge",
                ),
                standingChargeDurationMinutes = optionalNumber(
                    binding.etStandingChargeDurationMinutes,
                    "Standing duration",
                ),
                waitingAllowance = optionalNumber(
                    binding.etWaitingAllowance,
                    "Waiting allowance",
                ),
                cancellationAllowance = optionalNumber(
                    binding.etCancellationAllowance,
                    "Cancellation allowance",
                ),
                tollCharge = optionalNumber(binding.etTollCharge, "Toll charge"),
            )
        } catch (_: IllegalArgumentException) {
            return
        }

        val token = session.bearerToken
        setLoading(true, "Saving…")
        requestJob?.cancel()
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = api.updateAgencySettings(token, request)
                if (_binding == null) return@launch
                if (!response.success) {
                    Toast.makeText(
                        requireContext(),
                        response.error ?: "Couldn't save agency rates.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                session.ratePerKm = request.kmRate.toInputText()
                session.ratePackage = request.packageAmount.toInputText()
                Toast.makeText(requireContext(), "Rates saved successfully", Toast.LENGTH_SHORT).show()
                dismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding != null) {
                    Toast.makeText(
                        requireContext(),
                        "Couldn't save agency rates: ${e.localizedMessage ?: "network error"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                if (_binding != null) setLoading(false, "Save")
            }
        }
    }

    private fun requiredNumber(field: EditText, label: String): Double =
        optionalNumber(field, label) ?: run {
            field.error = "$label is required"
            field.requestFocus()
            throw IllegalArgumentException()
        }

    private fun optionalNumber(field: EditText, label: String): Double? {
        val raw = field.text.toString().trim()
        if (raw.isBlank()) return null
        val value = raw.toDoubleOrNull()
        if (value == null || !value.isFinite() || value < 0) {
            field.error = "$label must be zero or more"
            field.requestFocus()
            throw IllegalArgumentException()
        }
        field.error = null
        return value
    }

    private fun setLoading(loading: Boolean, label: String) {
        binding.btnSaveRates.isEnabled = !loading
        binding.btnSaveRates.text = label
    }

    private fun Double.toInputText(): String =
        if (this % 1.0 == 0.0) toLong().toString() else toString()

    override fun onDestroyView() {
        requestJob?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
