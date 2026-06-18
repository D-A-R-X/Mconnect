package com.manjugroups.m_connect.ui.library.loans

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffData
import kotlinx.coroutines.launch
import java.util.Locale

class LoanDeskFragment : Fragment() {

    private lateinit var rvLoanDesk: RecyclerView
    private lateinit var etSearchLoanDesk: EditText
    private lateinit var adapter: LoanDeskAdapter
    private lateinit var tvSelectedRole: TextView

    private val api by lazy { ApiService.create() }
    private lateinit var session: SessionManager

    private var isLegalTeamMode = true // Default to Legal Team mode (matches screenshot!)

    // Initial mock list of cards matching the user screenshot
    private var allItems = listOf(
        LoanDeskItem(
            id = "1",
            name = "Karthi S",
            phone = "7812828268",
            amount = "₹13,50,000",
            location = "OMR Road, Sholinganallur...",
            date = "16 Jun '26",
            status = "Docs Pending",
            pills = listOf("PAN", "Aadhaar", "+7"),
            rejectionRemarks = null
        ),
        LoanDeskItem(
            id = "2",
            name = "S_client3",
            phone = "9000200003",
            amount = "₹13,50,000",
            location = "Anna Nagar, Chennai...",
            date = "16 Jun '26",
            status = "Docs Pending",
            pills = listOf("PAN", "+6"),
            rejectionRemarks = null
        ),
        LoanDeskItem(
            id = "3",
            name = "S Ramakrishnan",
            phone = "9710085351",
            amount = "₹30,00,000",
            location = "T. Nagar, Chennai - 6...",
            date = "16 Jun '26",
            status = "App Received",
            pills = listOf("PAN", "+8"),
            rejectionRemarks = null,
            doc1Name = "pan_card_proof.jpg",
            doc2Name = "aadhaar_card_proof.jpg",
            doc3Name = "bank_statement_proof.pdf",
            doc4Name = "pay_slip_proof.jpg"
        )
    )

    private var filteredItems = allItems.toList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_loan_desk, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Set up header back navigation
        val btnBack = view.findViewById<View>(R.id.btnBack)
        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Search Input Setup
        etSearchLoanDesk = view.findViewById(R.id.etSearchLoanDesk)
        etSearchLoanDesk.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Role Selector Setup
        val btnRoleSelector = view.findViewById<View>(R.id.btnRoleSelector)
        tvSelectedRole = view.findViewById(R.id.tvSelectedRole)

        // Enforce default role to "Legal Team"
        tvSelectedRole.text = "Legal Team"
        isLegalTeamMode = true

        btnRoleSelector.setOnClickListener {
            val popup = PopupMenu(requireContext(), btnRoleSelector)
            popup.menu.add("Sales Team")
            popup.menu.add("Legal Team")
            popup.menu.add("Legal Manager")
            popup.setOnMenuItemClickListener { menuItem ->
                val selected = menuItem.title.toString()
                tvSelectedRole.text = selected
                when (selected) {
                    "Sales Team" -> {
                        isLegalTeamMode = false
                        adapter.setRoleMode(LoanDeskAdapter.ROLE_SALES_TEAM)
                    }
                    "Legal Team" -> {
                        isLegalTeamMode = true
                        adapter.setRoleMode(LoanDeskAdapter.ROLE_LEGAL_TEAM)
                    }
                    "Legal Manager" -> {
                        isLegalTeamMode = true
                        adapter.setRoleMode(LoanDeskAdapter.ROLE_LEGAL_MANAGER)
                    }
                }
                true
            }
            popup.show()
        }

        // Recycler Setup
        rvLoanDesk = view.findViewById(R.id.rvLoanDesk)
        rvLoanDesk.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = LoanDeskAdapter(
            items = filteredItems,
            onItemClick = { item ->
                if (isLegalTeamMode) {
                    showUploadBottomSheet(item, isViewMode = true)
                } else {
                    showUploadBottomSheet(item, isViewMode = false)
                }
            },
            onAcceptClick = { item ->
                item.status = "Approved"
                item.rejectionRemarks = null
                filterList(etSearchLoanDesk.text.toString())
                Toast.makeText(requireContext(), "Documents approved successfully", Toast.LENGTH_SHORT).show()
            },
            onRejectClick = { item ->
                LoanDeskRejectBottomSheet.newInstance(item.id)
                    .show(parentFragmentManager, "LoanDeskRejectBottomSheet")
            },
            onRectifyClick = { item ->
                showUploadBottomSheet(item, isViewMode = false)
            },
            onAssignClick = { item ->
                showAssignBottomSheet(item)
            }
        )
        adapter.setLegalTeamMode(isLegalTeamMode)
        rvLoanDesk.adapter = adapter

        // Listen for rejection remarks
        parentFragmentManager.setFragmentResultListener(
            LoanDeskRejectBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val itemId = bundle.getString("itemId")
            val remarks = bundle.getString("remarks")
            if (itemId != null && remarks != null) {
                val item = allItems.find { it.id == itemId }
                if (item != null) {
                    item.status = "Rejected"
                    item.rejectionRemarks = remarks
                    filterList(etSearchLoanDesk.text.toString())
                    Toast.makeText(requireContext(), "Documents rejected with remarks", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun filterList(query: String) {
        val trimmed = query.trim().lowercase()
        filteredItems = if (trimmed.isEmpty()) {
            allItems
        } else {
            allItems.filter {
                it.name.lowercase().contains(trimmed) ||
                it.phone.contains(trimmed) ||
                it.location.lowercase().contains(trimmed)
            }
        }
        adapter.updateList(filteredItems)
    }

    private fun showUploadBottomSheet(item: LoanDeskItem, isViewMode: Boolean = false) {
        val bottomSheet = LoanDeskUploadBottomSheet.newInstance(item, isViewMode) { doc1, doc2, doc3, doc4 ->
            // Update item details upon successful documents submission / rectification
            item.status = "App Received"
            item.rejectionRemarks = null
            item.doc1Name = doc1
            item.doc2Name = doc2
            item.doc3Name = doc3
            item.doc4Name = doc4
            // Update pills to show that multiple files were added
            item.pills = when (item.id) {
                "1" -> listOf("PAN", "Aadhaar", "+9") // Simulated update (+7 became +9 docs or similar)
                "2" -> listOf("PAN", "Aadhaar", "+8")
                else -> item.pills
            }
            
            // Refresh the adapter lists
            filterList(etSearchLoanDesk.text.toString())
        }
        bottomSheet.show(parentFragmentManager, "LoanDeskUploadBottomSheet")
    }

    private fun showAssignBottomSheet(item: LoanDeskItem) {
        if (!isAdded) return
        val context = requireContext()
        val content = layoutInflater.inflate(R.layout.bottom_sheet_multi_people_picker, null)
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)

        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            val params = sheet.layoutParams
            params.height = (resources.displayMetrics.heightPixels * 0.9f).toInt()
            sheet.layoutParams = params
            sheet.setBackgroundResource(R.drawable.bg_bottom_sheet)
            androidx.core.view.ViewCompat.setElevation(sheet, 0f)
            BottomSheetBehavior.from(sheet).apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
                isDraggable = true
            }
        }

        dialog.setOnShowListener { dialogInterface ->
            val d = dialogInterface as BottomSheetDialog
            d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { s ->
                s.setBackgroundResource(R.drawable.bg_bottom_sheet)
                androidx.core.view.ViewCompat.setElevation(s, 0f)
            }
        }

        val titleView = content.findViewById<TextView>(R.id.tvSheetTitle)
        val closeBtn = content.findViewById<View>(R.id.btnSheetClose)
        val searchField = content.findViewById<EditText>(R.id.etSearchPeople)
        val peopleCard = content.findViewById<LinearLayout>(R.id.peopleCard)
        val emptyView = content.findViewById<TextView>(R.id.tvEmptyPeople)
        val doneBtn = content.findViewById<FrameLayout>(R.id.btnDone)
        val doneLabel = content.findViewById<TextView>(R.id.tvDoneLabel)
        val countView = content.findViewById<TextView>(R.id.tvSelectedCount)

        titleView.text = "Assign Verification"
        doneLabel.text = "Assign"
        countView.text = "0 selected"

        var selectedStaff: StaffData? = null
        val mockStaff = listOf(
            StaffData(id = "m1", name = "Rajesh Kumar", phone = "+91 98765 43210", role = "Legal", designation = "Legal Executive", status = "active", employeeId = "EMP101", department = "Legal"),
            StaffData(id = "m2", name = "Sandhya R", phone = "+91 98765 43211", role = "Legal", designation = "Senior Legal Specialist", status = "active", employeeId = "EMP102", department = "Legal"),
            StaffData(id = "m3", name = "Vignesh Murthy", phone = "+91 98765 43212", role = "Legal", designation = "Legal Officer", status = "active", employeeId = "EMP103", department = "Legal"),
            StaffData(id = "m4", name = "Aisha Banu", phone = "+91 98765 43213", role = "Legal", designation = "Verification Officer", status = "active", employeeId = "EMP104", department = "Legal")
        )
        var allPeople = mockStaff

        closeBtn.setOnClickListener { dialog.dismiss() }

        fun updateDoneButton() {
            val enabled = selectedStaff != null
            countView.text = if (enabled) "1 selected" else "0 selected"
            doneBtn.isClickable = enabled
            doneBtn.isFocusable = enabled
            doneBtn.setBackgroundResource(
                if (enabled) R.drawable.bg_sheet_start_button
                else R.drawable.bg_sheet_start_button_disabled
            )
        }

        fun bindPeople(staffList: List<StaffData>) {
            peopleCard.removeAllViews()
            if (staffList.isEmpty()) {
                emptyView.text = "No matching people"
                emptyView.visibility = View.VISIBLE
                peopleCard.visibility = View.GONE
                return
            }
            emptyView.visibility = View.GONE
            peopleCard.visibility = View.VISIBLE

            peopleCard.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            val dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
                setSize(0, (resources.displayMetrics.density * 0.5f).toInt().coerceAtLeast(1))
                setColor(Color.parseColor("#E4E7EC"))
            }
            peopleCard.dividerDrawable = dividerDrawable

            staffList.forEachIndexed { index, member ->
                val row = layoutInflater.inflate(R.layout.item_chat_sheet_person, peopleCard, false)
                row.tag = member

                val tvName = row.findViewById<TextView>(R.id.tvName)
                val tvSubtitle = row.findViewById<TextView>(R.id.tvSubtitle)
                val radio = row.findViewById<View>(R.id.radioButton)
                val avatarCheck = row.findViewById<View>(R.id.avatarCheck)
                val onlineDot = row.findViewById<View>(R.id.onlineDot)

                tvName.text = member.name ?: "User"
                tvSubtitle.text = listOfNotNull(member.designation, member.department)
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(" • ")
                    ?: member.phone ?: ""

                val initials = initialsFor(member.name ?: "User")
                bindAvatar(
                    row.findViewById(R.id.avatarContainer),
                    row.findViewById(R.id.tvAvatar),
                    row.findViewById(R.id.ivAvatarPhoto),
                    member.photo,
                    initials,
                    index + (member.name?.length ?: 0)
                )

                val isSel = selectedStaff?.id == member.id
                radio.setBackgroundResource(
                    if (isSel) R.drawable.bg_sheet_radio_on
                    else R.drawable.bg_sheet_radio_off
                )
                avatarCheck.visibility = if (isSel) View.VISIBLE else View.GONE
                onlineDot.visibility = View.GONE

                row.setOnClickListener {
                    if (selectedStaff?.id == member.id) {
                        selectedStaff = null
                    } else {
                        selectedStaff = member
                    }

                    // Update all row selections in-place
                    for (i in 0 until peopleCard.childCount) {
                        val child = peopleCard.getChildAt(i)
                        val childMember = child.tag as? StaffData ?: continue
                        val childRadio = child.findViewById<View>(R.id.radioButton)
                        val childAvatarCheck = child.findViewById<View>(R.id.avatarCheck)
                        val childIsSel = selectedStaff?.id == childMember.id
                        childRadio.setBackgroundResource(
                            if (childIsSel) R.drawable.bg_sheet_radio_on
                            else R.drawable.bg_sheet_radio_off
                        )
                        childAvatarCheck.visibility = if (childIsSel) View.VISIBLE else View.GONE
                    }

                    updateDoneButton()
                }

                peopleCard.addView(row)
            }
        }

        fun filterPeople(query: String) {
            var visibleCount = 0
            val trimmedQuery = query.trim()
            for (i in 0 until peopleCard.childCount) {
                val child = peopleCard.getChildAt(i)
                val member = child.tag as? StaffData ?: continue
                
                val matches = if (trimmedQuery.isEmpty()) {
                    true
                } else {
                    (member.name ?: "").contains(trimmedQuery, ignoreCase = true) ||
                    (member.designation ?: "").contains(trimmedQuery, ignoreCase = true) ||
                    (member.department ?: "").contains(trimmedQuery, ignoreCase = true) ||
                    (member.phone ?: "").contains(trimmedQuery)
                }
                
                if (matches) {
                    child.visibility = View.VISIBLE
                    visibleCount++
                } else {
                    child.visibility = View.GONE
                }
            }
            
            if (visibleCount == 0) {
                emptyView.text = "No matching people"
                emptyView.visibility = View.VISIBLE
                peopleCard.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                peopleCard.visibility = View.VISIBLE
            }
        }

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterPeople(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        doneBtn.setOnClickListener {
            val staff = selectedStaff
            if (staff != null) {
                item.assignedTo = staff.name
                filterList(etSearchLoanDesk.text.toString())
                Toast.makeText(context, "Assigned to ${staff.name} successfully", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        // Initial population with local mock verifiers
        bindPeople(allPeople)

        // Asynchronously load active staff from the API if possible
        if (::session.isInitialized && session.isLoggedIn) {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching {
                    api.getStaff(session.bearerToken, status = "active")
                }.onSuccess { response ->
                    val apiStaff = response.staff.filter { it.id != null }
                    if (apiStaff.isNotEmpty()) {
                        // Filter for legal staff, or default to all active staff if no specific legal staff are found
                        val legalOnly = apiStaff.filter {
                            it.department?.contains("legal", ignoreCase = true) == true ||
                            it.designation?.contains("legal", ignoreCase = true) == true ||
                            it.role?.contains("legal", ignoreCase = true) == true
                        }
                        allPeople = if (legalOnly.isNotEmpty()) legalOnly else apiStaff
                        if (dialog.isShowing) {
                            bindPeople(allPeople)
                            filterPeople(searchField.text.toString())
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun initialsFor(name: String): String =
        name.split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { name.take(1).uppercase(Locale.getDefault()) }

    private fun bindAvatar(
        container: View,
        label: TextView,
        ivPhoto: ImageView,
        photoUrl: String?,
        text: String,
        seed: Int
    ) {
        val resolved = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(photoUrl)
        if (!resolved.isNullOrBlank()) {
            ivPhoto.visibility = View.VISIBLE
            label.visibility = View.GONE
            ivPhoto.load(resolved) {
                crossfade(true)
                transformations(CircleCropTransformation())
            }
        } else {
            ivPhoto.visibility = View.GONE
            label.visibility = View.VISIBLE
            
            val palette = when (seed.mod(4)) {
                0 -> "#E0F2FE" to "#0284C7"
                1 -> "#F0FDF4" to "#16A34A"
                2 -> "#FEF3C7" to "#D97706"
                else -> "#FEE2E2" to "#DC2626"
            }
            container.background?.mutate()?.setTint(Color.parseColor(palette.first))
            label.setTextColor(Color.parseColor(palette.second))
            label.text = text
        }
    }

    override fun onResume() {
        super.onResume()
        // Hide the main bottom tab bar when inside Loan Desk sub-page
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.parseColor("#FFFFFF"), true, fullBleed = false)
        }
    }
}
