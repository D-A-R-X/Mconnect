package com.manjugroups.m_connect.ui.telecaller

import android.content.Context
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
import com.manjugroups.m_connect.network.DialDooctiRequest
import com.manjugroups.m_connect.network.TelecallerLeadData
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
    private var calling: Boolean = false

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
            parentFragmentManager.popBackStack()
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

    private fun loadLeads(root: View) {
        val loading = root.findViewById<View>(R.id.leadsLoading)
        val empty = root.findViewById<TextView>(R.id.tvLeadsEmpty)
        val list = root.findViewById<LinearLayout>(R.id.leadsList)
        val countText = root.findViewById<TextView>(R.id.tvLeadsCount)

        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        list.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMyLeads(session.bearerToken, limit = 200)
                loading.visibility = View.GONE
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
                loading.visibility = View.GONE
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

        row.findViewById<View>(R.id.btnLeadCall).setOnClickListener {
            placeCall(lead.mobileNumber)
        }
        row.setOnClickListener { placeCall(lead.mobileNumber) }
    }

    private fun placeCall(rawPhone: String?) {
        val phone = rawPhone?.trim()?.filter { it.isDigit() }
        if (phone.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No phone on this lead", Toast.LENGTH_SHORT).show()
            return
        }
        if (phone.length < 10) {
            Toast.makeText(
                requireContext(),
                "Lead phone is too short to dial",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (calling) return
        calling = true
        val station = requireContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATION, DEFAULT_STATION) ?: DEFAULT_STATION
        Toast.makeText(requireContext(), "Placing call…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.dialDoocti(
                    DOOCTI_URL,
                    DialDooctiRequest(phone_number = phone, station = station),
                )
                val ok = resp.ok == true
                val msg = if (ok) {
                    "Call placed — your phone will ring shortly"
                } else {
                    "Call failed: ${resp.error ?: resp.stage ?: "unknown"}"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                calling = false
            }
        }
    }

    companion object {
        private const val ARG_MODE = "arg_mode"
        private const val PREFS = "mconnect_prefs"
        private const val KEY_STATION = "dialer.station"
        private const val DEFAULT_STATION = "6369487527"
        private const val DOOCTI_URL = "https://mms.aivida.in/api/doocti-call"

        fun newInstance(mode: Mode = Mode.ALL): MyLeadsFragment = MyLeadsFragment().apply {
            arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
        }
    }
}
