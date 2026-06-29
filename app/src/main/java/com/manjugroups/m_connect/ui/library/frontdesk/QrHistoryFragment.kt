package com.manjugroups.m_connect.ui.library.frontdesk

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentQrHistoryBinding
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat


class QrHistoryFragment : Fragment() {

    private var _binding: FragmentQrHistoryBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQrHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnClearHistory.setOnClickListener {
            clearHistory()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // History top bar spacer
            val lp = binding.statusBarPlaceholder.layoutParams
            lp.height = sysBars.top
            binding.statusBarPlaceholder.layoutParams = lp

            // Verification layout top spacer
            val lpVerification = binding.spacerStatusBarVerificationHistory.layoutParams
            lpVerification.height = sysBars.top
            binding.spacerStatusBarVerificationHistory.layoutParams = lpVerification

            // Add navigation bar bottom padding to absolute floating buttons panel
            val extraBottomPadding = (16 * resources.displayMetrics.density).toInt()
            binding.llBottomButtonsPanelHistory.setPadding(
                binding.llBottomButtonsPanelHistory.paddingLeft,
                binding.llBottomButtonsPanelHistory.paddingTop,
                binding.llBottomButtonsPanelHistory.paddingRight,
                sysBars.bottom + extraBottomPadding
            )

            insets
        }

        binding.btnCancelHeaderVerificationHistory.setOnClickListener {
            binding.visitorVerificationContainerHistory.visibility = View.GONE
        }

        binding.btnHistoryVerificationHistory.setOnClickListener {
            // Already on history page, just close layout overview
            binding.visitorVerificationContainerHistory.visibility = View.GONE
        }

        binding.btnCloseVerificationHistory.setOnClickListener {
            binding.visitorVerificationContainerHistory.visibility = View.GONE
        }

        renderHistory()
    }

    private fun renderHistory() {
        if (_binding == null) return
        
        binding.llHistoryList.removeAllViews()
        
        val sharedPrefs = requireContext().getSharedPreferences("qr_scanner_prefs", Context.MODE_PRIVATE)
        val historyStr = sharedPrefs.getString("qr_history_list", "[]") ?: "[]"
        
        val list = parseHistoryList(historyStr)
        
        if (list.isEmpty()) {
            binding.llEmptyState.visibility = View.VISIBLE
            binding.llHistoryList.visibility = View.GONE
            binding.btnClearHistory.visibility = View.GONE
        } else {
            binding.llEmptyState.visibility = View.GONE
            binding.llHistoryList.visibility = View.VISIBLE
            binding.btnClearHistory.visibility = View.VISIBLE
            
            list.forEach { item ->
                val rowView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_qr_scan_row, binding.llHistoryList, false)
                
                val tvScanValue = rowView.findViewById<TextView>(R.id.tvScanValue)
                val tvScanDetails = rowView.findViewById<TextView>(R.id.tvScanDetails)
                val tvScanHost = rowView.findViewById<TextView>(R.id.tvScanHost)
                val tvScanTime = rowView.findViewById<TextView>(R.id.tvScanTime)

                var isJson = false
                var primaryName = ""
                var company = ""
                var visitorType = ""
                var purpose = ""
                var hostPerson = ""

                try {
                    val json = JSONObject(item.value)
                    if (json.optBoolean("isStructured", false)) {
                        isJson = true
                        primaryName = json.optString("primaryName", "")
                        company = json.optString("primaryCompany", "")
                        visitorType = json.optString("visitorType", "")
                        purpose = json.optString("purpose", "")
                        hostPerson = json.optString("hostPerson", "")
                    }
                } catch (_: Exception) {}

                if (isJson) {
                    tvScanValue.text = primaryName
                    tvScanDetails.text = "$company • $visitorType ($purpose)"
                    tvScanHost.text = "Host: $hostPerson"
                    tvScanDetails.visibility = View.VISIBLE
                    tvScanHost.visibility = View.VISIBLE
                } else {
                    tvScanValue.text = item.value
                    tvScanDetails.visibility = View.GONE
                    tvScanHost.visibility = View.GONE
                }
                
                tvScanTime.text = item.timestamp
                
                // Clicking a history row shows its details in the glass modal
                rowView.setOnClickListener {
                    showHistoryDetailModal(item.value)
                }
                
                binding.llHistoryList.addView(rowView)
            }
        }
    }

    private fun showHistoryDetailModal(historyValue: String) {
        var primaryName = "Jane Doe"
        var primaryPhone = "+91 98765 43210"
        var primaryAge = "28"
        var primaryEmail = "jane.doe@example.com"
        var primaryCompany = "Tech Solutions"
        var visitorType = "Vendor"
        var purpose = "Installation"
        var hostPerson = "Dev Super Admin"
        var expectedTime = "29 Jun 2026, 11:30 AM - 01:30 PM"
        var meetingNotes = "Technical interview and workspace verification."
        val secondaryList = ArrayList<JSONObject>()

        try {
            val json = JSONObject(historyValue)
            if (json.optBoolean("isStructured", false)) {
                primaryName = json.optString("primaryName", primaryName)
                primaryCompany = json.optString("primaryCompany", primaryCompany)
                primaryPhone = json.optString("primaryPhone", primaryPhone)
                primaryEmail = json.optString("primaryEmail", primaryEmail)
                primaryAge = json.optString("primaryAge", primaryAge)
                
                val secArr = json.optJSONArray("secondaryList")
                if (secArr != null) {
                    for (i in 0 until secArr.length()) {
                        secondaryList.add(secArr.getJSONObject(i))
                    }
                }
                
                visitorType = json.optString("visitorType", visitorType)
                purpose = json.optString("purpose", purpose)
                hostPerson = json.optString("hostPerson", hostPerson)
                expectedTime = json.optString("expectedTime", expectedTime)
                meetingNotes = json.optString("meetingNotes", meetingNotes)
            } else {
                meetingNotes = historyValue
                secondaryList.clear()
            }
        } catch (e: Exception) {
            meetingNotes = historyValue
            secondaryList.clear()
        }

        binding.tvPrimaryNameHistory.text = primaryName
        binding.tvPrimaryCompanyHistory.text = primaryCompany
        binding.tvPrimaryPhoneHistory.text = primaryPhone
        binding.tvPrimaryEmailHistory.text = primaryEmail
        binding.tvPrimaryAgeHistory.text = "Age: $primaryAge"

        binding.tvVisitCategoryTypeHistory.text = "$visitorType ($purpose)"
        binding.tvVisitHostHistory.text = hostPerson
        binding.tvVisitTimeWindowHistory.text = expectedTime
        binding.tvVisitNotesHistory.text = meetingNotes

        binding.containerSecondaryListHistory.removeAllViews()
        if (secondaryList.isEmpty()) {
            binding.layoutSecondaryVisitorsHistory.visibility = View.GONE
        } else {
            binding.layoutSecondaryVisitorsHistory.visibility = View.VISIBLE
            for (sec in secondaryList) {
                val secView = LayoutInflater.from(requireContext()).inflate(R.layout.item_secondary_visitor, binding.containerSecondaryListHistory, false)
                val tvSecName = secView.findViewById<TextView>(R.id.tvSecondaryName)
                val tvSecCompany = secView.findViewById<TextView>(R.id.tvSecondaryCompany)
                val tvSecPhone = secView.findViewById<TextView>(R.id.tvSecondaryPhone)
                val tvSecEmail = secView.findViewById<TextView>(R.id.tvSecondaryEmail)
                val tvSecAge = secView.findViewById<TextView>(R.id.tvSecondaryAge)

                tvSecName.text = sec.optString("name", "N/A")
                tvSecCompany.text = sec.optString("company", "N/A")
                tvSecPhone.text = sec.optString("phone", "N/A")
                tvSecEmail.text = sec.optString("email", "N/A")
                tvSecAge.text = "Age: ${sec.optString("age", "N/A")}"

                binding.containerSecondaryListHistory.addView(secView)
            }
        }

        binding.visitorVerificationContainerHistory.visibility = View.VISIBLE
    }

    private fun parseHistoryList(historyStr: String): List<ScanItem> {
        val items = mutableListOf<ScanItem>()
        try {
            val jsonArray = JSONArray(historyStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(
                    ScanItem(
                        value = obj.optString("value", ""),
                        timestamp = obj.optString("timestamp", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return items
    }

    private fun clearHistory() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Clear Scan History")
            .setMessage("Are you sure you want to delete all scans?")
            .setPositiveButton("Clear") { dialog, _ ->
                val sharedPrefs = requireContext().getSharedPreferences("qr_scanner_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().remove("qr_history_list").apply()
                renderHistory()
                dialog.dismiss()
                Toast.makeText(requireContext(), "Scan history cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(android.graphics.Color.WHITE, true, true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class ScanItem(val value: String, val timestamp: String)
}
