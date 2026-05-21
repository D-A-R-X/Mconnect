package com.manjugroups.m_connect.ui.home

import android.app.DatePickerDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.ConvertCpVisitToSiteVisitRequest
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.MarkClientMetRequest
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.network.ProposedSiteVisit
import com.manjugroups.m_connect.network.SetOutcomeRequest
import com.manjugroups.m_connect.network.SiteVisitAttendeeRequest
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Outcome Information bottom sheet — full Booking flow (7 sub-tabs).
 *
 * State machine:
 *   - Top tab (Outcome): BOOKING · SITE_VISIT · POSTPONE · NOT_INTERESTED
 *   - Booking sub-tab (BookingSub): CLIENT · PROFESSIONAL · OFFICE · BOOKING
 *                                   · CHARGES · PAYMENT · STAFF
 *   - Within CLIENT sub-tab: BookingStep FIND_MOBILE → CLIENT_FORM
 *
 * Submit (Save Booking) collects every captured field across all sub-tabs
 * and writes them to the visit's setOutcome.notes payload (single string
 * the backend already accepts), with outcome="converted_to_booking". When
 * a dedicated booking-create endpoint lands, persistBooking() flips to it.
 */
class CompleteCpVisitBottomSheet : BottomSheetDialogFragment() {

    private val geoApi = GeoTrackApi.create()
    private val api = ApiService.create()
    private lateinit var session: SessionManager

    // ---- Enums ----------------------------------------------------------
    private enum class Outcome { BOOKING, SITE_VISIT, POSTPONE, NOT_INTERESTED }
    private enum class BookingSub {
        CLIENT, PROFESSIONAL, OFFICE, BOOKING, CHARGES, PAYMENT, STAFF
    }
    private enum class BookingStep { FIND_MOBILE, CLIENT_FORM }
    private enum class YesNo { YES, NO }
    private enum class SaveAs { DRAFT, CONFIRMED }

    private var activeOutcome: Outcome = Outcome.BOOKING
    private var bookingSub: BookingSub = BookingSub.CLIENT
    private var bookingStep: BookingStep = BookingStep.FIND_MOBILE

    // Form state for radio/checkbox rows
    private var bookIsAgainstVisit: YesNo = YesNo.YES
    private var bookDuplicate: Boolean = true
    private var payGstApplicable: Boolean = true
    private var payOtherApplicable: Boolean = true
    private var payFlexi: Boolean = true
    private var staffSaveAs: SaveAs = SaveAs.DRAFT

    // ---- Top tab views --------------------------------------------------
    private data class OutcomeTab(
        val cell: View?,
        val circle: View?,
        val label: TextView?,
        val indicator: View?,
    )
    private var tabBooking = OutcomeTab(null, null, null, null)
    private var tabSiteVisit = OutcomeTab(null, null, null, null)
    private var tabPostpone = OutcomeTab(null, null, null, null)
    private var tabNotInterested = OutcomeTab(null, null, null, null)

    // SV-via-CP locked mode. When the CP visit carries proposedSiteVisit,
    // the sheet locks to Site Visit only, fades + disables every other
    // tab, renders form fields read-only, and swaps Save for a Reject /
    // Confirm pair.
    private var lockedFromProposedSv: Boolean = false
    private var cpLockedFooter: View? = null
    private var btnCpLockedReject: TextView? = null
    private var btnCpLockedConfirm: TextView? = null

    // ---- Sub-tab views --------------------------------------------------
    private var subTabsScroll: HorizontalScrollView? = null
    private var subTabClient: TextView? = null
    private var subTabProfessional: TextView? = null
    private var subTabOffice: TextView? = null
    private var subTabBooking: TextView? = null
    private var subTabCharges: TextView? = null
    private var subTabPayment: TextView? = null
    private var subTabStaff: TextView? = null

    // ---- Body view roots ------------------------------------------------
    private var bodyComingSoon: View? = null
    private var bodyFindClient: View? = null
    private var bodyClientForm: View? = null
    private var bodyProfessional: View? = null
    private var bodyOffice: View? = null
    private var bodyBooking: View? = null
    private var bodyCharges: View? = null
    private var bodyPayment: View? = null
    private var bodyStaff: View? = null

    // ---- Field refs (lots — keep them grouped) -------------------------
    // Client form (Frame 3) — many already declared.
    private var etClientMobile: EditText? = null
    private var tvFormPhone: TextView? = null
    private var tvFormTitle: TextView? = null
    private var etFormName: EditText? = null
    private var etFormFather: EditText? = null
    private var tvFormDob: TextView? = null
    private var tvFormAnniversary: TextView? = null
    private var etFormAltNumber: EditText? = null
    private var etFormWhatsApp: EditText? = null
    private var etFormEmail: EditText? = null
    private var tvFormNationality: TextView? = null
    private var etFormHomeAddress: EditText? = null
    private var etFormPincode: EditText? = null
    private var etFormState: EditText? = null
    private var etFormDistrict: EditText? = null
    private var etFormLocation: EditText? = null

    // Professional
    private var tvProfProfession: TextView? = null
    private var etProfDesignation: EditText? = null
    private var etProfIncome: EditText? = null

    // Office
    private var etOfficeName: EditText? = null
    private var etOfficeEmail: EditText? = null
    private var etOfficeMobile: EditText? = null
    private var etOfficePhone: EditText? = null
    private var etOfficeAddress: EditText? = null

    // Booking
    private var tvBookType: TextView? = null
    private var tvBookSource: TextView? = null
    private var etBookCef: EditText? = null
    private var tvBookDate: TextView? = null
    private var tvBookProject: TextView? = null
    private var tvBookPlot: TextView? = null
    private var tvBookProperty: TextView? = null
    private var tvBookMode: TextView? = null
    private var ivBookVisitYes: ImageView? = null
    private var ivBookVisitNo: ImageView? = null
    private var ivBookDuplicate: ImageView? = null

    // Charges
    private var etChargeBookingCost: EditText? = null
    private var etChargeGuidelineValue: EditText? = null
    private var etChargeSpecialConsideration: EditText? = null
    private var etChargeDiscountApprovedBy: EditText? = null
    private var etChargeScReason: EditText? = null
    private var etChargeScValidity: EditText? = null
    private var etChargePromoOffers: EditText? = null
    private var tvChargePromoTnc: TextView? = null
    private var etChargePromoValue: EditText? = null
    private var etChargeOfferValidity: EditText? = null

    // Payment
    private var etPayRegCharges: EditText? = null
    private var etPayGstAmount: EditText? = null
    private var ivPayGstApplicable: ImageView? = null
    private var etPayDocCharges: EditText? = null
    private var etPayOtherCharges: EditText? = null
    private var ivPayOtherApplicable: ImageView? = null
    private var etPayAdvanceAmount: EditText? = null
    private var tvPayPaymentMode: TextView? = null
    private var ivPayFlexi: ImageView? = null
    private var etPayAllotDue: EditText? = null
    private var tvPayAllotDate: TextView? = null
    private var etPay2Mode: EditText? = null
    private var tvPay2Date: TextView? = null
    private var etPay3Mode: EditText? = null
    private var tvPay3Date: TextView? = null
    private var etPay4Mode: EditText? = null
    private var tvPay4Date: TextView? = null
    private var tvPayPrefReg: TextView? = null

    // Staff
    private var tvStaffAvp: TextView? = null
    private var tvStaffGm: TextView? = null
    private var tvStaffSm: TextView? = null
    private var tvStaffBdo: TextView? = null
    private var tvStaffTelecaller: TextView? = null
    private var etStaffAadhar: EditText? = null
    private var etStaffPancard: EditText? = null
    private var etStaffRefName1: EditText? = null
    private var etStaffRefMobile1: EditText? = null
    private var etStaffRefProf1: EditText? = null
    private var etStaffRefName2: EditText? = null
    private var etStaffRefMobile2: EditText? = null
    private var etStaffRefProf2: EditText? = null
    private var tvStaffDocPrep: TextView? = null
    private var ivStaffSaveDraft: ImageView? = null
    private var ivStaffSaveConfirmed: ImageView? = null

    // Site Visit body
    private var bodySiteVisit: View? = null
    private var tvSvProject: TextView? = null
    private var tvSvDate: TextView? = null
    private var tvSvTime: TextView? = null
    private var btnSvTravelOwn: TextView? = null
    private var btnSvTravelCab: TextView? = null
    private var etSvPickupAddress: EditText? = null
    private var tvSvIncharge: TextView? = null
    private var tvSvHod: TextView? = null
    private var tvSvAvp: TextView? = null
    private var tvSvGm: TextView? = null
    private var tvSvSm: TextView? = null
    private var etSvVisitorCount: EditText? = null
    private var siteVisitorRows: LinearLayout? = null

    // Postpone body
    private var bodyPostpone: View? = null
    private var etPostBudget: EditText? = null
    private var etPostTiming: EditText? = null
    private var etPostProject: EditText? = null
    private var etPostOther: EditText? = null
    private var tvPostDateTime: TextView? = null

    // Not Interested body
    private var bodyNotInterested: View? = null
    private var etNiBudget: EditText? = null
    private var etNiTiming: EditText? = null
    private var etNiProject: EditText? = null
    private var etNiOther: EditText? = null

    // Site Visit selections + cache
    private var svProject: MarketingProject? = null
    private var svIncharge: StaffData? = null
    private var svHod: StaffData? = null
    private var svAvp: StaffData? = null
    private var svGm: StaffData? = null
    private var svSm: StaffData? = null
    private var svTravelMode: String? = null   // "own_vehicle" or "cab"
    private var svProjectCache: List<MarketingProject> = emptyList()
    private var svStaffCache: List<StaffData> = emptyList()

    private var btnEdit: TextView? = null
    private var btnSubmit: TextView? = null
    private var tvError: TextView? = null

    // ---- Lifecycle ------------------------------------------------------
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        // Resize the dialog window when the IME comes up so the input
        // field and the Next button stay above the keyboard instead of
        // disappearing under it. The default for BottomSheetDialog is
        // ADJUST_NOTHING (edge-to-edge), which is exactly what hid the
        // bottom of the form before. Opting back into ADJUST_RESIZE +
        // decor-fits-system-windows brings classic IME behaviour back.
        dialog.window?.let { window ->
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)
        }
        dialog.setOnShowListener { di ->
            val sheet = (di as BottomSheetDialog)
                .findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                val behavior = BottomSheetBehavior.from(it)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                isCancelable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_cp_visit_complete, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        session = SessionManager(requireContext())

        bindTopTabs(view)
        bindSubTabs(view)
        bindBodies(view)
        bindClientFormFields(view)
        bindProfessionalFields(view)
        bindOfficeFields(view)
        bindBookingFields(view)
        bindChargesFields(view)
        bindPaymentFields(view)
        bindStaffFields(view)
        bindSiteVisitFields(view)
        bindPostponeFields(view)
        bindNotInterestedFields(view)

        btnEdit = view.findViewById(R.id.btnOutcomeEdit)
        btnSubmit = view.findViewById(R.id.btnCpSubmit)
        tvError = view.findViewById(R.id.tvCpError)
        cpLockedFooter = view.findViewById(R.id.cpLockedFooter)
        btnCpLockedReject = view.findViewById(R.id.btnCpLockedReject)
        btnCpLockedConfirm = view.findViewById(R.id.btnCpLockedConfirm)

        // Pre-seed from args if caller passed an outcome.
        arguments?.getString(ARG_CP_OUTCOME)?.takeIf { it.isNotBlank() }
            ?.let { ext -> outcomeFromArg(ext)?.let { activeOutcome = it } }

        // If TripNavigationFragment already detected this is an SV-fix
        // CP, switch to Site Visit + fade the other tabs BEFORE the
        // first renderState() call. Without this the user briefly sees
        // the Booking tab default for one frame while the async detect
        // resolves, then it snaps to locked Site Visit — visible as a
        // flicker. Applying the hint synchronously here makes the first
        // paint show locked Site Visit straight away. detectAndApply
        // below still runs and refines the pre-fill values + final
        // verification.
        val isSvFixedHint = arguments?.getBoolean(ARG_IS_SV_FIXED_HINT, false) == true
        if (isSvFixedHint) {
            activeOutcome = Outcome.SITE_VISIT
        }

        wireInteractions()
        renderState()

        if (isSvFixedHint) {
            // Pale-out the non-SV tabs immediately so the first paint
            // matches the locked layout. detectAndApplyLockedSvMode
            // below adds the read-only / Reject-Confirm footer once
            // the server-side payload arrives.
            listOf(tabBooking, tabPostpone, tabNotInterested).forEach { tab ->
                tab.cell?.isClickable = false
                tab.cell?.alpha = 0.35f
            }
        }

        // Check whether the CP visit was pre-fixed by the telecaller. If
        // so, lock the sheet to Site Visit and surface Reject / Confirm.
        detectAndApplyLockedSvMode()
    }

    // ---- View binding helpers ------------------------------------------
    private fun bindTopTabs(view: View) {
        tabBooking = OutcomeTab(
            cell = view.findViewById(R.id.outcomeTopBooking),
            circle = view.findViewById(R.id.circleOutcomeTopBooking),
            label = view.findViewById(R.id.tvOutcomeTopBooking),
            indicator = view.findViewById(R.id.indicatorOutcomeTopBooking),
        )
        tabSiteVisit = OutcomeTab(
            cell = view.findViewById(R.id.outcomeTopSiteVisit),
            circle = view.findViewById(R.id.circleOutcomeTopSiteVisit),
            label = view.findViewById(R.id.tvOutcomeTopSiteVisit),
            indicator = view.findViewById(R.id.indicatorOutcomeTopSiteVisit),
        )
        tabPostpone = OutcomeTab(
            cell = view.findViewById(R.id.outcomeTopPostpone),
            circle = view.findViewById(R.id.circleOutcomeTopPostpone),
            label = view.findViewById(R.id.tvOutcomeTopPostpone),
            indicator = view.findViewById(R.id.indicatorOutcomeTopPostpone),
        )
        tabNotInterested = OutcomeTab(
            cell = view.findViewById(R.id.outcomeTopNotInterested),
            circle = view.findViewById(R.id.circleOutcomeTopNotInterested),
            label = view.findViewById(R.id.tvOutcomeTopNotInterested),
            indicator = view.findViewById(R.id.indicatorOutcomeTopNotInterested),
        )
    }

    private fun bindSubTabs(view: View) {
        subTabsScroll = view.findViewById(R.id.bookingSubTabsScroll)
        subTabClient = view.findViewById(R.id.subTabClientDetails)
        subTabProfessional = view.findViewById(R.id.subTabProfessionalDetails)
        subTabOffice = view.findViewById(R.id.subTabOfficeDetails)
        subTabBooking = view.findViewById(R.id.subTabBookingDetails)
        subTabCharges = view.findViewById(R.id.subTabChargesDetails)
        subTabPayment = view.findViewById(R.id.subTabPaymentDetails)
        subTabStaff = view.findViewById(R.id.subTabStaffDetails)
    }

    private fun bindBodies(view: View) {
        bodyComingSoon = view.findViewById(R.id.bodyComingSoon)
        bodyFindClient = view.findViewById(R.id.bodyBookingFindClient)
        bodyClientForm = view.findViewById(R.id.bodyBookingClientForm)
        bodyProfessional = view.findViewById(R.id.bodyBookingProfessional)
        bodyOffice = view.findViewById(R.id.bodyBookingOffice)
        bodyBooking = view.findViewById(R.id.bodyBookingBooking)
        bodyCharges = view.findViewById(R.id.bodyBookingCharges)
        bodyPayment = view.findViewById(R.id.bodyBookingPayment)
        bodyStaff = view.findViewById(R.id.bodyBookingStaff)
    }

    private fun bindClientFormFields(view: View) {
        etClientMobile = view.findViewById(R.id.etOutcomeClientMobile)
        tvFormPhone = view.findViewById(R.id.tvFormClientPhone)
        tvFormTitle = view.findViewById(R.id.tvFormClientTitle)
        etFormName = view.findViewById(R.id.etFormClientName)
        etFormFather = view.findViewById(R.id.etFormFatherName)
        tvFormDob = view.findViewById(R.id.tvFormDob)
        tvFormAnniversary = view.findViewById(R.id.tvFormAnniversary)
        etFormAltNumber = view.findViewById(R.id.etFormAlternateNumber)
        etFormWhatsApp = view.findViewById(R.id.etFormWhatsApp)
        etFormEmail = view.findViewById(R.id.etFormEmail)
        tvFormNationality = view.findViewById(R.id.tvFormNationality)
        etFormHomeAddress = view.findViewById(R.id.etFormHomeAddress)
        etFormPincode = view.findViewById(R.id.etFormPincode)
        etFormState = view.findViewById(R.id.etFormState)
        etFormDistrict = view.findViewById(R.id.etFormDistrict)
        etFormLocation = view.findViewById(R.id.etFormLocation)
    }

    private fun bindProfessionalFields(view: View) {
        tvProfProfession = view.findViewById(R.id.tvProfProfession)
        etProfDesignation = view.findViewById(R.id.etProfDesignation)
        etProfIncome = view.findViewById(R.id.etProfIncome)
    }

    private fun bindOfficeFields(view: View) {
        etOfficeName = view.findViewById(R.id.etOfficeName)
        etOfficeEmail = view.findViewById(R.id.etOfficeEmail)
        etOfficeMobile = view.findViewById(R.id.etOfficeMobile)
        etOfficePhone = view.findViewById(R.id.etOfficePhone)
        etOfficeAddress = view.findViewById(R.id.etOfficeAddress)
    }

    private fun bindBookingFields(view: View) {
        tvBookType = view.findViewById(R.id.tvBookType)
        tvBookSource = view.findViewById(R.id.tvBookSource)
        etBookCef = view.findViewById(R.id.etBookCef)
        tvBookDate = view.findViewById(R.id.tvBookDate)
        tvBookProject = view.findViewById(R.id.tvBookProject)
        tvBookPlot = view.findViewById(R.id.tvBookPlot)
        tvBookProperty = view.findViewById(R.id.tvBookProperty)
        tvBookMode = view.findViewById(R.id.tvBookMode)
        ivBookVisitYes = view.findViewById(R.id.ivBookVisitYes)
        ivBookVisitNo = view.findViewById(R.id.ivBookVisitNo)
        ivBookDuplicate = view.findViewById(R.id.ivBookDuplicate)
    }

    private fun bindChargesFields(view: View) {
        etChargeBookingCost = view.findViewById(R.id.etChargeBookingCost)
        etChargeGuidelineValue = view.findViewById(R.id.etChargeGuidelineValue)
        etChargeSpecialConsideration = view.findViewById(R.id.etChargeSpecialConsideration)
        etChargeDiscountApprovedBy = view.findViewById(R.id.etChargeDiscountApprovedBy)
        etChargeScReason = view.findViewById(R.id.etChargeScReason)
        etChargeScValidity = view.findViewById(R.id.etChargeScValidity)
        etChargePromoOffers = view.findViewById(R.id.etChargePromoOffers)
        tvChargePromoTnc = view.findViewById(R.id.tvChargePromoTnc)
        etChargePromoValue = view.findViewById(R.id.etChargePromoValue)
        etChargeOfferValidity = view.findViewById(R.id.etChargeOfferValidity)
    }

    private fun bindPaymentFields(view: View) {
        etPayRegCharges = view.findViewById(R.id.etPayRegCharges)
        etPayGstAmount = view.findViewById(R.id.etPayGstAmount)
        ivPayGstApplicable = view.findViewById(R.id.ivPayGstApplicable)
        etPayDocCharges = view.findViewById(R.id.etPayDocCharges)
        etPayOtherCharges = view.findViewById(R.id.etPayOtherCharges)
        ivPayOtherApplicable = view.findViewById(R.id.ivPayOtherApplicable)
        etPayAdvanceAmount = view.findViewById(R.id.etPayAdvanceAmount)
        tvPayPaymentMode = view.findViewById(R.id.tvPayPaymentMode)
        ivPayFlexi = view.findViewById(R.id.ivPayFlexi)
        etPayAllotDue = view.findViewById(R.id.etPayAllotDue)
        tvPayAllotDate = view.findViewById(R.id.tvPayAllotDate)
        etPay2Mode = view.findViewById(R.id.etPay2Mode)
        tvPay2Date = view.findViewById(R.id.tvPay2Date)
        etPay3Mode = view.findViewById(R.id.etPay3Mode)
        tvPay3Date = view.findViewById(R.id.tvPay3Date)
        etPay4Mode = view.findViewById(R.id.etPay4Mode)
        tvPay4Date = view.findViewById(R.id.tvPay4Date)
        tvPayPrefReg = view.findViewById(R.id.tvPayPrefReg)
    }

    private fun bindStaffFields(view: View) {
        tvStaffAvp = view.findViewById(R.id.tvStaffAvp)
        tvStaffGm = view.findViewById(R.id.tvStaffGm)
        tvStaffSm = view.findViewById(R.id.tvStaffSm)
        tvStaffBdo = view.findViewById(R.id.tvStaffBdo)
        tvStaffTelecaller = view.findViewById(R.id.tvStaffTelecaller)
        etStaffAadhar = view.findViewById(R.id.etStaffAadhar)
        etStaffPancard = view.findViewById(R.id.etStaffPancard)
        etStaffRefName1 = view.findViewById(R.id.etStaffRefName1)
        etStaffRefMobile1 = view.findViewById(R.id.etStaffRefMobile1)
        etStaffRefProf1 = view.findViewById(R.id.etStaffRefProf1)
        etStaffRefName2 = view.findViewById(R.id.etStaffRefName2)
        etStaffRefMobile2 = view.findViewById(R.id.etStaffRefMobile2)
        etStaffRefProf2 = view.findViewById(R.id.etStaffRefProf2)
        tvStaffDocPrep = view.findViewById(R.id.tvStaffDocPrep)
        ivStaffSaveDraft = view.findViewById(R.id.ivStaffSaveDraft)
        ivStaffSaveConfirmed = view.findViewById(R.id.ivStaffSaveConfirmed)
    }

    private fun bindPostponeFields(view: View) {
        bodyPostpone = view.findViewById(R.id.bodyPostpone)
        etPostBudget = view.findViewById(R.id.etPostBudget)
        etPostTiming = view.findViewById(R.id.etPostTiming)
        etPostProject = view.findViewById(R.id.etPostProject)
        etPostOther = view.findViewById(R.id.etPostOther)
        tvPostDateTime = view.findViewById(R.id.tvPostDateTime)
    }

    private fun bindNotInterestedFields(view: View) {
        bodyNotInterested = view.findViewById(R.id.bodyNotInterested)
        etNiBudget = view.findViewById(R.id.etNiBudget)
        etNiTiming = view.findViewById(R.id.etNiTiming)
        etNiProject = view.findViewById(R.id.etNiProject)
        etNiOther = view.findViewById(R.id.etNiOther)
    }

    private fun bindSiteVisitFields(view: View) {
        bodySiteVisit = view.findViewById(R.id.bodySiteVisit)
        tvSvProject = view.findViewById(R.id.tvSvProject)
        tvSvDate = view.findViewById(R.id.tvSvDate)
        tvSvTime = view.findViewById(R.id.tvSvTime)
        btnSvTravelOwn = view.findViewById(R.id.btnSvTravelOwn)
        btnSvTravelCab = view.findViewById(R.id.btnSvTravelCab)
        etSvPickupAddress = view.findViewById(R.id.etSvPickupAddress)
        tvSvIncharge = view.findViewById(R.id.tvSvIncharge)
        tvSvHod = view.findViewById(R.id.tvSvHod)
        tvSvAvp = view.findViewById(R.id.tvSvAvp)
        tvSvGm = view.findViewById(R.id.tvSvGm)
        tvSvSm = view.findViewById(R.id.tvSvSm)
        etSvVisitorCount = view.findViewById(R.id.etSvVisitorCount)
        siteVisitorRows = view.findViewById(R.id.siteVisitorRows)
    }

    // ---- Wire all interactions -----------------------------------------
    private fun wireInteractions() {
        // Top tabs
        tabBooking.cell?.setOnClickListener { switchOutcome(Outcome.BOOKING) }
        tabSiteVisit.cell?.setOnClickListener { switchOutcome(Outcome.SITE_VISIT) }
        tabPostpone.cell?.setOnClickListener { switchOutcome(Outcome.POSTPONE) }
        tabNotInterested.cell?.setOnClickListener { switchOutcome(Outcome.NOT_INTERESTED) }

        // Sub-tabs are read-only progress indicators driven by the bottom
        // CTA — no click listeners are wired here intentionally. The
        // user advances through Client → Professional → Office → Booking
        // → Charges → Payment → Staff via Next, and the active pill auto-
        // scrolls into view (see renderState).
        listOf(
            subTabClient, subTabProfessional, subTabOffice, subTabBooking,
            subTabCharges, subTabPayment, subTabStaff,
        ).forEach { tab ->
            tab?.isClickable = false
            tab?.isFocusable = false
        }

        // Client-form date / dropdown pickers
        view?.findViewById<View>(R.id.rowFormTitle)?.setOnClickListener {
            picker("Select Title", listOf("Mr", "Mrs", "Ms", "Dr", "Prof")) { tvFormTitle?.text = it }
        }
        view?.findViewById<View>(R.id.rowFormDob)?.setOnClickListener { pickDate(tvFormDob) }
        view?.findViewById<View>(R.id.rowFormAnniversary)?.setOnClickListener { pickDate(tvFormAnniversary) }
        view?.findViewById<View>(R.id.rowFormNationality)?.setOnClickListener {
            picker("Select Nationality", listOf("Indian", "NRI", "Foreign National")) { tvFormNationality?.text = it }
        }

        // Professional dropdown
        view?.findViewById<View>(R.id.rowProfProfession)?.setOnClickListener {
            picker("Select Profession", listOf("Business", "Salaried", "Self-Employed", "Other")) {
                tvProfProfession?.text = it
            }
        }

        // Booking sub-tab pickers
        view?.findViewById<View>(R.id.rowBookType)?.setOnClickListener {
            picker("Select Booking Type", listOf("Direct", "Channel Partner", "Online")) { tvBookType?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookSource)?.setOnClickListener {
            picker("Select Source", listOf("Walk-in", "Referral", "Marketing", "Online")) { tvBookSource?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookDate)?.setOnClickListener { pickDate(tvBookDate) }
        view?.findViewById<View>(R.id.rowBookProject)?.setOnClickListener {
            picker("Select Project", listOf("Project A", "Project B", "Project C")) { tvBookProject?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookPlot)?.setOnClickListener {
            picker("Select Plot", listOf("Plot 101", "Plot 102", "Plot 103")) { tvBookPlot?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookProperty)?.setOnClickListener {
            picker("Select Property Type", listOf("Plot", "Apartment", "Villa")) { tvBookProperty?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookMode)?.setOnClickListener {
            picker("Select Booking Mode", listOf("Cash", "Cheque", "Online Transfer")) { tvBookMode?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookVisitYes)?.setOnClickListener {
            bookIsAgainstVisit = YesNo.YES; refreshBookingRadios()
        }
        view?.findViewById<View>(R.id.rowBookVisitNo)?.setOnClickListener {
            bookIsAgainstVisit = YesNo.NO; refreshBookingRadios()
        }
        view?.findViewById<View>(R.id.rowBookDuplicate)?.setOnClickListener {
            bookDuplicate = !bookDuplicate
            ivBookDuplicate?.setImageResource(
                if (bookDuplicate) R.drawable.ic_outcome_radio_on else R.drawable.ic_outcome_radio_off
            )
        }

        // Charges picker
        view?.findViewById<View>(R.id.rowChargePromoTnc)?.setOnClickListener {
            picker("Select Offers T&C", listOf("Default T&C", "Festive T&C", "Custom T&C")) {
                tvChargePromoTnc?.text = it
            }
        }

        // Payment toggles + pickers
        view?.findViewById<View>(R.id.rowPayGstApplicable)?.setOnClickListener {
            payGstApplicable = !payGstApplicable
            ivPayGstApplicable?.setImageResource(
                if (payGstApplicable) R.drawable.ic_outcome_checkbox_checked
                else R.drawable.ic_outcome_checkbox_empty
            )
        }
        view?.findViewById<View>(R.id.rowPayOtherApplicable)?.setOnClickListener {
            payOtherApplicable = !payOtherApplicable
            ivPayOtherApplicable?.setImageResource(
                if (payOtherApplicable) R.drawable.ic_outcome_checkbox_checked
                else R.drawable.ic_outcome_checkbox_empty
            )
        }
        view?.findViewById<View>(R.id.rowPayPaymentMode)?.setOnClickListener {
            picker("Select Payment Mode", listOf("Lump Sum", "Construction-Linked", "Flexi")) {
                tvPayPaymentMode?.text = it
            }
        }
        view?.findViewById<View>(R.id.rowPayFlexi)?.setOnClickListener {
            payFlexi = !payFlexi
            ivPayFlexi?.setImageResource(
                if (payFlexi) R.drawable.ic_outcome_radio_on else R.drawable.ic_outcome_radio_off
            )
        }
        view?.findViewById<View>(R.id.rowPayAllotDate)?.setOnClickListener { pickDate(tvPayAllotDate) }
        view?.findViewById<View>(R.id.rowPay2Date)?.setOnClickListener { pickDate(tvPay2Date) }
        view?.findViewById<View>(R.id.rowPay3Date)?.setOnClickListener { pickDate(tvPay3Date) }
        view?.findViewById<View>(R.id.rowPay4Date)?.setOnClickListener { pickDate(tvPay4Date) }
        view?.findViewById<View>(R.id.rowPayPrefReg)?.setOnClickListener { pickDate(tvPayPrefReg) }

        // Staff pickers + radio
        view?.findViewById<View>(R.id.rowStaffAvp)?.setOnClickListener {
            picker("Select AVP", listOf("AVP A", "AVP B")) { tvStaffAvp?.text = it }
        }
        view?.findViewById<View>(R.id.rowStaffGm)?.setOnClickListener {
            picker("Select GM", listOf("GM A", "GM B")) { tvStaffGm?.text = it }
        }
        view?.findViewById<View>(R.id.rowStaffSm)?.setOnClickListener {
            picker("Select Senior Manager", listOf("SM A", "SM B")) { tvStaffSm?.text = it }
        }
        view?.findViewById<View>(R.id.rowStaffBdo)?.setOnClickListener {
            picker("Select BDO", listOf("BDO A", "BDO B")) { tvStaffBdo?.text = it }
        }
        view?.findViewById<View>(R.id.rowStaffTelecaller)?.setOnClickListener {
            picker("Select Telecaller", listOf("Telecaller A", "Telecaller B")) { tvStaffTelecaller?.text = it }
        }
        view?.findViewById<View>(R.id.rowStaffDocPrep)?.setOnClickListener {
            picker("Document Language", listOf("English", "Tamil", "Hindi")) { tvStaffDocPrep?.text = it }
        }
        view?.findViewById<View>(R.id.rowStaffSaveDraft)?.setOnClickListener {
            staffSaveAs = SaveAs.DRAFT; refreshStaffSaveRadios()
        }
        view?.findViewById<View>(R.id.rowStaffSaveConfirmed)?.setOnClickListener {
            staffSaveAs = SaveAs.CONFIRMED; refreshStaffSaveRadios()
        }

        // Top-level chrome
        btnEdit?.setOnClickListener {
            // Take the user back to the mobile-find step from any sub-tab.
            activeOutcome = Outcome.BOOKING
            bookingSub = BookingSub.CLIENT
            bookingStep = BookingStep.FIND_MOBILE
            renderState()
        }
        btnSubmit?.setOnClickListener { onCtaTap() }

        // ---- Site Visit interactions ----
        view?.findViewById<View>(R.id.rowSvProject)?.setOnClickListener { pickSvProject() }
        view?.findViewById<View>(R.id.rowSvDate)?.setOnClickListener { pickDate(tvSvDate, "dd-MM-yyyy") }
        view?.findViewById<View>(R.id.rowSvTime)?.setOnClickListener { pickTime(tvSvTime) }

        view?.findViewById<View>(R.id.rowSvIncharge)?.setOnClickListener {
            pickSvStaff("Select Site Incharge") { svIncharge = it; tvSvIncharge?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowSvHod)?.setOnClickListener {
            pickSvStaff("Select HOD") { svHod = it; tvSvHod?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowSvAvp)?.setOnClickListener {
            pickSvStaff("Select AVP") { svAvp = it; tvSvAvp?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowSvGm)?.setOnClickListener {
            pickSvStaff("Select GM") { svGm = it; tvSvGm?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowSvSm)?.setOnClickListener {
            pickSvStaff("Select Senior Manager") { svSm = it; tvSvSm?.text = it.name ?: "Selected" }
        }

        btnSvTravelOwn?.setOnClickListener { setTravelMode("own_vehicle") }
        btnSvTravelCab?.setOnClickListener { setTravelMode("cab") }

        // Each keystroke in the visitor count rebuilds the visitor card list.
        etSvVisitorCount?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderVisitorRows(s?.toString()?.toIntOrNull() ?: 0)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })

        // ---- Postpone interactions ----
        // The Date & Time row opens a date picker first, then a time
        // picker, and renders the combined "dd/MM/yyyy hh:mm" string back
        // into the row label.
        view?.findViewById<View>(R.id.rowPostDateTime)?.setOnClickListener { pickPostponeDateTime() }
    }

    private fun pickPostponeDateTime() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d)
                android.app.TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        cal.set(Calendar.HOUR_OF_DAY, hour)
                        cal.set(Calendar.MINUTE, minute)
                        tvPostDateTime?.text = SimpleDateFormat(
                            "dd/MM/yyyy hh:mm a", Locale.US
                        ).format(cal.time)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    false,
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun refreshBookingRadios() {
        ivBookVisitYes?.setImageResource(
            if (bookIsAgainstVisit == YesNo.YES) R.drawable.ic_outcome_radio_on
            else R.drawable.ic_outcome_radio_off
        )
        ivBookVisitNo?.setImageResource(
            if (bookIsAgainstVisit == YesNo.NO) R.drawable.ic_outcome_radio_on
            else R.drawable.ic_outcome_radio_off
        )
    }

    private fun refreshStaffSaveRadios() {
        ivStaffSaveDraft?.setImageResource(
            if (staffSaveAs == SaveAs.DRAFT) R.drawable.ic_outcome_radio_on
            else R.drawable.ic_outcome_radio_off
        )
        ivStaffSaveConfirmed?.setImageResource(
            if (staffSaveAs == SaveAs.CONFIRMED) R.drawable.ic_outcome_radio_on
            else R.drawable.ic_outcome_radio_off
        )
    }

    // ---- State transitions ---------------------------------------------
    private fun switchOutcome(o: Outcome) {
        activeOutcome = o
        if (o == Outcome.BOOKING) {
            bookingSub = BookingSub.CLIENT
            bookingStep = BookingStep.FIND_MOBILE
        }
        renderState()
    }

    private fun switchBookingSub(sub: BookingSub) {
        bookingSub = sub
        if (sub == BookingSub.CLIENT && bookingStep != BookingStep.CLIENT_FORM) {
            bookingStep = BookingStep.FIND_MOBILE
        }
        renderState()
    }

    /**
     * Single render entrypoint — flips top tabs, sub-tab pills, body
     * visibility, and the CTA label off the current state.
     */
    private fun renderState() {
        // Top tabs (4)
        styleOutcomeTab(tabBooking, active = activeOutcome == Outcome.BOOKING)
        styleOutcomeTab(tabSiteVisit, active = activeOutcome == Outcome.SITE_VISIT)
        styleOutcomeTab(tabPostpone, active = activeOutcome == Outcome.POSTPONE)
        styleOutcomeTab(tabNotInterested, active = activeOutcome == Outcome.NOT_INTERESTED)

        // Sub-tab row (only on Booking)
        val showSubTabs = activeOutcome == Outcome.BOOKING
        subTabsScroll?.visibility = if (showSubTabs) View.VISIBLE else View.GONE
        if (showSubTabs) {
            styleSubTab(subTabClient, bookingSub == BookingSub.CLIENT)
            styleSubTab(subTabProfessional, bookingSub == BookingSub.PROFESSIONAL)
            styleSubTab(subTabOffice, bookingSub == BookingSub.OFFICE)
            styleSubTab(subTabBooking, bookingSub == BookingSub.BOOKING)
            styleSubTab(subTabCharges, bookingSub == BookingSub.CHARGES)
            styleSubTab(subTabPayment, bookingSub == BookingSub.PAYMENT)
            styleSubTab(subTabStaff, bookingSub == BookingSub.STAFF)

            // Since the pills aren't tappable, the user can't manually
            // scroll the strip to see what's next — auto-scroll the
            // active pill into the centre so they always have context.
            val activePill: TextView? = when (bookingSub) {
                BookingSub.CLIENT -> subTabClient
                BookingSub.PROFESSIONAL -> subTabProfessional
                BookingSub.OFFICE -> subTabOffice
                BookingSub.BOOKING -> subTabBooking
                BookingSub.CHARGES -> subTabCharges
                BookingSub.PAYMENT -> subTabPayment
                BookingSub.STAFF -> subTabStaff
            }
            val scroll = subTabsScroll
            if (activePill != null && scroll != null) {
                scroll.post {
                    val target = activePill.left - (scroll.width - activePill.width) / 2
                    scroll.smoothScrollTo(target.coerceAtLeast(0), 0)
                }
            }
        }

        // Body swap.
        //  - Booking         : sub-tab drives which of the 7 form bodies show.
        //  - Site visit      : one-page conversion form.
        //  - Postpone        : 4 free-text fields + follow-up date/time.
        //  - Not Interested  : same 4 free-text fields, no follow-up.
        val bookingActive = activeOutcome == Outcome.BOOKING
        val siteVisitActive = activeOutcome == Outcome.SITE_VISIT
        val postponeActive = activeOutcome == Outcome.POSTPONE
        val notInterestedActive = activeOutcome == Outcome.NOT_INTERESTED

        // Coming Soon is only the fallback now — every outcome has a body.
        bodyComingSoon?.visibility = View.GONE
        bodyFindClient?.visibility = if (bookingActive && bookingSub == BookingSub.CLIENT &&
            bookingStep == BookingStep.FIND_MOBILE) View.VISIBLE else View.GONE
        bodyClientForm?.visibility = if (bookingActive && bookingSub == BookingSub.CLIENT &&
            bookingStep == BookingStep.CLIENT_FORM) View.VISIBLE else View.GONE
        bodyProfessional?.visibility = if (bookingActive && bookingSub == BookingSub.PROFESSIONAL)
            View.VISIBLE else View.GONE
        bodyOffice?.visibility = if (bookingActive && bookingSub == BookingSub.OFFICE)
            View.VISIBLE else View.GONE
        bodyBooking?.visibility = if (bookingActive && bookingSub == BookingSub.BOOKING)
            View.VISIBLE else View.GONE
        bodyCharges?.visibility = if (bookingActive && bookingSub == BookingSub.CHARGES)
            View.VISIBLE else View.GONE
        bodyPayment?.visibility = if (bookingActive && bookingSub == BookingSub.PAYMENT)
            View.VISIBLE else View.GONE
        bodyStaff?.visibility = if (bookingActive && bookingSub == BookingSub.STAFF)
            View.VISIBLE else View.GONE
        bodySiteVisit?.visibility = if (siteVisitActive) View.VISIBLE else View.GONE
        bodyPostpone?.visibility = if (postponeActive) View.VISIBLE else View.GONE
        bodyNotInterested?.visibility = if (notInterestedActive) View.VISIBLE else View.GONE

        // Edit chip visible from the moment we leave the find-mobile step,
        // so the user can always jump back and re-enter the client mobile.
        val onFindMobile = bookingActive && bookingSub == BookingSub.CLIENT &&
            bookingStep == BookingStep.FIND_MOBILE
        btnEdit?.visibility = if (bookingActive && !onFindMobile) View.VISIBLE else View.GONE

        // CTA label
        btnSubmit?.text = when {
            siteVisitActive -> "Save"
            postponeActive -> "Save"
            notInterestedActive -> "Save"
            onFindMobile -> "Next"
            bookingActive && bookingSub == BookingSub.STAFF -> "Save Booking"
            bookingActive -> "Next"
            else -> "Close"
        }
    }

    private fun styleOutcomeTab(tab: OutcomeTab, active: Boolean) {
        val ctx = context ?: return
        tab.circle?.setBackgroundResource(
            if (active) R.drawable.bg_outcome_tab_active
            else R.drawable.bg_outcome_tab_inactive
        )
        val icon = (tab.circle as? FrameLayout)?.getChildAt(0) as? ImageView
        icon?.imageTintList = android.content.res.ColorStateList.valueOf(
            if (active) Color.WHITE else Color.parseColor("#6A6D78")
        )
        tab.label?.setTextColor(
            if (active) Color.parseColor("#0B61CA") else Color.parseColor("#6A6D78")
        )
        tab.label?.typeface = ResourcesCompat.getFont(
            ctx,
            if (active) R.font.inter_semibold else R.font.inter_medium
        )
        tab.indicator?.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    private fun styleSubTab(view: TextView?, active: Boolean) {
        val v = view ?: return
        val ctx = context ?: return
        v.setBackgroundResource(
            if (active) R.drawable.bg_outcome_subtab_active
            else R.drawable.bg_outcome_subtab_inactive
        )
        v.setTextColor(if (active) Color.WHITE else Color.parseColor("#475467"))
        v.typeface = ResourcesCompat.getFont(
            ctx,
            if (active) R.font.inter_semibold else R.font.inter_medium
        )
    }

    // ---- CTA --------------------------------------------------------
    private fun onCtaTap() {
        clearError()
        when (activeOutcome) {
            Outcome.SITE_VISIT -> {
                persistSiteVisit()
                return
            }
            Outcome.POSTPONE -> {
                persistPostpone()
                return
            }
            Outcome.NOT_INTERESTED -> {
                persistNotInterested()
                return
            }
            Outcome.BOOKING -> { /* fall through to the Booking flow below */ }
        }
        // From the mobile-find step, validate + advance to the form.
        if (bookingSub == BookingSub.CLIENT && bookingStep == BookingStep.FIND_MOBILE) {
            val raw = etClientMobile?.text?.toString().orEmpty().trim()
            if (raw.length < 6) {
                showError("Enter a valid mobile number")
                return
            }
            tvFormPhone?.text = raw
            bookingStep = BookingStep.CLIENT_FORM
            renderState()
            return
        }
        // Final submit is on the Staff sub-tab. Anywhere else, Next moves
        // to the next sub-tab in order.
        if (bookingSub == BookingSub.STAFF) {
            val name = etFormName?.text?.toString().orEmpty().trim()
            if (name.isEmpty()) {
                showError("Client name is required (Client Details tab)")
                return
            }
            persistBooking()
            return
        }
        bookingSub = nextSubTab(bookingSub)
        renderState()
    }

    private fun nextSubTab(current: BookingSub): BookingSub = when (current) {
        BookingSub.CLIENT -> BookingSub.PROFESSIONAL
        BookingSub.PROFESSIONAL -> BookingSub.OFFICE
        BookingSub.OFFICE -> BookingSub.BOOKING
        BookingSub.BOOKING -> BookingSub.CHARGES
        BookingSub.CHARGES -> BookingSub.PAYMENT
        BookingSub.PAYMENT -> BookingSub.STAFF
        BookingSub.STAFF -> BookingSub.STAFF
    }

    // ---- Pickers ----------------------------------------------------
    private fun picker(title: String, items: List<String>, onPicked: (String) -> Unit) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = title,
            options = items.map { SearchableOption(item = it, title = it) },
            emptyMessage = "No options found",
        ) { onPicked(it) }
    }

    private fun pickDate(target: TextView?, format: String = "dd/MM/yyyy") {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d)
                target?.text = SimpleDateFormat(format, Locale.US).format(cal.time)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun pickTime(target: TextView?) {
        val cal = Calendar.getInstance()
        android.app.TimePickerDialog(
            requireContext(),
            { _, hour, minute ->
                target?.text = String.format(Locale.US, "%02d:%02d", hour, minute)
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            false,
        ).show()
    }

    // ---- Site Visit helpers -----------------------------------------
    private fun setTravelMode(mode: String) {
        svTravelMode = mode
        // Active button gets the gradient blue + white text; the other goes
        // back to outlined.
        val ctx = context ?: return
        val ownActive = mode == "own_vehicle"
        btnSvTravelOwn?.setBackgroundResource(
            if (ownActive) R.drawable.bg_outcome_segment_active
            else R.drawable.bg_outcome_segment_inactive
        )
        btnSvTravelOwn?.setTextColor(
            if (ownActive) Color.WHITE else Color.parseColor("#475467")
        )
        btnSvTravelCab?.setBackgroundResource(
            if (!ownActive) R.drawable.bg_outcome_segment_active
            else R.drawable.bg_outcome_segment_inactive
        )
        btnSvTravelCab?.setTextColor(
            if (!ownActive) Color.WHITE else Color.parseColor("#475467")
        )
    }

    private fun pickSvProject() {
        if (svProjectCache.isNotEmpty()) {
            showSvProjectPicker(svProjectCache)
            return
        }
        // Lazy-load on first tap; subsequent taps hit the cache.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMarketingProjects(session.bearerToken)
                if (!resp.success || resp.projects.isEmpty()) {
                    showError(resp.error ?: "No projects available")
                    return@launch
                }
                svProjectCache = resp.projects
                showSvProjectPicker(resp.projects)
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load projects")
            }
        }
    }

    private fun showSvProjectPicker(items: List<MarketingProject>) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select project",
            options = items.map { p ->
                SearchableOption(
                    item = p,
                    title = p.name ?: "Unnamed project",
                    subtitle = listOfNotNull(p.location, p.status).joinToString(" • ").takeIf { it.isNotBlank() },
                    keywords = listOfNotNull(p.id, p.scope, p.location, p.status).joinToString(" "),
                )
            },
            emptyMessage = "No projects found",
        ) { project ->
            svProject = project
            tvSvProject?.text = project.name ?: "Selected"
        }
    }

    private fun pickSvStaff(title: String, onPicked: (StaffData) -> Unit) {
        // Sales-marketing + telesales staff are the eligible pool — same
        // filter the legacy convert flow used.
        if (svStaffCache.isNotEmpty()) {
            showSvStaffPicker(title, svStaffCache, onPicked)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaff(session.bearerToken, status = "active")
                val filtered = resp.staff.filter {
                    val dept = it.department.orEmpty().lowercase(Locale.US)
                    dept.contains("telesales") || dept.contains("sales")
                }
                if (filtered.isEmpty()) {
                    showError("No Sales / Telesales staff found")
                    return@launch
                }
                svStaffCache = filtered
                showSvStaffPicker(title, filtered, onPicked)
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load staff")
            }
        }
    }

    private fun showSvStaffPicker(
        title: String,
        items: List<StaffData>,
        onPicked: (StaffData) -> Unit,
    ) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = title,
            options = items.map { staff ->
                SearchableOption(
                    item = staff,
                    title = staff.name ?: "Unnamed staff",
                    subtitle = listOfNotNull(staff.designation, staff.department, staff.employeeId)
                        .joinToString(" • ").takeIf { it.isNotBlank() },
                    keywords = listOfNotNull(staff.phone, staff.role, staff.status).joinToString(" "),
                )
            },
            emptyMessage = "No staff found",
        ) { onPicked(it) }
    }

    /** Inflates one visitor card per expected attendee (capped at 12). */
    private fun renderVisitorRows(count: Int) {
        val rows = siteVisitorRows ?: return
        rows.removeAllViews()
        val safeCount = count.coerceIn(0, 12)
        repeat(safeCount) {
            val card = layoutInflater.inflate(
                R.layout.item_outcome_site_visitor, rows, false
            )
            wireVisitorCardToggles(card)
            rows.addView(card)
        }
    }

    private fun wireVisitorCardToggles(card: View) {
        val veg = card.findViewWithTag<TextView>("foodVeg")
        val nonVeg = card.findViewWithTag<TextView>("foodNonVeg")
        // Initial state: Veg pre-selected via the item layout. Wire the
        // toggle so the user can flip between Veg / Non-Veg.
        veg?.setOnClickListener {
            veg.setBackgroundResource(R.drawable.bg_outcome_segment_active)
            veg.setTextColor(Color.WHITE)
            nonVeg?.setBackgroundResource(R.drawable.bg_outcome_segment_inactive)
            nonVeg?.setTextColor(Color.parseColor("#475467"))
        }
        nonVeg?.setOnClickListener {
            nonVeg.setBackgroundResource(R.drawable.bg_outcome_segment_active)
            nonVeg.setTextColor(Color.WHITE)
            veg?.setBackgroundResource(R.drawable.bg_outcome_segment_inactive)
            veg?.setTextColor(Color.parseColor("#475467"))
        }
        // Relation dropdown opens a simple picker.
        card.findViewWithTag<View>("relationRow")?.setOnClickListener {
            picker(
                "Relation",
                listOf("Spouse", "Parent", "Sibling", "Child", "Friend", "Colleague", "Other"),
            ) { card.findViewWithTag<TextView>("relation")?.text = it }
        }
    }

    private fun collectVisitors(): List<SiteVisitAttendeeRequest> {
        val rows = siteVisitorRows ?: return emptyList()
        val out = mutableListOf<SiteVisitAttendeeRequest>()
        for (i in 0 until rows.childCount) {
            val card = rows.getChildAt(i)
            val name = card.findViewWithTag<EditText>("name")?.text?.toString()?.trim().orEmpty()
            val relation = card.findViewWithTag<TextView>("relation")?.text?.toString()?.trim().orEmpty()
            val age = card.findViewWithTag<EditText>("age")?.text?.toString()?.trim().orEmpty()
            val vegBtn = card.findViewWithTag<TextView>("foodVeg")
            // Veg is "active" iff its background is the gradient drawable —
            // mirror that to isVeg=true on the API payload.
            val isVeg = (vegBtn?.currentTextColor == Color.WHITE)
            out.add(
                SiteVisitAttendeeRequest(
                    name = name.takeIf { it.isNotEmpty() },
                    relation = relation.takeIf { it.isNotEmpty() },
                    age = age.takeIf { it.isNotEmpty() },
                    isVeg = isVeg,
                )
            )
        }
        return out
    }

    private fun persistSiteVisit() {
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        val project = svProject ?: return showError("Please select a project")
        val date = tvSvDate?.text?.toString()?.trim().orEmpty()
        if (date.isEmpty()) return showError("Please pick a date")
        val time = tvSvTime?.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() }

        btnSubmit?.isClickable = false
        btnSubmit?.text = "Saving…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // KOS-52: SV branch implies the user met the client at the
                // place, so we still flip clientMet=true on the CP visit.
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(id = cpVisitId, clientMet = true),
                )
                if (!metResp.success) {
                    finishCtaSiteVisit(metResp.error ?: "Failed to record client met")
                    return@launch
                }

                val convertResp = geoApi.convertCpVisitToSiteVisit(
                    session.bearerToken,
                    ConvertCpVisitToSiteVisitRequest(
                        id = cpVisitId,
                        projectId = project.id,
                        scheduledDate = date,
                        scheduledTime = time,
                        inchargeStaffId = svIncharge?.id,
                        hodStaffId = svHod?.id,
                        avpStaffId = svAvp?.id,
                        gmStaffId = svGm?.id,
                        seniorManagerStaffId = svSm?.id,
                        expectedAttendeeCount = etSvVisitorCount?.text?.toString()?.toIntOrNull()
                            ?.takeIf { it > 0 },
                        attendees = collectVisitors().takeIf { it.isNotEmpty() },
                        pickupAddress = etSvPickupAddress?.text?.toString()?.trim()
                            ?.takeIf { it.isNotEmpty() },
                        travelMode = svTravelMode,
                        notes = "Created from mobile CP visit",
                    ),
                )
                if (!convertResp.success) {
                    finishCtaSiteVisit(convertResp.error ?: "Failed to create site visit")
                    return@launch
                }

                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to true,
                        KEY_OUTCOME to OUTCOME_SITE_VISIT,
                    ),
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                finishCtaSiteVisit(e.message ?: "Network error")
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun finishCtaSiteVisit(error: String) {
        btnSubmit?.isClickable = true
        btnSubmit?.text = "Save"
        showError(error)
    }

    // ---- Postpone -------------------------------------------------------
    private fun persistPostpone() {
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        // At least one field should be filled so the back office knows why
        // the visit is being held.
        val budget = etPostBudget?.text?.toString()?.trim().orEmpty()
        val timing = etPostTiming?.text?.toString()?.trim().orEmpty()
        val project = etPostProject?.text?.toString()?.trim().orEmpty()
        val other = etPostOther?.text?.toString()?.trim().orEmpty()
        val dateTime = tvPostDateTime?.text?.toString()?.trim().orEmpty()
        if (budget.isEmpty() && timing.isEmpty() && project.isEmpty() &&
            other.isEmpty() && dateTime.isEmpty()
        ) {
            showError("Please share at least one reason for the postpone")
            return
        }
        finalizeTerminalOutcome(
            cpVisitId = cpVisitId,
            outcomeEnum = OUTCOME_POSTPONED,
            notes = buildString {
                appendLine("[Postponed]")
                if (budget.isNotEmpty()) appendLine("Budget concern: $budget")
                if (timing.isNotEmpty()) appendLine("Timing: $timing")
                if (project.isNotEmpty()) appendLine("Project details: $project")
                if (other.isNotEmpty()) appendLine("Other: $other")
                if (dateTime.isNotEmpty()) appendLine("Follow-up: $dateTime")
            }.trimEnd(),
        )
    }

    // ---- Not Interested -------------------------------------------------
    private fun persistNotInterested() {
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        val budget = etNiBudget?.text?.toString()?.trim().orEmpty()
        val timing = etNiTiming?.text?.toString()?.trim().orEmpty()
        val project = etNiProject?.text?.toString()?.trim().orEmpty()
        val other = etNiOther?.text?.toString()?.trim().orEmpty()
        if (budget.isEmpty() && timing.isEmpty() && project.isEmpty() && other.isEmpty()) {
            showError("Please share at least one reason")
            return
        }
        finalizeTerminalOutcome(
            cpVisitId = cpVisitId,
            outcomeEnum = OUTCOME_NOT_INTERESTED,
            notes = buildString {
                appendLine("[Not interested]")
                if (budget.isNotEmpty()) appendLine("Budget concern: $budget")
                if (timing.isNotEmpty()) appendLine("Timing: $timing")
                if (project.isNotEmpty()) appendLine("Project details: $project")
                if (other.isNotEmpty()) appendLine("Other: $other")
            }.trimEnd(),
        )
    }

    /**
     * Shared persistence path for the terminal outcomes (Postpone /
     * Not Interested): mark the client as met (since the user reached
     * this sheet only after the OTP-verified "Yes, I saw the client"
     * branch) and then set the outcome with a serialized notes payload.
     */
    private fun finalizeTerminalOutcome(
        cpVisitId: String,
        outcomeEnum: String,
        notes: String,
    ) {
        btnSubmit?.isClickable = false
        btnSubmit?.text = "Saving…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(id = cpVisitId, clientMet = true),
                )
                if (!metResp.success) {
                    finishCtaSave(metResp.error ?: "Failed to record client met")
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    SetOutcomeRequest(
                        id = cpVisitId,
                        outcome = outcomeEnum,
                        postponeReasons = null,
                        notes = notes,
                    ),
                )
                if (!outcomeResp.success) {
                    finishCtaSave(outcomeResp.error ?: "Failed to save outcome")
                    return@launch
                }
                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(KEY_CLIENT_MET to true, KEY_OUTCOME to outcomeEnum),
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                finishCtaSave(e.message ?: "Network error")
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun finishCtaSave(error: String) {
        btnSubmit?.isClickable = true
        btnSubmit?.text = "Save"
        showError(error)
    }

    // ---- Persistence ------------------------------------------------
    private fun persistBooking() {
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        val met = true

        btnSubmit?.isClickable = false
        btnSubmit?.text = "Saving…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(id = cpVisitId, clientMet = met),
                )
                if (!metResp.success) {
                    finishCta(error = metResp.error ?: "Failed to record client met")
                    return@launch
                }

                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    SetOutcomeRequest(
                        id = cpVisitId,
                        outcome = OUTCOME_BOOKING,
                        postponeReasons = null,
                        notes = serializeBookingForm(),
                    ),
                )
                if (!outcomeResp.success) {
                    finishCta(error = outcomeResp.error ?: "Failed to save booking")
                    return@launch
                }

                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to met,
                        KEY_OUTCOME to OUTCOME_BOOKING,
                    ),
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                finishCta(error = e.message ?: "Network error")
                Toast.makeText(requireContext(), e.message ?: "Network error", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun finishCta(error: String) {
        btnSubmit?.isClickable = true
        btnSubmit?.text = "Save Booking"
        showError(error)
    }

    /**
     * Serializes every captured field across the 7 sub-tabs into a single
     * labeled multi-line string. This is the only payload the backend's
     * setOutcome.notes endpoint accepts today — when a dedicated
     * createBooking endpoint exists, structure this as JSON instead.
     */
    private fun serializeBookingForm(): String? {
        val sb = StringBuilder()

        fun section(title: String) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("[").append(title).append("]")
        }
        fun row(label: String, v: CharSequence?) {
            val t = v?.toString()?.trim().orEmpty()
            if (t.isEmpty()) return
            sb.append("\n").append(label).append(": ").append(t)
        }

        // Client Details
        section("Booking · Client Details")
        row("Phone", tvFormPhone?.text)
        row("Title", tvFormTitle?.text)
        row("Name", etFormName?.text)
        row("Father/Spouse", etFormFather?.text)
        row("DOB", tvFormDob?.text)
        row("Anniversary", tvFormAnniversary?.text)
        row("Alt number", etFormAltNumber?.text)
        row("WhatsApp", etFormWhatsApp?.text)
        row("Email", etFormEmail?.text)
        row("Nationality", tvFormNationality?.text)
        row("Home Address", etFormHomeAddress?.text)
        row("Pincode", etFormPincode?.text)
        row("State", etFormState?.text)
        row("District", etFormDistrict?.text)
        row("Location", etFormLocation?.text)

        // Professional
        section("Booking · Professional Details")
        row("Profession", tvProfProfession?.text)
        row("Designation", etProfDesignation?.text)
        row("Income Per Annum", etProfIncome?.text)

        // Office
        section("Booking · Office Details")
        row("Office Name", etOfficeName?.text)
        row("Office Email", etOfficeEmail?.text)
        row("Office Mobile", etOfficeMobile?.text)
        row("Office Phone", etOfficePhone?.text)
        row("Office Address", etOfficeAddress?.text)

        // Booking
        section("Booking · Booking Details")
        row("Booking Type", tvBookType?.text)
        row("Source Type", tvBookSource?.text)
        row("CEF No", etBookCef?.text)
        row("Booking Date", tvBookDate?.text)
        row("Project", tvBookProject?.text)
        row("Plot", tvBookPlot?.text)
        row("Property Type", tvBookProperty?.text)
        row("Booking Mode", tvBookMode?.text)
        row("Is Against Client Visit", if (bookIsAgainstVisit == YesNo.YES) "Yes" else "No (Online Sales)")
        row("Duplicate Bookings", if (bookDuplicate) "Yes" else "No")

        // Charges
        section("Booking · Charges Details")
        row("Booking Cost", etChargeBookingCost?.text)
        row("Guideline Value", etChargeGuidelineValue?.text)
        row("Special Consideration", etChargeSpecialConsideration?.text)
        row("Discount Approved By", etChargeDiscountApprovedBy?.text)
        row("SC Reason", etChargeScReason?.text)
        row("SC Validity (days)", etChargeScValidity?.text)
        row("Promotional Offers", etChargePromoOffers?.text)
        row("Promotional Offers T&C", tvChargePromoTnc?.text)
        row("Promotional Offers Value", etChargePromoValue?.text)
        row("Offer Validity Period (days)", etChargeOfferValidity?.text)

        // Payment
        section("Booking · Payment Details")
        row("Registration Charges", etPayRegCharges?.text)
        row("GST Amount", etPayGstAmount?.text)
        row("GST If Applicable", if (payGstApplicable) "Yes" else "No")
        row("Document Charges", etPayDocCharges?.text)
        row("Other Charges", etPayOtherCharges?.text)
        row("Other Charges If Applicable", if (payOtherApplicable) "Yes" else "No")
        row("Advance Amount", etPayAdvanceAmount?.text)
        row("Payment Mode", tvPayPaymentMode?.text)
        row("Flexi Payment", if (payFlexi) "Yes" else "No")
        row("Allotment Due Amount", etPayAllotDue?.text)
        row("Allotment Due Date", tvPayAllotDate?.text)
        row("2nd Payment Mode", etPay2Mode?.text)
        row("2nd Payment Date", tvPay2Date?.text)
        row("3rd Payment Mode", etPay3Mode?.text)
        row("3rd Payment Date", tvPay3Date?.text)
        row("4th Payment Mode", etPay4Mode?.text)
        row("4th Payment Date", tvPay4Date?.text)
        row("Preferred Registration Date", tvPayPrefReg?.text)

        // Staff
        section("Booking · Staff Details")
        row("AVP", tvStaffAvp?.text)
        row("General Manager", tvStaffGm?.text)
        row("Senior Manager", tvStaffSm?.text)
        row("BDO", tvStaffBdo?.text)
        row("Telecaller", tvStaffTelecaller?.text)
        row("Aadhar", etStaffAadhar?.text)
        row("Pancard", etStaffPancard?.text)
        row("Reference Name 1", etStaffRefName1?.text)
        row("Reference Mobile 1", etStaffRefMobile1?.text)
        row("Reference Profession 1", etStaffRefProf1?.text)
        row("Reference Name 2", etStaffRefName2?.text)
        row("Reference Mobile 2", etStaffRefMobile2?.text)
        row("Reference Profession 2", etStaffRefProf2?.text)
        row("Document to be prepared in", tvStaffDocPrep?.text)
        row("Save as", if (staffSaveAs == SaveAs.DRAFT) "Draft" else "Confirmed")

        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun showError(msg: String) {
        tvError?.text = msg
        tvError?.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvError?.visibility = View.GONE
    }

    private fun outcomeFromArg(value: String): Outcome? = when (value) {
        OUTCOME_BOOKING -> Outcome.BOOKING
        OUTCOME_SITE_VISIT -> Outcome.SITE_VISIT
        OUTCOME_POSTPONED -> Outcome.POSTPONE
        OUTCOME_NOT_INTERESTED -> Outcome.NOT_INTERESTED
        else -> null
    }

    // ── SV-via-CP locked-tab mode ───────────────────────────────────────
    //
    // The telecaller can fix a Site Visit via the "same area" routing
    // from the dialer. That path doesn't create the SV directly — it
    // creates a CP visit with a `proposedSiteVisit` payload and assigns
    // a field staff to verify with the client first. When the field
    // staff opens this sheet after meeting the client, the proper UX
    // is "you don't fill anything in, you just Reject or Confirm what
    // the telecaller already prepared". This helper detects that
    // payload and re-shapes the sheet accordingly.

    private fun detectAndApplyLockedSvMode() {
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
        if (cpVisitId.isNullOrBlank()) {
            android.util.Log.d(LOG_TAG, "detect: no cpVisitId arg, skipping")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // We previously called /api/marketing/clientPlaceVisits/get
                // here, but the server side never wired up that HTTP route.
                // Use the existing /my list endpoint and find the visit by
                // id — the list is paginated to the latest 200 CP visits
                // for the bearer, which always includes the one we're
                // actively completing.
                val resp = geoApi.getMyMarketingCpVisits(
                    session.bearerToken,
                    fromDate = null,
                    toDate = null,
                )
                if (!resp.success) {
                    android.util.Log.d(
                        LOG_TAG,
                        "detect: list call failed: ${resp.error ?: "(no error)"}",
                    )
                    return@launch
                }
                val visit = resp.visits.firstOrNull { it.id == cpVisitId }
                if (visit == null) {
                    android.util.Log.d(
                        LOG_TAG,
                        "detect: cpVisitId=$cpVisitId not in list of ${resp.visits.size} visits",
                    )
                    return@launch
                }
                // Diagnostic dump of every signal we use to detect
                // "this CP came from a telecaller-fixed SV". If the
                // locked UI still doesn't activate on a known SV-fixed
                // visit, this line in logcat tells us which signal is
                // missing on the server-side row.
                android.util.Log.d(
                    LOG_TAG,
                    "detect: cpVisitId=$cpVisitId " +
                        "leadFollowUpStatus=${visit.lead?.followUpStatus} " +
                        "origin=${visit.origin} " +
                        "outcome=${visit.outcome} " +
                        "fieldVisitStatus=${visit.fieldVisit?.status} " +
                        "expectedAttendeeCount=${visit.expectedAttendeeCount} " +
                        "attendeesSize=${visit.attendees?.size ?: 0} " +
                        "foodPreferences=${visit.foodPreferences} " +
                        "vehiclePreference=${visit.vehiclePreference} " +
                        "proposed.project=${visit.proposedSiteVisit?.projectId} " +
                        "proposed.incharge=${visit.proposedSiteVisit?.inchargeStaffId} " +
                        "proposed.date=${visit.proposedSiteVisit?.scheduledDate}",
                )

                val proposed = visit.proposedSiteVisit
                val proposedMeaningful = proposed?.isMeaningful() == true
                val leadFlaggedSvFixed = visit.lead?.followUpStatus
                    ?.lowercase(Locale.getDefault())
                    ?.let { s -> s == "sv_fixed" || s.contains("sv_fixed") || s.contains("sv-fixed") }
                    ?: false
                // The telecaller-fixed SV path on web (telecaller/leads/[id]
                // page.tsx, lines 1133-1158) is the ONLY CP-create flow
                // that spreads partyArgs — expectedAttendeeCount /
                // attendees / foodPreferences / vehiclePreference — onto
                // the CP visit row. Regular CP-only creates never include
                // them, and createFromMobile / mobile-side createCpVisit
                // doesn't take those args either. So any party data on a
                // CP visit is a strong server-side fingerprint that the
                // telecaller went through "Fix Site Visit -> same area"
                // for this row.
                val hasSvFixParty =
                    (visit.expectedAttendeeCount ?: 0) > 0 ||
                        (visit.attendees?.isNotEmpty() == true) ||
                        !visit.foodPreferences.isNullOrBlank() ||
                        !visit.vehiclePreference.isNullOrBlank()

                // Lock the sheet if ANY signal fires:
                //   1. proposedSiteVisit has at least one populated field
                //   2. The lead's followUpStatus is already "sv_fixed"
                //   3. Party data is on the CP visit row (SV-fix-only)
                if (!proposedMeaningful && !leadFlaggedSvFixed && !hasSvFixParty) {
                    android.util.Log.d(
                        LOG_TAG,
                        "detect: cpVisitId=$cpVisitId no SV-fix signal -> normal mode",
                    )
                    return@launch
                }
                val source = when {
                    proposedMeaningful -> "proposedSiteVisit"
                    leadFlaggedSvFixed -> "lead.followUpStatus=sv_fixed"
                    else -> "partyData"
                }
                android.util.Log.d(
                    LOG_TAG,
                    "detect: cpVisitId=$cpVisitId locked SV mode (source=$source)",
                )
                // Pass the proposed payload through whenever it's
                // meaningful so the form pre-fills. Otherwise hand a
                // blank payload — the locked UX (Reject / Confirm,
                // other tabs pale) still applies, just without
                // pre-filled values.
                applyLockedSvMode(visit, proposed ?: ProposedSiteVisit())
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "detect: exception ${e.message}", e)
            }
        }
    }

    /**
     * proposedSiteVisit can be persisted as an empty object `{}` if the
     * web form was opened and abandoned, which Gson deserialises into a
     * ProposedSiteVisit with every field null. Lock the sheet only
     * when at least one meaningful field has been populated — that
     * way an empty stub doesn't accidentally hide all the other tabs.
     */
    private fun ProposedSiteVisit.isMeaningful(): Boolean {
        return !projectId.isNullOrBlank() ||
            !scheduledDate.isNullOrBlank() ||
            !scheduledTime.isNullOrBlank() ||
            !inchargeStaffId.isNullOrBlank() ||
            !hodStaffId.isNullOrBlank() ||
            !bdoStaffId.isNullOrBlank() ||
            !avpStaffId.isNullOrBlank() ||
            !gmStaffId.isNullOrBlank() ||
            !seniorManagerStaffId.isNullOrBlank()
    }

    private suspend fun applyLockedSvMode(visit: CpVisitDetail, proposed: ProposedSiteVisit) {
        if (!isAdded) return
        lockedFromProposedSv = true

        // 1. Force the active outcome to Site Visit and re-render so the
        //    SV body is the one on screen.
        activeOutcome = Outcome.SITE_VISIT
        renderState()

        // 2. Fade non-SV tabs and make them unclickable.
        listOf(tabBooking, tabPostpone, tabNotInterested).forEach { tab ->
            tab.cell?.isClickable = false
            tab.cell?.alpha = 0.35f
        }

        // 3. Pre-fill the SV form from `proposedSiteVisit` + visit-level
        //    fields (attendees, food prefs, pickup address from the
        //    client place if we have it).
        tvSvDate?.text = proposed.scheduledDate ?: visit.scheduledDate ?: ""
        tvSvTime?.text = proposed.scheduledTime ?: visit.scheduledTime ?: ""
        val visitorCount = visit.expectedAttendeeCount ?: visit.attendees?.size ?: 0
        if (visitorCount > 0) {
            etSvVisitorCount?.setText(visitorCount.toString())
        }
        etSvPickupAddress?.setText(
            visit.clientPlace?.address
                ?: visit.clientPlace?.formattedAddress
                ?: visit.lead?.preferredArea
                ?: "",
        )

        // Resolve project + staff names from the caches (load them if
        // they're cold so the labels render as something meaningful
        // instead of "Selected").
        prefillProjectIfPossible(proposed.projectId)
        prefillSvStaff(proposed)

        // 4. Disable every input row inside the SV body so the form
        //    reads as a read-only confirmation surface.
        applyReadOnlyToSvBody()

        // 5. Swap the single Save button for the Reject / Confirm pair.
        btnSubmit?.visibility = View.GONE
        cpLockedFooter?.visibility = View.VISIBLE
        btnCpLockedReject?.setOnClickListener { onLockedRejectTap() }
        btnCpLockedConfirm?.setOnClickListener { onLockedConfirmTap() }
    }

    private suspend fun prefillProjectIfPossible(projectId: String?) {
        if (projectId.isNullOrBlank()) return
        if (svProjectCache.isEmpty()) {
            runCatching {
                val resp = api.getMarketingProjects(session.bearerToken)
                if (resp.success && resp.projects.isNotEmpty()) {
                    svProjectCache = resp.projects
                }
            }
        }
        val match = svProjectCache.firstOrNull { it.id == projectId }
        if (match != null) {
            svProject = match
            tvSvProject?.text = match.name ?: "Selected"
        } else {
            tvSvProject?.text = "Selected"
        }
    }

    private suspend fun prefillSvStaff(proposed: ProposedSiteVisit) {
        // Build the label-set we need to resolve. If anything is set,
        // load the staff cache once and map IDs -> names.
        val anyStaff = listOfNotNull(
            proposed.inchargeStaffId,
            proposed.hodStaffId,
            proposed.avpStaffId,
            proposed.gmStaffId,
            proposed.seniorManagerStaffId,
        )
        if (anyStaff.isEmpty()) return
        if (svStaffCache.isEmpty()) {
            runCatching {
                val resp = api.getStaff(session.bearerToken, status = "active")
                svStaffCache = resp.staff
            }
        }
        fun byId(id: String?): StaffData? =
            if (id.isNullOrBlank()) null else svStaffCache.firstOrNull { it.id == id }

        byId(proposed.inchargeStaffId)?.let { svIncharge = it; tvSvIncharge?.text = it.name ?: "Selected" }
            ?: run { if (!proposed.inchargeStaffId.isNullOrBlank()) tvSvIncharge?.text = "Selected" }
        byId(proposed.hodStaffId)?.let { svHod = it; tvSvHod?.text = it.name ?: "Selected" }
            ?: run { if (!proposed.hodStaffId.isNullOrBlank()) tvSvHod?.text = "Selected" }
        byId(proposed.avpStaffId)?.let { svAvp = it; tvSvAvp?.text = it.name ?: "Selected" }
            ?: run { if (!proposed.avpStaffId.isNullOrBlank()) tvSvAvp?.text = "Selected" }
        byId(proposed.gmStaffId)?.let { svGm = it; tvSvGm?.text = it.name ?: "Selected" }
            ?: run { if (!proposed.gmStaffId.isNullOrBlank()) tvSvGm?.text = "Selected" }
        byId(proposed.seniorManagerStaffId)?.let { svSm = it; tvSvSm?.text = it.name ?: "Selected" }
            ?: run { if (!proposed.seniorManagerStaffId.isNullOrBlank()) tvSvSm?.text = "Selected" }
    }

    private fun applyReadOnlyToSvBody() {
        val body = bodySiteVisit ?: return
        // Walk the SV body and dim + disable every interactive view.
        // We deliberately leave TextViews readable (not transparent) —
        // the user still needs to see the values.
        fun walk(v: View) {
            if (v is EditText) {
                v.isFocusable = false
                v.isFocusableInTouchMode = false
                v.isCursorVisible = false
                v.isEnabled = false
                v.alpha = 0.7f
            } else if (v is android.view.ViewGroup) {
                v.isClickable = false
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            } else {
                v.isClickable = false
                v.isFocusable = false
            }
        }
        walk(body)
        // Buttons (Own / Cab travel pills) are TextView siblings — kill
        // their click listeners too.
        btnSvTravelOwn?.isClickable = false
        btnSvTravelCab?.isClickable = false
        btnSvTravelOwn?.alpha = 0.7f
        btnSvTravelCab?.alpha = 0.7f
    }

    private fun onLockedConfirmTap() {
        // Confirming just reuses the existing persistSiteVisit flow —
        // it already calls markClientMet + convertCpVisitToSiteVisit with
        // the populated SV fields, which we filled from the telecaller's
        // proposed payload.
        clearError()
        persistSiteVisit()
    }

    private fun onLockedRejectTap() {
        // Rejection records the field-staff "client said no" decision
        // on the CP visit. We piggyback on the Not Interested flow's
        // mutation contract — markClientMet=true (the staff did meet
        // the client) + outcome=not_interested — so the back office
        // sees a clean terminal status.
        clearError()
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        btnCpLockedConfirm?.isClickable = false
        btnCpLockedReject?.isClickable = false
        btnCpLockedReject?.text = "Rejecting…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(id = cpVisitId, clientMet = true),
                )
                if (!metResp.success) {
                    finishLockedReject(metResp.error ?: "Failed to record client met")
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    SetOutcomeRequest(
                        id = cpVisitId,
                        outcome = OUTCOME_NOT_INTERESTED,
                        notes = "Rejected by client at CP visit",
                    ),
                )
                if (!outcomeResp.success) {
                    finishLockedReject(outcomeResp.error ?: "Failed to record rejection")
                    return@launch
                }
                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to true,
                        KEY_OUTCOME to OUTCOME_NOT_INTERESTED,
                    ),
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                finishLockedReject(e.message ?: "Network error")
            }
        }
    }

    private fun finishLockedReject(error: String) {
        btnCpLockedConfirm?.isClickable = true
        btnCpLockedReject?.isClickable = true
        btnCpLockedReject?.text = "Reject It"
        showError(error)
    }

    companion object {
        private const val LOG_TAG = "CpOutcomeSheet"
        const val RESULT_KEY = "cp_visit_complete_result"
        const val KEY_CLIENT_MET = "clientMet"
        const val KEY_OUTCOME = "outcome"
        private const val ARG_CP_VISIT_ID = "arg_cp_visit_id"
        private const val ARG_CP_CLIENT_MET = "arg_cp_client_met"
        private const val ARG_CP_OUTCOME = "arg_cp_outcome"
        // Pre-pass from TripNavigationFragment when the upstream
        // reconcile already determined this CP came from a telecaller-
        // fixed SV. Lets us avoid the brief Booking-tab flash while the
        // sheet's own detect call resolves.
        private const val ARG_IS_SV_FIXED_HINT = "arg_is_sv_fixed_hint"

        private const val OUTCOME_BOOKING = "converted_to_booking"
        private const val OUTCOME_SITE_VISIT = "converted_to_site_visit"
        private const val OUTCOME_POSTPONED = "postponed"
        private const val OUTCOME_NOT_INTERESTED = "not_interested"

        fun newInstance(
            cpVisitId: String,
            cpClientMet: Boolean? = null,
            cpOutcome: String? = null,
            isSvFixedHint: Boolean = false,
        ): CompleteCpVisitBottomSheet =
            CompleteCpVisitBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CP_VISIT_ID, cpVisitId)
                    if (cpClientMet != null) putBoolean(ARG_CP_CLIENT_MET, cpClientMet)
                    if (!cpOutcome.isNullOrBlank()) putString(ARG_CP_OUTCOME, cpOutcome)
                    if (isSvFixedHint) putBoolean(ARG_IS_SV_FIXED_HINT, true)
                }
            }
    }
}
