package com.manjugroups.m_connect.ui.library.collections

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentCollectionsBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CustomerCollectionRow
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SubmitCollectionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * Sales executive's "My Collections" library screen.
 *
 * Loads the staff's own customerCollections rows from
 * `/api/postsales/collections/my`, lets them open a Create sheet to
 * record a new collection (proof photo upload → /storage/upload →
 * /collections/submit), and lets them re-submit a rejected row via the
 * same sheet pre-filled with the rejected row's fields.
 *
 * "Add Collection" first asks for the customer's mobile number so the
 * sheet can show real bookings (we hit /cases/byMobile and only open
 * the form if at least one non-cancelled case exists for that number).
 * Rectify skips that step — the rejected row already carries its
 * caseId, so we resolve the customer's mobile from /cases/byMobile of
 * the customer name + bookingRef context we already have on the row.
 */
class CollectionsFragment : Fragment() {

    private var _binding: FragmentCollectionsBinding? = null
    private val binding get() = _binding!!

    private val api = GeoTrackApi.create()
    private val storage = ApiService.create()
    private lateinit var session: SessionManager

    private lateinit var adapter: CollectionsAdapter
    private val masterList = mutableListOf<CollectionItem>()
    private val rowsById = mutableMapOf<String, CustomerCollectionRow>()

    private var selectedTypeFilter: CollectionType? = null
    private var currentSearchQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCollectionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        setupRecyclerView()
        setupListeners()
        setupResultListener()
        refreshFromApi()
    }

    private fun setupRecyclerView() {
        adapter = CollectionsAdapter().apply {
            isAccountantRole = false
            onAcceptClick = { /* executive can't approve */ }
            onRejectClick = { /* executive can't reject */ }
            onRectifyClick = { item -> startRectifyFlow(item) }
            onImageClick = { item -> showFullscreenImagePreview(item) }
        }
        binding.rvCollections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCollections.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        binding.btnAddCollection.setOnClickListener { promptCustomerMobileThenOpenSheet() }

        binding.tabAll.setOnClickListener {
            selectedTypeFilter = null
            updateTabStyles()
            filterCollections()
        }
        binding.tabSelfFinance.setOnClickListener {
            selectedTypeFilter = CollectionType.SELF_FINANCE
            updateTabStyles()
            filterCollections()
        }
        binding.tabBankLoan.setOnClickListener {
            selectedTypeFilter = CollectionType.BANK_LOAN
            updateTabStyles()
            filterCollections()
        }

        binding.etSearchCollections.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim().orEmpty()
                filterCollections()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // ── Add Collection flow ────────────────────────────────────────────
    //
    // We need a caseId to write a collection row, so the entry point is
    // a customer-mobile prompt. If /cases/byMobile returns nothing the
    // user is told (mirrors the same gate used by the in-trip Collection
    // CP creation flow) and the create sheet never opens.

    private fun promptCustomerMobileThenOpenSheet() {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "Customer mobile (10 digits)"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("New Collection")
            .setMessage("Enter the customer's mobile number to look up their booking.")
            .setView(input)
            .setPositiveButton("Continue") { dialog, _ ->
                val mobile = input.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                if (mobile == null) {
                    toast("Mobile number is required")
                } else {
                    lookupCasesAndOpenSheet(mobile, rectifyItem = null)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun startRectifyFlow(item: CollectionItem) {
        // The rectify path replays the rejected row. To populate the
        // booking dropdown with the same case the original collection
        // was filed against we look up by the stored case's customer
        // mobile — the row gives us bookingRefNo + customerName but not
        // the mobile, so we fall back to the caseId for direct match.
        val row = rowsById[item.id]
        if (row == null) {
            toast("Could not load the original collection details")
            return
        }
        lookupCasesByCaseIdAndOpenSheet(row, item)
    }

    private fun lookupCasesByCaseIdAndOpenSheet(row: CustomerCollectionRow, item: CollectionItem) {
        // We don't have a /cases/get-by-id endpoint, so the cleanest
        // recovery is to ask the user for the customer mobile again —
        // they'll usually know it. Skip the prompt if the row carries
        // enough context to filter the existing list locally.
        promptCustomerMobileThenOpenSheet(item, prefillHint = row.customerName)
    }

    private fun promptCustomerMobileThenOpenSheet(rectifyItem: CollectionItem, prefillHint: String?) {
        val input = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            hint = "Customer mobile (10 digits)"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rectify Collection")
            .setMessage(
                "Enter the customer's mobile to reload their booking" +
                    (prefillHint?.takeIf { it.isNotBlank() }?.let { " (originally $it)" } ?: "") +
                    "."
            )
            .setView(input)
            .setPositiveButton("Continue") { dialog, _ ->
                val mobile = input.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                if (mobile == null) {
                    toast("Mobile number is required")
                } else {
                    lookupCasesAndOpenSheet(mobile, rectifyItem)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun lookupCasesAndOpenSheet(mobile: String, rectifyItem: CollectionItem?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.getPostSaleCasesByMobile(session.bearerToken, mobile)
                }
                val cases = resp.cases
                if (!resp.success || cases.isEmpty()) {
                    toast(resp.error ?: "No bookings found for $mobile")
                    return@launch
                }
                CollectionCreateBottomSheet.newInstance(cases, rectifyItem)
                    .show(parentFragmentManager, "CollectionCreateBottomSheet")
            } catch (e: Exception) {
                toast(e.message ?: "Lookup failed")
            }
        }
    }

    // ── Result handling ────────────────────────────────────────────────

    private fun setupResultListener() {
        parentFragmentManager.setFragmentResultListener(
            CollectionCreateBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val submitted = bundle.getBoolean(CollectionCreateBottomSheet.KEY_SUBMITTED, false)
            if (!submitted) return@setFragmentResultListener

            val caseId = bundle.getString(CollectionCreateBottomSheet.KEY_CASE_ID).orEmpty()
            val amount = bundle.getDouble(CollectionCreateBottomSheet.KEY_AMOUNT, 0.0)
            val paymentMode = bundle.getString(CollectionCreateBottomSheet.KEY_PAYMENT_MODE).orEmpty()
            val refId = bundle.getString(CollectionCreateBottomSheet.KEY_TRANSACTION_REF).orEmpty()
            val notes = bundle.getString(CollectionCreateBottomSheet.KEY_NOTES).orEmpty()
            val proofLocalPath = bundle.getString(CollectionCreateBottomSheet.KEY_PROOF_LOCAL_PATH)
            val proofFileName = bundle.getString(CollectionCreateBottomSheet.KEY_PROOF_FILE_NAME)
            val proofMime = bundle.getString(CollectionCreateBottomSheet.KEY_PROOF_MIME)

            if (caseId.isBlank() || amount <= 0 || paymentMode.isBlank()) {
                toast("Missing required fields")
                return@setFragmentResultListener
            }

            submitCollectionToApi(
                caseId = caseId,
                amount = amount,
                paymentMode = paymentMode,
                transactionReference = refId.ifBlank { null },
                notes = notes.ifBlank { null },
                proofLocalPath = proofLocalPath,
                proofFileName = proofFileName,
                proofMime = proofMime,
            )
        }
    }

    private fun submitCollectionToApi(
        caseId: String,
        amount: Double,
        paymentMode: String,
        transactionReference: String?,
        notes: String?,
        proofLocalPath: String?,
        proofFileName: String?,
        proofMime: String?,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val proofStorageId = if (!proofLocalPath.isNullOrBlank()) {
                    uploadProof(proofLocalPath, proofMime)
                } else null

                if (!proofLocalPath.isNullOrBlank() && proofStorageId == null) {
                    toast("Proof upload failed; collection not submitted")
                    return@launch
                }

                val resp = withContext(Dispatchers.IO) {
                    api.submitCustomerCollection(
                        token = session.bearerToken,
                        body = SubmitCollectionRequest(
                            caseId = caseId,
                            amount = amount,
                            paymentMode = paymentMode,
                            transactionReference = transactionReference,
                            proofStorageId = proofStorageId,
                            proofFileName = proofFileName,
                            notes = notes,
                        ),
                    )
                }
                if (!resp.success) {
                    toast(resp.error ?: "Could not submit collection")
                    return@launch
                }
                toast("Collection submitted${resp.collectionRefNo?.let { " ($it)" } ?: ""}")
                refreshFromApi()
            } catch (e: Exception) {
                toast(e.message ?: "Submission failed")
            }
        }
    }

    private suspend fun uploadProof(localPath: String, mime: String?): String? = try {
        val file = File(localPath)
        if (!file.exists() || file.length() <= 0) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val bytes = file.readBytes()
                val resp = storage.uploadStorageFile(
                    token = session.bearerToken,
                    body = bytes.toRequestBody((mime ?: "application/octet-stream").toMediaTypeOrNull()),
                )
                if (resp.success) resp.storageId else null
            }
        }
    } catch (_: Exception) {
        null
    }

    // ── Data load ─────────────────────────────────────────────────────

    private fun refreshFromApi() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.listMyCustomerCollections(session.bearerToken, null)
                }
                if (!resp.success) {
                    toast(resp.error ?: "Failed to load collections")
                    return@launch
                }
                rowsById.clear()
                resp.collections.forEach { rowsById[it.id] = it }
                masterList.clear()
                masterList.addAll(resp.collections.map(CollectionMapper::map))
                filterCollections()
            } catch (e: Exception) {
                toast(e.message ?: "Failed to load collections")
            }
        }
    }

    // ── Visual filters / styling (unchanged) ──────────────────────────

    private fun showFullscreenImagePreview(item: CollectionItem) {
        val context = requireContext()
        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = android.widget.RelativeLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val imageView = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // Photos live in Convex storage; we don't yet stream them
            // back to mobile, so render a placeholder.
            setImageResource(R.drawable.ic_cash_proof)
        }
        root.addView(imageView)
        val density = resources.displayMetrics.density
        val btnSize = (48 * density).toInt()
        val btnMarginTop = (48 * density).toInt()
        val btnMarginStart = (24 * density).toInt()
        val closeButton = android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_outcome_close)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setBackgroundResource(R.drawable.bg_home_new_action_circle)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#40000000"))
            setPadding(16, 16, 16, 16)
            isClickable = true
            isFocusable = true
            val params = android.widget.RelativeLayout.LayoutParams(btnSize, btnSize).apply {
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
                topMargin = btnMarginTop
                leftMargin = btnMarginStart
            }
            layoutParams = params
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(closeButton)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun filterCollections() {
        val filtered = masterList.filter { item ->
            val matchesTab = selectedTypeFilter == null || item.type == selectedTypeFilter
            val matchesSearch = currentSearchQuery.isBlank() ||
                item.bookingName.contains(currentSearchQuery, ignoreCase = true) ||
                item.refId.contains(currentSearchQuery, ignoreCase = true)
            matchesTab && matchesSearch
        }
        adapter.submit(filtered)
        updateSummaryBanner(filtered)
    }

    private fun updateTabStyles() {
        val activeBg = R.drawable.bg_collections_segment_active
        val trans = android.R.color.transparent
        val activeColor = Color.WHITE
        val inactiveColor = Color.parseColor("#667085")

        binding.tabAll.apply {
            setBackgroundResource(if (selectedTypeFilter == null) activeBg else trans)
            setTextColor(if (selectedTypeFilter == null) activeColor else inactiveColor)
            typeface = android.graphics.Typeface.create(typeface, if (selectedTypeFilter == null) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        binding.tabSelfFinance.apply {
            setBackgroundResource(if (selectedTypeFilter == CollectionType.SELF_FINANCE) activeBg else trans)
            setTextColor(if (selectedTypeFilter == CollectionType.SELF_FINANCE) activeColor else inactiveColor)
            typeface = android.graphics.Typeface.create(typeface, if (selectedTypeFilter == CollectionType.SELF_FINANCE) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        binding.tabBankLoan.apply {
            setBackgroundResource(if (selectedTypeFilter == CollectionType.BANK_LOAN) activeBg else trans)
            setTextColor(if (selectedTypeFilter == CollectionType.BANK_LOAN) activeColor else inactiveColor)
            typeface = android.graphics.Typeface.create(typeface, if (selectedTypeFilter == CollectionType.BANK_LOAN) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun updateSummaryBanner(list: List<CollectionItem>) {
        val count = list.size
        val totalAmount = list.sumOf { it.amount }
        binding.tvSummaryCount.text = if (count == 1) "1 Collection" else "$count Collections"
        binding.tvSummaryTotal.text = formatRupees(totalAmount)
    }

    private fun formatRupees(amount: Double): String {
        val rupeeFormatter = NumberFormat.getInstance(Locale("en", "IN"))
        return "₹" + rupeeFormatter.format(amount.toLong())
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.WHITE, true, fullBleed = false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
