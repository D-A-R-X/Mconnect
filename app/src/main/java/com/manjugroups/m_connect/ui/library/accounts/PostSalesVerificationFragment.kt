package com.manjugroups.m_connect.ui.library.accounts

import com.manjugroups.m_connect.ui.common.toastSafe

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
import com.manjugroups.m_connect.network.ApproveCollectionRequest
import com.manjugroups.m_connect.network.CustomerCollectionRow
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.RejectCollectionRequest
import com.manjugroups.m_connect.network.userMessage
import com.manjugroups.m_connect.ui.library.collections.CollectionItem
import com.manjugroups.m_connect.ui.library.collections.CollectionMapper
import com.manjugroups.m_connect.ui.library.collections.CollectionRejectBottomSheet
import com.manjugroups.m_connect.ui.library.collections.CollectionStatus
import com.manjugroups.m_connect.ui.library.collections.CollectionType
import com.manjugroups.m_connect.ui.library.collections.CollectionsAdapter
import com.manjugroups.m_connect.ui.common.InfiniteScrollPager
import com.manjugroups.m_connect.ui.common.SkeletonLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

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
    private lateinit var session: SessionManager

    private lateinit var adapter: CollectionsAdapter
    private val masterList = mutableListOf<CollectionItem>()
    private val rowsById = mutableMapOf<String, CustomerCollectionRow>()
    private val proofUrlCache = mutableMapOf<String, String>()

    private var selectedTypeFilter: CollectionType? = null
    private var currentSearchQuery: String = ""

    // The RecyclerView lives inside a NestedScrollView (wrap_content, nested
    // scrolling off), so it can't recycle — every row would inflate up-front
    // AND fire its own signed-URL fetch for the proof thumbnail. Render a
    // growing window instead, extended as the user scrolls.
    private val pager = InfiniteScrollPager(onLoadMore = { renderWindow() })
    private var filteredCollections: List<CollectionItem> = emptyList()
    private var skeletonAnim: android.animation.ObjectAnimator? = null
    private var hasRenderedOnce = false
    private var searchJob: kotlinx.coroutines.Job? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    /** Last queue this staff member saw, painted instantly on the next open. */
    private val cacheKey: String
        get() = "postsales_verification_${session.staffId.orEmpty()}"

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
        paintFromCacheThenRefresh()
    }

    /**
     * The queue is one large response (it can carry ~900 rows). Show the last
     * copy at once, then refresh quietly instead of holding the skeleton until
     * the whole download lands.
     */
    private fun paintFromCacheThenRefresh() {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                runCatching {
                    com.manjugroups.m_connect.ui.common.LocalCache
                        .getList(ctx, cacheKey, CustomerCollectionRow::class.java)
                }.getOrNull()
            }
            if (!cached.isNullOrEmpty() && !hasRenderedOnce) applyRows(cached)
            refreshFromApi()
        }
    }

    /** Maps off the main thread, then renders. */
    private suspend fun applyRows(rows: List<CustomerCollectionRow>) {
        val mapped = withContext(Dispatchers.Default) { rows.map(CollectionMapper::map) }
        if (_binding == null) return
        rowsById.clear()
        rows.forEach { rowsById[it.id] = it }
        masterList.clear()
        masterList.addAll(mapped)
        hasRenderedOnce = true
        showSkeleton(false)
        filterCollections()
    }

    /** Approve / reject change one row: update it here, no full reload. */
    private fun markRow(collectionId: String, status: CollectionStatus, remarks: String?) {
        val index = masterList.indexOfFirst { it.id == collectionId }
        if (index < 0) return
        // A copy, not an in-place edit, so the adapter's diff sees the change.
        val item = masterList[index]
        masterList[index] = item.copy(status = status, remarks = remarks ?: item.remarks)
        val serverStatus = if (status == CollectionStatus.APPROVED) "approved" else "rejected"
        rowsById[collectionId]?.let { rowsById[collectionId] = it.copy(verificationStatus = serverStatus) }
        filterCollections()
        val ctx = context?.applicationContext ?: return
        val snapshot = rowsById.values.toList()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                com.manjugroups.m_connect.ui.common.LocalCache
                    .put(ctx, cacheKey, snapshot, System.currentTimeMillis())
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = CollectionsAdapter().apply {
            isAccountantRole = true
            onAcceptClick = { item -> approveCollection(item) }
            onRejectClick = { item ->
                CollectionRejectBottomSheet.newInstance(item.id)
                    .showOnce(parentFragmentManager, "CollectionRejectBottomSheet")
            }
            onRectifyClick = { /* No rectify on the accountant side */ }
            onImageClick = { item -> showFullscreenImagePreview(item) }
            proofLoader = { storageId, target -> loadProofThumbnail(storageId, target) }
        }
        binding.rvCollections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCollections.adapter = adapter
        // Grow the window as the outer scroller nears the bottom.
        pager.bindNestedScroll(
            binding.postSalesScroll,
            totalCount = { filteredCollections.size },
        )
    }

    /** Render only the current window of the filtered list. */
    private fun renderWindow() {
        if (_binding == null) return
        adapter.submit(filteredCollections.take(pager.limit))
    }

    private fun showSkeleton(show: Boolean) {
        val b = _binding ?: return
        if (show) {
            b.rvCollections.visibility = View.GONE
            b.skeletonCollections.visibility = View.VISIBLE
            skeletonAnim?.cancel()
            skeletonAnim = SkeletonLoader.show(b.skeletonCollections, 4)
        } else {
            skeletonAnim?.cancel()
            skeletonAnim = null
            b.skeletonCollections.removeAllViews()
            b.skeletonCollections.visibility = View.GONE
            b.rvCollections.visibility = View.VISIBLE
        }
    }

    private fun loadProofThumbnail(storageId: String, target: ImageView) {
        // No placeholder/error mocks — adapter pre-cleared the tile.
        val url = proofUrlCache[storageId]
            ?: com.manjugroups.m_connect.network.MobileStorageFiles.resolve(storageId)
                ?.also { proofUrlCache[storageId] = it }
            ?: return
        // Decode at tile size (the full-screen preview passes its own size).
        val edge = maxOf(target.width, target.height)
            .takeIf { it > 0 } ?: (resources.displayMetrics.density * 144).toInt()
        target.load(url) {
            crossfade(true)
            size(edge)
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        // Full-bleed with its own status-bar padding: no strip resize (page
        // jump) when entering or leaving. See StatusBarInsets.
        com.manjugroups.m_connect.ui.common.StatusBarInsets.padHeader(binding.postSalesVerificationHeader, fixedHeight = true)

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
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(250)
                    filterCollections()
                }
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

    /**
     * Web parity: the accountant confirms whether a receipt number exists
     * before approving (the server rejects an approve without it).
     */
    private fun approveCollection(item: CollectionItem) {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val withReceipt = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "With receipt"
        }
        val withoutReceipt = android.widget.RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "Without receipt"
        }
        val group = android.widget.RadioGroup(ctx).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            addView(withReceipt)
            addView(withoutReceipt)
        }
        val receiptInput = android.widget.EditText(ctx).apply {
            hint = "Receipt number"
            isSingleLine = true
            visibility = View.GONE
        }
        group.setOnCheckedChangeListener { _, checked ->
            receiptInput.visibility = if (checked == withReceipt.id) View.VISIBLE else View.GONE
        }
        val content = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(group)
            addView(receiptInput)
        }
        val dialog = android.app.AlertDialog.Builder(ctx)
            .setTitle("Approve collection")
            .setMessage("Is a receipt number available for this collection?")
            .setView(content)
            .setPositiveButton("Approve", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mode = when (group.checkedRadioButtonId) {
                    withReceipt.id -> "with_receipt"
                    withoutReceipt.id -> "without_receipt"
                    else -> {
                        toast("Choose With receipt or Without receipt")
                        return@setOnClickListener
                    }
                }
                val receiptNo = receiptInput.text?.toString()?.trim().orEmpty()
                if (mode == "with_receipt" && receiptNo.isEmpty()) {
                    receiptInput.error = "Enter the receipt number"
                    return@setOnClickListener
                }
                dialog.dismiss()
                submitApproval(item, mode, receiptNo.takeIf { mode == "with_receipt" })
            }
        }
        dialog.show()
    }

    private fun submitApproval(item: CollectionItem, receiptMode: String, receiptNo: String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.approveCustomerCollection(
                        token = session.bearerToken,
                        body = ApproveCollectionRequest(
                            collectionId = item.id,
                            notes = null,
                            receiptMode = receiptMode,
                            receiptNo = receiptNo,
                        ),
                    )
                }
                if (!resp.success) {
                    toast(resp.error ?: "Could not approve collection")
                    return@launch
                }
                toast("Collection approved")
                markRow(item.id, CollectionStatus.APPROVED, null)
            } catch (e: Exception) {
                toast(e.userMessage("Approval failed"))
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
                markRow(collectionId, CollectionStatus.REJECTED, remarks)
            } catch (e: Exception) {
                toast(e.userMessage("Rejection failed"))
            }
        }
    }

    private fun refreshFromApi() {
        // Skeleton only on the cold load — an approve/reject refresh keeps the
        // existing rows on screen instead of flashing placeholders.
        if (!hasRenderedOnce) showSkeleton(true)
        loadJob?.cancel()
        val ctx = requireContext().applicationContext
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.listCustomerCollectionsForAccounts(session.bearerToken)
                }
                if (!resp.success) {
                    showSkeleton(false)
                    if (!hasRenderedOnce) toast(resp.error ?: "Failed to load verification queue")
                    return@launch
                }
                applyRows(resp.collections)
                withContext(Dispatchers.IO) {
                    runCatching {
                        com.manjugroups.m_connect.ui.common.LocalCache
                            .put(ctx, cacheKey, resp.collections, System.currentTimeMillis())
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showSkeleton(false)
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
        filteredCollections = filtered
        // New tab/search/fetch → back to the first page of the window.
        pager.reset()
        renderWindow()
        // Counts + total stay keyed off the FULL filtered set, not the window.
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
        toastSafe(message)
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.WHITE, true, fullBleed = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
