package com.manjugroups.m_connect.ui.hr

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentStaffDetailBinding
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.StaffFullData
import com.manjugroups.m_connect.ui.chat.ChatMessagesFragment
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import com.manjugroups.m_connect.ui.common.commitOnce

class StaffDetailFragment : Fragment() {

    private var _binding: FragmentStaffDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var session: SessionManager
    private val api = ApiService.create()
    private var staffId: String = ""
    private var staffData: StaffFullData? = null
    private var currentTab = 0

    data class TabDef(val label: String, val items: List<Pair<String, String>>)

    companion object {
        fun newInstance(staffId: String) = StaffDetailFragment().apply {
            arguments = Bundle().apply { putString("staffId", staffId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStaffDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        staffId = arguments?.getString("staffId") ?: return

        binding.btnBack.setOnClickListener { navigateUp() }

        loadDetail()
    }

    private fun loadDetail() {
        SkeletonUtils.startSkeletonPulse(binding.skeletonContainer)
        binding.headerCard.visibility = View.GONE
        binding.tabStripScroll.visibility = View.GONE
        binding.tabContentScroll.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaffDetail(session.bearerToken, staffId)
                if (resp.success && resp.staff != null) {
                    staffData = resp.staff
                    renderHeader(resp.staff)
                    setupTabs(resp.staff)
                    setupMessageButton(resp.staff)
                }
            } catch (_: Exception) { }
            
            SkeletonUtils.stopSkeletonPulse(binding.skeletonContainer)
            binding.headerCard.visibility = View.VISIBLE
            binding.tabStripScroll.visibility = View.VISIBLE
            binding.tabContentScroll.visibility = View.VISIBLE
        }
    }

    private fun renderHeader(s: StaffFullData) {
        val name = s.name ?: "—"
        binding.tvHeaderTitle.text = name
        binding.tvAvatar.text = name.split(" ").take(2).joinToString("") {
            if (it.isNotEmpty()) it.first().uppercase() else ""
        }
        binding.tvName.text = name
        binding.tvDesignation.text = listOfNotNull(s.designation, s.department).joinToString(" · ")

        val status = s.status ?: "active"
        binding.tvStatus.text = status.replaceFirstChar { it.uppercase() }
        if (status == "active") {
            binding.tvStatus.setBackgroundResource(R.drawable.bg_badge_success)
            binding.tvStatus.setTextColor(resolveColor(R.attr.colorSuccess))
        } else {
            binding.tvStatus.setBackgroundResource(R.drawable.bg_badge_error)
            binding.tvStatus.setTextColor(resolveColor(R.attr.colorError))
        }
    }

    private fun setupTabs(s: StaffFullData) {
        val tabs = buildTabs(s)
        if (tabs.isEmpty()) return

        binding.tabStrip.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        tabs.forEachIndexed { index, tab ->
            val chip = TextView(requireContext()).apply {
                text = tab.label
                textSize = 13f
                typeface = resources.getFont(R.font.inter_semibold)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { selectTab(index, tabs) }
            }
            binding.tabStrip.addView(chip)
        }

        selectTab(0, tabs)
    }

    private fun selectTab(index: Int, tabs: List<TabDef>) {
        currentTab = index
        val primary = resolveColor(R.attr.colorForegroundPrimary)
        val muted = resolveColor(R.attr.colorForegroundMuted)

        for (i in 0 until binding.tabStrip.childCount) {
            val chip = binding.tabStrip.getChildAt(i) as TextView
            if (i == index) {
                chip.setBackgroundResource(R.drawable.bg_chip_active)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_chip_inactive)
                chip.setTextColor(muted)
            }
        }

        renderTabContent(tabs[index].items)
    }

    private fun renderTabContent(items: List<Pair<String, String>>) {
        val container = binding.tabContent
        container.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        items.forEach { (label, value) ->
            if (value.isBlank()) return@forEach

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }

            val labelTv = TextView(requireContext()).apply {
                text = label
                setTextColor(resolveColor(R.attr.colorForegroundMuted))
                textSize = 12f
                typeface = resources.getFont(R.font.inter_regular)
            }

            val valueTv = TextView(requireContext()).apply {
                text = value
                setTextColor(resolveColor(R.attr.colorForegroundPrimary))
                textSize = 14f
                typeface = resources.getFont(R.font.inter_medium)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }

            row.addView(labelTv)
            row.addView(valueTv)

            // Divider
            val divider = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                )
                setBackgroundColor(resolveColor(R.attr.colorBorder))
            }

            container.addView(row)
            container.addView(divider)
        }
    }

    private fun buildTabs(s: StaffFullData): List<TabDef> {
        val tabs = mutableListOf<TabDef>()

        // Personal
        val personal = listOfNotNull(
            "Full Name" to (s.name ?: ""),
            "Phone" to (s.phone ?: ""),
            s.email?.let { "Email" to it },
            s.gender?.let { "Gender" to it.replaceFirstChar { c -> c.uppercase() } },
            s.dateOfBirth?.let { "Date of Birth" to it },
            s.bloodGroup?.let { "Blood Group" to it },
            s.maritalStatus?.let { "Marital Status" to it.replaceFirstChar { c -> c.uppercase() } },
            s.nationality?.let { "Nationality" to it },
            s.religion?.let { "Religion" to it },
            s.qualification?.let { "Qualification" to it },
            s.address?.let { "Address" to it },
            s.city?.let { "City" to it },
            s.state?.let { "State" to it },
            s.pincode?.let { "Pincode" to it }
        )
        if (personal.isNotEmpty()) tabs.add(TabDef("Personal", personal))

        // Family
        val family = listOfNotNull(
            s.fatherName?.let { "Father's Name" to it },
            s.motherName?.let { "Mother's Name" to it },
            s.emergencyContact?.name?.let { "Emergency Contact" to it },
            s.emergencyContact?.phone?.let { "Emergency Phone" to it },
            s.emergencyContact?.relation?.let { "Relation" to it }
        )
        if (family.isNotEmpty()) tabs.add(TabDef("Family", family))

        // Employment
        val employment = listOfNotNull(
            "Employee ID" to (s.employeeId ?: ""),
            "Designation" to (s.designation ?: ""),
            "Department" to (s.department ?: ""),
            "Role" to (s.role ?: ""),
            s.company?.let { "Company" to it },
            s.branch?.let { "Branch" to it },
            s.joiningDate?.let { "Joining Date" to it },
            s.reportingToName?.let { "Reporting To" to it },
            s.experienceYears?.let { "Experience" to "$it years" }
        )
        if (employment.isNotEmpty()) tabs.add(TabDef("Employment", employment))

        // Documents
        val docs = s.documents?.map { d ->
            (d.docType ?: "Document") to (d.name ?: "Uploaded ${d.uploadedOn ?: ""}")
        } ?: emptyList()
        if (docs.isNotEmpty()) tabs.add(TabDef("Documents", docs))

        // Bank
        val bank = listOfNotNull(
            s.bankName?.let { "Bank Name" to it },
            s.accountNumber?.let { "Account Number" to it },
            s.branchName?.let { "Branch" to it },
            s.ifscCode?.let { "IFSC Code" to it },
            s.aadhaarNumber?.let { "Aadhaar Number" to it },
            s.panNumber?.let { "PAN Number" to it }
        )
        if (bank.isNotEmpty()) tabs.add(TabDef("Bank", bank))

        return tabs
    }

    private fun setupMessageButton(s: StaffFullData) {
        binding.btnMessage.setOnClickListener {
            val targetId = s.id ?: return@setOnClickListener
            val name = s.name ?: "Chat"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val dmResp = api.startDm(session.bearerToken,
                        com.manjugroups.m_connect.network.StartDmRequest(targetId))
                    if (dmResp.success && dmResp.conversationId != null) {
                        parentFragmentManager.beginTransaction()
                            .replace(R.id.fragmentContainer,
                                ChatMessagesFragment.forConversation(dmResp.conversationId, name))
                            .addToBackStack(null)
                            .commitOnce()
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
        _binding = null
    }
}
