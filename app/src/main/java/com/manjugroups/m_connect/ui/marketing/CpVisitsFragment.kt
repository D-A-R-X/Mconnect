package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CreateCpVisitRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.home.TripNavigationFragment
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class CpVisitsFragment : Fragment() {
    private val geoApi = GeoTrackApi.create()
    private lateinit var session: SessionManager
    private var rootView: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_cp_visits, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        session = SessionManager(requireContext())
        rootView = view
        view.findViewById<View>(R.id.btnCpVisitsBack).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<View>(R.id.btnCreateCpVisit).setOnClickListener { showCreateDialog() }
        loadVisits()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    private fun loadVisits() {
        val root = rootView ?: return
        val skeletonContainer = root.findViewById<View>(R.id.skeletonContainer)
        val loading = root.findViewById<View>(R.id.cpVisitsLoading)
        val empty = root.findViewById<TextView>(R.id.tvCpVisitsEmpty)
        val list = root.findViewById<LinearLayout>(R.id.cpVisitsList)
        SkeletonUtils.startSkeletonPulse(skeletonContainer)
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        list.removeAllViews()

        val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val from = ymd.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 60)
        val to = ymd.format(cal.time)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.getMySiteVisits(session.bearerToken, from, to)
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                loading.visibility = View.GONE
                if (!resp.success) {
                    empty.text = resp.error ?: "Failed to load CP visits"
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                val visits = resp.visits
                    .filter { it.tripType == "client_place" || it.clientPlaceVisitId != null }
                    .sortedByDescending { it.scheduledDate }
                if (visits.isEmpty()) {
                    empty.text = "No CP visits yet. Tap Create to add one."
                    empty.visibility = View.VISIBLE
                    return@launch
                }
                visits.forEach { list.addView(createRow(it, list)) }
            } catch (e: Exception) {
                SkeletonUtils.stopSkeletonPulse(skeletonContainer)
                loading.visibility = View.GONE
                empty.text = "Network error: ${e.message ?: "unknown"}"
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun createRow(visit: TodayVisit, parent: ViewGroup): View {
        val itemView = layoutInflater.inflate(R.layout.item_home_today_visit, parent, false)
        itemView.findViewById<TextView>(R.id.tvVisitItemStaffName).text =
            visit.placeName ?: visit.leadName ?: "CP Visit"
        itemView.findViewById<TextView>(R.id.tvVisitItemStaffRole).visibility = View.GONE
        itemView.findViewById<TextView>(R.id.tvVisitItemAvatar).text =
            (visit.placeName ?: visit.leadName ?: "C").first().uppercase()
        itemView.findViewById<TextView>(R.id.tvVisitItemTitle).text =
            visit.placeName ?: visit.leadName ?: "CP Visit"
        itemView.findViewById<TextView>(R.id.tvVisitItemTime).text = visit.scheduledDate
        itemView.findViewById<TextView>(R.id.tvVisitItemDistance).text =
            if (visit.placeLat != null && visit.placeLng != null) "Open route" else "Not mapped"
        itemView.findViewById<TextView>(R.id.tvVisitItemEta).text = "After start"
        itemView.findViewById<TextView>(R.id.tvVisitItemLead).visibility = View.GONE
        itemView.findViewById<TextView>(R.id.tvVisitItemStatus).text =
            when (visit.status.lowercase(Locale.US)) {
                "completed" -> "Complete"
                "in-progress", "arrived" -> "Enroute"
                else -> "Start"
            }
        itemView.findViewById<TextView>(R.id.tvVisitItemActionLabel).text =
            if (visit.status == "completed") "Complete" else "Start Trip"
        itemView.setOnClickListener { openVisit(visit) }
        itemView.findViewById<LinearLayout>(R.id.btnVisitItemAction).setOnClickListener { openVisit(visit) }
        return itemView
    }

    private fun openVisit(visit: TodayVisit) {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.fragmentContainer,
                TripNavigationFragment.forVisit(
                    visitId = visit.id,
                    placeName = visit.placeName,
                    placeAddress = visit.placeAddress,
                    destLat = visit.placeLat,
                    destLng = visit.placeLng,
                    status = visit.status,
                    tripType = visit.tripType,
                    clientPlaceVisitId = visit.clientPlaceVisitId,
                    cpClientMet = visit.cpVisit?.clientMet,
                    cpOutcome = visit.cpVisit?.outcome,
                )
            )
            .addToBackStack(null)
            .commit()
    }

    private fun showCreateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_create_cp_visit, null)
        val date = view.findViewById<EditText>(R.id.etCpVisitDate)
        date.setText(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time))

        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(view)
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        view.findViewById<TextView>(R.id.btnCancelCpCreate).setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.btnSubmitCpCreate).setOnClickListener {
            createCpVisitFromDialog(view, dialog)
        }
        dialog.show()
    }

    private fun createCpVisitFromDialog(view: View, dialog: Dialog) {
        val phone = view.findViewById<EditText>(R.id.etCpClientPhone).text.toString().filter(Char::isDigit).takeLast(10)
        val name = view.findViewById<EditText>(R.id.etCpClientName).text.toString().trim()
        val date = view.findViewById<EditText>(R.id.etCpVisitDate).text.toString().trim()
        val time = view.findViewById<EditText>(R.id.etCpVisitTime).text.toString().trim()
        val address = view.findViewById<EditText>(R.id.etCpVisitAddress).text.toString().trim()
        val maps = view.findViewById<EditText>(R.id.etCpMapsLink).text.toString().trim()
        val notes = view.findViewById<EditText>(R.id.etCpNotes).text.toString().trim()
        val staffId = session.staffId

        if (phone.length != 10) return Toast.makeText(requireContext(), "Enter 10 digit phone", Toast.LENGTH_SHORT).show()
        if (staffId.isNullOrBlank()) return Toast.makeText(requireContext(), "Staff session missing", Toast.LENGTH_SHORT).show()
        if (date.isBlank()) return Toast.makeText(requireContext(), "Date is required", Toast.LENGTH_SHORT).show()
        if (address.isBlank()) return Toast.makeText(requireContext(), "Address is required", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = geoApi.createCpVisit(
                    session.bearerToken,
                    CreateCpVisitRequest(
                        clientName = name.takeIf { it.isNotBlank() },
                        mobileNumber = phone,
                        assignedStaffId = staffId,
                        scheduledDate = date,
                        scheduledTime = time.takeIf { it.isNotBlank() },
                        visitAddress = address,
                        googleMapsLink = maps.takeIf { it.isNotBlank() },
                        notes = notes.takeIf { it.isNotBlank() },
                    )
                )
                if (!resp.success) {
                    Toast.makeText(requireContext(), resp.error ?: "Failed to create CP visit", Toast.LENGTH_LONG).show()
                    return@launch
                }
                dialog.dismiss()
                Toast.makeText(requireContext(), "CP visit created", Toast.LENGTH_SHORT).show()
                loadVisits()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_LONG).show()
            }
        }
    }
}
