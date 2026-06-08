package com.manjugroups.m_connect.ui.library

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentMyTripsBinding
import com.manjugroups.m_connect.network.TodayVisit
import com.manjugroups.m_connect.ui.home.DriverEndTripBottomSheet
import com.manjugroups.m_connect.ui.home.DriverStartTripBottomSheet
import com.manjugroups.m_connect.ui.home.DriverTripCompletedBottomSheet
import com.manjugroups.m_connect.ui.home.HomeUiState
import com.manjugroups.m_connect.ui.home.HomeViewModel
import com.manjugroups.m_connect.ui.home.TripNavigationFragment
import com.manjugroups.m_connect.ui.marketing.CompletedVisitDetailFragment
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MyTripsFragment : Fragment() {

    private var _binding: FragmentMyTripsBinding? = null
    private val binding get() = _binding!!

    // Shared activity ViewModel to keep visits and active visit state synchronized with the Home dashboard.
    private val viewModel: HomeViewModel by activityViewModels()
    private lateinit var session: SessionManager

    private var selectedTab: TabType = TabType.ALL
    private var searchQuery: String = ""
    private var allVisits: List<TodayVisit> = emptyList()

    private lateinit var tripsAdapter: TripsAdapter

    private enum class TabType { ALL, UPCOMING, COMPLETED }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyTripsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        setupToolbar()
        setupSearch()
        setupTabs()
        setupRecyclerView()
        collectState()

        // Load or refresh data
        viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)

        // Listen for bottom sheet results to sync navigation
        setFragmentResultListener(DriverStartTripBottomSheet.RESULT_KEY) { _, bundle ->
            val success = bundle.getBoolean("success")
            if (success) {
                val visitId = bundle.getString("visitId").orEmpty()
                viewModel.loadHomeData(session.bearerToken, requireContext().applicationContext)
                val state = viewModel.uiState.value
                if (state is HomeUiState.Loaded) {
                    state.todayVisits.firstOrNull { it.id == visitId }?.let { visit ->
                        val startedVisit = visit.copy(status = "in-progress")
                        openTripNavigationForVisit(startedVisit)
                    }
                }
            }
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.btnCalendar.setOnClickListener {
            Toast.makeText(requireContext(), "Calendar filter coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty().trim().lowercase(Locale.getDefault())
                filterAndDisplayTrips()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupTabs() {
        binding.segmentedTabs.setTabs(
            listOf("All", "Upcoming", "Completed"),
            selectedTab.ordinal
        ) { position ->
            selectedTab = TabType.values()[position]
            filterAndDisplayTrips()
        }
    }

    private fun setupRecyclerView() {
        tripsAdapter = TripsAdapter(
            onItemClicked = { visit -> handleTripClick(visit) },
            onActionClicked = { visit -> handleTripActionClick(visit) },
            session = session
        )
        binding.rvTrips.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTrips.adapter = tripsAdapter
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state is HomeUiState.Loaded) {
                        allVisits = state.todayVisits
                        filterAndDisplayTrips()
                    }
                }
            }
        }
    }

    private fun filterAndDisplayTrips() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

        val filtered = allVisits.filter { visit ->
            val status = visit.status.lowercase(Locale.getDefault())
            val isCompleted = status in setOf("completed", "complete", "done", "closed")
            val isUpcoming = visit.scheduledDate > todayStr && !isCompleted

            // Tab Filter
            val matchesTab = when (selectedTab) {
                TabType.ALL -> true
                TabType.UPCOMING -> isUpcoming
                TabType.COMPLETED -> isCompleted
            }

            // Search Query Filter
            val matchesSearch = if (searchQuery.isEmpty()) {
                true
            } else {
                val placeName = visit.placeName.orEmpty().lowercase(Locale.getDefault())
                val leadName = visit.leadName.orEmpty().lowercase(Locale.getDefault())
                placeName.contains(searchQuery) || leadName.contains(searchQuery)
            }

            matchesTab && matchesSearch
        }

        tripsAdapter.submitList(filtered)
        binding.emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTrips.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun handleTripClick(visit: TodayVisit) {
        val status = visit.status.lowercase(Locale.getDefault())
        val isCompleted = status in setOf("completed", "complete", "done", "closed")
        val isInProgress = status in setOf(
            "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
        )

        if (session.isDriverMode) {
            if (isCompleted) {
                DriverTripCompletedBottomSheet.newInstance(visit.id)
                    .show(parentFragmentManager, "driver_trip_completed")
            } else if (!isInProgress) {
                // Future/Ready trip start bottom sheet
                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                if (visit.scheduledDate > todayStr) {
                    Toast.makeText(requireContext(), "This trip is scheduled for a future date.", Toast.LENGTH_SHORT).show()
                } else {
                    DriverStartTripBottomSheet.newInstance(visit.id)
                        .show(parentFragmentManager, "driver_start_trip")
                }
            } else {
                openTripNavigationForVisit(visit)
            }
        } else {
            if (isCompleted) {
                openCompletedVisitDetail(visit)
            } else {
                openTripNavigationForVisit(visit)
            }
        }
    }

    private fun handleTripActionClick(visit: TodayVisit) {
        // Triggers the exact same action logic as card click
        handleTripClick(visit)
    }

    private fun openTripNavigationForVisit(visit: TodayVisit) {
        val fragment = TripNavigationFragment.forVisit(
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
            visitCategory = visit.visitCategory,
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    private fun openCompletedVisitDetail(visit: TodayVisit) {
        val fragment = CompletedVisitDetailFragment.forVisit(visit)
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onResume() {
        super.onResume()
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(
                Color.WHITE,
                darkStatusIcons = true,
                fullBleed = false
            )
        }
    }

    override fun onPause() {
        (activity as? com.manjugroups.m_connect.MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ── Adapter and ViewHolder implementation ──

    private class TripsAdapter(
        private val onItemClicked: (TodayVisit) -> Unit,
        private val onActionClicked: (TodayVisit) -> Unit,
        private val session: SessionManager
    ) : RecyclerView.Adapter<TripsAdapter.TripViewHolder>() {

        private var items: List<TodayVisit> = emptyList()

        fun submitList(newItems: List<TodayVisit>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_my_trip_card, parent, false)
            return TripViewHolder(view, onItemClicked, onActionClicked, session)
        }

        override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class TripViewHolder(
            itemView: View,
            private val onItemClicked: (TodayVisit) -> Unit,
            private val onActionClicked: (TodayVisit) -> Unit,
            private val session: SessionManager
        ) : RecyclerView.ViewHolder(itemView) {

            private val tvAvatar: TextView = itemView.findViewById(R.id.tvAvatar)
            private val tvStaffName: TextView = itemView.findViewById(R.id.tvStaffName)
            private val tvStaffId: TextView = itemView.findViewById(R.id.tvStaffId)
            private val statusPill: LinearLayout = itemView.findViewById(R.id.statusPill)
            private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
            private val statusDot: View = itemView.findViewById(R.id.statusDot)

            private val tvDetail1Value: TextView = itemView.findViewById(R.id.tvDetail1Value)
            private val tvDetail2Value: TextView = itemView.findViewById(R.id.tvDetail2Value)
            
            private val tvDetail3Label: TextView = itemView.findViewById(R.id.tvDetail3Label)
            private val tvDetail3Value: TextView = itemView.findViewById(R.id.tvDetail3Value)
            private val ivDetail3Icon: ImageView = itemView.findViewById(R.id.ivDetail3Icon)

            private val tvDetail4Label: TextView = itemView.findViewById(R.id.tvDetail4Label)
            private val tvDetail4Value: TextView = itemView.findViewById(R.id.tvDetail4Value)
            private val ivDetail4Icon: ImageView = itemView.findViewById(R.id.ivDetail4Icon)

            private val btnTripAction: LinearLayout = itemView.findViewById(R.id.btnTripAction)
            private val ivTripActionIcon: ImageView = itemView.findViewById(R.id.ivTripActionIcon)
            private val tvTripActionLabel: TextView = itemView.findViewById(R.id.tvTripActionLabel)

            fun bind(visit: TodayVisit) {
                val context = itemView.context
                val clientName = visit.placeName ?: visit.leadName ?: "Scheduled Visit"
                
                // Name and Staff ID Role
                tvStaffName.text = session.userName ?: "Donald Trump"
                val rolePrefix = if (session.isDriverMode) "Driver ID:" else "Field Executive ID:"
                tvStaffId.text = "$rolePrefix${session.employeeId ?: "38212"}"
                tvAvatar.text = session.userName?.firstOrNull()?.toString()?.uppercase() ?: "M"

                val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val status = visit.status.lowercase(Locale.getDefault())
                val isCompleted = status in setOf("completed", "complete", "done", "closed")
                val isInProgress = status in setOf(
                    "in-progress", "in_progress", "ongoing", "started", "active", "arrived"
                )
                val isUpcoming = visit.scheduledDate > todayStr && !isCompleted

                // Grid Details Bind
                tvDetail1Value.text = clientName
                tvDetail2Value.text = if (visit.placeLat != null && visit.placeLng != null) "12.4Km" else "Not mapped"

                // Upcoming layout has Site/Client, Distance, Time, ETA.
                // Ready/Completed layout has Site/Client, Distance, Phone Number, Time.
                if (isUpcoming) {
                    tvDetail3Label.text = "Time"
                    tvDetail3Value.text = visit.scheduledStartTime ?: "09:30 AM"
                    ivDetail3Icon.setImageResource(R.drawable.ic_clock)

                    tvDetail4Label.text = "ETA"
                    tvDetail4Value.text = "25 Mins"
                    ivDetail4Icon.setImageResource(R.drawable.ic_stat_eta)
                } else {
                    tvDetail3Label.text = "Phone Number"
                    tvDetail3Value.text = visit.leadPhone ?: "9874827382"
                    ivDetail3Icon.setImageResource(R.drawable.ic_phone_outline)

                    tvDetail4Label.text = "Time"
                    tvDetail4Value.text = visit.scheduledStartTime ?: "09:30 AM"
                    ivDetail4Icon.setImageResource(R.drawable.ic_clock)
                }

                // Status & Action button states
                when {
                    isCompleted -> {
                        tvStatus.text = "Completed"
                        statusPill.setBackgroundResource(R.drawable.bg_home_trip_status_done)
                        tvStatus.setTextColor(Color.parseColor("#475467"))
                        statusDot.setBackgroundResource(R.drawable.bg_home_trip_status_dot)

                        tvTripActionLabel.text = "Completed"
                        btnTripAction.setBackgroundResource(R.drawable.bg_home_trip_action_disabled)
                        ivTripActionIcon.visibility = View.GONE
                        btnTripAction.isEnabled = true // Clickable for reading details
                    }
                    isInProgress -> {
                        val label = if (status == "arrived") "Reaching" else "Enroute"
                        tvStatus.text = label
                        statusPill.setBackgroundResource(R.drawable.bg_home_trip_status_progress)
                        tvStatus.setTextColor(Color.parseColor("#B54708"))
                        statusDot.setBackgroundResource(R.drawable.bg_home_trip_status_dot)

                        tvTripActionLabel.text = if (status == "arrived") "Complete Trip" else "Enroute"
                        btnTripAction.setBackgroundResource(R.drawable.bg_home_trip_action_ready)
                        ivTripActionIcon.visibility = View.GONE
                    }
                    isUpcoming -> {
                        tvStatus.text = "Upcoming"
                        statusPill.setBackgroundResource(R.drawable.bg_home_trip_status_upcoming)
                        tvStatus.setTextColor(Color.parseColor("#0B63C6"))
                        statusDot.setBackgroundResource(R.drawable.bg_home_trip_status_dot)

                        tvTripActionLabel.text = "Upcoming Trip"
                        btnTripAction.setBackgroundResource(R.drawable.bg_my_trips_upcoming_btn)
                        ivTripActionIcon.setImageResource(R.drawable.ic_calendar_days)
                        ivTripActionIcon.visibility = View.VISIBLE
                    }
                    else -> { // Ready to start
                        tvStatus.text = "Ready"
                        statusPill.setBackgroundResource(R.drawable.bg_home_trip_status_ready)
                        tvStatus.setTextColor(Color.parseColor("#169B2F"))
                        statusDot.setBackgroundResource(R.drawable.bg_home_trip_status_dot)

                        tvTripActionLabel.text = "Start Trip"
                        btnTripAction.setBackgroundResource(R.drawable.bg_home_trip_action_ready)
                        ivTripActionIcon.setImageResource(R.drawable.ic_home_trip_play)
                        ivTripActionIcon.visibility = View.VISIBLE
                    }
                }

                itemView.setOnClickListener { onItemClicked(visit) }
                btnTripAction.setOnClickListener { onActionClicked(visit) }
            }
        }
    }
}
