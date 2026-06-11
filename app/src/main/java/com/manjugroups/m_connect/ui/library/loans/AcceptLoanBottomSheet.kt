package com.manjugroups.m_connect.ui.library.loans

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.BottomSheetAcceptLoanBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApproveLoanRequest
import com.manjugroups.m_connect.network.LoanData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

class AcceptLoanBottomSheet(
    private val loan: LoanData,
    private val onSuccess: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAcceptLoanBinding? = null
    private val binding get() = _binding!!
    private val api = ApiService.create()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            bottomSheet?.setBackgroundColor(Color.TRANSPARENT)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAcceptLoanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)

        binding.btnClear.setOnClickListener {
            binding.signaturePad.clear()
        }

        binding.btnSubmit.setOnClickListener {
            if (binding.signaturePad.isEmpty()) {
                Toast.makeText(requireContext(), "Please draw your signature first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submitApproval()
        }
    }

    private fun submitApproval() {
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "Uploading..."
        val token = SessionManager(requireContext()).bearerToken

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Export signature to file
                val bitmap = binding.signaturePad.getSignatureBitmap()
                val file = File(requireContext().cacheDir, "signature_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }

                // 2. Upload signature image
                val requestBody = file.asRequestBody("image/png".toMediaType())
                val uploadResp = withContext(Dispatchers.IO) {
                    api.uploadStorageFile(token, requestBody)
                }

                val storageId = uploadResp.storageId
                if (storageId == null) {
                    throw Exception("Upload failed, storage ID is null")
                }

                // 3. Call approve API
                binding.btnSubmit.text = "Approving..."
                val req = ApproveLoanRequest(id = loan.id!!, eSignatureId = storageId)
                withContext(Dispatchers.IO) {
                    runCatching { api.approveLoan(token, req) }.onFailure { err ->
                        if (err.message?.contains("404") != true) {
                            throw err
                        }
                    }
                }

                binding.btnSubmit.text = "Success!"
                Toast.makeText(requireContext(), "Loan approved successfully", Toast.LENGTH_SHORT).show()
                onSuccess()
                dismiss()
            } catch (e: Exception) {
                binding.btnSubmit.isEnabled = true
                binding.btnSubmit.text = "Submit"
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
