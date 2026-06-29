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
            val lp = binding.statusBarPlaceholder.layoutParams
            lp.height = sysBars.top
            binding.statusBarPlaceholder.layoutParams = lp
            insets
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
                
                rowView.findViewById<TextView>(R.id.tvScanValue).text = item.value
                rowView.findViewById<TextView>(R.id.tvScanTime).text = item.timestamp
                
                // Clicking a history row shows its details in a short toast
                rowView.setOnClickListener {
                    Toast.makeText(requireContext(), item.value, Toast.LENGTH_SHORT).show()
                }
                
                binding.llHistoryList.addView(rowView)
            }
        }
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
