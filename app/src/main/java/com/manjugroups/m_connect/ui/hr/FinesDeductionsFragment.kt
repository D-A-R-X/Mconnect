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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import coil.load
import coil.transform.CircleCropTransformation
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.FragmentFinesDeductionsBinding
import com.manjugroups.m_connect.ui.common.setupPullToRefresh
import com.manjugroups.m_connect.ui.common.AvatarUtils.loadUserAvatar
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

class FinesDeductionsFragment : Fragment() {

    private var _binding: FragmentFinesDeductionsBinding? = null
    private val binding get() = _binding!!

    private val api = com.manjugroups.m_connect.network.ApiService.create()
    private val session by lazy { com.manjugroups.m_connect.auth.SessionManager(requireContext()) }
    private var loadJob: kotlinx.coroutines.Job? = null

    // Real role-based access (replaces the old mock Admin/User spinner):
    //   canViewAll  — fines.view (admin / HR): sees the company-wide list.
    //   canManage   — fines.manage: can create a fine (the + button).
    // Everyone else is view-only and sees just their OWN fines (incl. any
    // attendance late fines) via /api/hr/fines/my.
    private val canViewAll get() = session.hasPermission("fines.view")
    private val canManage get() = session.hasPermission("fines.manage")

    // Real fine records loaded from /api/hr/fines/list — no mock data.
    private val fineRecords = mutableListOf<FineRecord>()

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
            loadFines()
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
                    // The mobile create sheet is a stub — fines are created on
                    // the web admin. Reload from the backend so the list only
                    // ever reflects real, persisted data.
                    loadFines()
                }
            })
            sheet.showOnce(parentFragmentManager, "create_fine_sheet")
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

        // Role-based visibility, driven by real IAM permissions:
        //   - Create (+) only for fines.manage holders.
        //   - Search only in the company-wide (fines.view) view; the own-fines
        //     list is short and doesn't need it.
        binding.roleSwitcherContainer.visibility = View.GONE
        binding.btnCreateFineContainer.visibility = if (canManage) View.VISIBLE else View.GONE
        binding.llSearchContainer.visibility = if (canViewAll) View.VISIBLE else View.GONE

        loadFines()
    }

    /** Fetch real fines from /api/hr/fines/list (active only) and render. */
    private fun loadFines() {
        if (_binding == null) return
        val token = session.bearerToken
        if (token.isBlank()) {
            binding.finesRefresh.isRefreshing = false
            return
        }
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                // fines.view holders get the company-wide list; everyone else
                // gets only their own fines (incl. attendance late fines).
                val resp = if (canViewAll) {
                    api.listFines(token, status = "active")
                } else {
                    api.listMyFines(token)
                }
                if (_binding == null) return@launch
                fineRecords.clear()
                fineRecords.addAll(
                    resp.fines.map { f ->
                        val dept = f.department.trim()
                            .replaceFirstChar { c -> c.uppercase() }
                            .ifBlank { "—" }
                        val statusLabel = f.status
                            .replaceFirstChar { c -> c.uppercase() }
                        FineRecord(
                            name = f.staffName.ifBlank { f.employeeId },
                            department = dept,
                            fineType = f.typeName,
                            amount = f.amount,
                            date = listOf(f.monthName, f.year.toString())
                                .filter { it.isNotBlank() }
                                .joinToString(" "),
                            status = statusLabel,
                            photoUrl = f.staffPhotoUrl,
                            finePhotoUrl = f.photoUrl,
                            reason = f.notes,
                            photoResId = null,
                        )
                    }
                )
                renderList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                android.widget.Toast.makeText(
                    requireContext(),
                    "Couldn't load fines: ${e.message ?: "network error"}",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            } finally {
                if (_binding != null) binding.finesRefresh.isRefreshing = false
            }
        }
    }

    private fun renderList() {
        binding.llFinesList.removeAllViews()

        val filteredList = if (currentSearchQuery.isEmpty() || !canViewAll) {
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

                // Card avatar: the staff member's profile photo, or a coloured
                // first-letter avatar when they have none.
                itemView.findViewById<ImageView>(R.id.ivEmployeeAvatar).loadUserAvatar(
                    com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(record.photoUrl),
                    record.name,
                )

                // Tapping the card opens the fine's detail — captured picture + reason.
                itemView.setOnClickListener { showFineDetail(record) }

                binding.llFinesList.addView(itemView)
            }
        }
    }

    /** Bottom sheet for a fine row: the captured picture + the reason. */
    private fun showFineDetail(record: FineRecord) {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.sheet_fine_detail, null)

        content.findViewById<TextView>(R.id.tvFineDetailName).text = record.name
        content.findViewById<TextView>(R.id.tvFineDetailMeta).text =
            listOf(
                record.fineType,
                String.format(Locale.getDefault(), "₹ %.0f", record.amount),
                record.status,
                record.date,
            ).filter { it.isNotBlank() }.joinToString("  ·  ")

        val photo = content.findViewById<ImageView>(R.id.ivFineDetailPhoto)
        val noPhoto = content.findViewById<TextView>(R.id.tvFineDetailNoPhoto)
        val finePhoto = com.manjugroups.m_connect.ui.common.ProfilePhotos.resolve(record.finePhotoUrl)
        if (!finePhoto.isNullOrEmpty()) {
            photo.visibility = View.VISIBLE
            noPhoto.visibility = View.GONE
            photo.load(finePhoto) { crossfade(true) }
            // Tap the picture to view it full-screen.
            photo.setOnClickListener { showImagePreview(finePhoto) }
        } else {
            photo.visibility = View.GONE
            noPhoto.visibility = View.VISIBLE
        }

        content.findViewById<TextView>(R.id.tvFineDetailReason).text =
            record.reason?.takeIf { it.isNotBlank() } ?: "No reason provided"

        sheet.setContentView(content)
        sheet.show()
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
        // Refresh so a fine added on the web shows up when returning here.
        loadFines()
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(
            android.graphics.Color.parseColor("#FEFEFE"), true
        )
        super.onPause()
    }

    override fun onDestroyView() {
        loadJob?.cancel()
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
        val photoUrl: String?,        // staff profile photo
        val finePhotoUrl: String?,    // the fine's captured picture
        val reason: String?,          // why the fine was applied (notes)
        val photoResId: Int? = null
    )
}
