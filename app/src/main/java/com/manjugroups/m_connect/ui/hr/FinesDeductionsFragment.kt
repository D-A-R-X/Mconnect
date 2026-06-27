package com.manjugroups.m_connect.ui.hr

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentFinesDeductionsBinding
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import java.util.Locale

class FinesDeductionsFragment : Fragment() {

    private var _binding: FragmentFinesDeductionsBinding? = null
    private val binding get() = _binding!!

    // Local list of fine records initialized with mockup data matching the screenshot
    private val fineRecords = mutableListOf<FineRecord>(
        FineRecord(
            name = "Mari Muthu.R",
            department = "Sales Department",
            fineType = "Grooming",
            amount = 500.0,
            date = "22 May 2026",
            status = "Active",
            photoUrl = null,
            photoResId = R.drawable.avatar_mari_muthu_1
        ),
        FineRecord(
            name = "Sudalai Muthu.R",
            department = "Sales Department",
            fineType = "Late Attendance",
            amount = 500.0,
            date = "22 May 2026",
            status = "Active",
            photoUrl = null,
            photoResId = R.drawable.avatar_sudalai_muthu
        ),
        FineRecord(
            name = "Mari Muthu.R",
            department = "Sales Department",
            fineType = "Late Attendance",
            amount = 500.0,
            date = "22 May 2026",
            status = "Active",
            photoUrl = null,
            photoResId = R.drawable.avatar_mari_muthu_2
        )
    )

    private var currentSearchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFinesDeductionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.btnCreateFineContainer.setPadding(
                binding.btnCreateFineContainer.paddingLeft,
                binding.btnCreateFineContainer.paddingTop,
                binding.btnCreateFineContainer.paddingRight,
                sysBars.bottom
            )
            insets
        }

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.finesRefresh.setupPullToRefresh {
            renderList()
            binding.finesRefresh.isRefreshing = false
        }

        binding.btnCreateFine.setOnClickListener {
            val sheet = CreateFineBottomSheet.newInstance()
            sheet.setOnFineCreatedListener(object : CreateFineBottomSheet.OnFineCreatedListener {
                override fun onFineCreated(
                    name: String,
                    department: String,
                    fineType: String,
                    amount: Double,
                    dateStr: String,
                    photo: String?
                ) {
                    val resolvedResId = when (name) {
                        "Mari Muthu.R" -> R.drawable.avatar_mari_muthu_1
                        "Sudalai Muthu.R" -> R.drawable.avatar_sudalai_muthu
                        else -> null
                    }
                    fineRecords.add(
                        0, // Insert at top
                        FineRecord(
                            name = name,
                            department = department,
                            fineType = fineType,
                            amount = amount,
                            date = dateStr,
                            status = "Active",
                            photoUrl = photo,
                            photoResId = resolvedResId
                        )
                    )
                    renderList()
                }
            })
            sheet.show(parentFragmentManager, "create_fine_sheet")
        }

        // Add search filtering
        binding.etSearchEmployee.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderList()
    }

    private fun renderList() {
        binding.llFinesList.removeAllViews()

        val filteredList = if (currentSearchQuery.isEmpty()) {
            fineRecords
        } else {
            fineRecords.filter {
                it.name.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        if (filteredList.isEmpty()) {
            binding.llEmptyState.visibility = View.VISIBLE
            binding.llFinesList.visibility = View.GONE
        } else {
            binding.llEmptyState.visibility = View.GONE
            binding.llFinesList.visibility = View.VISIBLE

            filteredList.forEach { record ->
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_fine_record, binding.llFinesList, false)

                itemView.findViewById<TextView>(R.id.tvEmployeeName).text = record.name
                itemView.findViewById<TextView>(R.id.tvFineDetails).text = "${record.department}\n${record.fineType}"
                itemView.findViewById<TextView>(R.id.tvFineAmount).text = String.format(Locale.getDefault(), "₹ %.0f", record.amount)
                itemView.findViewById<TextView>(R.id.tvFineStatus).text = record.status
                itemView.findViewById<TextView>(R.id.tvFineDate).text = record.date

                val avatarView = itemView.findViewById<ImageView>(R.id.ivEmployeeAvatar)
                val resolvedUrl = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(record.photoUrl)
                if (record.photoResId != null) {
                    avatarView.load(record.photoResId) {
                        transformations(CircleCropTransformation())
                    }
                } else if (!resolvedUrl.isNullOrEmpty()) {
                    avatarView.load(resolvedUrl) {
                        crossfade(true)
                        placeholder(R.drawable.bg_attendance_avatar_placeholder)
                        error(R.drawable.bg_attendance_avatar_placeholder)
                        transformations(CircleCropTransformation())
                    }
                } else {
                    avatarView.load(R.drawable.bg_attendance_avatar_placeholder) {
                        transformations(CircleCropTransformation())
                    }
                }

                binding.llFinesList.addView(itemView)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(android.graphics.Color.WHITE, true)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
            android.graphics.Color.parseColor("#FEFEFE"), true
        )
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class FineRecord(
        val name: String,
        val department: String,
        val fineType: String,
        val amount: Double,
        val date: String,
        val status: String,
        val photoUrl: String?,
        val photoResId: Int? = null
    )
}
