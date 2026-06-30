package com.manjugroups.m_connect.ui.hr

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ProjectSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale

class OnDutyFormBottomSheet : BottomSheetDialogFragment() {

    private lateinit var session: SessionManager
    private val api = ApiService.create()

    private var selectedCategory: String? = null // "Projects", "Vendors", "Others"
    private var selectedTargetId: String? = null
    private var selectedTargetName: String? = null
    private var selectedVehicleOwnership: String? = null // "Own Vehicle", "Office Vehicle"
    private var selectedVehicleType: String? = null // "2 Wheeler", "4 Wheeler"
    private var isAnimatingSelection = false

    private var allProjects: List<ProjectSummary> = emptyList()
    // Real vendor master data, fetched from /api/library/vendors. Empty
    // until the first fetch returns; the list and search both render
    // against this. Replaces the old hardcoded sample list which was
    // showing on screen even though the web Vendors tab had different
    // data (or no data at all for a fresh tenant).
    private var allVendors: List<VendorItem> = emptyList()

    private val displayedItems = mutableListOf<DisplayItem>()
    private lateinit var listAdapter: ListAdapter

    // Top Header Views
    private lateinit var tvSheetTitle: TextView
    private lateinit var tvSheetSubtitle: TextView

    // Step 1 Views
    private lateinit var layoutStep1: LinearLayout
    private lateinit var layoutCategoryCards: LinearLayout
    private lateinit var cardProjects: LinearLayout
    private lateinit var cardVendors: LinearLayout
    private lateinit var cardOthers: LinearLayout
    
    private lateinit var layoutSearchContainer: LinearLayout
    private lateinit var etSearchQuery: EditText
    private lateinit var rvPickerList: RecyclerView
    private lateinit var layoutRemarksContainer: LinearLayout
    private lateinit var etOnDutyRemarks: EditText
    private lateinit var btnOnDutyNext: View

    // Step 2 Views
    private lateinit var layoutStep2: LinearLayout
    private lateinit var cardOwnTwoWheeler: View
    private lateinit var cardOwnFourWheeler: View
    private lateinit var cardOfficeTwoWheeler: View
    private lateinit var cardOfficeFourWheeler: View
    private lateinit var cardOfficePublicTransport: View

    private lateinit var tvOwnTwoWheeler: TextView
    private lateinit var tvOwnFourWheeler: TextView
    private lateinit var tvOfficeTwoWheeler: TextView
    private lateinit var tvOfficeFourWheeler: TextView
    private lateinit var tvOfficePublicTransport: TextView

    private lateinit var imgOwnTwoWheelerAnim: ImageView
    private lateinit var imgOwnFourWheelerAnim: ImageView
    private lateinit var imgOfficeTwoWheelerAnim: ImageView
    private lateinit var imgOfficeFourWheelerAnim: ImageView
    private lateinit var imgOfficePublicTransportAnim: ImageView

    private lateinit var btnOnDutySubmit: View
    private lateinit var layoutBottomButtons: View

    data class DisplayItem(val id: String, val name: String)
    // address kept locally so a follow-up screen can show the vendor's
    // location on a map without a second fetch.
    data class VendorItem(val id: String, val name: String, val address: String? = null)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)

        // Intercept the system back gesture / hardware back button so it
        // walks the multi-step sheet back one step at a time instead of
        // dismissing outright.
        //
        // Two listeners are wired because Android handles back differently
        // depending on the device + version:
        //   • OnBackPressedDispatcher — fires for the gesture-back swipe
        //     on Android 13+ (the only path that actually delivers there;
        //     setOnKeyListener with KEYCODE_BACK doesn't fire under
        //     predictive back). Without this, swipe-back was just
        //     dismissing the whole sheet.
        //   • setOnKeyListener — covers hardware back keys on older
        //     devices / emulators where the dispatcher route may be a no-op.
        dialog.onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            },
        )
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                if (event.action == android.view.KeyEvent.ACTION_UP) {
                    handleBackPress()
                }
                true
            } else {
                false
            }
        }

        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundColor(Color.TRANSPARENT)
                it.elevation = 0f
                val behavior = BottomSheetBehavior.from(it)
                val displayMetrics = resources.displayMetrics
                val screenHeight = displayMetrics.heightPixels
                behavior.peekHeight = screenHeight / 2
                behavior.skipCollapsed = false
                behavior.state = BottomSheetBehavior.STATE_COLLAPSED
                behavior.isDraggable = true
                
                // Allow bottom sheet to expand to full height when dragged up
                it.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT

                // Pin the floating buttons to the bottom of the visible sheet area
                behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        updateButtonTranslation(bottomSheet)
                    }

                    override fun onSlide(bottomSheet: View, slideOffset: Float) {
                        updateButtonTranslation(bottomSheet)
                    }
                })

                it.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    updateButtonTranslation(it)
                }

                // Update layout translation right before the view is drawn to avoid initial layout positioning delay
                it.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        updateButtonTranslation(it)
                        return true
                    }
                })
            }
        }
        return dialog
    }

    private fun handleBackPress() {
        if (layoutStep2.visibility == View.VISIBLE) {
            showStep1b()
        } else if (selectedCategory != null) {
            showCategorySelectionStep()
        } else {
            dismissAllowingStateLoss()
        }
    }

    private fun updateButtonTranslation(bottomSheet: View) {
        if (::layoutBottomButtons.isInitialized) {
            val targetTranslation = -bottomSheet.top.toFloat()
            if (layoutBottomButtons.translationY != targetTranslation) {
                layoutBottomButtons.translationY = targetTranslation
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_on_duty_form, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        // Initialize Top Header Views
        tvSheetTitle = view.findViewById(R.id.tvSheetTitle)
        tvSheetSubtitle = view.findViewById(R.id.tvSheetSubtitle)

        // Initialize Step 1 Views
        layoutStep1 = view.findViewById(R.id.layoutStep1)
        layoutCategoryCards = view.findViewById(R.id.layoutCategoryCards)
        cardProjects = view.findViewById(R.id.cardProjects)
        cardVendors = view.findViewById(R.id.cardVendors)
        cardOthers = view.findViewById(R.id.cardOthers)
        
        layoutSearchContainer = view.findViewById(R.id.layoutSearchContainer)
        etSearchQuery = view.findViewById(R.id.etSearchQuery)
        rvPickerList = view.findViewById(R.id.rvPickerList)
        layoutRemarksContainer = view.findViewById(R.id.layoutRemarksContainer)
        etOnDutyRemarks = view.findViewById(R.id.etOnDutyRemarks)
        btnOnDutyNext = view.findViewById(R.id.btnOnDutyNext)

        // Initialize Step 2 Views
        layoutStep2 = view.findViewById(R.id.layoutStep2)
        cardOwnTwoWheeler = view.findViewById(R.id.cardOwnTwoWheeler)
        cardOwnFourWheeler = view.findViewById(R.id.cardOwnFourWheeler)
        cardOfficeTwoWheeler = view.findViewById(R.id.cardOfficeTwoWheeler)
        cardOfficeFourWheeler = view.findViewById(R.id.cardOfficeFourWheeler)
        cardOfficePublicTransport = view.findViewById(R.id.cardOfficePublicTransport)

        tvOwnTwoWheeler = view.findViewById(R.id.tvOwnTwoWheeler)
        tvOwnFourWheeler = view.findViewById(R.id.tvOwnFourWheeler)
        tvOfficeTwoWheeler = view.findViewById(R.id.tvOfficeTwoWheeler)
        tvOfficeFourWheeler = view.findViewById(R.id.tvOfficeFourWheeler)
        tvOfficePublicTransport = view.findViewById(R.id.tvOfficePublicTransport)

        imgOwnTwoWheelerAnim = view.findViewById(R.id.imgOwnTwoWheelerAnim)
        imgOwnFourWheelerAnim = view.findViewById(R.id.imgOwnFourWheelerAnim)
        imgOfficeTwoWheelerAnim = view.findViewById(R.id.imgOfficeTwoWheelerAnim)
        imgOfficeFourWheelerAnim = view.findViewById(R.id.imgOfficeFourWheelerAnim)
        imgOfficePublicTransportAnim = view.findViewById(R.id.imgOfficePublicTransportAnim)

        btnOnDutySubmit = view.findViewById(R.id.btnOnDutySubmit)
        layoutBottomButtons = view.findViewById(R.id.layoutBottomButtons)

        // Setup RecyclerView
        rvPickerList.layoutManager = LinearLayoutManager(requireContext())
        listAdapter = ListAdapter()
        rvPickerList.adapter = listAdapter

        // Category Cards Listeners
        cardProjects.setOnClickListener { selectCategory("Projects") }
        cardVendors.setOnClickListener { selectCategory("Vendors") }
        cardOthers.setOnClickListener { selectCategory("Others") }

        // Search text changes
        etSearchQuery.addTextChangedListener { text ->
            filterList(text?.toString().orEmpty())
        }

        // Remarks text changes
        etOnDutyRemarks.addTextChangedListener {
            validateStep1()
        }

        // Next Button Click
        btnOnDutyNext.setOnClickListener {
            if (btnOnDutyNext.isEnabled) {
                showStep2()
            }
        }

        // Step 2 Listeners with custom slide animations
        cardOwnTwoWheeler.setOnClickListener {
            animateVehicleSelection(
                cardOwnTwoWheeler,
                imgOwnTwoWheelerAnim,
                tvOwnTwoWheeler,
                "Own Vehicle",
                "2 Wheeler"
            )
        }
        cardOwnFourWheeler.setOnClickListener {
            animateVehicleSelection(
                cardOwnFourWheeler,
                imgOwnFourWheelerAnim,
                tvOwnFourWheeler,
                "Own Vehicle",
                "4 Wheeler"
            )
        }
        cardOfficeTwoWheeler.setOnClickListener {
            animateVehicleSelection(
                cardOfficeTwoWheeler,
                imgOfficeTwoWheelerAnim,
                tvOfficeTwoWheeler,
                "Office Vehicle",
                "2 Wheeler"
            )
        }
        cardOfficeFourWheeler.setOnClickListener {
            animateVehicleSelection(
                cardOfficeFourWheeler,
                imgOfficeFourWheelerAnim,
                tvOfficeFourWheeler,
                "Office Vehicle",
                "4 Wheeler"
            )
        }
        cardOfficePublicTransport.setOnClickListener {
            animateVehicleSelection(
                cardOfficePublicTransport,
                imgOfficePublicTransportAnim,
                tvOfficePublicTransport,
                "Public Vehicle",
                "Public Transport"
            )
        }

        btnOnDutySubmit.setOnClickListener {
            if (btnOnDutySubmit.isEnabled) {
                submitOnDuty()
            }
        }

        // Fetch master lists in background.
        fetchProjects()
        fetchVendors()
    }

    private fun selectCategory(category: String) {
        selectedCategory = category
        selectedTargetId = null
        selectedTargetName = null

        // Update card backgrounds (just for state sanity, though they will be hidden)
        cardProjects.setBackgroundResource(
            if (category == "Projects") R.drawable.bg_on_duty_card_selected
            else R.drawable.bg_on_duty_card_normal
        )
        cardVendors.setBackgroundResource(
            if (category == "Vendors") R.drawable.bg_on_duty_card_selected
            else R.drawable.bg_on_duty_card_normal
        )
        cardOthers.setBackgroundResource(
            if (category == "Others") R.drawable.bg_on_duty_card_selected
            else R.drawable.bg_on_duty_card_normal
        )

        // Hide Category Selection cards, and show Next button
        layoutCategoryCards.visibility = View.GONE
        btnOnDutyNext.visibility = View.VISIBLE
        btnOnDutySubmit.visibility = View.GONE

        // Configure Title, Subtitle, and input visibility based on Category
        when (category) {
            "Projects" -> {
                tvSheetTitle.text = "Select Project"
                tvSheetSubtitle.text = "Select a Project Which ever you want"
                layoutSearchContainer.visibility = View.VISIBLE
                layoutRemarksContainer.visibility = View.GONE
                etSearchQuery.hint = "Search Projects"
                etSearchQuery.setText("")
                loadProjectsToList()
            }
            "Vendors" -> {
                tvSheetTitle.text = "Select Vendor"
                tvSheetSubtitle.text = "Select a Vendor Which ever you want"
                layoutSearchContainer.visibility = View.VISIBLE
                layoutRemarksContainer.visibility = View.GONE
                etSearchQuery.hint = "Search Vendors"
                etSearchQuery.setText("")
                loadVendorsToList()
            }
            "Others" -> {
                tvSheetTitle.text = "Enter Remarks"
                tvSheetSubtitle.text = "Provide remarks for on-duty"
                layoutSearchContainer.visibility = View.GONE
                layoutRemarksContainer.visibility = View.VISIBLE
                etOnDutyRemarks.setText("")
            }
        }
        validateStep1()
    }

    private fun fetchProjects() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMyProjects(session.bearerToken)
                if (resp.success) {
                    allProjects = resp.projects
                }
            } catch (_: Exception) {
                // Fail silently, use empty list
            }
        }
    }

    private fun fetchVendors() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getVendors(session.bearerToken)
                if (resp.success) {
                    allVendors = resp.vendors
                        .filter { it.status?.equals("inactive", true) != true }
                        .map { VendorItem(it.id, it.name, it.address) }
                    // If the user is already viewing the Vendors list,
                    // re-render now that data has arrived.
                    if (selectedCategory == "Vendors") {
                        loadVendorsToList()
                    }
                }
            } catch (_: Exception) {
                // Silent — list stays empty, search still works against
                // whatever's already loaded.
            }
        }
    }

    private fun loadProjectsToList() {
        displayedItems.clear()
        allProjects.forEach {
            displayedItems.add(DisplayItem(it.id, it.name.orEmpty()))
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun loadVendorsToList() {
        displayedItems.clear()
        allVendors.forEach {
            displayedItems.add(DisplayItem(it.id, it.name))
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun filterList(query: String) {
        val clean = query.trim().lowercase(Locale.getDefault())
        displayedItems.clear()

        if (selectedCategory == "Projects") {
            val filtered = if (clean.isEmpty()) {
                allProjects
            } else {
                allProjects.filter { (it.name ?: "").lowercase(Locale.getDefault()).contains(clean) }
            }
            filtered.forEach {
                displayedItems.add(DisplayItem(it.id, it.name.orEmpty()))
            }
        } else if (selectedCategory == "Vendors") {
            val filtered = if (clean.isEmpty()) {
                allVendors
            } else {
                allVendors.filter { it.name.lowercase(Locale.getDefault()).contains(clean) }
            }
            filtered.forEach {
                displayedItems.add(DisplayItem(it.id, it.name))
            }
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun validateStep1() {
        val isEnabled = when (selectedCategory) {
            "Projects", "Vendors" -> {
                selectedTargetId != null && selectedTargetName != null
            }
            "Others" -> {
                etOnDutyRemarks.text.toString().trim().isNotEmpty()
            }
            else -> false
        }

        btnOnDutyNext.isEnabled = isEnabled
        btnOnDutyNext.setBackgroundResource(
            if (isEnabled) R.drawable.bg_attendance_btn_primary
            else R.drawable.bg_on_duty_btn_disabled
        )
    }

    private fun showStep2() {
        selectedVehicleOwnership = null
        selectedVehicleType = null
        isAnimatingSelection = false
        layoutStep1.visibility = View.GONE
        layoutStep2.visibility = View.VISIBLE
        btnOnDutyNext.visibility = View.GONE
        btnOnDutySubmit.visibility = View.VISIBLE
        tvSheetTitle.text = "Select Vehicle"
        tvSheetSubtitle.text = "Which Vehicle You are going to use"
        resetAllStep2Cards()
        setCardsClickable(true)
        validateStep2()
    }

    private fun showStep1b() {
        layoutStep2.visibility = View.GONE
        layoutStep1.visibility = View.VISIBLE
        btnOnDutyNext.visibility = View.VISIBLE
        btnOnDutySubmit.visibility = View.GONE
        
        // Restore title and subtitle based on Category
        when (selectedCategory) {
            "Projects" -> {
                tvSheetTitle.text = "Select Project"
                tvSheetSubtitle.text = "Select a Project Which ever you want"
            }
            "Vendors" -> {
                tvSheetTitle.text = "Select Vendor"
                tvSheetSubtitle.text = "Select a Vendor Which ever you want"
            }
            "Others" -> {
                tvSheetTitle.text = "Enter Remarks"
                tvSheetSubtitle.text = "Provide remarks for on-duty"
            }
        }
        validateStep1()
    }

    private fun showCategorySelectionStep() {
        selectedCategory = null
        selectedTargetId = null
        selectedTargetName = null

        cardProjects.setBackgroundResource(R.drawable.bg_on_duty_card_normal)
        cardVendors.setBackgroundResource(R.drawable.bg_on_duty_card_normal)
        cardOthers.setBackgroundResource(R.drawable.bg_on_duty_card_normal)

        layoutCategoryCards.visibility = View.VISIBLE
        btnOnDutyNext.visibility = View.GONE
        btnOnDutySubmit.visibility = View.GONE
        layoutSearchContainer.visibility = View.GONE
        layoutRemarksContainer.visibility = View.GONE

        tvSheetTitle.text = "On Duty Details"
        tvSheetSubtitle.text = "Select a category to start on duty flow"
    }

    private fun animateVehicleSelection(
        selectedCard: View,
        animatingIcon: ImageView,
        textView: TextView,
        ownership: String,
        vehicleType: String
    ) {
        // Prevent simultaneous or rapid clicks during animation
        if (isAnimatingSelection) return

        // If already selected, do not allow unselection or re-triggering animation
        if (selectedVehicleOwnership == ownership && selectedVehicleType == vehicleType) return
        if (animatingIcon.visibility == View.VISIBLE) return

        isAnimatingSelection = true
        selectedVehicleOwnership = ownership
        selectedVehicleType = vehicleType

        // Disable all cards during animation to prevent concurrent selection clicks
        setCardsClickable(false)

        // 1. Reset all cards to normal unselected state
        resetAllStep2Cards()

        // 2. Prepare the animating icon
        animatingIcon.visibility = View.VISIBLE
        animatingIcon.translationX = -60f // Start slightly off-screen inside the card
        animatingIcon.alpha = 1f

        // Fade the text slightly during the slide animation
        textView.animate().alpha(0.2f).setDuration(150).start()

        // 3. Animate the icon from left to right across the card width
        val distance = if (selectedCard.width > 0) selectedCard.width.toFloat() else 350f

        animatingIcon.animate()
            .translationX(distance)
            .alpha(0.1f) // Fade out as it exits the right edge
            .setDuration(700) // 700ms slide transition
            .withEndAction {
                animatingIcon.visibility = View.INVISIBLE
                animatingIcon.translationX = 0f
                animatingIcon.alpha = 1f
                textView.alpha = 1f

                // 4. Update the card to selected styling
                selectedCard.setBackgroundResource(R.drawable.bg_on_duty_card_selected_blue)
                textView.setTextColor(Color.WHITE)

                // Re-enable all cards after animation completes
                setCardsClickable(true)

                // 5. Update validation state
                validateStep2()

                isAnimatingSelection = false
            }
            .start()
    }

    private fun resetAllStep2Cards() {
        cardOwnTwoWheeler.setBackgroundResource(R.drawable.bg_on_duty_card_normal)
        cardOwnFourWheeler.setBackgroundResource(R.drawable.bg_on_duty_card_normal)
        cardOfficeTwoWheeler.setBackgroundResource(R.drawable.bg_on_duty_card_normal)
        cardOfficeFourWheeler.setBackgroundResource(R.drawable.bg_on_duty_card_normal)
        cardOfficePublicTransport.setBackgroundResource(R.drawable.bg_on_duty_card_normal)

        tvOwnTwoWheeler.setTextColor(Color.parseColor("#101828"))
        tvOwnFourWheeler.setTextColor(Color.parseColor("#101828"))
        tvOfficeTwoWheeler.setTextColor(Color.parseColor("#101828"))
        tvOfficeFourWheeler.setTextColor(Color.parseColor("#101828"))
        tvOfficePublicTransport.setTextColor(Color.parseColor("#101828"))

        tvOwnTwoWheeler.alpha = 1f
        tvOwnFourWheeler.alpha = 1f
        tvOfficeTwoWheeler.alpha = 1f
        tvOfficeFourWheeler.alpha = 1f
        tvOfficePublicTransport.alpha = 1f

        imgOwnTwoWheelerAnim.visibility = View.INVISIBLE
        imgOwnFourWheelerAnim.visibility = View.INVISIBLE
        imgOfficeTwoWheelerAnim.visibility = View.INVISIBLE
        imgOfficeFourWheelerAnim.visibility = View.INVISIBLE
        imgOfficePublicTransportAnim.visibility = View.INVISIBLE
    }

    private fun setCardsClickable(clickable: Boolean) {
        if (::cardOwnTwoWheeler.isInitialized) {
            cardOwnTwoWheeler.isClickable = clickable
            cardOwnFourWheeler.isClickable = clickable
            cardOfficeTwoWheeler.isClickable = clickable
            cardOfficeFourWheeler.isClickable = clickable
            cardOfficePublicTransport.isClickable = clickable

            cardOwnTwoWheeler.isFocusable = clickable
            cardOwnFourWheeler.isFocusable = clickable
            cardOfficeTwoWheeler.isFocusable = clickable
            cardOfficeFourWheeler.isFocusable = clickable
            cardOfficePublicTransport.isFocusable = clickable
        }
    }

    private fun validateStep2() {
        val isEnabled = selectedVehicleOwnership != null && selectedVehicleType != null
        btnOnDutySubmit.isEnabled = isEnabled
        btnOnDutySubmit.setBackgroundResource(
            if (isEnabled) R.drawable.bg_attendance_btn_primary
            else R.drawable.bg_on_duty_btn_disabled
        )
    }

    private fun submitOnDuty() {
        val remarks = etOnDutyRemarks.text.toString().trim()
        val targetName = if (selectedCategory == "Others") remarks else selectedTargetName
        val targetAddress = allVendors
            .firstOrNull { it.id == selectedTargetId }?.address

        // Local state first — instant UI response, the Dynamic Island
        // pill and Complete On Duty button can render immediately.
        session.isOnDuty = true
        session.onDutyType = selectedCategory
        session.onDutyTargetName = targetName
        session.onDutyTargetId = selectedTargetId
        session.onDutyVehicleOwnership = selectedVehicleOwnership
        session.onDutyVehicleType = selectedVehicleType
        // Clear any stale tripId from a previous session before the new
        // start call returns.
        session.onDutyTripId = null

        val resultBundle = Bundle().apply {
            putBoolean(KEY_STARTED, true)
        }
        setFragmentResult(RESULT_KEY, resultBundle)
        Toast.makeText(requireContext(), "On Duty started.", Toast.LENGTH_SHORT).show()
        dismissAllowingStateLoss()

        // Backend sync — fire after dismissing so the bottom sheet UX
        // doesn't stall on a network round-trip. The activity owns the
        // coroutine so the call survives this fragment's destruction.
        val appCtx = requireContext().applicationContext
        val sessionRef = session
        val category = selectedCategory
        val targetIdRef = selectedTargetId
        val vehicleOwnership = selectedVehicleOwnership
        val vehicleType = selectedVehicleType
        requireActivity().lifecycleScope.launch {
            try {
                val loc = fetchCurrentLocationOrNull(appCtx)
                val resp = api.startOnDutyTrip(
                    sessionRef.bearerToken,
                    com.manjugroups.m_connect.network.StartOnDutyTripRequest(
                        lat = loc?.latitude,
                        lng = loc?.longitude,
                        address = null,
                        category = category,
                        targetId = targetIdRef,
                        targetName = targetName,
                        targetAddress = targetAddress,
                        vehicleOwnership = vehicleOwnership,
                        vehicleType = vehicleType,
                    ),
                )
                if (resp.success && !resp.tripId.isNullOrBlank()) {
                    sessionRef.onDutyTripId = resp.tripId
                }
            } catch (_: Exception) {
                // Network down or server hiccup — the user is still
                // marked on-duty locally; the trip will be visible to
                // HR once we add retry-on-resume. Silent for now.
            }
        }
    }

    /** Best-effort current location for the on-duty trip start. Uses
     *  the same Fused client pattern as the rest of the attendance
     *  flow. Returns null if permission is missing or no fix arrives. */
    private suspend fun fetchCurrentLocationOrNull(
        ctx: android.content.Context,
    ): android.location.Location? {
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        return try {
            val client = com.google.android.gms.location
                .LocationServices.getFusedLocationProviderClient(ctx)
            val token = com.google.android.gms.tasks.CancellationTokenSource().token
            client.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                token,
            ).await() ?: client.lastLocation.await()
        } catch (_: Exception) { null }
    }

    private inner class ListAdapter : RecyclerView.Adapter<ListAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_on_duty_list_row, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = displayedItems.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(displayedItems[position])
        }

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.tvRowName)
            private val check: ImageView = itemView.findViewById(R.id.imgRowCheck)

            fun bind(item: DisplayItem) {
                name.text = item.name
                val isSelected = item.id == selectedTargetId
                
                // Set item background based on selection
                itemView.setBackgroundResource(
                    if (isSelected) R.drawable.bg_on_duty_card_selected
                    else R.drawable.bg_on_duty_card_normal
                )

                // Set check image source based on selection
                check.setImageResource(
                    if (isSelected) R.drawable.ic_leave_radio_selected
                    else R.drawable.ic_leave_radio_unselected
                )

                itemView.setOnClickListener {
                    selectedTargetId = item.id
                    selectedTargetName = item.name
                    notifyDataSetChanged()
                    validateStep1()
                }
            }
        }
    }

    companion object {
        const val RESULT_KEY = "OnDutyFormResult"
        const val KEY_STARTED = "started"

        fun newInstance(): OnDutyFormBottomSheet = OnDutyFormBottomSheet()
    }
}
