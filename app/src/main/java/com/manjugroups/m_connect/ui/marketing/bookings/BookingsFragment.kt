package com.manjugroups.m_connect.ui.marketing.bookings

import android.os.Bundle
import android.text.TextWatcher
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.Booking
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Marketing > Bookings list screen.
 *
 * Mirrors CpVisitsFragment's structure (top bar, search bar, filter
 * pill row, list / empty / skeleton stack) because the design system
 * is shared across marketing list screens.
 *
 * Data is fetched from `/api/marketing/bookings/my` which server-side
 * scopes rows to bookings the calling staffer is involved in (creator
 * or any of the 5 original-staff slots) and is IAM-gated on
 * `marketing.bookings.view`. Users without that permission get an
 * empty list — we do not pre-hide the screen client-side because the
 * AppLibrary tile is already permission-gated.
 *
 * The + button in the top bar is only visible when the user has
 * `marketing.bookings.create`; tapping it opens [BookingCreateFragment].
 */
class BookingsFragment : Fragment() {

    private lateinit var session: SessionManager
    private val api by lazy { ApiService.create() }

    private enum class StatusFilter(val label: String, val apiValue: String?) {
        ALL("All", null),
        DRAFT("Draft", "draft"),
        PENDING("Pending", "pending_confirmation"),
        CONFIRMED("Confirmed", "confirmed"),
        CANCELLED("Cancelled", "cancelled"),
    }

    private var activeFilter: StatusFilter = StatusFilter.DRAFT
    private var searchQuery: String = ""
    private var allBookings: List<Booking> = emptyList()
    private var hasLoadedOnce: Boolean = false

    // Views
    private var listContainer: LinearLayout? = null
    private var emptyState: View? = null
    private var skeletonContainer: View? = null
    private var refreshLayout: SwipeRefreshLayout? = null
    private var btnCreate: ImageView? = null
    private var pillAll: TextView? = null
    private var pillDraft: TextView? = null
    private var pillPending: TextView? = null
    private var pillConfirmed: TextView? = null
    private var pillCancelled: TextView? = null
    private var searchEt: EditText? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_bookings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        view.findViewById<ImageView>(R.id.btnBookingsBack)
            .setOnClickListener { parentFragmentManager.popBackStack() }

        btnCreate = view.findViewById<ImageView>(R.id.btnCreateBooking).apply {
            visibility = if (session.hasPermission("marketing.bookings.create")) {
                setOnClickListener { openCreate() }
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        listContainer = view.findViewById(R.id.bookingsList)
        emptyState = view.findViewById(R.id.bkEmptyState)
        skeletonContainer = view.findViewById(R.id.bkSkeletonContainer)
        refreshLayout = view.findViewById<SwipeRefreshLayout>(R.id.bkRefresh).apply {
            setColorSchemeColors(0xFF0B61CA.toInt())
            setOnRefreshListener { loadBookings(showSkeleton = false) }
        }

        pillAll = view.findViewById(R.id.pillBkAll)
        pillDraft = view.findViewById(R.id.pillBkDraft)
        pillPending = view.findViewById(R.id.pillBkPending)
        pillConfirmed = view.findViewById(R.id.pillBkConfirmed)
        pillCancelled = view.findViewById(R.id.pillBkCancelled)

        pillAll?.setOnClickListener { setFilter(StatusFilter.ALL) }
        pillDraft?.setOnClickListener { setFilter(StatusFilter.DRAFT) }
        pillPending?.setOnClickListener { setFilter(StatusFilter.PENDING) }
        pillConfirmed?.setOnClickListener { setFilter(StatusFilter.CONFIRMED) }
        pillCancelled?.setOnClickListener { setFilter(StatusFilter.CANCELLED) }

        searchEt = view.findViewById<EditText>(R.id.etBookingsSearch).apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim().orEmpty()
                    renderList()
                }
            })
        }

        renderFilterPills()
        loadBookings(showSkeleton = true)
    }

    override fun onResume() {
        super.onResume()
        // If a booking was created via the + button, refresh on return so
        // the new row appears without a manual pull-to-refresh.
        if (hasLoadedOnce) loadBookings(showSkeleton = false)
    }

    override fun onDestroyView() {
        listContainer = null
        emptyState = null
        skeletonContainer = null
        refreshLayout = null
        btnCreate = null
        pillAll = null; pillDraft = null; pillPending = null
        pillConfirmed = null; pillCancelled = null
        searchEt = null
        super.onDestroyView()
    }

    // ---------- Data ----------

    private fun loadBookings(showSkeleton: Boolean) {
        val token = session.bearerToken
        if (token.isBlank()) {
            refreshLayout?.isRefreshing = false
            return
        }
        if (showSkeleton && !hasLoadedOnce) {
            skeletonContainer?.visibility = View.VISIBLE
            listContainer?.visibility = View.GONE
            emptyState?.visibility = View.GONE
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Status is sent via the API filter when not ALL, so the
                // backend can short-circuit using its by_status index. The
                // client-side search is then applied across whatever the
                // server returned for that status bucket.
                val resp = api.listMyBookings(
                    token = token,
                    status = activeFilter.apiValue,
                )
                allBookings = if (resp.success) resp.bookings else emptyList()
            } catch (_: Exception) {
                allBookings = emptyList()
            } finally {
                hasLoadedOnce = true
                skeletonContainer?.visibility = View.GONE
                refreshLayout?.isRefreshing = false
                renderList()
            }
        }
    }

    // ---------- Filter / search ----------

    private fun setFilter(f: StatusFilter) {
        if (f == activeFilter) return
        activeFilter = f
        renderFilterPills()
        // Refetch — status changes which server-side bucket we hit.
        loadBookings(showSkeleton = false)
    }

    private fun renderFilterPills() {
        val pills = mapOf(
            pillAll to StatusFilter.ALL,
            pillDraft to StatusFilter.DRAFT,
            pillPending to StatusFilter.PENDING,
            pillConfirmed to StatusFilter.CONFIRMED,
            pillCancelled to StatusFilter.CANCELLED,
        )
        for ((tv, filter) in pills) {
            tv ?: continue
            val active = filter == activeFilter
            tv.setBackgroundResource(
                if (active) R.drawable.bg_cpv_filter_pill_active
                else R.drawable.bg_cpv_filter_pill_inactive
            )
            tv.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (active) android.R.color.white else android.R.color.darker_gray
                )
            )
            tv.typeface = androidx.core.content.res.ResourcesCompat.getFont(
                requireContext(),
                if (active) R.font.inter_semibold else R.font.inter_medium
            )
            // Lock the active text colour back to the spec colours (the
            // android system "darker_gray" is just a safe fallback above).
            tv.setTextColor(
                if (active) android.graphics.Color.WHITE
                else android.graphics.Color.parseColor("#475467")
            )
        }
    }

    // ---------- Render ----------

    private fun renderList() {
        val list = listContainer ?: return
        list.removeAllViews()

        val q = searchQuery.lowercase(Locale.getDefault())
        val rows = if (q.isEmpty()) {
            allBookings
        } else {
            allBookings.filter { b ->
                b.clientName?.lowercase(Locale.getDefault())?.contains(q) == true ||
                    b.mobileNumber?.lowercase(Locale.getDefault())?.contains(q) == true ||
                    b.bookingRefNo?.lowercase(Locale.getDefault())?.contains(q) == true ||
                    b.projectName?.lowercase(Locale.getDefault())?.contains(q) == true
            }
        }

        if (rows.isEmpty()) {
            list.visibility = View.GONE
            emptyState?.visibility = View.VISIBLE
        } else {
            list.visibility = View.VISIBLE
            emptyState?.visibility = View.GONE
            val inflater = LayoutInflater.from(requireContext())
            for (booking in rows) {
                val row = inflater.inflate(R.layout.item_booking, list, false)
                bindRow(row, booking)
                list.addView(row)
            }
        }
    }

    private fun bindRow(row: View, b: Booking) {
        row.findViewById<TextView>(R.id.tvBookingClient).text =
            b.clientName?.takeIf { it.isNotBlank() } ?: "—"

        val parts = mutableListOf<String>()
        b.bookingRefNo?.takeIf { it.isNotBlank() }?.let(parts::add)
        b.projectName?.takeIf { it.isNotBlank() }?.let(parts::add)
        (b.plotNumber ?: b.plotNo)?.takeIf { it.isNotBlank() }?.let { parts.add("Plot $it") }
        row.findViewById<TextView>(R.id.tvBookingMeta).text =
            if (parts.isEmpty()) (b.mobileNumber ?: "") else parts.joinToString(" · ")

        row.findViewById<TextView>(R.id.tvBookingDate).text =
            formatBookingDate(b.bookingDate)

        val amount = b.agreedAmount ?: b.bookingCost ?: 0.0
        row.findViewById<TextView>(R.id.tvBookingAmount).text =
            formatCurrency(amount)

        bindStatus(
            row.findViewById(R.id.tvBookingStatus),
            b.status,
        )

        row.setOnClickListener {
            // TODO: wire detail screen when the mobile booking-detail
            // fragment lands. For now the row is non-navigating.
        }
    }

    private fun bindStatus(pill: TextView, status: String?) {
        when (status) {
            "draft" -> {
                pill.text = "Draft"
                pill.setBackgroundResource(R.drawable.bg_home_trip_status_done)
                pill.setTextColor(android.graphics.Color.parseColor("#475467"))
            }
            "pending_confirmation" -> {
                pill.text = "Pending"
                pill.setBackgroundResource(R.drawable.bg_home_trip_status_progress)
                pill.setTextColor(android.graphics.Color.parseColor("#B54708"))
            }
            "confirmed" -> {
                pill.text = "Confirmed"
                pill.setBackgroundResource(R.drawable.bg_home_trip_status_ready)
                pill.setTextColor(android.graphics.Color.parseColor("#169B2F"))
            }
            "cancelled" -> {
                pill.text = "Cancelled"
                pill.setBackgroundResource(R.drawable.bg_booking_status_cancelled)
                pill.setTextColor(android.graphics.Color.parseColor("#B42318"))
            }
            else -> {
                pill.text = status?.replaceFirstChar { it.uppercase() } ?: "—"
                pill.setBackgroundResource(R.drawable.bg_home_trip_status_done)
                pill.setTextColor(android.graphics.Color.parseColor("#475467"))
            }
        }
    }

    private fun formatBookingDate(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val outFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            outFmt.format(inFmt.parse(raw)!!)
        } catch (_: Exception) {
            raw
        }
    }

    private fun formatCurrency(amount: Double): String {
        if (amount <= 0.0) return "—"
        return try {
            val nf = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build())
            nf.maximumFractionDigits = 0
            nf.format(amount)
        } catch (_: Exception) {
            "₹${amount.toLong()}"
        }
    }

    private fun openCreate() {
        // Bookings list and BookingCreateFragment both live inside the
        // MainActivity fragment container, so we navigate via the parent
        // fragment manager (same pattern AppLibraryFragment uses for its
        // tile clicks). Back stack is added so the user returns to the
        // list after submitting the form.
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, BookingCreateFragment.newEmpty())
            .addToBackStack(null)
            .commit()
    }

    companion object {
        fun newInstance(): BookingsFragment = BookingsFragment()
    }
}
