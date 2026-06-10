package com.manjugroups.m_connect.ui.library.loans

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
    
    private var selectedFile: File? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            handleSelectedUri(uri)
        }
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
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        binding.btnUploadBox.setOnClickListener {
            pickImage.launch("image/*")
        }

        binding.btnSubmit.setOnClickListener {
            if (selectedFile == null) {
                Toast.makeText(requireContext(), "Please upload an E-Signature", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submitApproval()
        }
    }

    private fun handleSelectedUri(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val file = File(requireContext().cacheDir, "signature_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            
            selectedFile = file
            binding.ivPreview.setImageURI(uri)
            binding.ivPreview.isVisible = true
            binding.tvUploadStatus.text = "Image selected"
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun submitApproval() {
        binding.btnSubmit.isEnabled = false
        binding.btnSubmit.text = "Uploading..."
        val token = SessionManager(requireContext()).bearerToken
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Upload photo
                val requestBody = selectedFile!!.asRequestBody("image/jpeg".toMediaType())
                val uploadResp = withContext(Dispatchers.IO) {
                    api.uploadStorageFile(token, requestBody)
                }
                
                val storageId = uploadResp.storageId
                if (storageId == null) {
                    throw Exception("Upload failed, storage ID is null")
                }

                // 2. Call approve API
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
