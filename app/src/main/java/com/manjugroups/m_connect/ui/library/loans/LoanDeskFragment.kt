package com.manjugroups.m_connect.ui.library.loans

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R

class LoanDeskFragment : Fragment() {

    private lateinit var rvLoanDesk: RecyclerView
    private lateinit var etSearchLoanDesk: EditText
    private lateinit var adapter: LoanDeskAdapter

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
            pills = listOf("PAN", "Aadhaar", "+7")
        ),
        LoanDeskItem(
            id = "2",
            name = "S_client3",
            phone = "9000200003",
            amount = "₹13,50,000",
            location = "Anna Nagar, Chennai...",
            date = "16 Jun '26",
            status = "Docs Pending",
            pills = listOf("PAN", "+6")
        ),
        LoanDeskItem(
            id = "3",
            name = "S Ramakrishnan",
            phone = "9710085351",
            amount = "₹30,00,000",
            location = "T. Nagar, Chennai - 6...",
            date = "16 Jun '26",
            status = "App Received",
            pills = listOf("PAN", "+8")
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

        // Recycler Setup
        rvLoanDesk = view.findViewById(R.id.rvLoanDesk)
        rvLoanDesk.layoutManager = LinearLayoutManager(requireContext())
        
        adapter = LoanDeskAdapter(filteredItems) { clickedItem ->
            showUploadBottomSheet(clickedItem)
        }
        rvLoanDesk.adapter = adapter
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

    private fun showUploadBottomSheet(item: LoanDeskItem) {
        val bottomSheet = LoanDeskUploadBottomSheet.newInstance {
            // Update item details upon successful documents submission
            item.status = "App Received"
            // Update pills to show that multiple files were added
            item.pills = when (item.id) {
                "1" -> listOf("PAN", "Aadhaar", "+9") // Simulated update (+7 became +9 docs or similar)
                "2" -> listOf("PAN", "Aadhaar", "+8")
                else -> item.pills
            }
            
            // Refresh the adapter lists
            adapter.updateList(filteredItems)
        }
        bottomSheet.show(parentFragmentManager, "LoanDeskUploadBottomSheet")
    }

    override fun onResume() {
        super.onResume()
        // Hide the main bottom tab bar when inside Loan Desk sub-page
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(Color.parseColor("#FFFFFF"), false, fullBleed = false)
        }
    }
}
