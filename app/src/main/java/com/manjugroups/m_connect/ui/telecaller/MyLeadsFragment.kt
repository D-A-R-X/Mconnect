package com.manjugroups.m_connect.ui.telecaller

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.TelecallerLeadData
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Locale as JLocale

/**
 * Lists leads assigned to the authenticated staff. Two modes:
 * - MODE_ALL: every lead assigned to me (default — "My Leads" entry point)
 * - MODE_DIALER: just leads still requiring follow-up (pending / contacted /
 *   no status). Sorted with most-recent first; tap "Call" to dial via the
 *   system dialer intent.
 */
class MyLeadsFragment : Fragment() {

    enum class Mode { ALL, DIALER }

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private var mode: Mode = Mode.ALL

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_leads_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())
        mode = arguments?.getString(ARG_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?: Mode.ALL

        view.findViewById<View>(R.id.btnLeadsBack).setOnClickListener {
            navigateUp()
        }
        view.findViewById<TextView>(R.id.tvLeadsTitle).text = when (mode) {
            Mode.ALL -> "My Leads"
            Mode.DIALER -> "Dialer Queue"
        }
        loadLeads(view)
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTopBarAppearance(android.graphics.Color.WHITE, true)
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTopBarAppearance(android.graphics.Color.parseColor("#FEFEFE"), true)
        (activity as? com.manjugroups.m_connect.MainActivity)
            ?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        SkeletonUtils.stopAll()
        super.onDestroyView()
    }

    private fun loadLeads(root: View) {
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        val empty = root.findViewById<TextView>(R.id.tvLeadsEmpty)
        val list = root.findViewById<LinearLayout>(R.id.leadsList)
        val countText = root.findViewById<TextView>(R.id.tvLeadsCount)

        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        empty.visibility = View.GONE
        list.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMyLeads(session.bearerToken, limit = 200)
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                if (!resp.success) {
                    empty.text = resp.error ?: "Failed to load leads"
                    empty.visibility = View.VISIBLE
                    countText.text = ""
                    return@launch
                }

                val all = resp.leads
                val visible = when (mode) {
                    Mode.ALL -> all
                    Mode.DIALER -> all.filter { lead ->
                        val status = lead.followUpStatus?.lowercase(JLocale.getDefault())
                        status == null || status == "pending" || status == "contacted"
                    }
                }

                countText.text = "${visible.size} of ${all.size}"
                if (visible.isEmpty()) {
                    empty.text = if (mode == Mode.DIALER)
                        "No leads waiting in your dialer queue."
                    else
                        "You don't have any assigned leads yet."
                    empty.visibility = View.VISIBLE
                    return@launch
                }

                val recvFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
                visible.forEach { lead ->
                    val row = layoutInflater.inflate(R.layout.item_lead, list, false)
                    bindRow(row, lead, recvFmt)
                    list.addView(row)
                }
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                empty.text = "Network error: ${e.message ?: "unknown"}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun bindRow(
        row: View,
        lead: TelecallerLeadData,
        recvFmt: SimpleDateFormat,
    ) {
        val name = lead.contactName?.takeIf { it.isNotBlank() } ?: "Unnamed lead"
        row.findViewById<TextView>(R.id.tvLeadAvatar).text =
            name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        row.findViewById<TextView>(R.id.tvLeadName).text = name
        row.findViewById<TextView>(R.id.tvLeadPhone).text =
            lead.mobileNumber?.takeIf { it.isNotBlank() } ?: "No phone"

        val received = lead.leadReceivedAt ?: lead.assignedDateTime ?: lead.createdAt
        val receivedText = received?.let { recvFmt.format(Date(it.toLong())) }

        row.findViewById<TextView>(R.id.tvLeadMeta).text = listOfNotNull(
            lead.source?.replaceFirstChar { it.uppercase() },
            lead.campaignName ?: lead.primaryCampaign,
            lead.locationPreferred ?: lead.clientCity,
            receivedText?.let { "Received $it" }
        ).joinToString(" · ")

        val status = lead.followUpStatus?.lowercase(JLocale.getDefault()) ?: "new"
        val statusBadge = row.findViewById<TextView>(R.id.tvLeadStatus)
        when (status) {
            "converted" -> {
                statusBadge.text = "Converted"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_success)
                statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_success))
            }
            "closed" -> {
                statusBadge.text = "Closed"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_error)
                statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_error))
            }
            "contacted" -> {
                statusBadge.text = "Contacted"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_warning))
            }
            "pending" -> {
                statusBadge.text = "Pending"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_warning))
            }
            else -> {
                statusBadge.text = "New"
                statusBadge.setBackgroundResource(R.drawable.bg_badge_warning)
                statusBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.lt_accent_primary))
            }
        }

        val callPill = row.findViewById<View>(R.id.btnLeadCall)
        val phone = lead.mobileNumber ?: lead.alternateNumber
        callPill.isClickable = true
        callPill.isFocusable = true
        callPill.setOnClickListener { openModernDialer(phone) }
        row.isClickable = false
        row.setOnClickListener(null)
    }

    private fun openModernDialer(rawPhone: String?) {
        val phone = rawPhone?.filter { it.isDigit() }.orEmpty()
        if (phone.length < 10) {
            Toast.makeText(requireContext(), "No valid phone number on this lead", Toast.LENGTH_SHORT).show()
            return
        }
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, DialerFragment.newInstance(phone))
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val ARG_MODE = "arg_mode"

        fun newInstance(mode: Mode = Mode.ALL): MyLeadsFragment = MyLeadsFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
        }
    }
}
