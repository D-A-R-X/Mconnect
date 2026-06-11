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
import com.manjugroups.m_connect.network.DigitalSignRequest
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
            existingSignatureStorageId = null
        }

        binding.btnSubmit.setOnClickListener {
            if (binding.signaturePad.isEmpty()) {
                Toast.makeText(requireContext(), "Please draw your signature first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            submitApproval()
        }

        loadExistingSignature()
    }

    /**
     * If the staff member already has a saved digital signature, fetch it
     * and show it in the pad so they can approve without re-drawing. Tapping
     * Clear (or drawing) discards it and forces a fresh signature.
     */
    private fun loadExistingSignature() {
        val token = SessionManager(requireContext()).bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val resp = withContext(Dispatchers.IO) { api.getDigitalSign(token) }
                if (resp.success && resp.hasSignature && !resp.url.isNullOrBlank()) {
                    val bmp = withContext(Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient()
                        val request = okhttp3.Request.Builder().url(resp.url).build()
                        client.newCall(request).execute().body?.byteStream()?.let {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }
                    if (_binding != null && bmp != null) {
                        existingSignatureStorageId = resp.storageId
                        binding.signaturePad.loadBitmap(bmp)
                        binding.btnSubmit.isEnabled = true
                    }
                }
            }
        }
    }

    /** Storage id of the staff's saved signature, when one was fetched. */
    private var existingSignatureStorageId: String? = null

    private fun submitApproval() {
        binding.btnSubmit.isEnabled = false
        val token = SessionManager(requireContext()).bearerToken
        val drewNew = binding.signaturePad.hasUserDrawn()
        val existing = existingSignatureStorageId

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val storageId: String = if (drewNew || existing == null) {
                    // Fresh signature → upload, then persist it as the
                    // staff's profile digital sign so it's reused next time.
                    binding.btnSubmit.text = "Uploading..."
                    val bitmap = binding.signaturePad.getSignatureBitmap()
                    val file = File(requireContext().cacheDir, "signature_${System.currentTimeMillis()}.png")
                    withContext(Dispatchers.IO) {
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    val requestBody = file.asRequestBody("image/png".toMediaType())
                    val uploadResp = withContext(Dispatchers.IO) {
                        api.uploadStorageFile(token, requestBody)
                    }
                    val newId = uploadResp.storageId
                        ?: throw Exception("Upload failed, storage ID is null")
                    withContext(Dispatchers.IO) {
                        runCatching {
                            api.saveDigitalSign(
                                token,
                                DigitalSignRequest(storageId = newId, fileName = "signature.png"),
                            )
                        }
                    }
                    newId
                } else {
                    // Reuse the already-saved signature — no re-upload.
                    existing
                }

                binding.btnSubmit.text = "Approving..."
                val req = ApproveLoanRequest(id = loan.id!!, eSignatureId = storageId)
                withContext(Dispatchers.IO) { api.approveLoan(token, req) }

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
