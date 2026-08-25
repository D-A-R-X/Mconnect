package com.manjugroups.m_connect.ui.library.collections

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
import com.manjugroups.m_connect.databinding.FragmentCollectionsBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CustomerCollectionRow
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.SubmitCollectionRequest
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

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
    // Storage-id → signed URL cache. /api/storage/get-url issues signed
    // URLs with TTLs comfortably longer than a screen session, so a
    // single fetch per id covers all scroll rebinds.
    private val proofUrlCache = mutableMapOf<String, String>()

    // Infinite scroll: render 20 rows, extend by 20 as the list nears its
    // end. Windowing bounds the RecyclerView to the current page; the full
    // filtered list still drives the empty state and the summary banner.
    private var collectionsLayoutManager: LinearLayoutManager? = null
    private var collectionsWindowCtx: String? = null
    private var collectionsFilteredCount: Int = 0
    private val collectionsPager = com.manjugroups.m_connect.ui.common.InfiniteScrollPager(
        onLoadMore = { filterCollections() },
        // The window above only pages through rows already in memory. The
        // server hands back one bounded page at a time (this table is far too
        // large to send whole — doing so is what 502'd the screen), so reaching
        // the end also has to pull the NEXT page in.
        onEndReached = { loadNextCollectionsPage() },
    )

    // Server-side scroll pagination state. `nextCursor` is the creation-time
    // watermark of the last row received; `hasMore` is the backend telling us
    // rows remain below it. `loadingPage` collapses the burst of end-of-list
    // scroll callbacks into a single in-flight request.
    private var collectionsCursor: Double? = null
    private var collectionsHasMore: Boolean = false
    private var loadingCollectionsPage: Boolean = false
    // A page whose rows all fail the active tab/search/date filter adds
    // nothing visible, and the user is already at the bottom with nothing left
    // to scroll — so the list would look finished while rows remain. Chase the
    // next page automatically in that case, bounded so a search that matches
    // nothing can't walk the entire table.
    private var emptyAppendStreak: Int = 0

    private var selectedTypeFilter: CollectionType? = null
    private var currentSearchQuery: String = ""
    // Date-range filter, set when the user picks a range from the
    // calendar pill in the header. Stored as YYYY-MM-DD strings so we
    // can string-compare against the row's createdAt date portion.
    private var dateFromYmd: String? = null
    private var dateToYmd: String? = null
    // Reused result key for the calendar picker so the listener
    // registration below picks the same bundle out reliably.
    private val calendarResultKey = "collections_calendar_range"

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
            viewerStaffId = session.staffId
            onAcceptClick = { /* executive can't approve */ }
            onRejectClick = { /* executive can't reject */ }
            onRectifyClick = { item -> startRectifyFlow(item) }
            onEditClick = { item -> startEditFlow(item) }
            onImageClick = { item -> showFullscreenImagePreview(item) }
            proofLoader = { storageId, target -> loadProofThumbnail(storageId, target) }
        }
        val lm = LinearLayoutManager(requireContext())
        collectionsLayoutManager = lm
        binding.rvCollections.layoutManager = lm
        binding.rvCollections.adapter = adapter
        collectionsPager.bindRecyclerView(binding.rvCollections, lm) { collectionsFilteredCount }
        // The RecyclerView sits inside a NestedScrollView, so it never scrolls
        // itself and the RecyclerView-bound trigger above never fires — the
        // window stayed frozen at the first 20 rows while the header counted
        // 300+. Drive the window from the OUTER scroll instead (same pattern as
        // Leaves / Permissions); the RecyclerView binding stays as a no-op
        // fallback should the layout ever change.
        collectionsPager.bindNestedScroll(binding.collectionsScroll, totalCount = { collectionsFilteredCount })
    }

    private fun loadProofThumbnail(storageId: String, target: ImageView) {
        // No placeholder or error mock — adapter cleared the view
        // before calling, and a blank tile is the right intermediate
        // state until Coil crossfades the real image in.
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
                // Silent — one row's failure shouldn't toast on every scroll.
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }

        binding.btnAddCollection.setOnClickListener {
            CollectionCreateBottomSheet.newInstance()
                .showOnce(parentFragmentManager, "CollectionCreateBottomSheet")
        }

        binding.btnCollectionsCalendar.setOnClickListener {
            CalendarRangePickerSheet.newInstance(
                title = "Filter Collections",
                subtitle = "Pick a date range",
                initialFrom = dateFromYmd,
                initialTo = dateToYmd,
                resultKey = calendarResultKey,
            ).showOnce(parentFragmentManager, "CollectionsCalendarRange")
        }
        parentFragmentManager.setFragmentResultListener(
            calendarResultKey,
            viewLifecycleOwner,
        ) { _, bundle ->
            dateFromYmd = bundle.getString(CalendarRangePickerSheet.KEY_FROM)?.takeIf { it.isNotBlank() }
            dateToYmd = bundle.getString(CalendarRangePickerSheet.KEY_TO)?.takeIf { it.isNotBlank() }
            filterCollections()
            if (dateFromYmd != null && dateToYmd != null) {
                Toast.makeText(
                    requireContext(),
                    "Showing $dateFromYmd to $dateToYmd",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

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

    // ── Rectify flow ───────────────────────────────────────────────────
    //
    // The rejected row already carries its caseId + booking caption
    // (clientName, bookingRefNo, project, plot). Rectify opens the same
    // designer form with the booking field locked to that case — staff
    // only edits the fields Accounts flagged.

    private fun startRectifyFlow(item: CollectionItem) {
        val row = rowsById[item.id]
        if (row == null) {
            toast("Could not load the original collection details")
            return
        }
        val bookingLabel = buildBookingLabel(row)
        CollectionCreateBottomSheet.forRectify(
            item = item,
            caseId = row.caseId,
            bookingLabel = bookingLabel,
        ).showOnce(parentFragmentManager, "CollectionCreateBottomSheet")
    }

    /**
     * Edit an own still-pending collection — fixes a wrong amount (e.g. 48,000
     * entered instead of 4,80,000). Allowed until Accounts acts on it; the
     * backend re-checks ownership + pending status. The row shows an "Edited"
     * tag afterward.
     */
    private fun startEditFlow(item: CollectionItem) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(
                if (item.amount == item.amount.toLong().toDouble())
                    item.amount.toLong().toString()
                else item.amount.toString()
            )
            hint = "Corrected amount (₹)"
            setSelection(text.length)
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Edit collection amount")
            .setMessage("Correct the amount before it reaches Accounts.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newAmount = input.text?.toString()?.trim()?.toDoubleOrNull()
                if (newAmount == null || newAmount <= 0.0) {
                    toast("Enter a valid amount greater than zero")
                    return@setPositiveButton
                }
                submitCollectionEdit(item, newAmount)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitCollectionEdit(item: CollectionItem, newAmount: Double) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    api.correctCustomerCollection(
                        session.bearerToken,
                        com.manjugroups.m_connect.network.CorrectCollectionRequest(
                            collectionId = item.id,
                            amount = newAmount,
                        ),
                    )
                }
                if (resp.success) {
                    toast("Collection updated")
                    refreshFromApi()
                } else {
                    toast(resp.error ?: "Could not update collection")
                }
            } catch (e: Exception) {
                toast(e.message ?: "Network error")
            }
        }
    }

    private fun buildBookingLabel(row: CustomerCollectionRow): String {
        val client = row.customerName.orEmpty().trim()
        val ref = row.bookingRefNo.orEmpty().trim()
        return when {
            client.isNotBlank() && ref.isNotBlank() -> "$client · $ref"
            client.isNotBlank() -> client
            ref.isNotBlank() -> ref
            else -> "Booking ${row.caseId.takeLast(6)}"
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
            val bankName = bundle.getString(CollectionCreateBottomSheet.KEY_BANK_NAME).orEmpty()
            val branchName = bundle.getString(CollectionCreateBottomSheet.KEY_BRANCH_NAME).orEmpty()
            val instrumentDate = bundle.getString(CollectionCreateBottomSheet.KEY_INSTRUMENT_DATE).orEmpty()
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
                bankName = bankName.ifBlank { null },
                branchName = branchName.ifBlank { null },
                paymentInstrumentDate = instrumentDate.ifBlank { null },
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
        bankName: String? = null,
        branchName: String? = null,
        paymentInstrumentDate: String? = null,
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
                            bankName = bankName,
                            branchName = branchName,
                            paymentInstrumentDate = paymentInstrumentDate,
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
                // Downscale photo proofs before upload (field networks are weak).
                // Non-image proofs pass through untouched.
                val isImage = (mime ?: "").startsWith("image/", ignoreCase = true)
                val upload = if (isImage) {
                    com.manjugroups.m_connect.util.ImageCompressor.compress(file)
                } else {
                    file
                }
                val bytes = upload.readBytes()
                if (upload !== file) runCatching { upload.delete() }
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

    private fun refreshFromApi() = loadCollectionsPage(reset = true)

    /**
     * Pull the next server page once the user scrolls to the end of what we
     * hold. No-ops unless the backend said more rows exist, so a fully loaded
     * list costs nothing no matter how much the user scrolls.
     */
    private fun loadNextCollectionsPage() {
        if (!collectionsHasMore || loadingCollectionsPage) return
        loadCollectionsPage(reset = false)
    }

    private fun loadCollectionsPage(reset: Boolean) {
        if (loadingCollectionsPage) return
        loadingCollectionsPage = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val cursor = if (reset) null else collectionsCursor
                val resp = withContext(Dispatchers.IO) {
                    api.listMyCustomerCollections(
                        token = session.bearerToken,
                        verificationStatus = null,
                        cursor = cursor?.toString(),
                    )
                }
                if (!resp.success) {
                    toast(resp.error ?: "Failed to load collections")
                    return@launch
                }
                if (reset) {
                    rowsById.clear()
                    masterList.clear()
                }
                // Append only rows we don't already hold. Pages don't overlap
                // (each starts strictly below the previous cursor), but a row
                // written between two requests could shift the boundary, and a
                // duplicate card is more visible than a missing one.
                val fresh = resp.collections.filterNot { rowsById.containsKey(it.id) }
                fresh.forEach { rowsById[it.id] = it }
                masterList.addAll(fresh.map(CollectionMapper::map))
                collectionsCursor = resp.nextCursor
                // Stop only when the backend says the stream is exhausted. A
                // page can legitimately arrive short — rows get filtered out
                // server-side — so a small page is NOT the end of the list.
                collectionsHasMore = resp.hasMore && resp.nextCursor != null
                val visibleBefore = collectionsFilteredCount
                filterCollections()
                emptyAppendStreak =
                    if (reset || collectionsFilteredCount > visibleBefore) 0 else emptyAppendStreak + 1
                if (collectionsHasMore &&
                    collectionsFilteredCount <= visibleBefore &&
                    emptyAppendStreak < MAX_EMPTY_APPEND_STREAK
                ) {
                    loadingCollectionsPage = false
                    loadCollectionsPage(reset = false)
                }
            } catch (e: Exception) {
                toast(e.message ?: "Failed to load collections")
            } finally {
                loadingCollectionsPage = false
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
        }
        // Resolve the proof's signed URL via the same code path the
        // list thumbnail uses — Coil shares its bitmap cache across
        // ImageView targets, so the URL the list pre-warmed is reused
        // here without a second network round-trip. Leaving the view
        // blank during the brief resolve avoids flashing the cash mock.
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
        val from = dateFromYmd
        val to = dateToYmd
        val filtered = masterList.filter { item ->
            val matchesTab = selectedTypeFilter == null || item.type == selectedTypeFilter
            val row = rowsById[item.id]
            val matchesSearch = currentSearchQuery.isBlank() ||
                item.bookingName.contains(currentSearchQuery, ignoreCase = true) ||
                item.refId.contains(currentSearchQuery, ignoreCase = true) ||
                // Web parity: the web Collections search also hits the
                // customer name, booking ref and plot number.
                row?.customerName.orEmpty().contains(currentSearchQuery, ignoreCase = true) ||
                row?.bookingRefNo.orEmpty().contains(currentSearchQuery, ignoreCase = true) ||
                row?.plotNo.orEmpty().contains(currentSearchQuery, ignoreCase = true)
            val matchesDate = if (from == null || to == null) {
                true
            } else {
                // Look up the original server row and compare the
                // date portion of its ISO createdAt to the picked
                // YYYY-MM-DD range. String comparison works because
                // the ISO date prefix is lexicographically sortable.
                val createdAt = rowsById[item.id]?.createdAt.orEmpty()
                val ymd = createdAt.take(10) // "2026-06-18"
                ymd.isNotEmpty() && ymd >= from && ymd <= to
            }
            matchesTab && matchesSearch && matchesDate
        }
        // Empty state / summary key off the FULL filtered size, not the window.
        collectionsFilteredCount = filtered.size

        // Reset the scroll window whenever the filter / search / date range /
        // source data changes so a fresh view starts from the first page.
        val windowCtx =
            "$selectedTypeFilter|$currentSearchQuery|$from|$to|${System.identityHashCode(masterList)}"
        if (windowCtx != collectionsWindowCtx) {
            collectionsWindowCtx = windowCtx
            collectionsPager.reset()
            binding.rvCollections.scrollToPosition(0)
        }

        adapter.submit(filtered.take(collectionsPager.limit))
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

    companion object {
        private const val MAX_EMPTY_APPEND_STREAK = 3
    }
}
