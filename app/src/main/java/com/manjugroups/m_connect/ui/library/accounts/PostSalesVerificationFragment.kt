package com.manjugroups.m_connect.ui.library.accounts

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentPostSalesVerificationBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ApproveCollectionRequest
import com.manjugroups.m_connect.network.CustomerCollectionRow
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.RejectCollectionRequest
import com.manjugroups.m_connect.ui.library.collections.CollectionItem
import com.manjugroups.m_connect.ui.library.collections.CollectionMapper
import com.manjugroups.m_connect.ui.library.collections.CollectionRejectBottomSheet
import com.manjugroups.m_connect.ui.library.collections.CollectionStatus
import com.manjugroups.m_connect.ui.library.collections.CollectionType
import com.manjugroups.m_connect.ui.library.collections.CollectionsAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Accountant verification queue. Loads pending + recently-approved
 * collections from `/api/postsales/collections/for-accounts`, lets the
 * accountant Approve in one tap or open the Reject sheet for remarks.
 * Approve/reject both hit the corresponding HTTP wrapper around
 * `customerCollections.updateVerification`; the list refreshes from
 * the server after each action so the caller sees the authoritative
 * state instead of an optimistic local toggle.
 */
class PostSalesVerificationFragment : Fragment() {

    private var _binding: FragmentPostSalesVerificationBinding? = null
    private val binding get() = _binding!!

    private val api = GeoTrackApi.create()
    private val storage = ApiService.create()
    private lateinit var session: SessionManager

    private lateinit var adapter: CollectionsAdapter
    private val masterList = mutableListOf<CollectionItem>()
    private val rowsById = mutableMapOf<String, CustomerCollectionRow>()
    private val proofUrlCache = mutableMapOf<String, String>()

    private var selectedTypeFilter: CollectionType? = null
    private var currentSearchQuery: String = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPostSalesVerificationBinding.inflate(inflater, container, false)
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
            isAccountantRole = true
            onAcceptClick = { item -> approveCollection(item) }
            onRejectClick = { item ->
                CollectionRejectBottomSheet.newInstance(item.id)
                    .show(parentFragmentManager, "CollectionRejectBottomSheet")
            }
            onRectifyClick = { /* No rectify on the accountant side */ }
            onImageClick = { item -> showFullscreenImagePreview(item) }
            proofLoader = { storageId, target -> loadProofThumbnail(storageId, target) }
        }
        binding.rvCollections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCollections.adapter = adapter
    }

    private fun loadProofThumbnail(storageId: String, target: ImageView) {
        // No placeholder/error mocks — adapter cleared the view so a
        // blank tile is the correct intermediate state until Coil
        // crossfades the real image in.
        val cached = proofUrlCache[storageId]
        if (cached != null) {
            target.load(cached) { crossfade(true) }
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    storage.getStorageUrl(session.bearerToken, storageId)
                }
                val url = resp.url
                if (resp.success && !url.isNullOrBlank()) {
                    proofUrlCache[storageId] = url
                    target.load(url) { crossfade(true) }
                }
            } catch (_: Exception) {
                // Silent; leave the tile blank.
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

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

    private fun setupResultListener() {
        parentFragmentManager.setFragmentResultListener(
            CollectionRejectBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, bundle ->
            val itemId = bundle.getString("itemId").orEmpty()
            val remarks = bundle.getString("remarks").orEmpty()
            if (itemId.isBlank() || remarks.isBlank()) {
                toast("Rejection requires remarks")
                return@setFragmentResultListener
            }
            rejectCollection(itemId, remarks)
        }
    }

    private fun approveCollection(item: CollectionItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.approveCustomerCollection(
                        token = session.bearerToken,
                        body = ApproveCollectionRequest(collectionId = item.id, notes = null),
                    )
                }
                if (!resp.success) {
                    toast(resp.error ?: "Could not approve collection")
                    return@launch
                }
                toast("Collection approved")
                refreshFromApi()
            } catch (e: Exception) {
                toast(e.message ?: "Approval failed")
            }
        }
    }

    private fun rejectCollection(collectionId: String, remarks: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.rejectCustomerCollection(
                        token = session.bearerToken,
                        body = RejectCollectionRequest(collectionId = collectionId, remarks = remarks),
                    )
                }
                if (!resp.success) {
                    toast(resp.error ?: "Could not reject collection")
                    return@launch
                }
                toast("Collection rejected")
                refreshFromApi()
            } catch (e: Exception) {
                toast(e.message ?: "Rejection failed")
            }
        }
    }

    private fun refreshFromApi() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.listCustomerCollectionsForAccounts(session.bearerToken)
                }
                if (!resp.success) {
                    toast(resp.error ?: "Failed to load verification queue")
                    return@launch
                }
                rowsById.clear()
                resp.collections.forEach { rowsById[it.id] = it }
                masterList.clear()
                masterList.addAll(resp.collections.map(CollectionMapper::map))
                filterCollections()
            } catch (e: Exception) {
                toast(e.message ?: "Failed to load verification queue")
            }
        }
    }

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
        }
        // Same signed-URL resolution as the list thumbnail; Coil shares
        // its bitmap cache so the warmed entry from the list is reused.
        // Blank during resolve avoids the cash-mock flash.
        val storageId = item.proofStorageId?.takeIf { it.isNotBlank() }
        if (storageId != null) {
            loadProofThumbnail(storageId, imageView)
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
        val pendingCount = list.count { it.status == CollectionStatus.PENDING }
        val totalAmount = list.sumOf { it.amount }
        binding.tvSummaryCount.text = if (pendingCount == 1) "1 Pending Verification" else "$pendingCount Pending Verification"
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
