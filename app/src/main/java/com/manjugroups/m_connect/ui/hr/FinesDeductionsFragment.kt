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

    private var currentRole = "Admin"

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
            finePhotoUrl = "https://images.unsplash.com/photo-1544005313-94ddf0286df2", // Mock fine image for User mode
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
            finePhotoUrl = "https://images.unsplash.com/photo-1506794778202-cad84cf45f1d", // Mock fine image for User mode
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
            finePhotoUrl = null,
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
                    employeePhoto: String?,
                    finePhoto: String?
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
                            photoUrl = employeePhoto,
                            finePhotoUrl = finePhoto,
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

        // Set up Spinner for Role
        val roles = arrayOf("Admin", "User")
        val adapter = android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, roles)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerRole.adapter = adapter
        binding.spinnerRole.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                currentRole = roles[position]
                if (currentRole == "Admin") {
                    binding.llSearchContainer.visibility = View.VISIBLE
                    binding.btnCreateFineContainer.visibility = View.VISIBLE
                } else {
                    binding.llSearchContainer.visibility = View.GONE
                    binding.btnCreateFineContainer.visibility = View.GONE
                }
                renderList()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        renderList()
    }

    private fun renderList() {
        binding.llFinesList.removeAllViews()

        val filteredList = if (currentSearchQuery.isEmpty() || currentRole == "User") {
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

                if (currentRole == "User") {
                    val resolvedFinePhoto = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(record.finePhotoUrl)
                    if (!resolvedFinePhoto.isNullOrEmpty()) {
                        // Load camera fine picture instead of profile avatar
                        avatarView.load(resolvedFinePhoto) {
                            crossfade(true)
                            placeholder(R.drawable.bg_attendance_avatar_placeholder)
                            error(R.drawable.bg_attendance_avatar_placeholder)
                            transformations(CircleCropTransformation())
                        }
                        // Click to preview full size image
                        avatarView.setOnClickListener {
                            showImagePreview(resolvedFinePhoto)
                        }
                    } else {
                        // Fallback: show default camera placeholder
                        avatarView.load(R.drawable.ic_header_camera_outline) {
                            placeholder(R.drawable.bg_attendance_avatar_placeholder)
                            error(R.drawable.bg_attendance_avatar_placeholder)
                            transformations(CircleCropTransformation())
                        }
                        avatarView.setOnClickListener(null)
                    }
                } else {
                    // Admin mode: Load employee profile picture
                    avatarView.setOnClickListener(null)
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
                }

                binding.llFinesList.addView(itemView)
            }
        }
    }

    private fun showImagePreview(imageUrl: String) {
        val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_image_preview)
        val imageView = dialog.findViewById<ImageView>(R.id.ivFullPreview)
        val btnClose = dialog.findViewById<View>(R.id.btnPreviewClose)

        imageView.load(imageUrl) {
            crossfade(true)
        }
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
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
        val finePhotoUrl: String?,
        val photoResId: Int? = null
    )
}
