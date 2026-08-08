package com.manjugroups.m_connect.ui.library

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentAdminFleetTripsBinding
import com.manjugroups.m_connect.databinding.ItemAdminFleetTripBinding
import com.manjugroups.m_connect.network.AllocateTripRequest
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CompleteOfflineTripRequest
import com.manjugroups.m_connect.network.FinalizeTravelDeskBillingRequest
import com.manjugroups.m_connect.network.FinalizeTravelDeskCancellationRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.TravelDeskApi
import com.manjugroups.m_connect.network.TravelDeskAppliedCharge
import com.manjugroups.m_connect.network.TravelDeskStatusUpdateRequest
import com.manjugroups.m_connect.network.TravelDeskTrip
import com.manjugroups.m_connect.network.TravelDeskVehicle
import com.manjugroups.m_connect.network.StorageUploader
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.ui.common.InfiniteScrollPager
import com.manjugroups.m_connect.ui.home.CompleteCpVisitBottomSheet

class AdminFleetTripsFragment : Fragment() {

    private var _binding: FragmentAdminFleetTripsBinding? = null
    private val binding get() = _binding!!

    private var activeFilter = "Pending"

    /**
     * Full rows for the active tab, plus a scroll window over them.
     *
     * rvAdminTrips is wrap_content with nestedScrollingEnabled=false inside a
     * NestedScrollView, which switches RecyclerView recycling OFF — every row
     * inflates up front. With ~68 pending trips that froze the main thread on
     * load and again on every tab switch. Windowing keeps the inflated count
     * bounded regardless.
     */
    private var filteredTrips: List<AdminTrip> = emptyList()

    /**
     * Why the last load produced nothing, or null when it simply returned no
     * rows. Kept so the empty state can state the reason — a toast fades and
     * the user is left looking at blank space with no explanation.
     */
    private var lastLoadError: String? = null
    private val tripsPager = InfiniteScrollPager(onLoadMore = { renderTripWindow() })
    private lateinit var tripsAdapter: AdminTripsAdapter
    private lateinit var session: SessionManager
    private val api = TravelDeskApi.create()

    // Live data (replaces the old hardcoded `companion object { tripsList }`).
    // The trip lists are independent feeds (pending vs assigned); completed
    // is derived from assigned by inspecting travel-desk end timestamps,
    // since the backend exposes only listPending / listAssigned today.
    private var pendingTrips: List<TravelDeskTrip> = emptyList()
    private var assignedActive: List<TravelDeskTrip> = emptyList()
    private var assignedInProgress: List<TravelDeskTrip> = emptyList()
    private var assignedBilling: List<TravelDeskTrip> = emptyList()
    private var assignedCompleted: List<TravelDeskTrip> = emptyList()
    private var vehicles: List<TravelDeskVehicle> = emptyList()
    private var driverRoster: List<com.manjugroups.m_connect.network.TravelDeskDriver> = emptyList()
    private var activeVehicleCount: Int = 0
    private var loadJob: Job? = null
    private var allocateJob: Job? = null
    private var isTripsScrollGesture = false
    private var hasInteractedWithTripsScroll = false

    /**
     * True when the signed-in principal is in-house fleet staff rather than an
     * external agency. Chooses which backend the portal talks to: agencies use
     * the travel-desk routes, staff use the MMS dispatch routes (their token
     * would 401 on the agency ones, which the watchdog reads as a dead session).
     */
    private val useMmsFleet: Boolean
        get() = !session.isExternalFleetAgencyOperator

    /**
     * Human-readable load failure.
     *
     * A 404 here has one meaning: the MMS dispatch routes aren't on the server
     * this build points at. Surfacing a bare "HTTP 404" makes that look like a
     * bug in the screen rather than a backend that hasn't been deployed yet.
     */
    private fun loadErrorMessage(e: Exception): String {
        // Connectivity first: a DNS/socket failure surfaces as
        // "Unable to resolve host ...", which reads like a server fault when
        // the phone simply has no working internet.
        if (e is java.net.SocketTimeoutException) {
            return "Assigned trips took too long to load. Pull to retry."
        }
        if (e is java.net.UnknownHostException ||
            e is java.net.ConnectException
        ) {
            return "No internet connection. Check the network and pull to refresh."
        }
        val code = (e as? retrofit2.HttpException)?.code()
        return when (code) {
            404 -> "Fleet dispatch isn't available on this server yet — it needs the latest backend deploy."
            403 -> "You don't have fleet permissions (marketing.fleet.view)."
            else -> e.message ?: "network error"
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminFleetTripsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // When the SV outcome form (Booking/Postpone/Not Interested) completes,
        // refresh the trips list so the completed-offline trip disappears.
        parentFragmentManager.setFragmentResultListener(
            CompleteCpVisitBottomSheet.RESULT_KEY,
            viewLifecycleOwner,
        ) { _, _ -> refresh() }

        binding.homeHeader.setup(this)
        binding.homeHeader.setFleetBannerMode()
        // Avatar tap routes the agency to their Settings tab (which IS their
        // profile/account overview inside the Admin Fleet portal) instead of
        // the staff ProfileFragment, which requires a real staff record.
        binding.homeHeader.setOnProfileClickListener {
            // Walk up the parent chain rather than assuming a direct parent, so
            // the profile tap always finds the container and opens Settings.
            var f: Fragment? = parentFragment
            while (f != null && f !is AdminFleetContainerFragment) f = f.parentFragment
            (f as? AdminFleetContainerFragment)?.openSettings()
        }
        binding.homeHeader.post { _binding?.homeHeader?.playEntryAnimation() }

        // The banner is pinned behind the scroller, so the content needs a
        // spacer exactly its height to start below it. Re-measured on every
        // layout pass: the header grows once insets and the staff name land,
        // and a stale spacer would leave the panel overlapping or floating.
        binding.homeHeader.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val b = _binding ?: return@addOnLayoutChangeListener
            val heroHeight = b.homeHeader.height
            if (heroHeight <= 0) return@addOnLayoutChangeListener
            // Stop line: the panel may rise until it meets the bottom of the
            // profile row, then clips. Everything below that — the fleet
            // artwork and stat cards — scrolls away, while the avatar / name /
            // bell stay put. Implemented as scroller top padding (with the
            // default clipToPadding=true) rather than a maxScrollY clamp: a
            // clamp would cap the whole page and strand most of the trips.
            val stop = b.homeHeader.profileRowBottom().coerceAtLeast(0)
            if (b.scrollAdminTrips.paddingTop != stop) {
                b.scrollAdminTrips.setPadding(0, stop, 0, 0)
            }
            // Spacer fills the gap between the stop line and the hero's base,
            // so at rest the panel still starts below the whole banner.
            val spacer = (heroHeight - stop).coerceAtLeast(0)
            if (b.heroSpacer.layoutParams.height != spacer) {
                b.heroSpacer.layoutParams = b.heroSpacer.layoutParams.apply { height = spacer }
                b.heroSpacer.requestLayout()
            }
            updateContentPanelHeight(b, spacer)

            // The spacer changes after the header receives its final insets.
            // NestedScrollView may preserve that layout delta as scrollY,
            // clipping the title/tabs and hiding the fleet nav on first load.
            if (!hasInteractedWithTripsScroll) {
                b.scrollAdminTrips.post {
                    if (_binding != null && !hasInteractedWithTripsScroll) {
                        b.scrollAdminTrips.scrollTo(0, 0)
                    }
                }
            }
        }
        binding.scrollAdminTrips.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val b = _binding ?: return@addOnLayoutChangeListener
            updateContentPanelHeight(b, b.heroSpacer.layoutParams.height)
        }
        setupRecyclerView()
        setupFilters()
        binding.btnTabBilling.visibility =
            if (!useMmsFleet && session.canBillExternalFleet) View.VISIBLE else View.GONE

        binding.scrollAdminTrips.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    isTripsScrollGesture = true
                    hasInteractedWithTripsScroll = true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> isTripsScrollGesture = false
            }
            false
        }

        // One listener doing both jobs. setOnScrollChangeListener holds a
        // single listener, so binding the pager separately here would silently
        // cancel this bottom-nav behaviour (or be cancelled by it, depending on
        // registration order) — see InfiniteScrollPager.onHostScroll.
        binding.scrollAdminTrips.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { view, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (isTripsScrollGesture && dy > 10) {
                (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(false)
            } else if (scrollY <= 10) {
                (parentFragment as? AdminFleetContainerFragment)?.setBottomNavScrollState(true)
            }
            tripsPager.onHostScroll(
                view as androidx.core.widget.NestedScrollView,
                scrollY,
                filteredTrips.size,
            )
        })

        // Render whatever we already have, then trigger a refresh.
        applyFilter("Pending")
        refresh()
    }

    private fun updateContentPanelHeight(
        b: FragmentAdminFleetTripsBinding,
        spacerHeight: Int,
    ) {
        val overlap = (28 * resources.displayMetrics.density).toInt()
        val viewportHeight =
            b.scrollAdminTrips.height - b.scrollAdminTrips.paddingTop - b.scrollAdminTrips.paddingBottom
        val minimumPanelHeight = (viewportHeight - spacerHeight + overlap).coerceAtLeast(0)
        if (b.tripsContentPanel.minimumHeight != minimumPanelHeight) {
            b.tripsContentPanel.minimumHeight = minimumPanelHeight
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up changes made on travel-desk web (e.g., allocations) when the
        // user comes back to this screen.
        refresh()
    }

    private fun refresh() {
        if (_binding == null) return
        val token = session.bearerToken
        if (token.isBlank()) return
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pendingResp = if (useMmsFleet) api.listMmsPending(token) else api.listPending(token)
                val assignedResp = if (useMmsFleet) api.listMmsAssigned(token) else api.listAssigned(token)
                val vehiclesResp = if (useMmsFleet) api.listMmsVehicles(token) else api.listVehicles(token)
                // Roster for the allocate driver dropdown. Best-effort — a
                // failure here shouldn't block the trip lists.
                driverRoster = runCatching {
                    (if (useMmsFleet) api.listMmsDrivers(token) else api.listDrivers(token)).rows
                }.getOrDefault(emptyList())

                lastLoadError = null
                // Guard against a payload whose shape doesn't match (an id-less
                // row can't be opened or allocated anyway) so one odd record
                // can't take the whole screen down.
                pendingTrips = pendingResp.rows.filter { !it.id.isNullOrBlank() }
                val assignedRows = assignedResp.rows.filter { !it.id.isNullOrBlank() }
                // Trips with an end timestamp are "Completed". A never-started
                // trip whose slot has passed is EXPIRED — it also belongs in
                // Completed (badged separately), NOT sitting in Assigned as if
                // it were still live. Everything else is in-flight ("Assigned").
                assignedBilling = assignedRows.filter(::needsAgencyBilling)
                assignedCompleted = assignedRows.filter(::isTripComplete)
                assignedInProgress = assignedRows.filter {
                    !isTripComplete(it) && !needsAgencyBilling(it) && progressState(it) != "assigned"
                }
                assignedActive = assignedRows.filter {
                    !isTripComplete(it) && !needsAgencyBilling(it) && progressState(it) == "assigned"
                }
                vehicles = vehiclesResp.rows
                activeVehicleCount = vehicles.count {
                    (it.status ?: "active").equals("active", ignoreCase = true)
                }

                if (_binding == null) return@launch
                bindBannerStats()
                applyFilter(activeFilter)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_binding == null) return@launch
                lastLoadError = loadErrorMessage(e)
                // Surface it on the screen too, not just as a toast.
                applyFilter(activeFilter)
                Toast.makeText(
                    requireContext(),
                    "Couldn't load trips: ${lastLoadError}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun hasRecordedOutcome(trip: TravelDeskTrip): Boolean =
        !trip.outcome.isNullOrBlank()

    private fun isTripComplete(trip: TravelDeskTrip): Boolean {
        if (trip.travelDeskBillingCompletedAt != null) return true
        if (!hasRecordedOutcome(trip)) return false
        if (trip.travelDeskTaskStatus.equals("completed", ignoreCase = true)) return true
        return trip.travelDeskTaskStatus == null &&
            tripEndedAt(trip) != null &&
            trip.travelDeskProofPending != true &&
            trip.travelAgency == null
    }

    private fun isCancelledTrip(trip: TravelDeskTrip): Boolean =
        trip.status.equals("cancelled", ignoreCase = true) ||
            trip.status.equals("no_show", ignoreCase = true)

    private fun needsAgencyBilling(trip: TravelDeskTrip): Boolean =
        !useMmsFleet &&
            trip.travelDeskBillingCompletedAt == null &&
            (trip.travelDeskEndedAt != null || isCancelledTrip(trip))

    private fun progressState(trip: TravelDeskTrip): String {
        if (hasRecordedOutcome(trip) && !isTripComplete(trip)) {
            return "pending"
        }
        if (
            trip.travelDeskArrivedAt != null ||
            trip.travelDeskStartedAt != null ||
            trip.travelDeskOnSiteAt != null ||
            trip.travelDeskPickedFromSiteAt != null ||
            trip.travelDeskEndedAt != null
        ) {
            return "ongoing"
        }
        return "assigned"
    }

    /** Surfaces the trip end timestamp regardless of which field the backend uses. */
    private fun tripEndedAt(trip: TravelDeskTrip): Long? {
        // Both fields can appear depending on the trip's phase in travel-desk.
        // Either non-null means "completed" for our purposes.
        // (status == "completed" is the canonical Convex field; some legacy
        // rows store it on travelDeskEndedAt only — keep both safe.)
        val statusCompleted = (trip.status ?: "").equals("completed", ignoreCase = true)
        // An agency trip's status stays "scheduled" the whole way, so the real
        // completion signal is travelDeskEndedAt — checking status alone left
        // finished trips stuck in the Assigned tab.
        return if (statusCompleted || trip.travelDeskEndedAt != null) {
            trip.travelDeskEndedAt ?: Long.MAX_VALUE
        } else {
            null
        }
    }

    private fun bindBannerStats() {
        if (_binding == null) return
        val header = binding.homeHeader.getHeaderBinding()
        // Today's Trips card = the count under the active "Pending" filter so
        // the agency sees how many allocations still need their attention.
        header.tvFleetTodaysTripsCount?.text = pendingTrips.size.toString()
        // Active vehicles = vehicles on this agency's roster currently active.
        header.tvFleetActiveVehiclesCount?.text = activeVehicleCount.toString()
    }

    private fun setupRecyclerView() {
        tripsAdapter = AdminTripsAdapter(
            onAllocateClick = { trip -> openAllocateSheet(trip) },
            onManageClick = { trip -> openManageSheet(trip) },
            onCompleteClick = { trip -> openCompleteOfflineSheet(trip) },
        ).also {
            it.canCompleteOffline = if (session.isExternalFleetAgencyOperator) {
                session.canBillExternalFleet
            } else {
                session.canCompleteOfflineFleet()
            }
            it.isExternalAgencySession = session.isExternalFleetAgencyOperator
        }
        binding.rvAdminTrips.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAdminTrips.adapter = tripsAdapter
        binding.rvAdminTrips.isNestedScrollingEnabled = false
    }

    private fun openManageSheet(trip: AdminTrip) {
        val canCompleteThisFleet =
            (session.isExternalFleetAgencyOperator && session.canBillExternalFleet) || !trip.external
        AdminFleetTripManageSheet.newInstance(
            trip = trip,
            onReassign = { openAllocateSheet(trip) },
            onRemove = { removeDriver(trip) },
            onComplete = if (canCompleteThisFleet) {
                { openCompleteOfflineSheet(trip) }
            } else {
                null
            },
            onOpenDriverLink = trip.driverTripUrl?.let { url ->
                {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                }
            },
            onCopyDriverLink = trip.driverTripUrl?.let { url ->
                {
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Driver trip link", url))
                    Toast.makeText(requireContext(), "Driver link copied.", Toast.LENGTH_SHORT).show()
                }
            },
            onResendDriverWhatsapp = if (!useMmsFleet && !trip.driverTripUrl.isNullOrBlank()) {
                { resendDriverWhatsapp(trip) }
            } else null,
            onUpdateTripStatus = if (!useMmsFleet) {
                { openTripStatusDialog(trip) }
            } else null,
            onProgressAction = { action, km, toll, beta ->
                submitProgressAction(trip, action, km, toll, beta)
            },
        ).showOnce(parentFragmentManager, "AdminFleetTripManageSheet")
    }

    private fun resendDriverWhatsapp(trip: AdminTrip) {
        viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching {
                api.resendDriverWhatsapp(
                    session.bearerToken,
                    com.manjugroups.m_connect.network.TravelDeskDriverTripRequest(trip.id),
                )
            }.getOrNull()
            Toast.makeText(
                requireContext(),
                if (response?.success == true) "WhatsApp queued for the driver."
                else response?.error ?: "Could not resend WhatsApp.",
                if (response?.success == true) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openTripStatusDialog(trip: AdminTrip) {
        val labels = arrayOf("Client unavailable", "Cancel trip", "Postpone trip")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Update trip status")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> promptStatusReason(trip, "not_available", "Client unavailable")
                    1 -> promptStatusReason(trip, "cancelled", "Cancel trip")
                    2 -> pickPostponedDate(trip)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptStatusReason(
        trip: AdminTrip,
        reasonCode: String,
        title: String,
        scheduledDate: String? = null,
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Reason (optional)"
            minLines = 2
            setPadding(32, 20, 32, 20)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(input)
            .setNegativeButton("Back", null)
            .setPositiveButton("Confirm") { _, _ ->
                submitTripStatus(
                    trip,
                    TravelDeskStatusUpdateRequest(
                        siteVisitId = trip.id,
                        reasonCode = reasonCode,
                        reasonText = input.text.toString().trim().ifBlank { null },
                        scheduledDate = scheduledDate,
                    ),
                )
            }
            .show()
    }

    private fun pickPostponedDate(trip: AdminTrip) {
        val today = java.util.Calendar.getInstance()
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val date = String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                promptStatusReason(trip, "postponed", "Postpone to $date", date)
            },
            today.get(java.util.Calendar.YEAR),
            today.get(java.util.Calendar.MONTH),
            today.get(java.util.Calendar.DAY_OF_MONTH),
        ).apply {
            datePicker.minDate = System.currentTimeMillis() - 1_000L
        }.show()
    }

    private fun submitTripStatus(trip: AdminTrip, request: TravelDeskStatusUpdateRequest) {
        viewLifecycleOwner.lifecycleScope.launch {
            val response = runCatching {
                api.updateTripStatus(session.bearerToken, request)
            }.getOrNull()
            if (_binding == null) return@launch
            if (response?.success == true) {
                Toast.makeText(requireContext(), "Trip status updated.", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(
                    requireContext(),
                    response?.error ?: "Could not update trip status.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * Advance the trip through its lifecycle stage. The admin can mark each
     * step on behalf of the driver:
     *   reached / start / on_site / picked_from_site / end
     *
     * Internal (MMS fleet) trips use GeoTrackApi's mms-fleet driver endpoints.
     * External (travel-desk) trips use TravelDeskApi's driver endpoints.
     * Both accept a staff token for admin-initiated progress updates.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun submitProgressAction(
        trip: AdminTrip,
        action: String,
        km: Double?,
        toll: Double?,
        beta: Double?,
    ) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        val sheet = parentFragmentManager.findFragmentByTag("AdminFleetTripManageSheet")
            as? AdminFleetTripManageSheet

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val auth = "Bearer $token"
                val id = trip.id
                when (action) {
                    "reached" -> {
                        if (useMmsFleet) {
                            val geoApi = GeoTrackApi.create()
                            geoApi.markMmsFleetDriverArrived(
                                auth,
                                com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest(id),
                            )
                        } else {
                            api.driverMarkArrived(
                                auth,
                                com.manjugroups.m_connect.network.TravelDeskDriverTripRequest(id),
                            )
                        }
                    }
                    "start" -> {
                        if (useMmsFleet) {
                            val geoApi = GeoTrackApi.create()
                            geoApi.startMmsFleetDriverTrip(
                                auth,
                                com.manjugroups.m_connect.network.MmsFleetDriverStartRequest(
                                    siteVisitId = id,
                                    photoIds = emptyList(),
                                    startKm = km,
                                ),
                            )
                        } else {
                            api.driverStartTrip(
                                auth,
                                com.manjugroups.m_connect.network.TravelDeskStartTripRequest(
                                    siteVisitId = id,
                                    otp = "0000",
                                    photoIds = emptyList(),
                                    startKm = km,
                                ),
                            )
                        }
                    }
                    "on_site" -> {
                        if (useMmsFleet) {
                            val geoApi = GeoTrackApi.create()
                            geoApi.markMmsFleetDriverOnSite(
                                auth,
                                com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest(id),
                            )
                        } else {
                            api.driverMarkOnSite(
                                auth,
                                com.manjugroups.m_connect.network.TravelDeskDriverTripRequest(id),
                            )
                        }
                    }
                    "picked_from_site" -> {
                        if (useMmsFleet) {
                            val geoApi = GeoTrackApi.create()
                            geoApi.markMmsFleetDriverPickedFromSite(
                                auth,
                                com.manjugroups.m_connect.network.MmsFleetDriverSiteVisitRequest(id),
                            )
                        } else {
                            api.driverMarkPickedFromSite(
                                auth,
                                com.manjugroups.m_connect.network.TravelDeskDriverTripRequest(id),
                            )
                        }
                    }
                    "end" -> {
                        if (useMmsFleet) {
                            val geoApi = GeoTrackApi.create()
                            geoApi.endMmsFleetDriverTrip(
                                auth,
                                com.manjugroups.m_connect.network.MmsFleetDriverEndRequest(
                                    siteVisitId = id,
                                    photoIds = emptyList(),
                                    endKm = km,
                                ),
                            )
                        } else {
                            api.driverEndTrip(
                                auth,
                                com.manjugroups.m_connect.network.TravelDeskEndTripRequest(
                                    siteVisitId = id,
                                    photoIds = emptyList(),
                                    endKm = km,
                                    tollAmount = toll,
                                    beta = beta,
                                ),
                            )
                        }
                    }
                }
                Toast.makeText(requireContext(), "Progress updated", Toast.LENGTH_SHORT).show()
                sheet?.dismissAllowingStateLoss()
                refresh()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val msg = e.localizedMessage ?: "Update failed"
                sheet?.showProgressError(msg)
            }
        }
    }

    private fun openCompleteOfflineSheet(trip: AdminTrip) {
        if (session.isExternalFleetAgencyOperator && !session.canBillExternalFleet) {
            Toast.makeText(requireContext(), "Billing access is required.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!session.canCompleteOfflineFleet()) {
            Toast.makeText(requireContext(), "You don't have permission to complete trips offline.", Toast.LENGTH_SHORT).show()
            return
        }
        if (trip.cancelled && !useMmsFleet) {
            openCancellationBilling(trip)
            return
        }
        if (!trip.outcomeRecorded && !trip.billingPending) {
            Toast.makeText(
                requireContext(),
                "Complete the Site Visit outcome before adding final fleet details.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (useMmsFleet && trip.external) {
            Toast.makeText(
                requireContext(),
                "Trip-detail completion is available here for internal fleet only.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        AdminFleetCompleteOfflineSheet.newInstance(trip, vehicles) { result ->
            submitCompleteOffline(trip, result)
        }.showOnce(parentFragmentManager, "AdminFleetCompleteOfflineSheet")
    }

    private fun openCancellationBilling(trip: AdminTrip) {
        if (!session.canBillExternalFleet) {
            Toast.makeText(requireContext(), "Billing access is required.", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Cancellation charge"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(32, 16, 32, 16)
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Cancellation billing")
            .setMessage("Enter the cancellation amount for ${trip.clientName}.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val amount = input.text.toString().trim().toDoubleOrNull()
                if (amount == null || amount < 0) {
                    Toast.makeText(requireContext(), "Enter a valid amount.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val response = runCatching {
                        api.finalizeCancellationBilling(
                            session.bearerToken,
                            FinalizeTravelDeskCancellationRequest(
                                siteVisitId = trip.id,
                                customCharges = listOf(
                                    TravelDeskAppliedCharge(
                                        label = "Cancellation allowance",
                                        amount = amount,
                                        unit = "trip",
                                    ),
                                ),
                            ),
                        )
                    }.getOrNull()
                    if (_binding == null) return@launch
                    if (response?.success == true) {
                        Toast.makeText(requireContext(), "Cancellation billing saved.", Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            response?.error ?: "Could not save cancellation billing.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun submitCompleteOffline(trip: AdminTrip, result: CompleteOfflineTripResult) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired â€” sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        allocateJob?.cancel()
        allocateJob = viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                suspend fun uploadProof(uri: android.net.Uri?): String? {
                    if (uri == null) return null
                    val file = java.io.File.createTempFile(
                        "fleet-proof-",
                        ".jpg",
                        requireContext().cacheDir,
                    )
                    requireContext().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not read selected image" }
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    val contentType =
                        requireContext().contentResolver.getType(uri) ?: "image/jpeg"
                    return try {
                        if (useMmsFleet) {
                            StorageUploader.upload(
                                ApiService.create(),
                                token,
                                file,
                                contentType = contentType,
                            ).storageId
                        } else {
                            val body = file.asRequestBody(contentType.toMediaType())
                            api.uploadStorageFile(token, body).storageId
                        }
                    } finally {
                        file.delete()
                    }
                }
                val startPhotoId = uploadProof(result.startImageUri)
                val endPhotoId = uploadProof(result.endImageUri)
                val request = CompleteOfflineTripRequest(
                    siteVisitId = trip.id,
                    packageAmount = result.packageAmount,
                    kmRate = result.kmRate,
                    beta = result.beta,
                    beta2 = result.beta2,
                    tollAmount = result.tollAmount,
                    hillCharge = result.hillCharge,
                    outstationCharge = result.outstationCharge,
                    permitCharge = result.permitCharge,
                    permitTax = result.permitTax,
                    standingCharge = result.standingCharge,
                    distanceKm = result.distanceKm,
                    driverName = result.driverName,
                    driverPhone = result.driverPhone,
                    fleetType = result.fleetType,
                    vehicleId = result.vehicleId,
                    agencyName = result.agencyName,
                    standingTimeMinutes = result.standingTimeMinutes,
                    standingWithAc = result.standingWithAc,
                    startKm = result.startKm,
                    endKm = result.endKm,
                    startPhotoIds = startPhotoId?.let(::listOf),
                    endPhotoIds = endPhotoId?.let(::listOf),
                )
                if (useMmsFleet) {
                    api.completeOfflineMms(token, request)
                } else if (trip.billingPending || trip.status == "Completed") {
                    val startKm = result.startKm
                        ?: throw IllegalArgumentException("Start km is required")
                    val endKm = result.endKm
                        ?: throw IllegalArgumentException("End km is required")
                    api.finalizeBilling(
                        token,
                        FinalizeTravelDeskBillingRequest(
                            siteVisitId = trip.id,
                            startKm = startKm,
                            endKm = endKm,
                            startPhotoIds = startPhotoId?.let(::listOf),
                            endPhotoIds = endPhotoId?.let(::listOf),
                            kmRate = result.kmRate,
                            packageAmount = result.packageAmount,
                            beta = result.beta,
                            beta2 = result.beta2,
                            tollAmount = result.tollAmount,
                            hillCharge = result.hillCharge,
                            outstationCharge = result.outstationCharge,
                            permitCharge = result.permitCharge,
                            permitTax = result.permitTax,
                            standingCharge = result.standingCharge,
                            vehicleId = result.vehicleId,
                            driverName = result.driverName,
                            driverPhone = result.driverPhone,
                        ),
                    )
                } else {
                    api.completeOfflineAgency(token, request)
                }
            }.getOrNull()
            if (_binding == null) return@launch
            if (resp?.success == true) {
                Toast.makeText(
                    requireContext(),
                    "Trip details saved.",
                    Toast.LENGTH_SHORT,
                ).show()
                refresh()
            } else {
                Toast.makeText(
                    requireContext(),
                    resp?.error ?: "Could not complete this trip.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun removeDriver(trip: AdminTrip) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        allocateJob?.cancel()
        allocateJob = viewLifecycleOwner.lifecycleScope.launch {
            val resp = runCatching {
                val body = com.manjugroups.m_connect.network.TravelDeskDriverTripRequest(trip.id)
                // MMS internal fleet unassigns via the staff-token dispatch
                // route; the travel-desk /unallocate only accepts an agency
                // session and 401s for staff.
                if (useMmsFleet) api.unassignMms(token, body) else api.unallocate(token, body)
            }.getOrNull()
            if (_binding == null) return@launch
            if (resp?.success == true) {
                Toast.makeText(requireContext(), "Driver removed — back to Pending.", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(
                    requireContext(),
                    resp?.error ?: "Could not remove the driver.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun openAllocateSheet(trip: AdminTrip) {
        // Find the original travel-desk trip (we only kept the UI model in the
        // adapter, so look it up by id from the cached lists).
        val originalTrip = pendingTrips.firstOrNull { it.id == trip.id }
            ?: assignedActive.firstOrNull { it.id == trip.id }
            ?: assignedInProgress.firstOrNull { it.id == trip.id }
            ?: assignedCompleted.firstOrNull { it.id == trip.id }
            ?: return

        if (vehicles.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No vehicles on this agency. Add one in travel-desk first.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val options = vehicles
            .filter { (it.status ?: "active").equals("active", ignoreCase = true) }
            .map {
                val number = it.vehicleNumber ?: "—"
                val typePart = it.type?.takeIf { t -> t.isNotBlank() }?.let { t -> "$t · " }.orEmpty()
                AllocateVehicleOption(
                    vehicleId = it.id,
                    label = "$typePart$number",
                    defaultDriverName = it.defaultDriverName,
                    defaultDriverPhone = it.defaultDriverPhone,
                )
            }

        // Internal (MMS) allocations price per trip; external agencies
        // carry a contracted rate on the agency record instead.
        val driverOptions = driverRoster
            .filter { it.status.equals("active", ignoreCase = true) }
            .map {
                AllocateDriverOption(
                    id = it.id,
                    name = it.name,
                    phone = it.phone.orEmpty(),
                )
            }
        AllocateVehicleBottomSheet.newInstance(
            vehicles = options,
            drivers = driverOptions,
            onAddNewDriver = { typedName, onCreated -> addDriverThenResume(typedName, onCreated) },
        ) { result ->
            submitAllocate(originalTrip, result)
        }.showOnce(parentFragmentManager, "AllocateVehicleBottomSheet")
    }

    /**
     * Open the add-driver form seeded with the name the dispatcher typed but
     * couldn't find in the roster. On success create it via the agency driver
     * route, add it to the local roster, and hand it back so the allocate sheet
     * selects it and the allocation resumes.
     */
    private fun addDriverThenResume(
        typedName: String,
        onCreated: (AllocateDriverOption) -> Unit,
    ) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        CreateDriverBottomSheet.newInstance { name, phone, address, category ->
            viewLifecycleOwner.lifecycleScope.launch {
                val req = com.manjugroups.m_connect.network.CreateDriverRequest(
                    name = name.trim(),
                    phone = phone.trim(),
                    address = address.trim().takeIf { it.isNotBlank() },
                    category = category,
                )
                val resp = runCatching {
                    if (useMmsFleet) api.createMmsDriver(token, req) else api.createDriver(token, req)
                }.getOrNull()
                if (_binding == null) return@launch
                val newId = resp?.driverId
                if (resp?.success == true && !newId.isNullOrBlank()) {
                    val created = AllocateDriverOption(newId, name.trim(), phone.trim())
                    driverRoster = driverRoster + com.manjugroups.m_connect.network.TravelDeskDriver(
                        id = newId,
                        name = name.trim(),
                        phone = phone.trim(),
                        category = category,
                    )
                    onCreated(created)
                    Toast.makeText(requireContext(), "Driver added.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        resp?.error ?: "Could not add the driver.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.apply {
            // Pre-fill the typed name so the dispatcher doesn't retype it.
            prefillName(typedName)
        }.showOnce(parentFragmentManager, "AddDriverFromAllocate")
    }

    private fun submitAllocate(trip: TravelDeskTrip, result: AllocateVehicleResult) {
        val token = session.bearerToken
        if (token.isBlank()) {
            Toast.makeText(requireContext(), "Session expired — sign in again.", Toast.LENGTH_SHORT).show()
            return
        }
        // No id means the row didn't parse as a real site visit; allocating
        // against it would fail server-side anyway.
        val siteVisitId = trip.id?.takeIf { it.isNotBlank() } ?: run {
            Toast.makeText(requireContext(), "This trip can't be allocated.", Toast.LENGTH_SHORT).show()
            return
        }
        allocateJob?.cancel()
        allocateJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val request = AllocateTripRequest(
                        siteVisitId = siteVisitId,
                        vehicleId = result.vehicleId,
                        pickupTime = result.pickupTime,
                        driverName = result.driverName,
                        driverPhone = result.driverPhone,
                        travelDeskDriverId = result.driverId,
                )
                val resp = if (useMmsFleet) {
                    api.allocateMms(token, request)
                } else {
                    api.allocate(token, request)
                }
                if (!resp.success) {
                    Toast.makeText(
                        requireContext(),
                        resp.error ?: "Allocation failed.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                Toast.makeText(
                    requireContext(),
                    "Trip allocated to ${result.vehicleLabel}.",
                    Toast.LENGTH_SHORT,
                ).show()
                // After a successful allocation the trip moves from the pending
                // feed to the assigned feed — switch tabs so the user sees the
                // result of their action.
                refresh()
                applyFilter("Assigned")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Allocation failed: ${loadErrorMessage(e)}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun setupFilters() {
        binding.btnTabPending.setOnClickListener { applyFilter("Pending") }
        binding.btnTabAssigned.setOnClickListener { applyFilter("Assigned") }
        binding.btnTabInProgress.setOnClickListener { applyFilter("In Progress") }
        binding.btnTabBilling.setOnClickListener { applyFilter("Billing") }
        binding.btnTabCompleted.setOnClickListener { applyFilter("Completed") }
    }

    private fun applyFilter(filter: String) {
        activeFilter = filter

        // Keep the banner's 3-dot indicator in sync with the active tab.
        _binding?.homeHeader?.setActiveFleetDot(
            when (filter) {
                "Assigned" -> 1
                "In Progress" -> 1
                "Billing" -> 2
                "Completed" -> 2
                else -> 0
            }
        )

        // Style the active/inactive tabs exactly to match the home screen's tab bar selectors
        val activeBg = R.drawable.bg_my_trips_tab_active
        val activeTextColor = Color.parseColor("#FFFFFF")
        val inactiveTextColor = Color.parseColor("#475467")

        val density = binding.root.resources.displayMetrics.density
        val verticalPadding = (8 * density).toInt()

        listOf(
            Triple(binding.btnTabPending, "Pending", activeFilter == "Pending"),
            Triple(binding.btnTabAssigned, "Assigned", activeFilter == "Assigned"),
            Triple(binding.btnTabInProgress, "In Progress", activeFilter == "In Progress"),
            Triple(binding.btnTabBilling, "Billing", activeFilter == "Billing"),
            Triple(binding.btnTabCompleted, "Completed", activeFilter == "Completed")
        ).forEach { (btn, _, isActive) ->
            if (isActive) {
                btn.setBackgroundResource(activeBg)
                btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#0B61CA")))
                btn.setTextColor(activeTextColor)
            } else {
                btn.setBackgroundResource(0)
                btn.setTextColor(inactiveTextColor)
            }
            btn.setPadding(0, verticalPadding, 0, verticalPadding)
        }

        val rows = when (filter) {
            "Assigned" -> assignedActive
            "In Progress" -> assignedInProgress
            "Billing" -> assignedBilling
            "Completed" -> assignedCompleted
            else -> pendingTrips
        }.map { mapTrip(it, filter) }

        filteredTrips = rows
        tripsPager.reset()
        renderTripWindow()
    }

    /** Render only the current window of the active tab's trips. */
    private fun renderTripWindow() {
        if (_binding == null) return
        tripsAdapter.submitList(filteredTrips.take(tripsPager.limit))

        val error = lastLoadError
        binding.tvTripsEmpty.visibility =
            if (filteredTrips.isEmpty()) View.VISIBLE else View.GONE
        binding.tvTripsEmpty.text = when {
            error != null -> error
            activeFilter == "Pending" -> "No trips waiting for allocation."
            activeFilter == "Assigned" -> "No trips are currently assigned."
            activeFilter == "In Progress" -> "No ongoing or pending-detail trips."
            activeFilter == "Billing" -> "No dropped trips are waiting for billing."
            else -> "No completed trips yet."
        }
    }

    private fun mapTrip(trip: TravelDeskTrip, statusLabel: String): AdminTrip {
        val date: String = trip.scheduledDate ?: "—"
        val timePart: String = trip.scheduledTime ?: trip.pickupTime ?: ""
        val timeLabel: String = if (timePart.isBlank()) date else "$date • $timePart"
        val attendees: String = trip.expectedAttendeeCount?.toString() ?: "—"
        val vehicleLabel: String = trip.vehiclePreference ?: "External"

        val pickup: String? = trip.pickupAddress?.trim()?.ifBlank { null }
        val projectName: String? = trip.project?.name
        val addressLine: String = when {
            pickup != null -> pickup
            projectName != null -> "Project: $projectName"
            else -> "Address pending"
        }

        val started =
            trip.travelDeskArrivedAt != null ||
                trip.travelDeskStartedAt != null ||
                trip.travelDeskOnSiteAt != null ||
                trip.travelDeskPickedFromSiteAt != null ||
                trip.travelDeskEndedAt != null
        val progress = when {
            trip.travelDeskEndedAt != null -> "Dropped"
            trip.travelDeskPickedFromSiteAt != null -> "Picked from site"
            trip.travelDeskOnSiteAt != null -> "On site"
            trip.travelDeskStartedAt != null -> "Picked from CP"
            trip.travelDeskArrivedAt != null -> "Reached client"
            else -> "Awaiting pickup"
        }
        val progressState = progressState(trip)
        val displayStatus = if (statusLabel == "In Progress") {
            if (progressState == "ongoing") "Ongoing" else "Pending details"
        } else if (statusLabel == "Billing") {
            if (isCancelledTrip(trip)) "Cancellation billing" else "Ready to bill"
        } else {
            statusLabel
        }

        return AdminTrip(
            id = trip.id.orEmpty(),
            time = timeLabel,
            clientName = trip.clientName?.trim()?.takeIf { it.isNotBlank() }
                ?: trip.lead?.contactName?.trim()?.takeIf { it.isNotBlank() }
                ?: "Unknown",
            address = addressLine,
            attendees = attendees,
            vehicleType = vehicleLabel,
            lmoName = trip.lmoName?.trim()?.ifBlank { null },
            status = displayStatus,
            driverName = trip.driverName?.trim()?.ifBlank { null },
            driverPhone = trip.driverPhone?.trim()?.ifBlank { null },
            allocatedVehicle = trip.vehicle?.vehicleNumber?.trim()?.ifBlank { null },
            progressLabel = progress,
            started = started,
            expired = false,
            outcomeRecorded = hasRecordedOutcome(trip),
            startKm = trip.travelDeskStartKm,
            endKm = trip.travelDeskEndKm,
            distanceKm = trip.travelDeskStartKm?.let { start ->
                trip.travelDeskEndKm?.let { end -> (end - start).takeIf { it >= 0.0 } }
            },
            startPhotoId = trip.travelDeskStartPhotoIds.firstOrNull(),
            endPhotoId = trip.travelDeskEndPhotoIds.firstOrNull(),
            packageAmount = trip.travelDeskPackageAmount,
            pricingMode = trip.travelDeskPricingMode,
            kmRate = trip.travelDeskKmRate,
            beta = trip.travelDeskBeta,
            beta2 = trip.travelDeskBeta2,
            tollAmount = trip.travelDeskTollAmount,
            hillCharge = trip.travelDeskHillCharge,
            outstationCharge = trip.travelDeskOutstationCharge,
            permitCharge = trip.travelDeskPermitCharge,
            permitTax = trip.travelDeskPermitTax,
            standingCharge = trip.travelDeskStandingCharge,
            customCharges = trip.travelDeskCustomCharges,
            totalAmount = trip.travelDeskTotalAmount,
            extraKm = trip.travelDeskExtraKm,
            extraKmRate = trip.travelDeskExtraKmRate,
            extraKmAmount = trip.travelDeskExtraKmAmount,
            extraKmStatus = trip.travelDeskExtraKmStatus,
            extraKmReviewReason = trip.travelDeskExtraKmReviewReason,
            external = trip.travelAgency?.name?.isNotBlank() == true,
            agencyName = trip.travelAgency?.name?.trim()?.ifBlank { null },
            driverTripUrl = trip.driverTripUrl?.takeIf(String::isNotBlank)
                ?: trip.driverAccessToken?.takeIf(String::isNotBlank)?.let {
                    "https://traveldesk.aivida.in/driver/trips/$it"
                },
            billingPending = statusLabel == "Billing",
            cancelled = isCancelledTrip(trip),
        )
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        allocateJob?.cancel()
        if (_binding != null) {
            binding.homeHeader.stopFloatingAnimation()
        }
        super.onDestroyView()
        _binding = null
    }

    data class AdminTrip(
        val id: String,
        val time: String,
        val clientName: String,
        val address: String,
        val attendees: String,
        val vehicleType: String,
        val lmoName: String?,
        var status: String,
        var driverName: String? = null,
        var driverPhone: String? = null,
        var allocatedVehicle: String? = null,
        var progressLabel: String = "",
        var started: Boolean = false,
        var expired: Boolean = false,
        var outcomeRecorded: Boolean = false,
        var startKm: Double? = null,
        var endKm: Double? = null,
        var distanceKm: Double? = null,
        var startPhotoId: String? = null,
        var endPhotoId: String? = null,
        var packageAmount: Double? = null,
        var pricingMode: String? = null,
        var kmRate: Double? = null,
        var beta: Double? = null,
        var beta2: Double? = null,
        var tollAmount: Double? = null,
        var hillCharge: Double? = null,
        var outstationCharge: Double? = null,
        var permitCharge: Double? = null,
        var permitTax: Double? = null,
        var standingCharge: Double? = null,
        var customCharges: List<TravelDeskAppliedCharge> = emptyList(),
        var totalAmount: Double? = null,
        var extraKm: Double? = null,
        var extraKmRate: Double? = null,
        var extraKmAmount: Double? = null,
        var extraKmStatus: String? = null,
        var extraKmReviewReason: String? = null,
        // An external-agency allotment vs the in-house (Internal / MFPL) fleet.
        // Drives the manage sheet's wording — an agency trip is removed with
        // "Remove agency", not "Remove driver".
        var external: Boolean = false,
        var agencyName: String? = null,
        var driverTripUrl: String? = null,
        var billingPending: Boolean = false,
        var cancelled: Boolean = false,
    )

    private class AdminTripsAdapter(
        private val onAllocateClick: (AdminTrip) -> Unit,
        private val onManageClick: (AdminTrip) -> Unit,
        private val onCompleteClick: (AdminTrip) -> Unit,
    ) : RecyclerView.Adapter<AdminTripsAdapter.ViewHolder>() {

        private var items: List<AdminTrip> = emptyList()
        var canCompleteOffline: Boolean = false
        var isExternalAgencySession: Boolean = false

        fun submitList(newList: List<AdminTrip>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAdminFleetTripBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemAdminFleetTripBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: AdminTrip) {
                binding.tvTripTime.text = item.time
                binding.tvClientName.text = item.clientName
                binding.tvTripAddress.text = item.address
                // People count only (the pill icon already conveys "people").
                val attendeesShort = item.attendees.replace(" Attendees", "").trim()
                binding.tvAttendeesTag.text = attendeesShort

                // LMO — the telecaller who created the visit. Kept visible (with
                // an em-dash fallback) so its weighted pill keeps the Allocate
                // button right-aligned on the row.
                // Just the LMO name (the person icon conveys the role), or "—".
                binding.tvLmoTag.text =
                    item.lmoName?.takeIf { it.isNotBlank() } ?: "—"
                // Expired takes over the badge from "Assigned" once the day is
                // lost, and hides Allocate — the backend would reject it anyway.
                binding.tvTripStatus.text = if (item.expired) "Expired" else item.status

                when {
                    item.expired -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_pending)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#B42318"))
                        if (canCompleteOffline) {
                            binding.btnAllocate.visibility = View.VISIBLE
                            binding.btnAllocate.text = "Completed"
                            binding.btnAllocate.setBackgroundResource(R.drawable.bg_allocate_button_green)
                            binding.btnAllocate.setOnClickListener { onCompleteClick(item) }
                        } else {
                            binding.btnAllocate.visibility = View.GONE
                        }
                    }
                    item.status == "Ongoing" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_info)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#1D4ED8"))
                        binding.btnAllocate.visibility = View.GONE
                    }
                    item.status == "Pending details" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_pending)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#B54708"))
                        if (
                            canCompleteOffline &&
                            item.outcomeRecorded &&
                            (isExternalAgencySession || !item.external)
                        ) {
                            binding.btnAllocate.visibility = View.VISIBLE
                            binding.btnAllocate.text = "Complete"
                            binding.btnAllocate.setBackgroundResource(R.drawable.bg_allocate_button_green)
                            binding.btnAllocate.setOnClickListener { onCompleteClick(item) }
                        } else {
                            binding.btnAllocate.visibility = View.GONE
                        }
                    }
                    item.status == "Ready to bill" || item.status == "Cancellation billing" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_pending)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#B54708"))
                        binding.btnAllocate.visibility = View.VISIBLE
                        binding.btnAllocate.text = if (item.cancelled) "Bill cancellation" else "Complete billing"
                        binding.btnAllocate.setBackgroundResource(R.drawable.bg_allocate_button_green)
                        binding.btnAllocate.setOnClickListener { onCompleteClick(item) }
                    }
                    item.status == "Pending" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_pending)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#EA580C"))
                        binding.btnAllocate.visibility = View.VISIBLE
                        binding.btnAllocate.text = "Allocate >"
                        binding.btnAllocate.setBackgroundResource(R.drawable.bg_admin_fleet_btn_allocate)
                        binding.btnAllocate.setOnClickListener { onAllocateClick(item) }
                    }
                    item.status == "Assigned" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_info)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#1D4ED8"))
                        binding.btnAllocate.visibility = View.GONE
                    }
                    item.status == "Completed" -> {
                        binding.tvTripStatus.setBackgroundResource(R.drawable.bg_badge_success)
                        binding.tvTripStatus.setTextColor(Color.parseColor("#047857"))
                        val canEditCompleted =
                            canCompleteOffline &&
                                (
                                    (isExternalAgencySession && item.external) ||
                                        (!isExternalAgencySession && !item.external)
                                    )
                        binding.btnAllocate.visibility =
                            if (canEditCompleted) View.VISIBLE else View.GONE
                        if (canEditCompleted) {
                            binding.btnAllocate.text = "Edit details"
                            binding.btnAllocate.setBackgroundResource(
                                R.drawable.bg_allocate_button_green,
                            )
                            binding.btnAllocate.setOnClickListener {
                                onCompleteClick(item)
                            }
                        }
                    }
                }

                // Minimal card: keep it to time / address / status. The full
                // allocation detail (vehicle, driver, progress, photos, km) lives
                // in the manage sheet the whole card opens on tap. Only a compact
                // progress pill stays, so an allocated trip still reads at a
                // glance without the tall detail block.
                val allocated = item.status != "Pending"
                binding.assignmentInfo.visibility = if (allocated) View.VISIBLE else View.GONE
                if (allocated) {
                    binding.tvAssignedVehicle.visibility = View.GONE
                    binding.tvAssignedDriver.visibility = View.GONE
                    // Expiry is already shown by the top-right status badge.
                    // Keep this compact pill exclusively for actual trip progress.
                    binding.tvAssignedProgress.visibility =
                        if (item.expired) View.GONE else View.VISIBLE
                    if (!item.expired) {
                        binding.tvAssignedProgress.text = item.progressLabel
                    }
                }

                // Any allocated trip opens the manage sheet (details + progress
                // + reassign / remove driver). Pending cards keep Allocate only.
                binding.root.setOnClickListener {
                    if (allocated) onManageClick(item)
                }
            }

        }
    }
}
