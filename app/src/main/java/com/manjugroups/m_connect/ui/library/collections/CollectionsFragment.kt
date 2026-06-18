package com.manjugroups.m_connect.ui.library.collections

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentCollectionsBinding
import android.widget.Toast
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CollectionsFragment : Fragment() {

    private var _binding: FragmentCollectionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: CollectionsAdapter
    private val masterList = mutableListOf<CollectionItem>()

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

        setupInitialMockData()
        setupRecyclerView()
        setupListeners()
        setupResultListener()
        filterCollections()
    }

    private fun setupInitialMockData() {
        if (masterList.isEmpty()) {
            masterList.add(
                CollectionItem(
                    id = "1",
                    bookingName = "Manju Groups Site A - Plot 12",
                    amount = 42000.0,
                    paymentMode = "UPI",
                    refId = "48782328100",
                    notes = "Initial mock collection approved",
                    photoPath = null,
                    dateString = "Oct 24, 2026 • 10:30 AM",
                    status = CollectionStatus.APPROVED,
                    type = CollectionType.BANK_LOAN
                )
            )
            masterList.add(
                CollectionItem(
                    id = "2",
                    bookingName = "Manju Groups Site A - Plot 45",
                    amount = 42000.0,
                    paymentMode = "UPI",
                    refId = "48782328100",
                    notes = "Initial mock collection rejected",
                    photoPath = null,
                    dateString = "Oct 24, 2026 • 10:30 AM",
                    status = CollectionStatus.REJECTED,
                    type = CollectionType.SELF_FINANCE,
                    remarks = "Invalid UPI reference ID. Please rectify reference ID details."
                )
            )
            masterList.add(
                CollectionItem(
                    id = "3",
                    bookingName = "Manju Groups Site B - Plot 8",
                    amount = 3000.0,
                    paymentMode = "Cash",
                    refId = "98127392182",
                    notes = "Cash collection",
                    photoPath = null,
                    dateString = "Jun 17, 2026 • 11:30 AM",
                    status = CollectionStatus.APPROVED,
                    type = CollectionType.SELF_FINANCE
                )
            )
            masterList.add(
                CollectionItem(
                    id = "4",
                    bookingName = "Manju Groups Site C - Plot 19",
                    amount = 9400.0,
                    paymentMode = "Bank Transfer",
                    refId = "12837283721",
                    notes = "Bank transfer collection",
                    photoPath = null,
                    dateString = "Jun 17, 2026 • 02:45 PM",
                    status = CollectionStatus.APPROVED,
                    type = CollectionType.BANK_LOAN
                )
            )
        }
    }

    private fun setupRecyclerView() {
        adapter = CollectionsAdapter().apply {
            isAccountantRole = false
            onAcceptClick = { /* Not available in employee mode */ }
            onRejectClick = { /* Not available in employee mode */ }
            onRectifyClick = { item ->
                CollectionCreateBottomSheet.newInstance(item)
                    .show(parentFragmentManager, "CollectionCreateBottomSheet")
            }
            onImageClick = { item ->
                showFullscreenImagePreview(item)
            }
        }
        binding.rvCollections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCollections.adapter = adapter
    }

    private fun setupListeners() {
        // Back Button
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Add Collection Button
        binding.btnAddCollection.setOnClickListener {
            CollectionCreateBottomSheet.newInstance()
                .show(parentFragmentManager, "CollectionCreateBottomSheet")
        }

        // Filter Tabs
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

        // Search Text Watcher
        binding.etSearchCollections.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                filterCollections()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }


    private fun showFullscreenImagePreview(item: CollectionItem) {
        val context = requireContext()
        val dialog = android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val root = android.widget.RelativeLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val imageView = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val photoPath = item.photoPath
        if (photoPath != null) {
            val file = java.io.File(photoPath)
            if (file.exists()) {
                imageView.setImageURI(android.net.Uri.fromFile(file))
            } else {
                imageView.setImageResource(R.drawable.ic_cash_proof)
            }
        } else {
            imageView.setImageResource(R.drawable.ic_cash_proof)
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

    private fun setupResultListener() {
        parentFragmentManager.setFragmentResultListener(
            CollectionCreateBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val amount = bundle.getDouble("amount", 0.0)
            val booking = bundle.getString("booking").orEmpty()
            val paymentMode = bundle.getString("paymentMode").orEmpty()
            val refId = bundle.getString("refId").orEmpty()
            val notes = bundle.getString("notes").orEmpty()
            val photoPath = bundle.getString("photoPath")
            val rectifiedId = bundle.getString("rectifiedId")

            if (rectifiedId != null) {
                val existingItem = masterList.find { it.id == rectifiedId }
                if (existingItem != null) {
                    existingItem.amount = amount
                    existingItem.bookingName = booking
                    existingItem.paymentMode = paymentMode
                    existingItem.refId = refId
                    existingItem.notes = notes
                    existingItem.photoPath = photoPath
                    existingItem.status = CollectionStatus.PENDING
                    existingItem.remarks = null

                    val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US)
                    existingItem.dateString = sdf.format(Date())
                }
            } else {
                val type = if (booking.contains("Plot 12") || booking.contains("Plot 19")) {
                    CollectionType.BANK_LOAN
                } else {
                    CollectionType.SELF_FINANCE
                }

                val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US)
                val dateString = sdf.format(Date())

                val newItem = CollectionItem(
                    id = (masterList.size + 1).toString(),
                    bookingName = booking,
                    amount = amount,
                    paymentMode = paymentMode,
                    refId = refId,
                    notes = notes,
                    photoPath = photoPath,
                    dateString = dateString,
                    status = CollectionStatus.PENDING,
                    type = type
                )

                masterList.add(0, newItem)
            }
            filterCollections()
        }

    }

    private fun filterCollections() {
        val filtered = masterList.filter { item ->
            // Filter by Tab Type
            val matchesTab = selectedTypeFilter == null || item.type == selectedTypeFilter

            // Filter by Search Query
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

        binding.tvSummaryCount.text = if (count == 1) "1 Collection Today" else "$count Collections Today"
        binding.tvSummaryTotal.text = formatRupees(totalAmount)
    }

    private fun formatRupees(amount: Double): String {
        val rupeeFormatter = NumberFormat.getInstance(Locale("en", "IN"))
        return "₹" + rupeeFormatter.format(amount.toLong())
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
