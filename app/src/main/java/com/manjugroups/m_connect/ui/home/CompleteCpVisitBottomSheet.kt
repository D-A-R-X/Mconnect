package com.manjugroups.m_connect.ui.home

import android.Manifest
import android.app.Dialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import coil.load
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.util.ongoingOnly
import com.manjugroups.m_connect.network.BookingPlotPrefillResponse
import com.manjugroups.m_connect.network.ClientProfile
import com.manjugroups.m_connect.network.ConvertCpVisitToSiteVisitRequest
import com.manjugroups.m_connect.network.CpVisitDetail
import com.manjugroups.m_connect.network.CreateBookingRequest
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.network.InventoryUnit
import com.manjugroups.m_connect.network.MarkClientMetRequest
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import com.manjugroups.m_connect.network.ProposedSiteVisit
import com.manjugroups.m_connect.network.SetOutcomeRequest
import com.manjugroups.m_connect.network.StorageUploader
import com.manjugroups.m_connect.network.ManualProfilePatch
import com.manjugroups.m_connect.network.SetSiteVisitOutcomeRequest
import com.manjugroups.m_connect.network.SvNotInterestedDetail
import com.manjugroups.m_connect.network.UpdateTelecallerLeadRequest
import com.manjugroups.m_connect.network.SiteVisitAttendeeRequest
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.manjugroups.m_connect.ui.common.showOnce

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

    /**
     * Booking-form auto-save. Pushes the serialised form state to a
     * Convex draft row + a local SharedPreferences mirror on a
     * debounced timer (2s). Restores on dialog open so an app
     * crash, kill-from-recent-apps, or login from another phone all
     * resume the operator's typing instead of throwing it away.
     * See BookingDraftManager.kt for the persistence details.
     */
    private var draftManager: BookingDraftManager? = null
    private var draftRestoreApplied: Boolean = false
    private var draftSuppressSave: Boolean = false

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
    /**
     * The deepest sub-tab the user has progressed THROUGH via the
     * Next button. Sub-tap navigation is gated on this — the user can
     * tap a previously-visited tab to jump back and edit, but cannot
     * tap a tab ahead of where they've validated up to. Forward
     * navigation must still go through Next so the per-step
     * validation runs (required fields, etc.).
     */
    private var maxVisitedBookingSub: BookingSub = BookingSub.CLIENT

    // Form state for radio/checkbox rows
    private var bookIsAgainstVisit: YesNo = YesNo.YES
    private var bookDuplicate: Boolean = true
    private var payGstApplicable: Boolean = true
    private var payOtherApplicable: Boolean = true
    // Balance Payment Schedule plan — "Regular" (30d) / "Flexi" (60d) /
    // "Special" (180d, only when the project enables it). Flexi keeps
    // mapping to the legacy freePayment flag on the wire.
    private var payPlan: String = "Regular"
    // specialPaymentEnabled from the latest plot-prefill; either this or the
    // selected project row unlocks the Special plan option.
    private var plotPrefillSpecialPayment: Boolean = false
    // specialPaymentEnabled resolved from /api/projects/get — that route
    // passes the RAW project doc through on every deployed backend, unlike
    // the trimmed marketing-projects / plot-prefill responses which only
    // carry the flag on newer deploys.
    private var projectDetailSpecialPayment: Boolean = false

    // Client Image (web parity: optional client photo on Client Details).
    // The storage id rides the create payload; server stores it on the
    // clients master row.
    private var clientImageStorageId: String? = null
    private var clientImageFileName: String? = null
    private var clientImageLocalUri: Uri? = null
    private var imgClientPhoto: ImageView? = null
    private var tvClientImageName: TextView? = null
    private var btnClientImageAction: TextView? = null
    private var cardClientImageUpload: View? = null
    private var tvClientImageAction: TextView? = null
    private var rowClientImagePreview: View? = null

    private val clientImageCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showClientImageCamera()
            else Toast.makeText(
                requireContext(), "Camera permission is needed to take a photo", Toast.LENGTH_SHORT,
            ).show()
        }

    private val pickClientImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) uploadClientImage(uri)
        }
    private enum class BookingDocumentKind { ADVANCE_PROOF, AADHAAR, PAN }
    private var pendingBookingDocumentKind: BookingDocumentKind? = null
    private var advanceProofStorageId: String? = null
    private var advanceProofFileName: String? = null
    private var aadhaarDocumentStorageId: String? = null
    private var aadhaarDocumentFileName: String? = null
    private var panDocumentStorageId: String? = null
    private var panDocumentFileName: String? = null
    private val pickBookingDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val kind = pendingBookingDocumentKind
            pendingBookingDocumentKind = null
            if (uri != null && kind != null) uploadBookingDocument(uri, kind)
        }
    private var staffSaveAs: SaveAs = SaveAs.DRAFT
    private var lastBookingPrefillKey: String? = null
    private var bookingGstPercent: Double? = null

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

    // Cached CP visit detail captured when the locked-SV mode is engaged.
    // The Confirm button path uses `convertedSiteVisitId` to decide whether
    // the SV already exists (telecaller pre-created it via
    // siteVisits.create with clientPlaceVisitId — call setOutcome to flip
    // the existing pending SV to confirmed) or still needs to be
    // materialized from the proposed payload (call convertToSiteVisit).
    // Without this distinction the old Confirm path was hitting
    // convertCpVisitToSiteVisit unconditionally, and that mutation
    // short-circuits when convertedSiteVisitId is already set — so the
    // linked SV's confirmationStatus stayed "pending" and the row never
    // left the Fixed tab on the web's /marketing/site-visits page.
    private var lockedCpVisit: CpVisitDetail? = null

    /**
     * "Pure SV" outcome mode. When [ARG_SITE_VISIT_ID] is set the sheet
     * is being invoked from the Trip Details screen of a real siteVisits
     * row (not a CP visit). In this mode:
     *   - The Site Visit top tab is disabled and faded (this row IS
     *     already an SV — re-converting would be nonsensical).
     *   - Booking / Postpone / Not Interested all persist via the
     *     /api/marketing/siteVisits/... endpoints instead of the CP
     *     path.
     *   - markClientMet is skipped (no CP visit to mark).
     */
    private val isSiteVisitMode: Boolean
        get() {
            val raw = arguments?.getString(ARG_SITE_VISIT_ID)
            return !raw.isNullOrBlank()
        }

    private val argSiteVisitId: String?
        get() = arguments?.getString(ARG_SITE_VISIT_ID)?.takeIf { it.isNotBlank() }

    private val siteVisitLockedOutcome: Outcome?
        get() = arguments?.getString(ARG_SITE_VISIT_LOCKED_OUTCOME)
            ?.takeIf { it.isNotBlank() }
            ?.let(::outcomeFromArg)

    /**
     * Standalone booking mode. Set when the sheet is opened from the
     * Bookings list (+ button) — no CP visit and no SV row behind it.
     * In this mode:
     *   - Site Visit / Postpone / Not Interested top tabs are faded
     *     and unclickable (only Booking makes sense without a visit
     *     to attach an outcome to).
     *   - persistBooking POSTs directly to /api/bookings via
     *     api.createBooking instead of the CP setOutcome path.
     */
    private val isStandaloneBookingMode: Boolean
        get() = arguments?.getBoolean(ARG_STANDALONE_BOOKING, false) == true

    /**
     * Cached display name for the visit's lead/client, set when the
     * locked SV mode initialises. Used by [renderVisitorRows] so the
     * first visitor card auto-fills with the lead's name + Relation =
     * Self — the common 1-visitor case where the only attendee is
     * the client themself. Field staff can still edit the row.
     */
    private var cachedLeadDisplayName: String? = null

    /**
     * Cached lead-side attendees array (if the telecaller pre-set any
     * via the SV-fix payload). Replays into the visitor cards
     * one-for-one when [renderVisitorRows] runs.
     */
    private var cachedPrefilledAttendees: List<com.manjugroups.m_connect.network.CpVisitAttendee>? = null

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
    private var etFormHomeDoorNo: EditText? = null
    private var etFormHomeStreet: EditText? = null
    private var etFormHomeAddressLine2: EditText? = null
    private var etFormPincode: EditText? = null
    private var etFormState: EditText? = null
    private var etFormDistrict: EditText? = null
    private var etFormLocation: EditText? = null

    // Professional
    private var tvProfProfession: TextView? = null
    private var tvProfDepartment: TextView? = null
    private var groupProfDepartment: View? = null
    private var groupProfOtherDepartment: View? = null
    private var etProfOtherDepartment: EditText? = null
    private var etProfDesignation: EditText? = null
    private var etProfIncome: EditText? = null

    // Office
    private var etOfficeName: EditText? = null
    private var etOfficeEmail: EditText? = null
    private var etOfficeMobile: EditText? = null
    private var etOfficePhone: EditText? = null
    private var etOfficeAddress: EditText? = null
    private var etOfficeDoorNo: EditText? = null
    private var etOfficeStreet: EditText? = null
    private var etOfficeAddressLine2: EditText? = null
    private var etOfficeArea: EditText? = null
    private var etOfficePincode: EditText? = null

    // Booking
    private var tvBookType: TextView? = null
    private var bookConversionManualEntry: Boolean = true
    private var bookExchangeManualEntry: Boolean = true
    private var groupBookConversion: View? = null
    private var groupBookConversionManual: View? = null
    private var groupBookConversionLinked: View? = null
    private var tvBookConversionSource: TextView? = null
    private var etBookConversionProject: EditText? = null
    private var etBookConversionPlot: EditText? = null
    private var etBookConversionCredit: EditText? = null
    private var etBookConversionNotes: EditText? = null
    private var etBookConversionSourceBooking: EditText? = null
    private var groupBookExchange: View? = null
    private var groupBookExchangeManual: View? = null
    private var groupBookExchangeLinkedInternal: View? = null
    private var groupBookExchangeLinked: View? = null
    private var tvBookExchangeSource: TextView? = null
    private var etBookExchangeProject: EditText? = null
    private var etBookExchangePlot: EditText? = null
    private var etBookExchangeExtent: EditText? = null
    private var etBookExchangeLookupProject: EditText? = null
    private var etBookExchangeLookupPlot: EditText? = null
    private var etBookExchangeMobile: EditText? = null
    private var etBookExchangeSourceBooking: EditText? = null
    private var lblBookExchangeValue: TextView? = null
    private var etBookExchangeValue: EditText? = null
    private var tvBookExchangeBalance: TextView? = null
    private var etBookExchangeNotes: EditText? = null
    private var tvBookSource: TextView? = null
    private var etBookSourceName: EditText? = null
    private var etBookSourceMobile: EditText? = null
    private var etBookReferralBenefit: EditText? = null
    private var etBookCef: EditText? = null
    private var tvBookDate: TextView? = null
    private var tvBookProject: TextView? = null
    private var tvBookPlot: TextView? = null
    private var tvBookProperty: TextView? = null
    private var tvBookMode: TextView? = null
    private var ivBookVisitYes: ImageView? = null
    private var ivBookVisitNo: ImageView? = null
    private var ivBookDuplicate: ImageView? = null
    private var groupBookSiteVisit: View? = null
    private var etBookSvName: EditText? = null
    private var etBookSvMobile: EditText? = null

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
    private var groupBookingChargesAdvance: View? = null
    private var groupBookingPaymentSchedule: View? = null
    private var etPayGstAmount: EditText? = null
    private var ivPayGstApplicable: ImageView? = null
    private var etPayDocCharges: EditText? = null
    private var etPayPattaCharges: EditText? = null
    private var etPayOtherCharges: EditText? = null
    private var ivPayOtherApplicable: ImageView? = null
    private var etPayAdvanceAmount: EditText? = null
    private var tvPayPaymentMode: TextView? = null
    private var lblPayLoanAmount: TextView? = null
    private var rowPayLoanAmount: View? = null
    private var etPayLoanAmount: EditText? = null
    private var tvPayMinimumAdvance: TextView? = null
    private var groupPayDigitalProof: View? = null
    private var etPayTransactionId: EditText? = null
    private var btnPayProofUpload: TextView? = null
    private var groupPayInstrument: View? = null
    private var lblPayInstrumentNo: TextView? = null
    private var etPayInstrumentNo: EditText? = null
    private var etPayBankName: EditText? = null
    private var etPayBankBranch: EditText? = null
    private var tvPayInstrumentDate: TextView? = null
    private var tvPayPlan: TextView? = null
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
    private var btnStaffAadhaarUpload: TextView? = null
    private var btnStaffPanUpload: TextView? = null
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

    // Postpone body — a next-visit date + a single reason box (simpler
    // than the Not Interested multi-reason checklist, which operators
    // were confusing it with). The chosen date is recorded in the notes
    // and the typed reason ships as the single postponeReasons entry the
    // backend's setOutcome requires for outcome="postponed".
    private var bodyPostpone: View? = null
    private var tvPostNextDate: TextView? = null
    private var etPostNotes: EditText? = null

    // Not Interested body — mirrors the web's SV "Mark not interested"
    // dialog (NOT_INTERESTED_REASONS in app/marketing/site-visits/[id]/page.tsx):
    // 8 reason checkboxes, each with an inline per-reason detail input,
    // plus a general optional notes textarea.
    //
    // Each (checkbox, detail) pair is a paired view — the detail input
    // is GONE until the checkbox is ticked. Storage of the picked
    // values:
    //   - notInterestedReasons: List<String> of the web-canonical labels
    //   - notInterestedDetails: List<{reason, detail?}> aligned with
    //     the checked reasons (omits unchecked ones)
    //   - notes: optional general context
    private var bodyNotInterested: View? = null
    private var cbNiPrice: android.widget.CheckBox? = null
    private var etNiPriceDetail: EditText? = null
    private var cbNiDistance: android.widget.CheckBox? = null
    private var etNiDistanceDetail: EditText? = null
    private var cbNiLocation: android.widget.CheckBox? = null
    private var etNiLocationDetail: EditText? = null
    private var cbNiDevelopment: android.widget.CheckBox? = null
    private var etNiDevelopmentDetail: EditText? = null
    private var cbNiPlot: android.widget.CheckBox? = null
    private var etNiPlotDetail: EditText? = null
    private var cbNiLoan: android.widget.CheckBox? = null
    private var etNiLoanDetail: EditText? = null
    private var cbNiStaffApproach: android.widget.CheckBox? = null
    private var etNiStaffApproachDetail: EditText? = null
    private var cbNiDriverApproach: android.widget.CheckBox? = null
    private var etNiDriverApproachDetail: EditText? = null
    private var etNiNotes: EditText? = null

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

    // Booking selections + caches. These back the Booking sub-tab pickers;
    // the labels stay in the XML views, while these hold the IDs needed by
    // the web-parity createBooking mutation.
    private var bookingProject: MarketingProject? = null
    private var bookingUnit: InventoryUnit? = null
    private var bookingStaffAvp: StaffData? = null
    private var bookingStaffGm: StaffData? = null
    private var bookingStaffSm: StaffData? = null
    private var bookingStaffBdo: StaffData? = null
    private var bookingStaffTelecaller: StaffData? = null
    private var bookingProjectCache: List<MarketingProject> = emptyList()
    private var bookingUnitCacheProjectId: String? = null
    private var bookingUnitCache: List<InventoryUnit> = emptyList()
    private var bookingStaffCache: List<StaffData> = emptyList()

    // "Edit" pill on the Client form header. Visible only after a
    // phone lookup actually returns a matching lead and the form is
    // prefilled. Two states:
    //   - inactive (default after prefill): pill is outlined, form
    //     fields are read-only so the user doesn't accidentally edit
    //     canonical lead data.
    //   - active (after tap): pill is filled green, form fields are
    //     editable; persistBooking pushes the new values back to the
    //     lead's manualProfile via /api/telecaller/leads/update.
    private var btnEdit: TextView? = null
    private var btnBack: TextView? = null
    private var btnClear: TextView? = null
    private var editEnabled: Boolean = false
    // _id of the lead the prefill came from; null when no match was
    // found. Drives whether the edit-push fires on submit.
    private var prefilledLeadId: String? = null
    private var lastLookedUpBookingPhone: String? = null

    // Last 6-digit pincode we successfully enriched. Without this the
    // TextWatcher would re-fire `/api/pincode` on every keystroke past
    // digit 6 (and on view-restore), spamming the proxy and racing the
    // user. Reset when the sheet recycles.
    private var lastEnrichedBookingPincode: String? = null
    private var btnSubmit: TextView? = null
    private var tvError: TextView? = null

    // ---- Lifecycle ------------------------------------------------------
    // Tracks whether the host MainActivity's bottom tab bar was visible
    // when this sheet opened. We hide the tab bar for the entire sheet
    // lifetime so the bottom 56dp of the form (Save / Reject buttons,
    // last fields) don't get clipped by the tab strip and so the slide-up
    // animation doesn't briefly composite tab-bar pixels through the
    // sheet's translucent background. Restored on dismiss to whatever
    // state the host had — works correctly both from root tabs (Marketing
    // → CP Visits, where the tab bar is visible) and from secondary
    // screens like CompletedVisitDetailFragment (where it's already
    // hidden and should stay that way).
    private var hostTabBarWasVisible: Boolean? = null

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
                // Pullable now that the form root is a NestedScrollView (was a
                // plain ScrollView): it hands the scroll off to the BottomSheet
                // correctly, so scrolling the body scrolls the content and
                // pulling the handle/header drags the sheet — no more stray
                // dismiss from a content scroll, which is why drag was disabled.
                behavior.isDraggable = true
                isCancelable = true
            }
            // Hide the host activity's bottom tab bar for the sheet's
            // lifetime. Without this the tab bar shows through the
            // translucent scrim during the slide-up animation (visible
            // glitch) and stays peeking under the sheet's rounded bottom
            // corners. Caching the prior state means we restore exactly
            // what was there on dismiss, even if the sheet was opened
            // from a secondary screen where the bar was already hidden.
            (activity as? com.manjugroups.m_connect.MainActivity)?.let { host ->
                if (hostTabBarWasVisible == null) {
                    hostTabBarWasVisible = host.isTabBarVisible()
                }
                host.setTabBarVisible(false)
            }
        }
        return dialog
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        // Restore the host's tab bar to whatever it was before we opened.
        // Runs on Reject / Confirm / Save / system-back / programmatic
        // dismiss — every exit path funnels through here.
        (activity as? com.manjugroups.m_connect.MainActivity)?.let { host ->
            val previous = hostTabBarWasVisible
            if (previous != null) {
                host.setTabBarVisible(previous)
            }
        }
        hostTabBarWasVisible = null
        super.onDismiss(dialog)
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
        btnEdit?.setOnClickListener { toggleEditMode() }
        btnBack = view.findViewById(R.id.btnOutcomeBack)
        btnBack?.setOnClickListener { goBackInBookingFlow() }
        btnClear = view.findViewById(R.id.btnOutcomeClear)
        btnClear?.setOnClickListener { clearBookingForm() }

        // Drag-handle row at the top of the sheet — tap to dismiss.
        // Drag-down dismiss is intentionally disabled (nested form
        // scrolls used to trigger it accidentally), so the handle
        // doubles as the close affordance.
        view.findViewById<View>(R.id.outcomeDragHandleRow)?.setOnClickListener {
            dismissAllowingStateLoss()
        }
        btnSubmit = view.findViewById(R.id.btnCpSubmit)
        tvError = view.findViewById(R.id.tvCpError)

        // Make the trailing " *" on every required-field label render
        // in destructive red so it visually pops the same way the
        // web's `<span className="text-destructive">*</span>` does.
        // The label texts in XML already carry the asterisk (e.g.
        // "Client Name *", "Booking Date *", "Client Phone Number *",
        // "Client Mobile Number *") — we just need to colour just the
        // star, not the whole label, on view bind.
        colorizeRequiredStarsRedRecursively(view as android.view.ViewGroup)

        // Wire booking-form draft auto-save AFTER all findViewById
        // calls so every EditText reference is non-null. Order:
        //   1. Build the manager with the bottom sheet's coroutine
        //      scope so debounced flushes survive a dismiss-and-
        //      reopen by tying lifetime to viewLifecycleOwner.
        //   2. Attach TextWatchers on every form EditText.
        //   3. Async fetch the most-recent draft (cloud OR local,
        //      whichever's newer) and apply it before the AI prefill
        //      from lookup runs so the operator's manual edits win
        //      the priority slot.
        draftManager = BookingDraftManager(
            context = requireContext(),
            api = api,
            scope = viewLifecycleOwner.lifecycleScope,
        )
        attachDraftWatchers()
        restoreDraftIfAny()
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

        // Pure-SV outcome mode (no CP behind it). Disables the Site
        // Visit top tab and pre-selects Booking — only the Booking /
        // Postpone / Not Interested paths persist, and they route to
        // the siteVisits endpoints instead of the CP path.
        if (isSiteVisitMode) applySiteVisitOutcomeMode()

        // Standalone booking mode (Bookings page + button). Lock to
        // the Booking outcome tab so the user is creating a booking
        // from scratch, not recording an outcome against a visit.
        if (isStandaloneBookingMode) applyStandaloneBookingMode()
    }

    private fun applyStandaloneBookingMode() {
        view?.findViewById<TextView>(R.id.tvOutcomeTitle)?.text = "New Booking"
        view?.findViewById<TextView>(R.id.tvOutcomeSubtitle)?.text = "Booking form"
        view?.findViewById<View>(R.id.outcomeTopTabs)?.visibility = View.GONE
        btnEdit?.visibility = View.GONE
        btnBack?.visibility = View.VISIBLE
        btnClear?.visibility = View.VISIBLE
        editEnabled = true
        prefilledLeadId = null
        bookingSub = BookingSub.CLIENT
        bookingStep = BookingStep.CLIENT_FORM
        if (tvBookDate?.text?.toString()?.trim().isNullOrEmpty()) {
            tvBookDate?.text = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                .format(Calendar.getInstance().time)
        }
        applyEditModeToFields(true)
        activeOutcome = Outcome.BOOKING
        renderState()
    }

    private fun applySiteVisitOutcomeMode() {
        // The Site Visit tab itself makes no sense on a row that IS
        // already a site visit — fade + disable it.
        tabSiteVisit.cell?.isClickable = false
        tabSiteVisit.cell?.alpha = 0.35f

        // When launched from a specific SV outcome button, keep the
        // same shared form UI but remove the outcome switcher so the
        // operator only sees the form they chose.
        val lockedOutcome = siteVisitLockedOutcome
        if (lockedOutcome != null) {
            view?.findViewById<View>(R.id.outcomeTopTabs)?.visibility = View.GONE
            view?.findViewById<TextView>(R.id.tvOutcomeTitle)?.text = when (lockedOutcome) {
                Outcome.BOOKING -> "Converted as Booking"
                Outcome.POSTPONE -> "Its Been Postponed"
                Outcome.NOT_INTERESTED -> "Client Not Interested"
                Outcome.SITE_VISIT -> "Site Visit"
            }
            view?.findViewById<TextView>(R.id.tvOutcomeSubtitle)?.text = when (lockedOutcome) {
                Outcome.BOOKING -> "Booking form"
                Outcome.POSTPONE -> "Postponed reason"
                Outcome.NOT_INTERESTED -> "Not interested reason"
                Outcome.SITE_VISIT -> "Site visit form"
            }
        }

        activeOutcome = lockedOutcome ?: Outcome.BOOKING
        renderState()
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
        subTabProfessional?.visibility = View.GONE
        subTabOffice?.visibility = View.GONE
        subTabCharges?.visibility = View.GONE
        subTabPayment?.visibility = View.GONE
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
        imgClientPhoto = view.findViewById(R.id.imgClientPhoto)
        tvClientImageName = view.findViewById(R.id.tvClientImageName)
        btnClientImageAction = view.findViewById(R.id.btnClientImageAction)
        cardClientImageUpload = view.findViewById(R.id.cardClientImageUpload)
        tvClientImageAction = view.findViewById(R.id.tvClientImageAction)
        rowClientImagePreview = view.findViewById(R.id.rowClientImagePreview)
        // The dashed card uploads/replaces; the pill's Remove only clears.
        cardClientImageUpload?.setOnClickListener { openClientImageCamera() }
        btnClientImageAction?.setOnClickListener {
            clientImageStorageId = null
            clientImageFileName = null
            clientImageLocalUri = null
            renderClientImage()
        }
        renderClientImage()
        etFormHomeDoorNo = view.findViewById(R.id.etFormHomeDoorNo)
        etFormHomeStreet = view.findViewById(R.id.etFormHomeStreet)
        etFormHomeAddress = view.findViewById(R.id.etFormHomeAddress)
        etFormHomeAddressLine2 = view.findViewById(R.id.etFormHomeAddressLine2)
        etFormPincode = view.findViewById(R.id.etFormPincode)
        etFormState = view.findViewById(R.id.etFormState)
        etFormDistrict = view.findViewById(R.id.etFormDistrict)
        etFormLocation = view.findViewById(R.id.etFormLocation)
        // Pincode → Location/District/State auto-fill. Mirrors the web's
        // usePincodeLocationEnrichment effect in mms-external-leads.tsx.
        // The post-office Name from India Post IS the locality (e.g.
        // 600083 → "Ashok Nagar"). We only ever fill blank fields, so a
        // manual entry from the operator always wins. The lookup itself
        // is throttled inside PincodeLookup (60s cache + dedup), but we
        // ALSO guard here with lastEnrichedBookingPincode so the same
        // pin doesn't re-fire as the user keeps typing or re-opens the
        // sheet.
        etFormPincode?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val pin = s?.toString()?.trim().orEmpty().filter { it.isDigit() }
                if (pin.length != 6) return
                if (pin == lastEnrichedBookingPincode) return
                lastEnrichedBookingPincode = pin
                enrichBookingPincode(pin)
            }
        })
        (tvFormPhone as? EditText)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val raw = s?.toString()?.trim().orEmpty()
                val digits = raw.filter { it.isDigit() }.takeLast(10)
                if (digits.length < 10 || digits == lastLookedUpBookingPhone) return
                lastLookedUpBookingPhone = digits
                lookupAndPrefillClientByPhone(digits)
            }
        })
    }

    private fun bindProfessionalFields(view: View) {
        tvProfProfession = view.findViewById(R.id.tvProfProfession)
        tvProfDepartment = view.findViewById(R.id.tvProfDepartment)
        groupProfDepartment = view.findViewById(R.id.groupProfDepartment)
        groupProfOtherDepartment = view.findViewById(R.id.groupProfOtherDepartment)
        etProfOtherDepartment = view.findViewById(R.id.etProfOtherDepartment)
        etProfDesignation = view.findViewById(R.id.etProfDesignation)
        etProfIncome = view.findViewById(R.id.etProfIncome)
    }

    private fun bindOfficeFields(view: View) {
        etOfficeName = view.findViewById(R.id.etOfficeName)
        etOfficeEmail = view.findViewById(R.id.etOfficeEmail)
        etOfficeMobile = view.findViewById(R.id.etOfficeMobile)
        etOfficePhone = view.findViewById(R.id.etOfficePhone)
        etOfficeDoorNo = view.findViewById(R.id.etOfficeDoorNo)
        etOfficeStreet = view.findViewById(R.id.etOfficeStreet)
        etOfficeAddress = view.findViewById(R.id.etOfficeAddress)
        etOfficeAddressLine2 = view.findViewById(R.id.etOfficeAddressLine2)
        etOfficeArea = view.findViewById(R.id.etOfficeArea)
        etOfficePincode = view.findViewById(R.id.etOfficePincode)
    }

    private fun bindBookingFields(view: View) {
        tvBookType = view.findViewById(R.id.tvBookType)
        groupBookConversion = view.findViewById(R.id.groupBookConversion)
        groupBookConversionManual = view.findViewById(R.id.groupBookConversionManual)
        groupBookConversionLinked = view.findViewById(R.id.groupBookConversionLinked)
        tvBookConversionSource = view.findViewById(R.id.tvBookConversionSource)
        etBookConversionProject = view.findViewById(R.id.etBookConversionProject)
        etBookConversionPlot = view.findViewById(R.id.etBookConversionPlot)
        etBookConversionCredit = view.findViewById(R.id.etBookConversionCredit)
        etBookConversionNotes = view.findViewById(R.id.etBookConversionNotes)
        etBookConversionSourceBooking = view.findViewById(R.id.etBookConversionSourceBooking)
        groupBookExchange = view.findViewById(R.id.groupBookExchange)
        groupBookExchangeManual = view.findViewById(R.id.groupBookExchangeManual)
        groupBookExchangeLinkedInternal = view.findViewById(R.id.groupBookExchangeLinkedInternal)
        groupBookExchangeLinked = view.findViewById(R.id.groupBookExchangeLinked)
        tvBookExchangeSource = view.findViewById(R.id.tvBookExchangeSource)
        etBookExchangeProject = view.findViewById(R.id.etBookExchangeProject)
        etBookExchangePlot = view.findViewById(R.id.etBookExchangePlot)
        etBookExchangeExtent = view.findViewById(R.id.etBookExchangeExtent)
        etBookExchangeLookupProject = view.findViewById(R.id.etBookExchangeLookupProject)
        etBookExchangeLookupPlot = view.findViewById(R.id.etBookExchangeLookupPlot)
        etBookExchangeMobile = view.findViewById(R.id.etBookExchangeMobile)
        etBookExchangeSourceBooking = view.findViewById(R.id.etBookExchangeSourceBooking)
        lblBookExchangeValue = view.findViewById(R.id.lblBookExchangeValue)
        etBookExchangeValue = view.findViewById(R.id.etBookExchangeValue)
        tvBookExchangeBalance = view.findViewById(R.id.tvBookExchangeBalance)
        etBookExchangeNotes = view.findViewById(R.id.etBookExchangeNotes)
        tvBookSource = view.findViewById(R.id.tvBookSource)
        etBookSourceName = view.findViewById(R.id.etBookSourceName)
        etBookSourceMobile = view.findViewById(R.id.etBookSourceMobile)
        etBookReferralBenefit = view.findViewById(R.id.etBookReferralBenefit)
        etBookCef = view.findViewById(R.id.etBookCef)
        tvBookDate = view.findViewById(R.id.tvBookDate)
        tvBookProject = view.findViewById(R.id.tvBookProject)
        tvBookPlot = view.findViewById(R.id.tvBookPlot)
        tvBookProperty = view.findViewById(R.id.tvBookProperty)
        tvBookMode = view.findViewById(R.id.tvBookMode)
        ivBookVisitYes = view.findViewById(R.id.ivBookVisitYes)
        ivBookVisitNo = view.findViewById(R.id.ivBookVisitNo)
        ivBookDuplicate = view.findViewById(R.id.ivBookDuplicate)
        groupBookSiteVisit = view.findViewById(R.id.groupBookSiteVisit)
        etBookSvName = view.findViewById(R.id.etBookSvName)
        etBookSvMobile = view.findViewById(R.id.etBookSvMobile)
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
        groupBookingChargesAdvance = view.findViewById(R.id.groupBookingChargesAdvance)
        groupBookingPaymentSchedule = view.findViewById(R.id.groupBookingPaymentSchedule)
        etPayRegCharges = view.findViewById(R.id.etPayRegCharges)
        etPayGstAmount = view.findViewById(R.id.etPayGstAmount)
        ivPayGstApplicable = view.findViewById(R.id.ivPayGstApplicable)
        etPayDocCharges = view.findViewById(R.id.etPayDocCharges)
        etPayPattaCharges = view.findViewById(R.id.etPayPattaCharges)
        etPayOtherCharges = view.findViewById(R.id.etPayOtherCharges)
        ivPayOtherApplicable = view.findViewById(R.id.ivPayOtherApplicable)
        etPayAdvanceAmount = view.findViewById(R.id.etPayAdvanceAmount)
        tvPayPaymentMode = view.findViewById(R.id.tvPayPaymentMode)
        lblPayLoanAmount = view.findViewById(R.id.lblPayLoanAmount)
        rowPayLoanAmount = view.findViewById(R.id.rowPayLoanAmount)
        etPayLoanAmount = view.findViewById(R.id.etPayLoanAmount)
        tvPayMinimumAdvance = view.findViewById(R.id.tvPayMinimumAdvance)
        groupPayDigitalProof = view.findViewById(R.id.groupPayDigitalProof)
        etPayTransactionId = view.findViewById(R.id.etPayTransactionId)
        btnPayProofUpload = view.findViewById(R.id.btnPayProofUpload)
        groupPayInstrument = view.findViewById(R.id.groupPayInstrument)
        lblPayInstrumentNo = view.findViewById(R.id.lblPayInstrumentNo)
        etPayInstrumentNo = view.findViewById(R.id.etPayInstrumentNo)
        etPayBankName = view.findViewById(R.id.etPayBankName)
        etPayBankBranch = view.findViewById(R.id.etPayBankBranch)
        tvPayInstrumentDate = view.findViewById(R.id.tvPayInstrumentDate)
        tvPayPlan = view.findViewById(R.id.tvPayPlan)
        etPayAllotDue = view.findViewById(R.id.etPayAllotDue)
        tvPayAllotDate = view.findViewById(R.id.tvPayAllotDate)
        etPay2Mode = view.findViewById(R.id.etPay2Mode)
        tvPay2Date = view.findViewById(R.id.tvPay2Date)
        etPay3Mode = view.findViewById(R.id.etPay3Mode)
        tvPay3Date = view.findViewById(R.id.tvPay3Date)
        etPay4Mode = view.findViewById(R.id.etPay4Mode)
        tvPay4Date = view.findViewById(R.id.tvPay4Date)
        tvPayPrefReg = view.findViewById(R.id.tvPayPrefReg)
        val recomputeWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applySpecialConsiderationVisibility()
                recomputeBookingFinanceDerivedFields()
            }
        }
        etChargeBookingCost?.addTextChangedListener(recomputeWatcher)
        etChargeSpecialConsideration?.addTextChangedListener(recomputeWatcher)
        etChargeGuidelineValue?.addTextChangedListener(recomputeWatcher)
        etPayAdvanceAmount?.addTextChangedListener(recomputeWatcher)
        etPayRegCharges?.addTextChangedListener(recomputeWatcher)
        etPayGstAmount?.addTextChangedListener(recomputeWatcher)
        etPayDocCharges?.addTextChangedListener(recomputeWatcher)
        etPayPattaCharges?.addTextChangedListener(recomputeWatcher)
        etPayOtherCharges?.addTextChangedListener(recomputeWatcher)
        applySpecialConsiderationVisibility()
        applyAdvancePaymentVisibility()
    }

    private fun bindStaffFields(view: View) {
        tvStaffAvp = view.findViewById(R.id.tvStaffAvp)
        tvStaffGm = view.findViewById(R.id.tvStaffGm)
        tvStaffSm = view.findViewById(R.id.tvStaffSm)
        tvStaffBdo = view.findViewById(R.id.tvStaffBdo)
        tvStaffTelecaller = view.findViewById(R.id.tvStaffTelecaller)
        etStaffAadhar = view.findViewById(R.id.etStaffAadhar)
        etStaffPancard = view.findViewById(R.id.etStaffPancard)
        btnStaffAadhaarUpload = view.findViewById(R.id.btnStaffAadhaarUpload)
        btnStaffPanUpload = view.findViewById(R.id.btnStaffPanUpload)
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
        tvPostNextDate = view.findViewById(R.id.tvPostNextDate)
        etPostNotes = view.findViewById(R.id.etPostNotes)
        // Next-visit date can't be in the past — a postpone always moves
        // the visit forward.
        view.findViewById<View>(R.id.rowPostNextDate)?.setOnClickListener {
            pickDate(tvPostNextDate, minDateMillis = System.currentTimeMillis())
        }
    }

    private fun bindNotInterestedFields(view: View) {
        bodyNotInterested = view.findViewById(R.id.bodyNotInterested)
        cbNiPrice = view.findViewById(R.id.cbNiPrice)
        etNiPriceDetail = view.findViewById(R.id.etNiPriceDetail)
        cbNiDistance = view.findViewById(R.id.cbNiDistance)
        etNiDistanceDetail = view.findViewById(R.id.etNiDistanceDetail)
        cbNiLocation = view.findViewById(R.id.cbNiLocation)
        etNiLocationDetail = view.findViewById(R.id.etNiLocationDetail)
        cbNiDevelopment = view.findViewById(R.id.cbNiDevelopment)
        etNiDevelopmentDetail = view.findViewById(R.id.etNiDevelopmentDetail)
        cbNiPlot = view.findViewById(R.id.cbNiPlot)
        etNiPlotDetail = view.findViewById(R.id.etNiPlotDetail)
        cbNiLoan = view.findViewById(R.id.cbNiLoan)
        etNiLoanDetail = view.findViewById(R.id.etNiLoanDetail)
        cbNiStaffApproach = view.findViewById(R.id.cbNiStaffApproach)
        etNiStaffApproachDetail = view.findViewById(R.id.etNiStaffApproachDetail)
        cbNiDriverApproach = view.findViewById(R.id.cbNiDriverApproach)
        etNiDriverApproachDetail = view.findViewById(R.id.etNiDriverApproachDetail)
        etNiNotes = view.findViewById(R.id.etNiNotes)
        // Toggle each per-reason detail input as its checkbox is
        // ticked — matches the web pattern where the detail input
        // only appears AFTER the reason is selected. Same behaviour
        // for both modes (CP serialises into notes; SV ships them
        // as notInterestedDetails to the backend).
        wireNotInterestedDetailVisibility()
    }

    private fun wireNotInterestedDetailVisibility() {
        val pairs = niReasonPairs()
        pairs.forEach { (cb, detail) ->
            // Initial sync — sheet may be re-opened with prior state
            // restored elsewhere later, but for now nothing pre-checks.
            detail?.visibility = if (cb?.isChecked == true) View.VISIBLE else View.GONE
            cb?.setOnCheckedChangeListener { _, isChecked ->
                detail?.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (!isChecked) detail?.setText("")
            }
        }
    }

    /**
     * Returns the (checkbox, detail-input) pairs in the SAME order as
     * the web's NOT_INTERESTED_REASONS so labels and array indices stay
     * aligned across the two surfaces.
     */
    private fun niReasonPairs(): List<Pair<android.widget.CheckBox?, EditText?>> = listOf(
        cbNiPrice to etNiPriceDetail,
        cbNiDistance to etNiDistanceDetail,
        cbNiLocation to etNiLocationDetail,
        cbNiDevelopment to etNiDevelopmentDetail,
        cbNiPlot to etNiPlotDetail,
        cbNiLoan to etNiLoanDetail,
        cbNiStaffApproach to etNiStaffApproachDetail,
        cbNiDriverApproach to etNiDriverApproachDetail,
    )

    /**
     * The 8 web-canonical reason labels in display order. Must match
     * NOT_INTERESTED_REASONS in
     * app/marketing/site-visits/[id]/page.tsx byte-for-byte so a row
     * saved by mobile reads identically from the web admin.
     */
    private val NI_REASON_LABELS = listOf(
        "Price",
        "Distance",
        "Location",
        "Development in sourcing area",
        "Preferred plot not choice",
        "Loan eligibility",
        "Internal staff approach or behaviour",
        "Driver approach or behaviour",
    )

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

        // Sub-tab navigation: forward must still go through Next so
        // per-step validation runs (required fields, etc.). BACKWARD
        // and re-visiting an already-seen sub-tab is allowed via tap
        // — the operator often realises they typed something wrong on
        // an earlier tab while filling a later one. Gating is on
        // `maxVisitedBookingSub` so an unvisited future tab can't be
        // tap-skipped past validation.
        fun wireSubTabJump(pill: TextView?, target: BookingSub) {
            pill?.isClickable = true
            pill?.isFocusable = true
            pill?.setOnClickListener {
                if (activeOutcome != Outcome.BOOKING) return@setOnClickListener
                if (target == bookingSub) return@setOnClickListener
                // Free navigation: any sub-tab can be jumped to in any
                // order. The operator often has details for a later tab
                // ready before an earlier one is complete (e.g. they
                // already have the payment mode but are still finding
                // the client's pincode). Submit-time validation in
                // `confirmationMissingFields` is the gate that prevents
                // an incomplete form from being saved.
                switchBookingSub(target)
            }
        }
        wireSubTabJump(subTabClient, BookingSub.CLIENT)
        wireSubTabJump(subTabProfessional, BookingSub.PROFESSIONAL)
        wireSubTabJump(subTabOffice, BookingSub.OFFICE)
        wireSubTabJump(subTabBooking, BookingSub.BOOKING)
        wireSubTabJump(subTabCharges, BookingSub.CHARGES)
        wireSubTabJump(subTabPayment, BookingSub.PAYMENT)
        wireSubTabJump(subTabStaff, BookingSub.STAFF)

        // Client-form date / dropdown pickers
        view?.findViewById<View>(R.id.rowFormTitle)?.setOnClickListener {
            picker("Select Title", listOf("Mr", "Mrs", "Ms", "Dr", "Prof")) { tvFormTitle?.text = it }
        }
        view?.findViewById<View>(R.id.rowFormDob)?.setOnClickListener { pickDate(tvFormDob) }
        view?.findViewById<View>(R.id.rowFormAnniversary)?.setOnClickListener { pickDate(tvFormAnniversary) }
        view?.findViewById<View>(R.id.rowFormNationality)?.setOnClickListener {
            picker("Select Nationality", listOf("Indian", "NRI", "Foreign National")) { tvFormNationality?.text = it }
        }

        // Profession dropdown. Matches the web Booking · Professional
        // form's three options exactly (Business / Salaried / Pension)
        // — the older Self-Employed / Other entries from mobile were
        // never accepted by the web's profession union and have been
        // removed. Uses an inline PopupMenu anchored to the row so the
        // panel drops down under the field (like the web's <select>)
        // instead of opening the generic SearchableSelectionDialog
        // bottom sheet — only ~3 choices, no search needed.
        view?.findViewById<View>(R.id.rowProfProfession)?.let { anchor ->
            anchor.setOnClickListener {
                val popup = android.widget.PopupMenu(requireContext(), anchor)
                listOf("Business", "Salaried", "Pension").forEachIndexed { idx, label ->
                    popup.menu.add(0, idx, idx, label)
                }
                popup.setOnMenuItemClickListener { menuItem ->
                    tvProfProfession?.text = menuItem.title
                    val salaried = menuItem.title.toString() == "Salaried"
                    groupProfDepartment?.visibility = if (salaried) View.VISIBLE else View.GONE
                    if (!salaried) {
                        tvProfDepartment?.text = ""
                        etProfOtherDepartment?.text?.clear()
                        groupProfOtherDepartment?.visibility = View.GONE
                    }
                    scheduleDraftPushIfActive()
                    true
                }
                popup.show()
            }
        }
        view?.findViewById<View>(R.id.rowProfDepartment)?.setOnClickListener {
            picker(
                "Select Department",
                listOf("Admin", "Sales", "HR", "Software Developer", "Other"),
            ) {
                tvProfDepartment?.text = it
                val other = it == "Other"
                groupProfOtherDepartment?.visibility = if (other) View.VISIBLE else View.GONE
                if (!other) etProfOtherDepartment?.text?.clear()
            }
        }

        // Booking sub-tab pickers.
        //
        // Each list mirrors the web's option set in
        // `app/marketing/bookings/new/page.tsx` so the values the
        // mobile sends match what the backend booking-mutation
        // validator + downstream reports expect. Any drift here
        // shows up as either a validation reject ("X is required")
        // or — worse — a row that silently lands with a value no
        // other surface can filter on (a CP/SV-tab list of bookings
        // with bookingType="Direct" disappears from the web's
        // bookingType=NEW filter).
        view?.findViewById<View>(R.id.rowBookType)?.setOnClickListener {
            // Web: ["NEW", "CONVERSION", "EXCHANGE", "INTERNAL EXCHANGE"]
            picker(
                "Select Booking Type",
                listOf("NEW", "CONVERSION", "EXCHANGE", "INTERNAL EXCHANGE"),
            ) {
                tvBookType?.text = it
                refreshBookingTypeSections()
            }
        }
        view?.findViewById<View>(R.id.rowBookConversionSource)?.setOnClickListener {
            bookConversionManualEntry = !bookConversionManualEntry
            refreshBookingTypeSections()
            scheduleDraftPushIfActive()
        }
        view?.findViewById<View>(R.id.rowBookExchangeSource)?.setOnClickListener {
            bookExchangeManualEntry = !bookExchangeManualEntry
            refreshBookingTypeSections()
            scheduleDraftPushIfActive()
        }
        etBookExchangeValue?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateExchangeBalance()
        })
        view?.findViewById<View>(R.id.rowBookSource)?.setOnClickListener {
            picker(
                "Select Client Source",
                listOf(
                    "Direct / Walk-in",
                    "Reference",
                    "Channel Partner",
                    "Site Visit",
                    "Online / Social Media",
                    "Other",
                ),
            ) { tvBookSource?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookDate)?.setOnClickListener {
            pickDate(tvBookDate) {
                loadBookingPlotPrefill(force = true)
                // The payment windows are anchored to the booking date —
                // snap any now-out-of-window scheduled dates back in.
                clampPaymentDatesToPlan()
            }
        }
        view?.findViewById<View>(R.id.rowBookProject)?.setOnClickListener {
            pickBookingProject()
        }
        view?.findViewById<View>(R.id.rowBookPlot)?.setOnClickListener {
            pickBookingUnit()
        }
        view?.findViewById<View>(R.id.rowBookProperty)?.setOnClickListener {
            // Web: ["Plot", "Apartment", "Villa", "Commercial"] —
            // mobile was missing "Commercial".
            picker(
                "Select Property Type",
                listOf("Plot", "Apartment", "Villa", "Commercial"),
            ) { tvBookProperty?.text = it }
        }
        view?.findViewById<View>(R.id.rowBookMode)?.setOnClickListener {
            picker(
                "Select Advance Booking Payment",
                listOf("CASH", "UPI", "NEFT", "RTGS", "CHEQUE", "DD"),
            ) {
                tvBookMode?.text = it
                applyAdvancePaymentVisibility()
            }
        }
        view?.findViewById<View>(R.id.rowBookVisitYes)?.setOnClickListener {
            bookIsAgainstVisit = YesNo.YES
            refreshBookingRadios()
            groupBookSiteVisit?.visibility = View.VISIBLE
        }
        view?.findViewById<View>(R.id.rowBookVisitNo)?.setOnClickListener {
            bookIsAgainstVisit = YesNo.NO
            refreshBookingRadios()
            groupBookSiteVisit?.visibility = View.GONE
        }
        view?.findViewById<View>(R.id.rowBookDuplicate)?.setOnClickListener {
            bookDuplicate = !bookDuplicate
            ivBookDuplicate?.setImageResource(
                if (bookDuplicate) R.drawable.ic_outcome_radio_on else R.drawable.ic_outcome_radio_off
            )
        }

        // Charges picker
        view?.findViewById<View>(R.id.rowChargePromoTnc)?.setOnClickListener {
            picker(
                "Select Terms & Conditions",
                listOf(
                    "Registration within 7 days",
                    "Registration within 15 days",
                    "Registration within 30 days",
                ),
            ) {
                tvChargePromoTnc?.text = it
                etChargeOfferValidity?.setText(it.filter(Char::isDigit))
            }
        }

        // Payment toggles + pickers
        view?.findViewById<View>(R.id.rowPayGstApplicable)?.setOnClickListener {
            payGstApplicable = !payGstApplicable
            ivPayGstApplicable?.setImageResource(
                if (payGstApplicable) R.drawable.ic_outcome_checkbox_checked
                else R.drawable.ic_outcome_checkbox_empty
            )
            recomputeBookingFinanceDerivedFields()
            scheduleDraftPushIfActive()
        }
        view?.findViewById<View>(R.id.rowPayOtherApplicable)?.setOnClickListener {
            payOtherApplicable = !payOtherApplicable
            ivPayOtherApplicable?.setImageResource(
                if (payOtherApplicable) R.drawable.ic_outcome_checkbox_checked
                else R.drawable.ic_outcome_checkbox_empty
            )
            recomputeBookingFinanceDerivedFields()
            scheduleDraftPushIfActive()
        }
        view?.findViewById<View>(R.id.rowPayPaymentMode)?.setOnClickListener {
            // Customer Payment Category — matches the web Booking
            // form's A/B/C union exactly. Selecting "B - Loan
            // Customer" reveals the conditional Loan Amount Required
            // field below; A and C hide it (and any value typed there
            // is dropped on submit since loanAmountRequested is only
            // emitted for B). The on-submit body parses the first
            // char of this label to send "A"/"B"/"C" as
            // customerPaymentCategory and the full label as
            // paymentMode for backward-compat with what older mobile
            // builds wrote.
            picker(
                "Select Customer Payment Category",
                listOf(
                    "A - Self Finance / Hand Cash",
                    "B - Loan Customer",
                    "C - High Risk",
                ),
            ) {
                tvPayPaymentMode?.text = it
                applyLoanAmountVisibility()
            }
        }
        view?.findViewById<View>(R.id.rowPayPlan)?.setOnClickListener {
            // Payment Plan — mirrors the web Balance Payment Schedule
            // select: Regular/Flexi always, Special only when the selected
            // project has specialPaymentEnabled (project row or plot
            // prefill). Flexi keeps mapping to the freePayment flag the
            // backend already understands.
            // If the trimmed project row left the Special gate unknown and
            // the background resolve hasn't landed yet, kick it again so
            // the next open reflects the real flag.
            if (!specialPaymentAllowed() && bookingProject?.specialPaymentEnabled == null) {
                resolveProjectSpecialPaymentFlag(bookingProject?.id)
            }
            picker("Select Payment Plan", paymentPlanOptions()) {
                payPlan = planFromLabel(it)
                tvPayPlan?.text = it
                clampPaymentDatesToPlan()
            }
        }
        // Web parity on date windows: allotment due ≤ 10 days from booking
        // date (all plans); 2nd/3rd/4th ≤ the plan's window (30/60/180).
        view?.findViewById<View>(R.id.rowPayAllotDate)?.setOnClickListener {
            pickDate(tvPayAllotDate, maxDateMillis = paymentDateLimitMillis(10))
        }
        view?.findViewById<View>(R.id.rowPay2Date)?.setOnClickListener {
            pickDate(tvPay2Date, maxDateMillis = paymentDateLimitMillis(paymentPlanDays()))
        }
        view?.findViewById<View>(R.id.rowPay3Date)?.setOnClickListener {
            pickDate(tvPay3Date, maxDateMillis = paymentDateLimitMillis(paymentPlanDays()))
        }
        view?.findViewById<View>(R.id.rowPay4Date)?.setOnClickListener {
            pickDate(tvPay4Date, maxDateMillis = paymentDateLimitMillis(paymentPlanDays()))
        }
        view?.findViewById<View>(R.id.rowPayPrefReg)?.setOnClickListener { pickDate(tvPayPrefReg) }
        view?.findViewById<View>(R.id.rowPayInstrumentDate)?.setOnClickListener {
            pickDate(tvPayInstrumentDate)
        }
        btnPayProofUpload?.setOnClickListener {
            chooseBookingDocument(BookingDocumentKind.ADVANCE_PROOF)
        }

        // Staff pickers + radio
        view?.findViewById<View>(R.id.rowStaffAvp)?.setOnClickListener {
            pickBookingStaff("Select AVP", "avp") { bookingStaffAvp = it; tvStaffAvp?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowStaffGm)?.setOnClickListener {
            pickBookingStaff("Select GM", "gm") { bookingStaffGm = it; tvStaffGm?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowStaffSm)?.setOnClickListener {
            pickBookingStaff("Select Senior Manager", "seniorManager") { bookingStaffSm = it; tvStaffSm?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowStaffBdo)?.setOnClickListener {
            pickBookingStaff("Select BDO", "bdo") { bookingStaffBdo = it; tvStaffBdo?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowStaffTelecaller)?.setOnClickListener {
            pickBookingStaff("Select Telecaller", "telecaller") { bookingStaffTelecaller = it; tvStaffTelecaller?.text = it.name ?: "Selected" }
        }
        view?.findViewById<View>(R.id.rowStaffDocPrep)?.setOnClickListener {
            picker(
                "Document Language",
                listOf("English", "Kannada", "Tamil", "Telugu", "Hindi"),
            ) { tvStaffDocPrep?.text = it }
        }
        val relations = listOf(
            "Father", "Mother", "Spouse", "Brother", "Sister", "Son", "Daughter",
            "Friend", "Colleague", "Neighbour", "Relative", "Other",
        )
        view?.findViewById<View>(R.id.rowStaffRefRelation1)?.setOnClickListener {
            picker("Reference Relation 1", relations) { etStaffRefProf1?.setText(it) }
        }
        view?.findViewById<View>(R.id.rowStaffRefRelation2)?.setOnClickListener {
            picker("Reference Relation 2", relations) { etStaffRefProf2?.setText(it) }
        }
        btnStaffAadhaarUpload?.setOnClickListener {
            chooseBookingDocument(BookingDocumentKind.AADHAAR)
        }
        btnStaffPanUpload?.setOnClickListener {
            chooseBookingDocument(BookingDocumentKind.PAN)
        }
        view?.findViewById<View>(R.id.rowStaffSaveDraft)?.setOnClickListener {
            staffSaveAs = SaveAs.DRAFT; refreshStaffSaveRadios()
        }
        view?.findViewById<View>(R.id.rowStaffSaveConfirmed)?.setOnClickListener {
            staffSaveAs = SaveAs.CONFIRMED; refreshStaffSaveRadios()
        }

        // Top-level chrome — Edit button was removed from the layout.
        btnSubmit?.setOnClickListener { onCtaTap() }

        // ---- Site Visit interactions ----
        view?.findViewById<View>(R.id.rowSvProject)?.setOnClickListener { pickSvProject() }
        view?.findViewById<View>(R.id.rowSvDate)?.setOnClickListener { pickDate(tvSvDate, minDateMillis = System.currentTimeMillis()) }
        view?.findViewById<View>(R.id.rowSvTime)?.setOnClickListener { pickTime(tvSvTime) }

        view?.findViewById<View>(R.id.rowSvIncharge)?.setOnClickListener {
            pickSvStaff("Select Site Incharge") {
                svIncharge = it
                tvSvIncharge?.text = it.name ?: "Selected"
                autoFillSvHierarchyFrom(it, SvHierarchyLevel.INCHARGE)
            }
        }
        view?.findViewById<View>(R.id.rowSvHod)?.setOnClickListener {
            pickSvStaff("Select HOD") {
                svHod = it
                tvSvHod?.text = it.name ?: "Selected"
                autoFillSvHierarchyFrom(it, SvHierarchyLevel.HOD)
            }
        }
        view?.findViewById<View>(R.id.rowSvAvp)?.setOnClickListener {
            pickSvStaff("Select AVP") {
                svAvp = it
                tvSvAvp?.text = it.name ?: "Selected"
                autoFillSvHierarchyFrom(it, SvHierarchyLevel.AVP)
            }
        }
        view?.findViewById<View>(R.id.rowSvGm)?.setOnClickListener {
            pickSvStaff("Select GM") {
                svGm = it
                tvSvGm?.text = it.name ?: "Selected"
                autoFillSvHierarchyFrom(it, SvHierarchyLevel.GM)
            }
        }
        view?.findViewById<View>(R.id.rowSvSm)?.setOnClickListener {
            // SM is the top of the chain — nothing above to auto-fill.
            pickSvStaff("Select Senior Manager") {
                svSm = it
                tvSvSm?.text = it.name ?: "Selected"
            }
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

        // Postpone tab no longer has interactive rows — the layout is a
        // pure checkbox list (matching the web's Postpone dialog) plus
        // a notes textarea. The previous Date & Time picker for a
        // follow-up was dropped because the web equivalent doesn't
        // carry one; the office tracks rescheduling from the
        // approval/timeline view, not from the field-staff sheet.
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
        groupBookSiteVisit?.visibility =
            if (bookIsAgainstVisit == YesNo.YES) View.VISIBLE else View.GONE
    }

    private fun refreshBookingTypeSections() {
        val type = textOrNull(tvBookType?.text).orEmpty()
        val isConversion = type == "CONVERSION"
        val isExchange = type == "EXCHANGE" || type == "INTERNAL EXCHANGE"
        groupBookConversion?.visibility = if (isConversion) View.VISIBLE else View.GONE
        groupBookExchange?.visibility = if (isExchange) View.VISIBLE else View.GONE

        tvBookConversionSource?.text = if (bookConversionManualEntry) {
            "Manual previous booking entry"
        } else {
            "Linked previous booking"
        }
        groupBookConversionManual?.visibility =
            if (isConversion && bookConversionManualEntry) View.VISIBLE else View.GONE
        groupBookConversionLinked?.visibility =
            if (isConversion && !bookConversionManualEntry) View.VISIBLE else View.GONE

        tvBookExchangeSource?.text = if (bookExchangeManualEntry) {
            "Manual old property entry"
        } else {
            "Linked old property"
        }
        groupBookExchangeManual?.visibility =
            if (isExchange && bookExchangeManualEntry) View.VISIBLE else View.GONE
        groupBookExchangeLinkedInternal?.visibility =
            if (type == "INTERNAL EXCHANGE" && !bookExchangeManualEntry) View.VISIBLE else View.GONE
        groupBookExchangeLinked?.visibility =
            if (isExchange && !bookExchangeManualEntry) View.VISIBLE else View.GONE
        lblBookExchangeValue?.text = if (type == "EXCHANGE") "Exchange Value *" else "Exchange Value"
        tvBookExchangeBalance?.visibility = if (type == "EXCHANGE") View.VISIBLE else View.GONE
        updateExchangeBalance()
    }

    private fun updateExchangeBalance() {
        if (textOrNull(tvBookType?.text) != "EXCHANGE") return
        val total = calculatedTotalPayableAmount() ?: 0.0
        val exchange = numberOrNull(etBookExchangeValue?.text) ?: 0.0
        tvBookExchangeBalance?.text = String.format(
            Locale.US,
            "Balance Payable: ₹%,.0f",
            (total - exchange).coerceAtLeast(0.0),
        )
    }

    private fun calculatedTotalPayableAmount(): Double? {
        val bookingCost = numberOrNull(etChargeBookingCost?.text) ?: return null
        val agreed = (bookingCost - (numberOrNull(etChargeSpecialConsideration?.text) ?: 0.0))
            .coerceAtLeast(0.0)
        return agreed +
            (numberOrNull(etPayRegCharges?.text) ?: 0.0) +
            (if (payGstApplicable) numberOrNull(etPayGstAmount?.text) ?: 0.0 else 0.0) +
            (numberOrNull(etPayDocCharges?.text) ?: 0.0) +
            (numberOrNull(etPayPattaCharges?.text) ?: 0.0) +
            (if (payOtherApplicable) numberOrNull(etPayOtherCharges?.text) ?: 0.0 else 0.0)
    }

    private fun refreshPaymentToggles() {
        ivPayGstApplicable?.setImageResource(
            if (payGstApplicable) R.drawable.ic_outcome_checkbox_checked
            else R.drawable.ic_outcome_checkbox_empty
        )
        ivPayOtherApplicable?.setImageResource(
            if (payOtherApplicable) R.drawable.ic_outcome_checkbox_checked
            else R.drawable.ic_outcome_checkbox_empty
        )
        tvPayPlan?.text = planLabel(payPlan)
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
            bookingStep = if (isStandaloneBookingMode) BookingStep.CLIENT_FORM else BookingStep.FIND_MOBILE
            // Switching back to Booking from another outcome resets
            // the visited-watermark — the user starts at CLIENT and
            // must Next-through again.
            maxVisitedBookingSub = BookingSub.CLIENT
        }
        renderState()
    }

    private fun switchBookingSub(sub: BookingSub) {
        bookingSub = sub
        if (!isStandaloneBookingMode && sub == BookingSub.CLIENT && bookingStep != BookingStep.CLIENT_FORM) {
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
            styleSubTab(subTabBooking, bookingSub == BookingSub.BOOKING)
            styleSubTab(subTabStaff, bookingSub == BookingSub.STAFF)

            val activePill: TextView? = when (bookingSub) {
                BookingSub.CLIENT -> subTabClient
                BookingSub.BOOKING -> subTabBooking
                BookingSub.STAFF -> subTabStaff
                BookingSub.PROFESSIONAL, BookingSub.OFFICE, BookingSub.CHARGES, BookingSub.PAYMENT -> subTabClient
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
        //  - Booking         : three web-parity tabs group the native sections.
        //  - Site visit      : one-page conversion form.
        //  - Postpone        : 4 free-text fields + follow-up date/time.
        //  - Not Interested  : same 4 free-text fields, no follow-up.
        val bookingActive = activeOutcome == Outcome.BOOKING
        val siteVisitActive = activeOutcome == Outcome.SITE_VISIT
        val postponeActive = activeOutcome == Outcome.POSTPONE
        val notInterestedActive = activeOutcome == Outcome.NOT_INTERESTED

        // Coming Soon is only the fallback now — every outcome has a body.
        bodyComingSoon?.visibility = View.GONE
        val showFindClient = bookingActive && bookingSub == BookingSub.CLIENT &&
            bookingStep == BookingStep.FIND_MOBILE && !isStandaloneBookingMode
        val showClientGroup = bookingActive && bookingSub == BookingSub.CLIENT &&
            (bookingStep == BookingStep.CLIENT_FORM || isStandaloneBookingMode)
        val showBookingFinance = bookingActive && bookingSub == BookingSub.BOOKING
        val showPaymentStaff = bookingActive && bookingSub == BookingSub.STAFF
        bodyFindClient?.visibility = if (showFindClient) View.VISIBLE else View.GONE
        bodyClientForm?.visibility = if (showClientGroup) View.VISIBLE else View.GONE
        bodyProfessional?.visibility = if (showClientGroup) View.VISIBLE else View.GONE
        bodyOffice?.visibility = if (showClientGroup) View.VISIBLE else View.GONE
        bodyBooking?.visibility = if (bookingActive && bookingSub == BookingSub.BOOKING)
            View.VISIBLE else View.GONE
        bodyCharges?.visibility = if (showBookingFinance) View.VISIBLE else View.GONE
        bodyPayment?.visibility = if (showBookingFinance || showPaymentStaff) View.VISIBLE else View.GONE
        groupBookingChargesAdvance?.visibility =
            if (showBookingFinance) View.VISIBLE else View.GONE
        groupBookingPaymentSchedule?.visibility =
            if (showPaymentStaff) View.VISIBLE else View.GONE
        bodyStaff?.visibility = if (showPaymentStaff) View.VISIBLE else View.GONE
        bodySiteVisit?.visibility = if (siteVisitActive) View.VISIBLE else View.GONE
        bodyPostpone?.visibility = if (postponeActive) View.VISIBLE else View.GONE
        bodyNotInterested?.visibility = if (notInterestedActive) View.VISIBLE else View.GONE

        // Edit chip visible from the moment we leave the find-mobile step,
        // so the user can always jump back and re-enter the client mobile.
        val onFindMobile = bookingActive && bookingSub == BookingSub.CLIENT &&
            bookingStep == BookingStep.FIND_MOBILE
        btnBack?.visibility = if (isStandaloneBookingMode && bookingActive) View.VISIBLE else View.GONE
        btnClear?.visibility = if (isStandaloneBookingMode && bookingActive) View.VISIBLE else View.GONE

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
        // From the mobile-find step, validate, look up an existing lead
        // by phone, and advance to the form (pre-filled when a match is
        // found). Mirrors the BookingCreateFragment flow so a staffer
        // who already engaged this client through the telecaller doesn't
        // have to retype name / address / WhatsApp / email / etc.
        if (bookingSub == BookingSub.CLIENT && bookingStep == BookingStep.FIND_MOBILE) {
            val raw = etClientMobile?.text?.toString().orEmpty().trim()
            if (raw.length < 6) {
                showError("Enter a valid mobile number")
                return
            }
            tvFormPhone?.text = raw
            // Advance immediately for snappy UX — the lookup runs async
            // and prefills fields when (if) a match arrives. Most users
            // type then tap Next and start filling the form; if a lead
            // exists the fields just appear filled by the time they
            // look at them.
            bookingStep = BookingStep.CLIENT_FORM
            renderState()
            lookupAndPrefillClientByPhone(raw)
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
            if (staffSaveAs == SaveAs.CONFIRMED) {
                val bookingCost = numberOrNull(etChargeBookingCost?.text)
                val advanceAmount = numberOrNull(etPayAdvanceAmount?.text)
                val missing = confirmationMissingFields(bookingCost, advanceAmount)
                if (missing.isNotEmpty()) {
                    showError("Complete required field: ${missing.first()}")
                    return
                }
                confirmationValidationError(bookingCost, advanceAmount)?.let {
                    showError(it)
                    return
                }
            }
            persistBooking()
            return
        }
        // Required-field gate — block forward navigation until the
        // current tab's must-have field is filled. Keeps the user
        // from landing on Staff with a half-filled booking that the
        // final Save would reject anyway. Tabs with no hard
        // requirement (Charges / Payment money fields are optional
        // — finance team finalises later) fall through cleanly.
        val gateError = currentSubTabRequiredFieldMissing()
        if (gateError != null) {
            showError(gateError)
            return
        }
        bookingSub = nextSubTab(bookingSub)
        // Bump the watermark so the tap-back navigation can let the
        // user jump straight to this sub-tab on a return trip.
        if (bookingSub.ordinal > maxVisitedBookingSub.ordinal) {
            maxVisitedBookingSub = bookingSub
        }
        renderState()
    }

    /**
     * Returns the user-facing error message for whichever required
     * field on the active sub-tab is still blank, or null when the
     * tab is OK to advance from. Matches the asterisk-marked fields
     * in the design — Client Name on CLIENT, Profession on
     * PROFESSIONAL, Office Name on OFFICE, Booking Date + Project on
     * BOOKING. CHARGES / PAYMENT / STAFF have no hard prerequisites
     * here (Staff has its own check on the final Save).
     */
    private fun currentSubTabRequiredFieldMissing(): String? {
        fun isBlankPlaceholder(value: String?, vararg placeholders: String): Boolean {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return true
            return placeholders.any { v.equals(it, ignoreCase = true) }
        }
        return when (bookingSub) {
            BookingSub.CLIENT -> {
                if (bookingStep != BookingStep.CLIENT_FORM) null
                else if ((textOrNull(tvFormPhone?.text) ?: textOrNull(etClientMobile?.text)).isNullOrEmpty())
                    "Mobile number is required before continuing"
                else if (etFormName?.text?.toString()?.trim().isNullOrEmpty())
                    "Client Name is required before continuing"
                else null
            }
            BookingSub.PROFESSIONAL, BookingSub.OFFICE -> null
            BookingSub.BOOKING -> {
                when {
                    isBlankPlaceholder(tvBookDate?.text?.toString(), "dd/mm/yyyy") ->
                        "Booking Date is required before continuing"
                    isBlankPlaceholder(tvBookProject?.text?.toString(), "Select Project") ->
                        "Project is required before continuing"
                    else -> null
                }
            }
            BookingSub.CHARGES, BookingSub.PAYMENT, BookingSub.STAFF -> null
        }
    }

    /**
     * Flip the prefilled client form between read-only (default
     * after a successful lookup) and editable. Edit button background
     * + text colour change to signal the state; underlying form
     * fields toggle isEnabled / focusability together.
     */
    private fun toggleEditMode() {
        editEnabled = !editEnabled
        applyEditModeToFields(editEnabled)
        val pill = btnEdit ?: return
        if (editEnabled) {
            pill.setBackgroundResource(R.drawable.bg_outcome_edit_chip_active)
            pill.setTextColor(Color.WHITE)
            pill.text = "Done"
            // Tap-through feedback so the operator knows the lock
            // really did flip — the previous chip-only colour change
            // was easy to miss in bright sunlight.
            Toast.makeText(
                requireContext(),
                "Fields unlocked — edit and tap Done when finished",
                Toast.LENGTH_SHORT,
            ).show()
        } else {
            pill.setBackgroundResource(R.drawable.bg_outcome_edit_chip_inactive)
            pill.setTextColor(Color.parseColor("#2DAE12"))
            pill.text = "Edit"
        }
    }

    // Cached original key listeners so the lock toggle can fully
    // disable typing (setKeyListener(null) is the only universally
    // reliable way to keep the IME from accepting input on Samsung /
    // Xiaomi keyboards — focus + clickable alone leaves a soft IME
    // entry path in some skins) and restore proper input behaviour
    // on unlock. One entry per EditText pointer-identity.
    private val cachedKeyListeners = mutableMapOf<android.widget.EditText, android.text.method.KeyListener?>()

    /**
     * Lock / unlock all PREFILLED form fields in one pass. Covers the
     * Client identity fields AND the Professional/Office groups since
     * any of those can be prefilled from the lead lookup or the
     * clients-master row. Visit-specific fields (Booking / Charges /
     * Payment / Staff) are NOT touched — those are always editable
     * because they're new per booking, not lead-cached.
     */
    private fun applyEditModeToFields(enabled: Boolean) {
        val fields = listOf(
            // Client identity
            etFormName, etFormFather, etFormAltNumber, etFormWhatsApp,
            etFormEmail, etFormHomeDoorNo, etFormHomeStreet,
            etFormHomeAddress, etFormHomeAddressLine2, etFormPincode,
            etFormState, etFormDistrict, etFormLocation,
            // Professional
            etProfDesignation, etProfOtherDepartment, etProfIncome,
            // Office
            etOfficeName, etOfficeEmail, etOfficeMobile, etOfficePhone,
            etOfficeDoorNo, etOfficeStreet, etOfficeAddress,
            etOfficeAddressLine2, etOfficeArea, etOfficePincode,
        )
        fields.forEach { f ->
            f ?: return@forEach
            // CRITICAL: do NOT use isEnabled=false here. Many Material
            // text themes pipe state_enabled=false through to a
            // near-transparent disabled colour on the EditText text,
            // which made the prefilled lead data invisible until the
            // user tapped Edit (which restored isEnabled=true).
            //
            // Lock editability via focus + click suppression + a null
            // keyListener so the IME literally has no input target.
            // Focus alone isn't enough on some OEM keyboards — they
            // re-grant focus on long-press / soft-paste.
            f.isFocusable = enabled
            f.isFocusableInTouchMode = enabled
            f.isClickable = enabled
            f.isLongClickable = enabled
            f.isCursorVisible = enabled
            f.alpha = if (enabled) 1f else 0.95f
            if (!enabled) {
                // Lock: cache the original key listener (only on the
                // FIRST lock so we don't accidentally cache the null
                // we just set) and detach.
                if (!cachedKeyListeners.containsKey(f)) {
                    cachedKeyListeners[f] = f.keyListener
                }
                f.keyListener = null
            } else {
                // Unlock: restore whichever key listener the field had
                // before we touched it. If we never cached one (i.e.
                // the field was created in unlock mode), leave alone.
                cachedKeyListeners[f]?.let { f.keyListener = it }
            }
        }
    }

    /**
     * Push field-staff edits back to the lead. No-op when the form
     * wasn't prefilled (prefilledLeadId is null) or the user never
     * tapped Edit (editEnabled is false). Silent on errors — the
     * booking save already succeeded; lead sync is best-effort.
     */
    private suspend fun pushClientEditsToLeadIfAny() {
        val leadId = prefilledLeadId ?: return
        if (!editEnabled) return
        try {
            val req = UpdateTelecallerLeadRequest(
                leadId = leadId,
                contactName = etFormName?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                emailId = etFormEmail?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                alternateNumber = etFormAltNumber?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                locationPreferred = etFormLocation?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                manualProfile = ManualProfilePatch(
                    clientName = etFormName?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                    pincode = etFormPincode?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                    address = etFormHomeAddress?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                    state = etFormState?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                    district = etFormDistrict?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                    alternateMobileNumber = etFormAltNumber?.text?.toString()?.trim()?.takeIf { it.isNotBlank() },
                ),
            )
            val resp = api.updateTelecallerLead(session.bearerToken, req)
            android.util.Log.d(
                LOG_TAG,
                "lead update ${if (resp.success) "ok" else "failed: ${resp.error}"}",
            )
        } catch (e: Exception) {
            android.util.Log.w(LOG_TAG, "lead update threw", e)
        }
    }

    /**
     * Lookup an existing client by phone and replay known profile data
     * onto the Client form fields. Telecaller lead data is used first,
     * then the clients master fills anything still blank.
     */
    /**
     * Fires the India Post `/api/pincode` proxy (same one the web hits)
     * and fills Location, District, State from the result — but ONLY
     * when those fields are still blank, so manual entries always win.
     *
     * Locality (stored visually in the Location field on the mobile
     * booking form) maps to the post-office `Name` returned by India
     * Post — see PincodeLookup for the suffix-cleaning and dedup logic.
     * Mirrors mms-external-leads.tsx::usePincodeLocationEnrichment on
     * the web so a pincode typed into the mobile sheet produces the
     * exact same Location/District/State the web would.
     */
    private fun enrichBookingPincode(pin: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val enriched = try {
                com.manjugroups.m_connect.network.PincodeLookup.lookup(pin)
            } catch (_: Throwable) {
                null
            } ?: return@launch
            // Field-blank checks live INSIDE the post-coroutine block so
            // values the operator typed while the network round-trip was
            // in flight aren't trampled. Same anti-overwrite policy as
            // the web's enrichment effect.
            fun isBlank(et: EditText?): Boolean =
                et?.text?.toString()?.trim().isNullOrEmpty()
            if (isBlank(etFormLocation) && !enriched.locality.isNullOrBlank()) {
                etFormLocation?.setText(enriched.locality)
            }
            if (isBlank(etFormDistrict) && !enriched.district.isNullOrBlank()) {
                etFormDistrict?.setText(enriched.district)
            }
            if (isBlank(etFormState) && !enriched.state.isNullOrBlank()) {
                etFormState?.setText(enriched.state)
            }
            // Treat the enrichment as a meaningful form-state change so
            // the auto-save scratchpad picks it up on the next debounce
            // window — without this the user could close the sheet and
            // come back to a pincode without its enriched locality.
            scheduleDraftPushIfActive()
        }
    }

    private fun lookupAndPrefillClientByPhone(phone: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            // Track a user-friendly reason if the lead lookup misses or
            // fails. We don't early-return on miss because the same
            // phone might still match in the clients master — the
            // function's contract is "lead first, then client". Only
            // surface this toast if BOTH lookups end up empty.
            var leadLookupError: String? = null
            val lead = try {
                val resp = api.searchTelecallerLeadsByPhone(session.bearerToken, phone)
                if (!resp.success) {
                    android.util.Log.d(LOG_TAG, "lead lookup: ${resp.error ?: "no leads"}")
                    // Server-side rejection (FORBIDDEN, scope, etc.) —
                    // record the message but still fall through to the
                    // client master in case that returns something.
                    leadLookupError = resp.error ?: "Lead lookup failed"
                    null
                } else {
                    resp.leads.firstOrNull().also {
                        if (it == null) {
                            android.util.Log.d(LOG_TAG, "lead lookup: no match for $phone")
                            leadLookupError = "No existing lead for $phone"
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "lead lookup failed", e)
                // Prefer parsed server message; otherwise the exception
                // string. Convex 500s carry the thrown reason in the body.
                val serverMsg = extractHttpErrorMessage(e)
                leadLookupError = serverMsg ?: e.message ?: "Lead lookup network error"
                null
            }
            val client = try {
                val resp = api.searchClientByPhone(session.bearerToken, phone)
                if (!resp.success) {
                    android.util.Log.d(LOG_TAG, "client lookup: ${resp.error ?: "no client"}")
                    null
                } else {
                    resp.client.also {
                        if (it == null) android.util.Log.d(LOG_TAG, "client lookup: no match for $phone")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(LOG_TAG, "client lookup failed", e)
                null
            }
            if (lead == null && client == null) {
                // Both lookups missed — only show the captured lead
                // error if we're still on screen. Helps the user
                // distinguish "no record yet" from a real fetch
                // failure (FORBIDDEN, network, etc.). Falls back to a
                // generic message when the lead miss was a clean
                // "no match" and the client miss was also clean.
                if (isAdded) {
                    val msg = leadLookupError ?: "No existing record for $phone — fill the form"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            if (!isAdded) return@launch

            fun fill(field: EditText?, value: String?) {
                val v = value?.trim().orEmpty()
                if (v.isEmpty()) return
                if (field?.text?.toString()?.trim().isNullOrEmpty()) field?.setText(v)
            }

            lead?.let {
                // Prefer the operator-edited manualProfile over the
                // AI-derived latestAnalysisProfile. The web's Edit
                // Live Profile dialog writes to lead.manualProfile,
                // and when the operator typed pincode/state/district
                // there, those values reflect their explicit intent
                // (often verified via the pincode-lookup auto-fill).
                // The AI analysis is a fallback for fields the
                // operator hasn't touched yet — fall through with `?:`
                // so an empty manualProfile entry still hits the AI
                // value without forcing the operator to re-type.
                val manual = it.manualProfile
                val ai = it.latestAnalysisProfile
                fill(etFormName, it.contactName ?: manual?.clientName ?: ai?.clientName)
                fill(etFormEmail, it.emailId)
                fill(etFormAltNumber, manual?.alternateMobileNumber ?: ai?.alternateMobileNumber)
                fill(etFormHomeDoorNo, it.clientPlaceProfile?.doorNo ?: manual?.doorNo ?: ai?.doorNo)
                fill(etFormHomeAddress, manual?.address ?: ai?.address ?: it.suggestedVisitAddress)
                fill(etFormPincode, manual?.pincode ?: ai?.pincode)
                fill(etFormState, manual?.state ?: ai?.state)
                fill(etFormDistrict, manual?.district ?: ai?.district)
                fill(etFormLocation, it.locationPreferred ?: it.clientCity)
            }
            client?.let { prefillFromClient(it, ::fill) }

            if (lead != null && !isStandaloneBookingMode) {
                // CP/SV lead-derived data is locked until Edit, so changes
                // can be pushed back to the lead audit trail intentionally.
                prefilledLeadId = lead.id
                editEnabled = false
                applyEditModeToFields(false)
                btnEdit?.visibility = View.VISIBLE
                btnEdit?.setBackgroundResource(R.drawable.bg_outcome_edit_chip_inactive)
                btnEdit?.setTextColor(Color.parseColor("#2DAE12"))
            } else {
                prefilledLeadId = null
                editEnabled = true
                applyEditModeToFields(true)
                btnEdit?.visibility = View.GONE
            }
        }
    }

    private fun prefillFromClient(
        client: ClientProfile,
        fill: (EditText?, String?) -> Unit,
    ) {
        fun fillLabel(field: TextView?, value: String?, placeholder: String) {
            val v = value?.trim().orEmpty()
            if (v.isEmpty()) return
            val current = field?.text?.toString()?.trim().orEmpty()
            if (current.isEmpty() || current.equals(placeholder, ignoreCase = true)) field?.text = v
        }
        fillLabel(tvFormTitle, client.title, "Title")
        fill(etFormName, client.clientName)
        fill(etFormFather, client.fatherSpouseName)
        fillLabel(tvFormDob, client.dateOfBirth, "dd/mm/yyyy")
        fillLabel(tvFormAnniversary, client.anniversaryDate, "dd/mm/yyyy")
        fillLabel(tvFormNationality, client.nationality, "Nationality")
        fill(etFormAltNumber, client.alternateNumbers)
        fill(etFormWhatsApp, client.whatsappNumber)
        fill(etFormEmail, client.email)
        fill(etFormHomeAddress, client.homeAddress ?: client.formattedAddress ?: client.addressLine1)
        fill(etFormPincode, client.pincode)
        fill(etFormState, client.state)
        fill(etFormDistrict, client.district)
        fill(etFormLocation, client.location)
        fillLabel(tvProfProfession, client.profession, "Select Profession")
        val isSalaried = client.profession.equals("Salaried", ignoreCase = true)
        groupProfDepartment?.visibility = if (isSalaried) View.VISIBLE else View.GONE
        if (isSalaried) {
            val department = client.department?.trim().orEmpty()
            val standardDepartments = setOf("Admin", "Sales", "HR", "Software Developer")
            if (department.isNotBlank()) {
                if (department in standardDepartments) {
                    tvProfDepartment?.text = department
                    groupProfOtherDepartment?.visibility = View.GONE
                } else {
                    tvProfDepartment?.text = "Other"
                    fill(etProfOtherDepartment, department)
                    groupProfOtherDepartment?.visibility = View.VISIBLE
                }
            }
        }
        fill(etProfDesignation, client.designation)
        fill(etProfIncome, client.incomePerAnnum)
        fill(etOfficeName, client.officeName)
        fill(etOfficeAddress, client.officeAddress)
        fill(etOfficeMobile, client.officeMobile)
        fill(etOfficePhone, client.officePhone)
        fill(etOfficeEmail, client.officeEmail)
        fill(etStaffAadhar, client.aadhaar)
        fill(etStaffPancard, client.pan)
        fill(etStaffRefName1, client.referenceName1)
        fill(etStaffRefMobile1, client.referenceMobile1)
        fill(etStaffRefProf1, client.referenceProfession1)
        fill(etStaffRefName2, client.referenceName2)
        fill(etStaffRefMobile2, client.referenceMobile2)
        fill(etStaffRefProf2, client.referenceProfession2)
    }

    /**
     * Walks the inflated view tree and re-styles the trailing " *" on
     * any TextView whose text ends with it, painting just the star in
     * the destructive red used by the rest of the app. Idempotent —
     * safe to call multiple times because the SpannableString
     * replacement preserves the underlying characters byte-for-byte;
     * a re-run just re-applies the same span on the same characters.
     *
     * We intentionally only colour the LAST trailing asterisk after
     * trimming, not arbitrary "*" inside the label, so a label like
     * "Promo Offers T&C" stays untouched and a label like "Booking
     * Date *" gets only its tail star reddened.
     *
     * Color choice (#D92D20) mirrors the web's `var(--bad)` / Tailwind
     * destructive token so the visual cue is consistent across
     * surfaces — the web booking form renders its required-field
     * asterisks in the same red.
     */
    private fun colorizeRequiredStarsRedRecursively(root: android.view.ViewGroup) {
        val redColor = Color.parseColor("#D92D20")
        fun visit(group: android.view.ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is android.view.ViewGroup) {
                    visit(child)
                } else if (child is android.widget.TextView) {
                    val raw = child.text?.toString() ?: continue
                    if (!raw.trimEnd().endsWith("*")) continue
                    val starIdx = raw.lastIndexOf('*')
                    if (starIdx < 0) continue
                    val styled = android.text.SpannableString(raw)
                    styled.setSpan(
                        android.text.style.ForegroundColorSpan(redColor),
                        starIdx,
                        starIdx + 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    child.text = styled
                }
            }
        }
        visit(root)
    }

    // ──────────────────────────────────────────────────────────────
    // Booking-form draft auto-save helpers. Three pieces:
    //   1. collectFormState() — snapshot every captured field into a
    //      map<String, String?> that round-trips through Gson.
    //   2. applyFormState(map) — restore those values into the
    //      EditTexts / TextViews on dialog re-open. Wrapped in
    //      `draftSuppressSave` so the TextWatcher pulse this kicks
    //      off doesn't immediately push the same blob back to the
    //      server (would be a no-op but burns network).
    //   3. attachDraftWatchers() — attach a single TextWatcher to
    //      every form EditText that schedules a debounced push().
    //
    // The picker rows (Title, Project, Plot, Booking Type, etc.)
    // update their TextView text directly in the existing onClick
    // handlers — those call scheduleDraftPushIfActive() as well so
    // dropdowns are captured too.
    // ──────────────────────────────────────────────────────────────

    private fun collectFormState(): Map<String, String?> {
        fun et(field: android.widget.EditText?) = field?.text?.toString()
        fun tv(field: android.widget.TextView?) = field?.text?.toString()
        return mapOf(
            // Client tab
            "etClientMobile" to et(etClientMobile),
            "tvFormClientPhone" to tv(tvFormPhone),
            "tvFormClientTitle" to tv(tvFormTitle),
            "etFormName" to et(etFormName),
            "etFormFather" to et(etFormFather),
            "tvFormDob" to tv(tvFormDob),
            "tvFormAnniversary" to tv(tvFormAnniversary),
            "tvFormNationality" to tv(tvFormNationality),
            "etFormAltNumber" to et(etFormAltNumber),
            "etFormWhatsApp" to et(etFormWhatsApp),
            "etFormEmail" to et(etFormEmail),
            "etFormHomeDoorNo" to et(etFormHomeDoorNo),
            "etFormHomeStreet" to et(etFormHomeStreet),
            "etFormHomeAddress" to et(etFormHomeAddress),
            "etFormHomeAddressLine2" to et(etFormHomeAddressLine2),
            "etFormPincode" to et(etFormPincode),
            "etFormState" to et(etFormState),
            "etFormDistrict" to et(etFormDistrict),
            "etFormLocation" to et(etFormLocation),
            // Professional
            "tvProfProfession" to tv(tvProfProfession),
            "tvProfDepartment" to tv(tvProfDepartment),
            "etProfOtherDepartment" to et(etProfOtherDepartment),
            "etProfDesignation" to et(etProfDesignation),
            "etProfIncome" to et(etProfIncome),
            // Office
            "etOfficeName" to et(etOfficeName),
            "etOfficeEmail" to et(etOfficeEmail),
            "etOfficeMobile" to et(etOfficeMobile),
            "etOfficePhone" to et(etOfficePhone),
            "etOfficeDoorNo" to et(etOfficeDoorNo),
            "etOfficeStreet" to et(etOfficeStreet),
            "etOfficeAddress" to et(etOfficeAddress),
            "etOfficeAddressLine2" to et(etOfficeAddressLine2),
            "etOfficeArea" to et(etOfficeArea),
            "etOfficePincode" to et(etOfficePincode),
            // Booking
            "tvBookType" to tv(tvBookType),
            "bookConversionManualEntry" to bookConversionManualEntry.toString(),
            "etBookConversionProject" to et(etBookConversionProject),
            "etBookConversionPlot" to et(etBookConversionPlot),
            "etBookConversionCredit" to et(etBookConversionCredit),
            "etBookConversionNotes" to et(etBookConversionNotes),
            "etBookConversionSourceBooking" to et(etBookConversionSourceBooking),
            "bookExchangeManualEntry" to bookExchangeManualEntry.toString(),
            "etBookExchangeProject" to et(etBookExchangeProject),
            "etBookExchangePlot" to et(etBookExchangePlot),
            "etBookExchangeExtent" to et(etBookExchangeExtent),
            "etBookExchangeLookupProject" to et(etBookExchangeLookupProject),
            "etBookExchangeLookupPlot" to et(etBookExchangeLookupPlot),
            "etBookExchangeMobile" to et(etBookExchangeMobile),
            "etBookExchangeSourceBooking" to et(etBookExchangeSourceBooking),
            "etBookExchangeValue" to et(etBookExchangeValue),
            "etBookExchangeNotes" to et(etBookExchangeNotes),
            "tvBookSource" to tv(tvBookSource),
            "etBookSourceName" to et(etBookSourceName),
            "etBookSourceMobile" to et(etBookSourceMobile),
            "etBookReferralBenefit" to et(etBookReferralBenefit),
            "etBookCef" to et(etBookCef),
            "tvBookDate" to tv(tvBookDate),
            "tvBookProject" to tv(tvBookProject),
            "tvBookPlot" to tv(tvBookPlot),
            "tvBookProperty" to tv(tvBookProperty),
            "tvBookMode" to tv(tvBookMode),
            "etBookSvName" to et(etBookSvName),
            "etBookSvMobile" to et(etBookSvMobile),
            // Charges
            "etChargeBookingCost" to et(etChargeBookingCost),
            "etChargeGuidelineValue" to et(etChargeGuidelineValue),
            "etChargeSpecialConsideration" to et(etChargeSpecialConsideration),
            "etChargeDiscountApprovedBy" to et(etChargeDiscountApprovedBy),
            "etChargeScReason" to et(etChargeScReason),
            "etChargeScValidity" to et(etChargeScValidity),
            "etChargePromoOffers" to et(etChargePromoOffers),
            "tvChargePromoTnc" to tv(tvChargePromoTnc),
            "etChargePromoValue" to et(etChargePromoValue),
            "etChargeOfferValidity" to et(etChargeOfferValidity),
            // Payment
            "tvPayPaymentMode" to tv(tvPayPaymentMode),
            "tvPayPlan" to tv(tvPayPlan),
            "etPayRegCharges" to et(etPayRegCharges),
            "etPayGstAmount" to et(etPayGstAmount),
            "etPayDocCharges" to et(etPayDocCharges),
            "etPayPattaCharges" to et(etPayPattaCharges),
            "etPayOtherCharges" to et(etPayOtherCharges),
            "etPayAdvanceAmount" to et(etPayAdvanceAmount),
            "etPayTransactionId" to et(etPayTransactionId),
            "advanceProofStorageId" to advanceProofStorageId,
            "advanceProofFileName" to advanceProofFileName,
            "etPayInstrumentNo" to et(etPayInstrumentNo),
            "etPayBankName" to et(etPayBankName),
            "etPayBankBranch" to et(etPayBankBranch),
            "tvPayInstrumentDate" to tv(tvPayInstrumentDate),
            "tvPayAllotDate" to tv(tvPayAllotDate),
            "etPayAllotDue" to et(etPayAllotDue),
            "tvPay2Date" to tv(tvPay2Date),
            "etPay2Mode" to et(etPay2Mode),
            "tvPay3Date" to tv(tvPay3Date),
            "etPay3Mode" to et(etPay3Mode),
            "tvPay4Date" to tv(tvPay4Date),
            "etPay4Mode" to et(etPay4Mode),
            "tvPayPrefReg" to tv(tvPayPrefReg),
            // Staff
            "tvStaffAvp" to tv(tvStaffAvp),
            "tvStaffGm" to tv(tvStaffGm),
            "tvStaffSm" to tv(tvStaffSm),
            "tvStaffBdo" to tv(tvStaffBdo),
            "tvStaffTelecaller" to tv(tvStaffTelecaller),
            "etStaffAadhar" to et(etStaffAadhar),
            "aadhaarDocumentStorageId" to aadhaarDocumentStorageId,
            "aadhaarDocumentFileName" to aadhaarDocumentFileName,
            "etStaffPancard" to et(etStaffPancard),
            "panDocumentStorageId" to panDocumentStorageId,
            "panDocumentFileName" to panDocumentFileName,
            "tvStaffDocPrep" to tv(tvStaffDocPrep),
            // References
            "etStaffRefName1" to et(etStaffRefName1),
            "etStaffRefMobile1" to et(etStaffRefMobile1),
            "etStaffRefProf1" to et(etStaffRefProf1),
            "etStaffRefName2" to et(etStaffRefName2),
            "etStaffRefMobile2" to et(etStaffRefMobile2),
            "etStaffRefProf2" to et(etStaffRefProf2),
            // Object-backed selections — the labels above can't rebuild
            // these on restore, and they feed the submit payload
            // (projectId/plotId) plus the Special-plan gate.
            "bookingProjectId" to bookingProject?.id,
            "bookingUnitId" to bookingUnit?.id,
            "bookingStaffAvpId" to bookingStaffAvp?.id,
            "bookingStaffGmId" to bookingStaffGm?.id,
            "bookingStaffSmId" to bookingStaffSm?.id,
            "bookingStaffBdoId" to bookingStaffBdo?.id,
            "bookingStaffTelecallerId" to bookingStaffTelecaller?.id,
            "bookIsAgainstVisit" to bookIsAgainstVisit.name,
            "bookDuplicate" to bookDuplicate.toString(),
            "payGstApplicable" to payGstApplicable.toString(),
            "payOtherApplicable" to payOtherApplicable.toString(),
            "staffSaveAs" to staffSaveAs.name,
        )
    }

    private fun applyFormState(map: Map<String, String?>) {
        if (map.isEmpty()) return
        draftSuppressSave = true
        try {
            fun et(field: android.widget.EditText?, key: String) {
                map[key]?.takeIf { it.isNotBlank() }?.let { field?.setText(it) }
            }
            fun tv(field: android.widget.TextView?, key: String) {
                map[key]?.takeIf { it.isNotBlank() }?.let { field?.text = it }
            }
            et(etClientMobile, "etClientMobile")
            tv(tvFormPhone, "tvFormClientPhone")
            tv(tvFormTitle, "tvFormClientTitle")
            et(etFormName, "etFormName")
            et(etFormFather, "etFormFather")
            tv(tvFormDob, "tvFormDob")
            tv(tvFormAnniversary, "tvFormAnniversary")
            tv(tvFormNationality, "tvFormNationality")
            et(etFormAltNumber, "etFormAltNumber")
            et(etFormWhatsApp, "etFormWhatsApp")
            et(etFormEmail, "etFormEmail")
            et(etFormHomeDoorNo, "etFormHomeDoorNo")
            et(etFormHomeStreet, "etFormHomeStreet")
            et(etFormHomeAddress, "etFormHomeAddress")
            et(etFormHomeAddressLine2, "etFormHomeAddressLine2")
            et(etFormPincode, "etFormPincode")
            et(etFormState, "etFormState")
            et(etFormDistrict, "etFormDistrict")
            et(etFormLocation, "etFormLocation")
            tv(tvProfProfession, "tvProfProfession")
            tv(tvProfDepartment, "tvProfDepartment")
            et(etProfOtherDepartment, "etProfOtherDepartment")
            et(etProfDesignation, "etProfDesignation")
            et(etProfIncome, "etProfIncome")
            et(etOfficeName, "etOfficeName")
            et(etOfficeEmail, "etOfficeEmail")
            et(etOfficeMobile, "etOfficeMobile")
            et(etOfficePhone, "etOfficePhone")
            et(etOfficeDoorNo, "etOfficeDoorNo")
            et(etOfficeStreet, "etOfficeStreet")
            et(etOfficeAddress, "etOfficeAddress")
            et(etOfficeAddressLine2, "etOfficeAddressLine2")
            et(etOfficeArea, "etOfficeArea")
            et(etOfficePincode, "etOfficePincode")
            tv(tvBookType, "tvBookType")
            bookConversionManualEntry =
                map["bookConversionManualEntry"]?.toBooleanStrictOrNull() ?: true
            et(etBookConversionProject, "etBookConversionProject")
            et(etBookConversionPlot, "etBookConversionPlot")
            et(etBookConversionCredit, "etBookConversionCredit")
            et(etBookConversionNotes, "etBookConversionNotes")
            et(etBookConversionSourceBooking, "etBookConversionSourceBooking")
            bookExchangeManualEntry =
                map["bookExchangeManualEntry"]?.toBooleanStrictOrNull() ?: true
            et(etBookExchangeProject, "etBookExchangeProject")
            et(etBookExchangePlot, "etBookExchangePlot")
            et(etBookExchangeExtent, "etBookExchangeExtent")
            et(etBookExchangeLookupProject, "etBookExchangeLookupProject")
            et(etBookExchangeLookupPlot, "etBookExchangeLookupPlot")
            et(etBookExchangeMobile, "etBookExchangeMobile")
            et(etBookExchangeSourceBooking, "etBookExchangeSourceBooking")
            et(etBookExchangeValue, "etBookExchangeValue")
            et(etBookExchangeNotes, "etBookExchangeNotes")
            tv(tvBookSource, "tvBookSource")
            et(etBookSourceName, "etBookSourceName")
            et(etBookSourceMobile, "etBookSourceMobile")
            et(etBookReferralBenefit, "etBookReferralBenefit")
            et(etBookCef, "etBookCef")
            tv(tvBookDate, "tvBookDate")
            tv(tvBookProject, "tvBookProject")
            tv(tvBookPlot, "tvBookPlot")
            tv(tvBookProperty, "tvBookProperty")
            tv(tvBookMode, "tvBookMode")
            et(etBookSvName, "etBookSvName")
            et(etBookSvMobile, "etBookSvMobile")
            et(etChargeBookingCost, "etChargeBookingCost")
            et(etChargeGuidelineValue, "etChargeGuidelineValue")
            et(etChargeSpecialConsideration, "etChargeSpecialConsideration")
            et(etChargeDiscountApprovedBy, "etChargeDiscountApprovedBy")
            et(etChargeScReason, "etChargeScReason")
            et(etChargeScValidity, "etChargeScValidity")
            et(etChargePromoOffers, "etChargePromoOffers")
            tv(tvChargePromoTnc, "tvChargePromoTnc")
            et(etChargePromoValue, "etChargePromoValue")
            et(etChargeOfferValidity, "etChargeOfferValidity")
            tv(tvPayPaymentMode, "tvPayPaymentMode")
            tv(tvPayPlan, "tvPayPlan")
            // Re-derive the plan state from the restored label so windows
            // and the request payload stay in sync with what's displayed.
            tvPayPlan?.text?.toString()?.substringBefore(" (")?.trim()
                ?.takeIf { it == "Regular" || it == "Flexi" || it == "Special" }
                ?.let { payPlan = it }
            et(etPayRegCharges, "etPayRegCharges")
            et(etPayGstAmount, "etPayGstAmount")
            et(etPayDocCharges, "etPayDocCharges")
            et(etPayPattaCharges, "etPayPattaCharges")
            et(etPayOtherCharges, "etPayOtherCharges")
            et(etPayAdvanceAmount, "etPayAdvanceAmount")
            et(etPayTransactionId, "etPayTransactionId")
            advanceProofStorageId = map["advanceProofStorageId"]?.takeIf { it.isNotBlank() }
            advanceProofFileName = map["advanceProofFileName"]?.takeIf { it.isNotBlank() }
            btnPayProofUpload?.text = advanceProofFileName?.let { "✓ $it" } ?: "Choose file"
            et(etPayInstrumentNo, "etPayInstrumentNo")
            et(etPayBankName, "etPayBankName")
            et(etPayBankBranch, "etPayBankBranch")
            tv(tvPayInstrumentDate, "tvPayInstrumentDate")
            tv(tvPayAllotDate, "tvPayAllotDate")
            et(etPayAllotDue, "etPayAllotDue")
            tv(tvPay2Date, "tvPay2Date")
            et(etPay2Mode, "etPay2Mode")
            tv(tvPay3Date, "tvPay3Date")
            et(etPay3Mode, "etPay3Mode")
            tv(tvPay4Date, "tvPay4Date")
            et(etPay4Mode, "etPay4Mode")
            tv(tvPayPrefReg, "tvPayPrefReg")
            tv(tvStaffAvp, "tvStaffAvp")
            tv(tvStaffGm, "tvStaffGm")
            tv(tvStaffSm, "tvStaffSm")
            tv(tvStaffBdo, "tvStaffBdo")
            tv(tvStaffTelecaller, "tvStaffTelecaller")
            et(etStaffAadhar, "etStaffAadhar")
            aadhaarDocumentStorageId = map["aadhaarDocumentStorageId"]?.takeIf { it.isNotBlank() }
            aadhaarDocumentFileName = map["aadhaarDocumentFileName"]?.takeIf { it.isNotBlank() }
            btnStaffAadhaarUpload?.text = aadhaarDocumentFileName?.let { "✓ $it" } ?: "Choose file"
            et(etStaffPancard, "etStaffPancard")
            panDocumentStorageId = map["panDocumentStorageId"]?.takeIf { it.isNotBlank() }
            panDocumentFileName = map["panDocumentFileName"]?.takeIf { it.isNotBlank() }
            btnStaffPanUpload?.text = panDocumentFileName?.let { "✓ $it" } ?: "Choose file"
            tv(tvStaffDocPrep, "tvStaffDocPrep")
            et(etStaffRefName1, "etStaffRefName1")
            et(etStaffRefMobile1, "etStaffRefMobile1")
            et(etStaffRefProf1, "etStaffRefProf1")
            et(etStaffRefName2, "etStaffRefName2")
            et(etStaffRefMobile2, "etStaffRefMobile2")
            et(etStaffRefProf2, "etStaffRefProf2")
            fun restoredStaff(idKey: String, labelKey: String): StaffData? {
                val id = map[idKey]?.takeIf { it.isNotBlank() } ?: return null
                return StaffData(
                    id = id,
                    name = map[labelKey]?.takeIf { it.isNotBlank() },
                    phone = null,
                    role = null,
                    designation = null,
                    status = null,
                    employeeId = null,
                    department = null,
                )
            }
            bookingStaffAvp = restoredStaff("bookingStaffAvpId", "tvStaffAvp")
            bookingStaffGm = restoredStaff("bookingStaffGmId", "tvStaffGm")
            bookingStaffSm = restoredStaff("bookingStaffSmId", "tvStaffSm")
            bookingStaffBdo = restoredStaff("bookingStaffBdoId", "tvStaffBdo")
            bookingStaffTelecaller = restoredStaff("bookingStaffTelecallerId", "tvStaffTelecaller")
            bookIsAgainstVisit = runCatching {
                YesNo.valueOf(map["bookIsAgainstVisit"].orEmpty())
            }.getOrDefault(YesNo.YES)
            bookDuplicate = map["bookDuplicate"]?.toBooleanStrictOrNull() ?: bookDuplicate
            payGstApplicable = map["payGstApplicable"]?.toBooleanStrictOrNull() ?: payGstApplicable
            payOtherApplicable = map["payOtherApplicable"]?.toBooleanStrictOrNull() ?: payOtherApplicable
            staffSaveAs = runCatching {
                SaveAs.valueOf(map["staffSaveAs"].orEmpty())
            }.getOrDefault(SaveAs.DRAFT)
            restoreBookingSelections(map)
            refreshBookingTypeSections()
            groupProfDepartment?.visibility =
                if (tvProfProfession?.text?.toString() == "Salaried") View.VISIBLE else View.GONE
            groupProfOtherDepartment?.visibility =
                if (tvProfProfession?.text?.toString() == "Salaried" &&
                    tvProfDepartment?.text?.toString() == "Other"
                ) View.VISIBLE else View.GONE
            refreshBookingRadios()
            refreshPaymentToggles()
            refreshStaffSaveRadios()
            applySpecialConsiderationVisibility()
            applyAdvancePaymentVisibility()
            applyLoanAmountVisibility()
        } finally {
            draftSuppressSave = false
        }
    }

    /** Drafts persist view labels plus the selected project/plot ids.
     *  Rebuild the object state that submit (projectId/plotId) and the
     *  Special-plan gate depend on; drafts saved before the ids existed
     *  fall back to matching the restored labels against the live lists. */
    private fun restoreBookingSelections(map: Map<String, String?>) {
        if (bookingProject != null) return // live prefill (CP/SV flow) wins
        val projectLabel = map["tvBookProject"]?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("Select Project", true) }
        val plotLabel = map["tvBookPlot"]?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("Select Plot", true) }
        val projectId = map["bookingProjectId"]?.takeIf { it.isNotBlank() }
        val unitId = map["bookingUnitId"]?.takeIf { it.isNotBlank() }

        if (projectId != null) {
            bookingProject = MarketingProject(id = projectId, name = projectLabel)
            if (unitId != null) {
                // Availability was checked when the draft captured it and the
                // server re-validates on submit.
                bookingUnit = InventoryUnit(
                    id = unitId,
                    projectId = projectId,
                    unitNumber = plotLabel,
                    status = "available",
                )
            }
            resolveProjectSpecialPaymentFlag(projectId)
            // Replace the lightweight draft placeholders with the current
            // server objects. This restores minimum-advance validation and
            // also re-checks that the saved plot is still bookable.
            viewLifecycleOwner.lifecycleScope.launch {
                val projects = runCatching { api.getMarketingProjects(session.bearerToken) }
                    .getOrNull()?.takeIf { it.success }?.projects.orEmpty()
                val liveProject = projects.firstOrNull { it.id == projectId }
                if (!isAdded || bookingProject?.id != projectId) return@launch
                if (liveProject != null) {
                    bookingProject = liveProject
                    bookingProjectCache = projects.filter {
                        it.status?.trim()?.lowercase(Locale.US) == "ongoing"
                    }
                    updateMinimumAdvanceHint(liveProject)
                    if (liveProject.specialPaymentEnabled == null) {
                        resolveProjectSpecialPaymentFlag(liveProject.id)
                    } else {
                        ensurePaymentPlanAllowed()
                    }
                }
                if (unitId == null) return@launch
                val units = runCatching {
                    api.listInventoryUnits(session.bearerToken, projectId)
                }.getOrNull()?.takeIf { it.success }?.units.orEmpty()
                    .filter(::isAvailableForBooking)
                if (!isAdded || bookingProject?.id != projectId) return@launch
                bookingUnitCacheProjectId = projectId
                bookingUnitCache = units
                val liveUnit = units.firstOrNull { it.id == unitId }
                if (liveUnit != null) {
                    bookingUnit = liveUnit
                    tvBookPlot?.text = liveUnit.unitNumber ?: plotLabel ?: "Selected"
                } else {
                    bookingUnit = null
                    tvBookPlot?.text = "Select Plot"
                    showError("The plot saved in this draft is no longer available")
                }
            }
            return
        }

        if (projectLabel == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val projects = bookingProjectCache.ifEmpty {
                runCatching { api.getMarketingProjects(session.bearerToken) }
                    .getOrNull()?.takeIf { it.success }?.projects.orEmpty()
            }
            if (projects.isNotEmpty()) bookingProjectCache = projects
            val project = projects.firstOrNull {
                it.name?.trim()?.equals(projectLabel, ignoreCase = true) == true
            } ?: return@launch
            if (!isAdded || bookingProject != null) return@launch
            bookingProject = project
            updateMinimumAdvanceHint(project)
            if (project.specialPaymentEnabled == null) {
                resolveProjectSpecialPaymentFlag(project.id)
            } else {
                ensurePaymentPlanAllowed()
            }
            if (plotLabel == null || bookingUnit != null) return@launch
            val units = runCatching {
                api.listInventoryUnits(session.bearerToken, project.id)
            }.getOrNull()?.takeIf { it.success }?.units.orEmpty()
                .filter(::isAvailableForBooking)
            val unit = units.firstOrNull {
                (it.unitNumber ?: it.id).trim().equals(plotLabel, ignoreCase = true)
            } ?: return@launch
            if (!isAdded || bookingUnit != null) return@launch
            bookingUnit = unit
            bookingUnitCacheProjectId = project.id
            bookingUnitCache = units
        }
    }

    private fun currentBookingSourceKey(): String =
        BookingDraftManager.buildSourceKey(
            cpVisitId = arguments?.getString(ARG_CP_VISIT_ID),
            siteVisitId = arguments?.getString(ARG_SITE_VISIT_ID),
            staffId = session.staffId,
        )

    private fun scheduleDraftPushIfActive() {
        if (draftSuppressSave) return
        val manager = draftManager ?: return
        if (!isAdded) return
        val sourceKey = currentBookingSourceKey()
        val state = collectFormState()
        if (state.values.all { it.isNullOrBlank() }) return
        manager.push(
            bearerToken = session.bearerToken,
            sourceKey = sourceKey,
            sourceCpVisitId = arguments?.getString(ARG_CP_VISIT_ID),
            sourceSiteVisitId = arguments?.getString(ARG_SITE_VISIT_ID),
            draftJson = manager.encode(state),
        )
    }

    private fun attachDraftWatchers() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                scheduleDraftPushIfActive()
            }
        }
        val editTexts = listOf(
            etClientMobile, etFormName, etFormFather, etFormAltNumber,
            etFormWhatsApp, etFormEmail, etFormHomeDoorNo, etFormHomeStreet,
            etFormHomeAddress, etFormHomeAddressLine2, etFormPincode,
            etFormState, etFormDistrict, etFormLocation, etProfDesignation,
            etProfOtherDepartment, etProfIncome, etOfficeName, etOfficeEmail, etOfficeMobile,
            etOfficePhone, etOfficeDoorNo, etOfficeStreet, etOfficeAddress,
            etOfficeAddressLine2, etOfficeArea, etOfficePincode,
            etBookConversionProject, etBookConversionPlot, etBookConversionCredit,
            etBookConversionNotes, etBookConversionSourceBooking,
            etBookExchangeProject, etBookExchangePlot, etBookExchangeExtent,
            etBookExchangeLookupProject, etBookExchangeLookupPlot,
            etBookExchangeMobile, etBookExchangeSourceBooking,
            etBookExchangeValue, etBookExchangeNotes,
            etBookSourceName, etBookSourceMobile, etBookReferralBenefit,
            etBookSvName, etBookSvMobile, etBookCef, etChargeBookingCost,
            etChargeGuidelineValue, etChargeSpecialConsideration,
            etChargeDiscountApprovedBy, etChargeScReason, etChargeScValidity,
            etChargePromoOffers, etChargePromoValue, etChargeOfferValidity,
            etPayRegCharges, etPayGstAmount, etPayDocCharges, etPayPattaCharges,
            etPayOtherCharges, etPayAdvanceAmount, etPayTransactionId,
            etPayInstrumentNo, etPayBankName, etPayBankBranch,
            etPayAllotDue, etPay2Mode,
            etPay3Mode, etPay4Mode, etStaffAadhar, etStaffPancard,
            etStaffRefName1, etStaffRefMobile1, etStaffRefProf1,
            etStaffRefName2, etStaffRefMobile2, etStaffRefProf2,
        )
        editTexts.forEach { it?.addTextChangedListener(watcher) }
    }

    private fun restoreDraftIfAny() {
        val manager = draftManager ?: return
        val sourceKey = currentBookingSourceKey()
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = manager.restore(session.bearerToken, sourceKey) ?: return@launch
            if (!isAdded || draftRestoreApplied) return@launch
            draftRestoreApplied = true
            applyFormState(manager.decode(snapshot.draftJson))
            // Bump the "AI prefill already happened" flag so the AI
            // analysis prefill that runs after lookup doesn't overwrite
            // the operator's saved typing.
            android.widget.Toast.makeText(
                requireContext(),
                "Resumed your draft from " +
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(snapshot.updatedAt)),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun clearDraftAfterSubmit() {
        val manager = draftManager ?: return
        manager.clear(session.bearerToken, currentBookingSourceKey())
    }

    private fun nextSubTab(current: BookingSub): BookingSub = when (current) {
        BookingSub.CLIENT -> BookingSub.BOOKING
        BookingSub.PROFESSIONAL, BookingSub.OFFICE -> BookingSub.BOOKING
        BookingSub.BOOKING -> BookingSub.STAFF
        BookingSub.CHARGES, BookingSub.PAYMENT -> BookingSub.STAFF
        BookingSub.STAFF -> BookingSub.STAFF
    }

    private fun previousSubTab(current: BookingSub): BookingSub? = when (current) {
        BookingSub.STAFF -> BookingSub.BOOKING
        BookingSub.BOOKING, BookingSub.PROFESSIONAL, BookingSub.OFFICE -> BookingSub.CLIENT
        BookingSub.CLIENT, BookingSub.CHARGES, BookingSub.PAYMENT -> null
    }

    private fun goBackInBookingFlow() {
        if (activeOutcome != Outcome.BOOKING) return
        val previous = previousSubTab(bookingSub)
        if (previous == null) {
            dismissAllowingStateLoss()
            return
        }
        bookingSub = previous
        bookingStep = BookingStep.CLIENT_FORM
        renderState()
    }

    private fun clearBookingForm() {
        if (!isStandaloneBookingMode) return
        listOf(
            etClientMobile, tvFormPhone as? EditText, etFormName, etFormFather,
            etFormAltNumber, etFormWhatsApp, etFormEmail, etFormHomeDoorNo,
            etFormHomeStreet, etFormHomeAddress, etFormHomeAddressLine2,
            etFormPincode, etFormState, etFormDistrict, etFormLocation,
            etProfDesignation, etProfOtherDepartment, etProfIncome, etOfficeName, etOfficeEmail,
            etOfficeMobile, etOfficePhone, etOfficeDoorNo, etOfficeStreet,
            etOfficeAddress, etOfficeAddressLine2, etOfficeArea, etOfficePincode,
            etBookConversionProject, etBookConversionPlot, etBookConversionCredit,
            etBookConversionNotes, etBookConversionSourceBooking,
            etBookExchangeProject, etBookExchangePlot, etBookExchangeExtent,
            etBookExchangeLookupProject, etBookExchangeLookupPlot,
            etBookExchangeMobile, etBookExchangeSourceBooking,
            etBookExchangeValue, etBookExchangeNotes,
            etBookSourceName, etBookSourceMobile, etBookReferralBenefit,
            etBookSvName, etBookSvMobile, etBookCef,
            etChargeBookingCost, etChargeGuidelineValue, etChargeSpecialConsideration,
            etChargeDiscountApprovedBy, etChargeScReason, etChargeScValidity,
            etChargePromoOffers, etChargePromoValue, etChargeOfferValidity,
            etPayRegCharges, etPayGstAmount, etPayDocCharges, etPayPattaCharges,
            etPayOtherCharges, etPayLoanAmount, etPayTransactionId,
            etPayInstrumentNo, etPayBankName, etPayBankBranch,
            etPayAdvanceAmount, etPayAllotDue, etPay2Mode, etPay3Mode, etPay4Mode,
            etStaffAadhar, etStaffPancard, etStaffRefName1, etStaffRefMobile1,
            etStaffRefProf1, etStaffRefName2, etStaffRefMobile2, etStaffRefProf2,
        ).forEach { it?.setText("") }
        listOf(
            tvFormTitle, tvFormDob, tvFormAnniversary, tvFormNationality,
            tvProfProfession, tvProfDepartment, tvBookType, tvBookSource, tvBookProject,
            tvBookPlot, tvBookProperty, tvBookMode, tvChargePromoTnc,
            tvPayPaymentMode, tvPayInstrumentDate, tvPayAllotDate, tvPay2Date, tvPay3Date,
            tvPay4Date, tvPayPrefReg, tvStaffAvp, tvStaffGm, tvStaffSm,
            tvStaffBdo, tvStaffTelecaller, tvStaffDocPrep,
        ).forEach { it?.text = "" }
        tvBookDate?.text = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            .format(Calendar.getInstance().time)
        bookingProject = null
        bookingUnit = null
        bookingStaffAvp = null
        bookingStaffGm = null
        bookingStaffSm = null
        bookingStaffBdo = null
        bookingStaffTelecaller = null
        prefilledLeadId = null
        lastLookedUpBookingPhone = null
        lastEnrichedBookingPincode = null
        lastBookingPrefillKey = null
        bookingGstPercent = null
        bookConversionManualEntry = true
        bookExchangeManualEntry = true
        bookIsAgainstVisit = YesNo.YES
        bookDuplicate = false
        payGstApplicable = true
        payOtherApplicable = true
        payPlan = "Regular"
        plotPrefillSpecialPayment = false
        projectDetailSpecialPayment = false
        clientImageStorageId = null
        clientImageFileName = null
        clientImageLocalUri = null
        advanceProofStorageId = null
        advanceProofFileName = null
        aadhaarDocumentStorageId = null
        aadhaarDocumentFileName = null
        panDocumentStorageId = null
        panDocumentFileName = null
        btnPayProofUpload?.text = "Choose file"
        btnStaffAadhaarUpload?.text = "Choose file"
        btnStaffPanUpload?.text = "Choose file"
        renderClientImage()
        staffSaveAs = SaveAs.DRAFT
        bookingSub = BookingSub.CLIENT
        bookingStep = BookingStep.CLIENT_FORM
        applyEditModeToFields(true)
        refreshBookingRadios()
        refreshBookingTypeSections()
        refreshPaymentToggles()
        applySpecialConsiderationVisibility()
        applyAdvancePaymentVisibility()
        applyLoanAmountVisibility()
        refreshStaffSaveRadios()
        clearError()
        renderState()
        Toast.makeText(requireContext(), "Booking form cleared", Toast.LENGTH_SHORT).show()
    }

    // ---- Pickers ----------------------------------------------------
    /**
     * Toggles the conditional "Loan Amount Required" field below the
     * Customer Payment Category dropdown. Visible only when the
     * picked category starts with "B" (Loan Customer); otherwise the
     * label + input row collapse and the typed value is dropped on
     * submit. Mirrors the same conditional on the web Booking form.
     */
    private fun applyLoanAmountVisibility() {
        val isLoanCustomer = parseCustomerPaymentCategory(tvPayPaymentMode?.text) == "B"
        val vis = if (isLoanCustomer) View.VISIBLE else View.GONE
        lblPayLoanAmount?.visibility = vis
        rowPayLoanAmount?.visibility = vis
        if (!isLoanCustomer) {
            etPayLoanAmount?.text?.clear()
        }
    }

    private fun applySpecialConsiderationVisibility() {
        val visible = (numberOrNull(etChargeSpecialConsideration?.text) ?: 0.0) > 0.0
        val state = if (visible) View.VISIBLE else View.GONE
        listOf(
            R.id.lblChargeDiscountApprovedBy,
            R.id.rowChargeDiscountApprovedBy,
            R.id.lblChargeScReason,
            R.id.rowChargeScReason,
            R.id.lblChargeScValidity,
            R.id.rowChargeScValidity,
        ).forEach { id -> view?.findViewById<View>(id)?.visibility = state }
        if (!visible) {
            etChargeDiscountApprovedBy?.text?.clear()
            etChargeScReason?.text?.clear()
            etChargeScValidity?.text?.clear()
        }
    }

    private fun applyAdvancePaymentVisibility() {
        val mode = textOrNull(tvBookMode?.text)?.uppercase(Locale.US).orEmpty()
        groupPayDigitalProof?.visibility =
            if (mode in setOf("UPI", "NEFT", "RTGS")) View.VISIBLE else View.GONE
        groupPayInstrument?.visibility =
            if (mode in setOf("CHEQUE", "DD")) View.VISIBLE else View.GONE
        lblPayInstrumentNo?.text = if (mode == "DD") "DD No *" else "Cheque No *"
    }

    /**
     * Pulls the leading category letter ("A" / "B" / "C") out of the
     * picker's display label (e.g. "B - Loan Customer" → "B"). Returns
     * null when the text doesn't start with a recognised letter — keeps
     * old picker values written by previous mobile builds from getting
     * forced into the union.
     */
    private fun parseCustomerPaymentCategory(text: CharSequence?): String? {
        val raw = text?.toString()?.trim().orEmpty()
        if (raw.length < 1) return null
        return when (raw[0].uppercaseChar()) {
            'A' -> "A"
            'B' -> "B"
            'C' -> "C"
            else -> null
        }
    }

    private fun picker(title: String, items: List<String>, onPicked: (String) -> Unit) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = title,
            options = items.map { SearchableOption(item = it, title = it) },
            emptyMessage = "No options found",
        ) {
            onPicked(it)
            // Every picker selection contributes to the draft so the
            // booking form survives an app crash mid-flow. Centralised
            // here so we don't have to wire each rowBookType /
            // rowBookProperty / rowChargePromoTnc / etc. individually.
            scheduleDraftPushIfActive()
        }
    }

    private fun pickDate(
        target: TextView?,
        format: String = "dd/MM/yyyy",
        maxDateMillis: Long? = null,
        minDateMillis: Long? = null,
        afterPicked: (() -> Unit)? = null,
    ) {
        val cal = Calendar.getInstance()
        val raw = target?.text?.toString()?.trim().orEmpty()
        listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(raw) }.getOrNull()
        }?.let { cal.time = it }
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                cal.set(year, month, day)
                target?.text = SimpleDateFormat(format, Locale.US).format(cal.time)
                afterPicked?.invoke()
                // Same draft-capture story as picker() above — date
                // edits land in the snapshot without per-callsite
                // wiring.
                scheduleDraftPushIfActive()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH),
        ).apply {
            maxDateMillis?.let { datePicker.maxDate = it }
            minDateMillis?.let { datePicker.minDate = it }
        }.show()
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

    // ---- Booking picker helpers --------------------------------------
    private fun pickBookingProject() {
        if (bookingProjectCache.isNotEmpty()) {
            showBookingProjectPicker(bookingProjectCache)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMarketingProjects(session.bearerToken)
                if (!resp.success || resp.projects.isEmpty()) {
                    showError(resp.error ?: "No projects available")
                    return@launch
                }
                val ongoing = resp.projects.filter {
                    it.status?.trim()?.lowercase(Locale.US) == "ongoing"
                }
                if (ongoing.isEmpty()) {
                    showError("No ongoing projects available")
                    return@launch
                }
                bookingProjectCache = ongoing
                showBookingProjectPicker(ongoing)
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load projects")
            }
        }
    }

    private fun showBookingProjectPicker(items: List<MarketingProject>) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select project",
            options = items.map { p ->
                SearchableOption(
                    item = p,
                    title = p.name ?: "Unnamed project",
                    subtitle = listOfNotNull(p.location, p.status).joinToString(" • ")
                        .takeIf { it.isNotBlank() },
                    keywords = listOfNotNull(p.id, p.scope, p.location, p.status).joinToString(" "),
                )
            },
            emptyMessage = "No projects found",
        ) { project ->
            bookingProject = project
            bookingUnit = null
            bookingUnitCacheProjectId = null
            bookingUnitCache = emptyList()
            lastBookingPrefillKey = null
            bookingGstPercent = null
            tvBookProject?.text = project.name ?: "Selected"
            tvBookPlot?.text = "Select Plot"
            updateMinimumAdvanceHint(project)
            // A different project may not allow the Special plan.
            plotPrefillSpecialPayment = false
            projectDetailSpecialPayment = false
            ensurePaymentPlanAllowed()
            // Older backends trim the flag out of the picker rows — resolve
            // it from the raw project doc.
            if (project.specialPaymentEnabled == null) {
                resolveProjectSpecialPaymentFlag(project.id)
            }
        }
    }

    private fun updateMinimumAdvanceHint(project: MarketingProject?) {
        tvPayMinimumAdvance?.text = String.format(
            Locale.US,
            "Project minimum: ₹%,.0f. Higher advance is allowed.",
            project?.minimumAdvanceAmount ?: 0.0,
        )
    }

    private fun pickBookingUnit() {
        val project = bookingProject
        if (project == null) {
            showError("Select project first")
            return
        }
        if (bookingUnitCacheProjectId == project.id && bookingUnitCache.isNotEmpty()) {
            showBookingUnitPicker(bookingUnitCache)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch every unit then apply the public/raw status mapping on
                // device. Older deployments stored bookable rows as raw
                // "available" while newer responses expose a normalized
                // public status; server-side filtering alone could therefore
                // return an empty picker for projects such as Rajan test.
                val resp = api.listInventoryUnits(
                    token = session.bearerToken,
                    projectId = project.id,
                )
                if (!resp.success) {
                    showError(resp.error ?: "Failed to load plots")
                    return@launch
                }
                val available = resp.units.filter(::isAvailableForBooking)
                if (available.isEmpty()) {
                    showError("No available plots in this project")
                    return@launch
                }
                bookingUnitCacheProjectId = project.id
                bookingUnitCache = available
                showBookingUnitPicker(available)
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load plots")
            }
        }
    }

    private fun showBookingUnitPicker(items: List<InventoryUnit>) {
        SearchableSelectionDialog.show(
            context = requireContext(),
            title = "Select plot",
            options = items.map { unit ->
                val title = unit.unitNumber ?: unit.id
                val subtitle = listOfNotNull(
                    unit.unitType,
                    unit.facing?.let { "Facing $it" },
                    unit.area?.let { "${it.toInt()} sqft" },
                    unit.priceSnapshot?.let { "₹${it.toLong()}" },
                ).joinToString(" • ").takeIf { it.isNotBlank() }
                SearchableOption(
                    item = unit,
                    title = title,
                    subtitle = subtitle,
                    keywords = listOfNotNull(unit.id, unit.block, unit.dimensions, unit.rawStatus)
                        .joinToString(" "),
                )
            },
            emptyMessage = "No plots found",
        ) { unit ->
            if (!isAvailableForBooking(unit)) {
                showError("Selected plot is no longer available")
                return@show
            }
            bookingUnit = unit
            tvBookPlot?.text = unit.unitNumber ?: unit.id
            loadBookingPlotPrefill(force = true)
        }
    }

    private fun isAvailableForBooking(unit: InventoryUnit): Boolean =
        unit.status.trim().equals("available", ignoreCase = true) ||
            unit.rawStatus?.trim()?.equals("available", ignoreCase = true) == true

    private fun loadBookingPlotPrefill(force: Boolean = false) {
        val unit = bookingUnit ?: return
        val key = "${unit.id}:${bookingDateForApi().orEmpty()}"
        if (!force && key == lastBookingPrefillKey) return
        lastBookingPrefillKey = key
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getBookingPlotPrefill(
                    token = session.bearerToken,
                    plotId = unit.id,
                    bookingDate = bookingDateForApi(),
                )
                if (!resp.success) {
                    showError(resp.error ?: "Failed to load plot finance details")
                    return@launch
                }
                applyBookingPlotPrefill(resp)
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load plot finance details")
            }
        }
    }

    private fun applyBookingPlotPrefill(resp: BookingPlotPrefillResponse) {
        bookingGstPercent = resp.project?.gstPercent
        plotPrefillSpecialPayment = resp.project?.specialPaymentEnabled == true
        ensurePaymentPlanAllowed()
        // Older backends trim the flag out of the prefill — resolve it from
        // the raw project doc instead.
        if (resp.project?.specialPaymentEnabled == null) {
            resolveProjectSpecialPaymentFlag(resp.project?.id ?: bookingProject?.id)
        }
        val fields = resp.fields
        fun money(value: Double?): String? {
            if (value == null || !value.isFinite()) return null
            val rounded = kotlin.math.round(value)
            return if (kotlin.math.abs(value - rounded) < 0.01) rounded.toLong().toString()
            else String.format(Locale.US, "%.2f", value)
        }
        fun setMoney(field: EditText?, value: Double?) {
            money(value)?.let { field?.setText(it) }
        }
        fun setDate(field: TextView?, iso: String?) {
            val parsed = dateTextForApi(iso) ?: return
            val date = runCatching {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(parsed)
            }.getOrNull() ?: return
            field?.text = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(date)
        }

        setMoney(etChargeBookingCost, fields?.bookingCost)
        setMoney(etChargeGuidelineValue, fields?.guidelineValue)
        setMoney(etPayRegCharges, fields?.registrationCharges)
        setMoney(etPayGstAmount, fields?.gstAmount)
        setMoney(etPayDocCharges, fields?.documentCharges)
        setMoney(etPayPattaCharges, fields?.pattaCharges)
        setMoney(etPayOtherCharges, fields?.otherCharges)
        setMoney(etPayAdvanceAmount, fields?.advanceAmount)
        setMoney(etPayAllotDue, fields?.allotmentDueAmount)
        setDate(tvPayAllotDate, fields?.allotmentDueDate)

        val schedules = resp.schedules
        schedules.getOrNull(0)?.let {
            setMoney(etPay2Mode, it.amount)
            setDate(tvPay2Date, it.dueDate)
        }
        schedules.getOrNull(1)?.let {
            setMoney(etPay3Mode, it.amount)
            setDate(tvPay3Date, it.dueDate)
        }
        schedules.getOrNull(2)?.let {
            setMoney(etPay4Mode, it.amount)
            setDate(tvPay4Date, it.dueDate)
        }
        recomputeBookingFinanceDerivedFields()
        Toast.makeText(requireContext(), "Plot pricing filled from project settings", Toast.LENGTH_SHORT).show()
    }

    private fun recomputeBookingFinanceDerivedFields() {
        updateExchangeBalance()
        val gstPercent = bookingGstPercent ?: return
        if (!payGstApplicable) return
        val bookingCost = numberOrNull(etChargeBookingCost?.text) ?: return
        val specialConsideration = numberOrNull(etChargeSpecialConsideration?.text) ?: 0.0
        val guidelineValue = numberOrNull(etChargeGuidelineValue?.text) ?: return
        val agreedAmount = bookingCost - specialConsideration
        val taxable = agreedAmount - guidelineValue
        if (taxable > 0 && gstPercent.isFinite()) {
            etPayGstAmount?.setText(kotlin.math.round((taxable * gstPercent) / 100).toLong().toString())
        }
    }

    private fun pickBookingStaff(
        title: String,
        roleKey: String,
        onPicked: (StaffData) -> Unit,
    ) {
        if (bookingStaffCache.isNotEmpty()) {
            showBookingStaffPicker(title, filterBookingStaff(roleKey, bookingStaffCache), onPicked)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaff(session.bearerToken, status = "active")
                bookingStaffCache = resp.staff
                showBookingStaffPicker(title, filterBookingStaff(roleKey, resp.staff), onPicked)
            } catch (e: Exception) {
                showError(e.message ?: "Failed to load staff")
            }
        }
    }

    private fun filterBookingStaff(roleKey: String, items: List<StaffData>): List<StaffData> {
        fun StaffData.haystack(): String = listOfNotNull(name, role, designation, department)
            .joinToString(" ")
            .lowercase(Locale.US)
        val tokens = when (roleKey) {
            "avp" -> listOf("avp", "assistant vice president")
            "gm" -> listOf("gm", "general manager")
            "seniorManager" -> listOf("senior manager", "sm")
            "bdo" -> listOf("bdo", "business development")
            "telecaller" -> listOf("telecaller", "tele caller", "telesales")
            else -> emptyList()
        }
        val filtered = items.filter { staff -> tokens.any { staff.haystack().contains(it) } }
        return filtered.ifEmpty { items }
    }

    private fun showBookingStaffPicker(
        title: String,
        items: List<StaffData>,
        onPicked: (StaffData) -> Unit,
    ) {
        if (items.isEmpty()) {
            showError("No staff found")
            return
        }
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
            // Site visits are scheduled only against ongoing projects.
            options = items.ongoingOnly().map { p ->
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

    /**
     * The Sales-Ownership hierarchy levels in ascending order.
     * INCHARGE → HOD → AVP → GM → SM. When the operator picks a staff
     * at level N, we walk up the reportingTo chain and fill any EMPTY
     * slots above. Already-filled slots are never overwritten — that
     * preserves any manual override the operator made above the level
     * they're currently editing.
     */
    private enum class SvHierarchyLevel { INCHARGE, HOD, AVP, GM, SM }

    /**
     * Auto-fills the Sales-Ownership pickers above `startLevel` by
     * walking up the picked staff's reportingTo chain. Mirrors the
     * web's effect in `app/marketing/site-visits/page.tsx:419-426`
     * (HOD = Incharge.reportingTo) and extends it the rest of the way
     * up (HOD → AVP → GM → SM) so the operator only has to pick the
     * bottom of the chain and the rest snaps into place.
     *
     * Resolution order for each step:
     *   1. svStaffCache (already loaded by the SV picker) — instant
     *   2. api.getStaffDetail(reportingToId) — for senior staff that
     *      fall outside the sales/telesales department filter and
     *      aren't in the cache
     *
     * The chain stops at the first link with no reportingToId OR when
     * a senior detail fetch fails (best-effort — operator can still
     * fill any remaining levels manually).
     */
    private fun autoFillSvHierarchyFrom(picked: StaffData, startLevel: SvHierarchyLevel) {
        viewLifecycleOwner.lifecycleScope.launch {
            var current: StaffData = picked
            var level = startLevel
            while (true) {
                val nextLevel = when (level) {
                    SvHierarchyLevel.INCHARGE -> SvHierarchyLevel.HOD
                    SvHierarchyLevel.HOD -> SvHierarchyLevel.AVP
                    SvHierarchyLevel.AVP -> SvHierarchyLevel.GM
                    SvHierarchyLevel.GM -> SvHierarchyLevel.SM
                    SvHierarchyLevel.SM -> return@launch
                }
                val reportingToId = current.reportingToId?.takeIf { it.isNotBlank() }
                    ?: current.reportingTo?.takeIf { it.isNotBlank() }
                    ?: return@launch
                // Already filled at this level — chain still continues
                // upward FROM the existing pick (so manual overrides
                // propagate too).
                val existing = svStaffAtLevel(nextLevel)
                val resolved = existing ?: resolveSvStaff(reportingToId) ?: return@launch
                if (existing == null) {
                    assignSvStaffAtLevel(nextLevel, resolved)
                }
                current = resolved
                level = nextLevel
            }
        }
    }

    private fun svStaffAtLevel(level: SvHierarchyLevel): StaffData? = when (level) {
        SvHierarchyLevel.INCHARGE -> svIncharge
        SvHierarchyLevel.HOD -> svHod
        SvHierarchyLevel.AVP -> svAvp
        SvHierarchyLevel.GM -> svGm
        SvHierarchyLevel.SM -> svSm
    }

    private fun assignSvStaffAtLevel(level: SvHierarchyLevel, staff: StaffData) {
        val label = staff.name ?: "Selected"
        when (level) {
            SvHierarchyLevel.INCHARGE -> { svIncharge = staff; tvSvIncharge?.text = label }
            SvHierarchyLevel.HOD -> { svHod = staff; tvSvHod?.text = label }
            SvHierarchyLevel.AVP -> { svAvp = staff; tvSvAvp?.text = label }
            SvHierarchyLevel.GM -> { svGm = staff; tvSvGm?.text = label }
            SvHierarchyLevel.SM -> { svSm = staff; tvSvSm?.text = label }
        }
    }

    /**
     * Resolves a staff id to a StaffData object. Hits the in-memory
     * svStaffCache first (zero-RTT for sales/telesales rows the picker
     * already loaded), then falls back to /api/staff/get/{id} for
     * senior management rows that aren't in the cache. Returns null on
     * network error so the chain just stops cleanly.
     */
    private suspend fun resolveSvStaff(staffId: String): StaffData? {
        svStaffCache.firstOrNull { it.id == staffId }?.let { return it }
        return runCatching {
            val resp = api.getStaffDetail(session.bearerToken, staffId)
            // StaffFullData → StaffData. The reporting chain is the
            // only field we need beyond identity; everything else gets
            // best-effort filled from the detail response. We populate
            // BOTH reportingTo and reportingToId from the detail's
            // single `reportingTo` field (it's the staff id at this
            // layer — see SessionManager.bootstrap saving
            // `staff.reportingTo` into `session.reportingToId`).
            resp.staff?.let { full ->
                StaffData(
                    id = full.id,
                    name = full.name,
                    phone = full.phone,
                    role = full.role,
                    designation = full.designation,
                    status = full.status,
                    employeeId = full.employeeId,
                    department = full.department,
                    reportingTo = full.reportingTo,
                    reportingToId = full.reportingTo,
                )
            }
        }.getOrNull()
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

    /**
     * Inflates one visitor card per expected attendee (capped at 12).
     *
     * Pre-fill behaviour:
     *   - Index 0 (first card): name + relation = "Self" use the cached
     *     lead/client name when available — the common 1-visitor case
     *     is the client themself, so save the user a tap.
     *   - All cards: when the telecaller pre-set attendees on the CP
     *     visit's SV-fix payload, replay those onto the cards in order
     *     (overrides the lead-only default on index 0).
     *
     * Field staff can edit anything pre-filled — this only sets the
     * initial values.
     */
    private fun renderVisitorRows(count: Int) {
        val rows = siteVisitorRows ?: return
        rows.removeAllViews()
        val safeCount = count.coerceIn(0, 12)
        val prefilled = cachedPrefilledAttendees ?: emptyList()
        val leadName = cachedLeadDisplayName
        repeat(safeCount) { index ->
            val card = layoutInflater.inflate(
                R.layout.item_outcome_site_visitor, rows, false
            )
            wireVisitorCardToggles(card)
            val attendee = prefilled.getOrNull(index)
            val nameField = card.findViewWithTag<EditText>("name")
            val relationField = card.findViewWithTag<TextView>("relation")
            val ageField = card.findViewWithTag<EditText>("age")
            when {
                attendee != null -> {
                    nameField?.setText(attendee.name.orEmpty())
                    relationField?.text = attendee.relation.orEmpty()
                    ageField?.setText(attendee.age.orEmpty())
                    if (attendee.isVeg == false) {
                        // Default layout pre-selects Veg; flip to Non-Veg
                        // when the telecaller captured otherwise.
                        card.findViewWithTag<TextView>("foodNonVeg")?.performClick()
                    }
                }
                index == 0 && !leadName.isNullOrBlank() -> {
                    nameField?.setText(leadName)
                    relationField?.text = "Self"
                }
            }
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
        // Relation dropdown opens a simple picker. "Self" sits at the
        // top because it's the most-used value — the first visitor row
        // is auto-filled with the lead's name + Self, and we want the
        // option present in the picker too so the user can re-select
        // it on subsequent cards or after editing.
        card.findViewWithTag<View>("relationRow")?.setOnClickListener {
            picker(
                "Relation",
                listOf(
                    "Self",
                    "Spouse",
                    "Parent",
                    "Sibling",
                    "Child",
                    "Friend",
                    "Colleague",
                    "Other",
                ),
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
                        // Stamp the current user as BOTH the
                        // convertedBy slot and the telecaller. Without
                        // these the server falls back to the CP visit's
                        // assignedStaffId (which is often a different
                        // field staff) — and then the freshly-created
                        // SV never shows up in this user's mobile feed
                        // because `listForViewerAsMobileVisits` keys
                        // off telecallerId / convertedByStaffId /
                        // inchargeStaffId.
                        telecallerId = session.staffId,
                        convertedByStaffId = session.staffId,
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
                // Retrofit throws HttpException on a 5xx before deserialising
                // the response body — `e.message` is just "HTTP 500 Internal
                // Server Error" which is useless to the user. Try to extract
                // the JSON {error: "..."} the backend puts in the body so the
                // toast shows the actual reason (staff busy, project missing,
                // etc.) instead of a generic 500.
                val serverMessage = extractHttpErrorMessage(e)
                val message = serverMessage ?: e.message ?: "Network error"
                finishCtaSiteVisit(message)
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Pull the `{ error: "..." }` field out of a Retrofit HttpException's
     * error body. Returns null on any failure (non-HttpException, body
     * unreadable, JSON parse error, missing field). Mirrors the helper
     * AttendanceFlowViewModel uses for the same purpose.
     */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        return runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            (obj.get("error")?.asString ?: obj.get("message")?.asString)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun finishCtaSiteVisit(error: String) {
        btnSubmit?.isClickable = true
        btnSubmit?.text = "Save"
        showError(error)
    }

    // ---- Postpone -------------------------------------------------------
    private fun persistPostpone() {
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
        if (!isSiteVisitMode && cpVisitId.isNullOrBlank()) {
            return showError("Missing CP visit id")
        }
        // Postpone captures a next-visit date + a reason. Both are
        // required: the date so the office knows when to re-run the
        // visit, the reason because the backend's setOutcome rejects an
        // empty postponeReasons array for outcome="postponed" (the reason
        // ships as that single entry, via postponedReasonsFromForm()).
        val nextDate = tvPostNextDate?.text?.toString()?.trim().orEmpty()
        if (nextDate.isBlank() || nextDate.equals("dd/mm/yyyy", ignoreCase = true)) {
            showError("Pick the next visit date")
            return
        }
        val reason = etPostNotes?.text?.toString()?.trim().orEmpty()
        if (reason.isBlank()) {
            showError("Enter a reason for postponement")
            return
        }
        // The next-visit date rides in the human-readable notes blob (the
        // outcome request has no dedicated date field); the reason travels
        // separately via postponeReasons.
        finalizeTerminalOutcome(
            cpVisitId = cpVisitId.orEmpty(),
            outcomeEnum = OUTCOME_POSTPONED,
            notes = "Next visit: $nextDate — $reason",
        )
    }

    // ---- Not Interested -------------------------------------------------
    private fun persistNotInterested() {
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
        if (!isSiteVisitMode && cpVisitId.isNullOrBlank()) {
            return showError("Missing CP visit id")
        }
        // Same web validation: at least one reason must be ticked.
        val picks = niPicksFromForm()
        if (picks.isEmpty()) {
            showError("Select at least one reason")
            return
        }
        val notes = etNiNotes?.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
        // For the CP path the backend's setOutcome doesn't accept
        // notInterestedReasons / notInterestedDetails — only `notes`.
        // We serialize the structured picks into the notes blob so the
        // web admin opening the row still sees what the operator
        // selected and any per-reason detail they typed. The SV path
        // ships them as structured fields (handled in
        // finalizeTerminalOutcome).
        val serializedForCp = buildNotInterestedNotesForCp(picks, notes)
        finalizeTerminalOutcome(
            cpVisitId = cpVisitId.orEmpty(),
            outcomeEnum = OUTCOME_NOT_INTERESTED,
            notes = serializedForCp,
        )
    }

    private data class NotInterestedPick(val reason: String, val detail: String?)

    /**
     * Walks the (checkbox, detail-input) pairs in display order and
     * returns one entry per ticked reason. Detail is null when the
     * inline input is empty — matches the web's
     * `notInterestedDetails.map(r => ({ reason, detail: trim() || undefined }))`
     * shape so the rows that land in the DB are isomorphic.
     */
    private fun niPicksFromForm(): List<NotInterestedPick> {
        val pairs = niReasonPairs()
        val picks = mutableListOf<NotInterestedPick>()
        pairs.forEachIndexed { index, (cb, detailInput) ->
            if (cb?.isChecked == true) {
                val detail = detailInput?.text?.toString()?.trim().orEmpty()
                picks += NotInterestedPick(
                    reason = NI_REASON_LABELS[index],
                    detail = detail.takeIf { it.isNotBlank() },
                )
            }
        }
        return picks
    }

    /**
     * Mobile-only serializer: turns the structured picks into a
     * human-readable notes blob for CP visits, since the CP backend
     * doesn't have notInterestedReasons / notInterestedDetails
     * columns. Keeps general notes after a blank line so the web
     * admin can distinguish "what was selected" from "what was
     * typed". Returns just the general notes when picks is empty
     * (never expected — persistNotInterested gates on picks first).
     */
    private fun buildNotInterestedNotesForCp(
        picks: List<NotInterestedPick>,
        generalNotes: String?,
    ): String = buildString {
        if (picks.isNotEmpty()) {
            append("Reasons: ")
            append(picks.joinToString(", ") { it.reason })
            picks.filter { !it.detail.isNullOrBlank() }.forEach {
                append("\n• ").append(it.reason).append(": ").append(it.detail)
            }
        }
        if (!generalNotes.isNullOrBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(generalNotes)
        }
    }.trim()

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
                // SV mode: write directly to the siteVisits row via the
                // dedicated endpoint. No markClientMet (there's no CP
                // visit to mark), no CP setOutcome. The SV path accepts
                // the same outcome strings (postponed / not_interested)
                // and stores notes/reasons on the row.
                if (isSiteVisitMode) {
                    val svId = argSiteVisitId
                        ?: run {
                            finishCtaSave("Missing site visit id")
                            return@launch
                        }
                    val resp = geoApi.setSiteVisitOutcome(
                        session.bearerToken,
                        SetSiteVisitOutcomeRequest(
                            id = svId,
                            outcome = outcomeEnum,
                            postponeReasons = if (outcomeEnum == OUTCOME_POSTPONED)
                                postponedReasonsFromForm() else null,
                            notInterestedReasons = if (outcomeEnum == OUTCOME_NOT_INTERESTED)
                                notInterestedReasonsFromForm() else null,
                            // SV backend's setOutcome stores notInterestedDetails
                            // alongside notInterestedReasons — same shape the
                            // web SV detail page reads from. Per-reason detail
                            // strings come from the inline inputs that
                            // appear under each checked reason.
                            notInterestedDetails = if (outcomeEnum == OUTCOME_NOT_INTERESTED)
                                notInterestedDetailsFromForm() else null,
                            notes = notes,
                        ),
                    )
                    if (!resp.success) {
                        finishCtaSave(resp.error ?: "Failed to save outcome")
                        return@launch
                    }
                    setFragmentResult(
                        RESULT_KEY,
                        bundleOf(KEY_CLIENT_MET to true, KEY_OUTCOME to outcomeEnum),
                    )
                    dismissAllowingStateLoss()
                    return@launch
                }

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
                        // Backend's setOutcome throws when outcome="postponed"
                        // and postponeReasons is empty/null. We now drive the
                        // CP path off the same checkbox set the SV path uses,
                        // so the row that lands in `clientPlaceVisits` looks
                        // identical no matter which surface saved it.
                        postponeReasons = if (outcomeEnum == OUTCOME_POSTPONED)
                            postponedReasonsFromForm() else null,
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

    /**
     * Best-effort extraction of structured postpone reasons from the
     * Postpone tab's form. The current CP path doesn't actually pass
     * these to the server (sends notes only), so for SV mode we
     * synthesise a single-element array from the captured fields when
     * present. The SV backend requires a non-empty array for
     * outcome=postponed.
     */
    /**
     * Pulls the currently-checked postpone reason labels in the SAME
     * order and SAME wording as the web's POSTPONE_REASONS list — so
     * the row that lands in `clientPlaceVisits.postponeReasons` is
     * byte-identical whether the operator saved from web or mobile.
     * No fallback synthesis: an empty result is a real signal that
     * the operator hit Save without ticking anything, and the caller
     * (persistPostpone) shows the same gist error the web shows.
     */
    private fun postponedReasonsFromForm(): List<String> {
        // The postpone form now carries a single free-text reason rather
        // than a checklist; ship it as the one-element array the backend
        // requires. Empty → caller (persistPostpone) shows the gist error.
        val reason = etPostNotes?.text?.toString()?.trim().orEmpty()
        return if (reason.isBlank()) emptyList() else listOf(reason)
    }

    /**
     * SV-path version of the picker — returns just the checked reason
     * labels (no details). Web-canonical strings; same order as
     * NOT_INTERESTED_REASONS. The SV backend's setOutcome rejects an
     * empty array when outcome="not_interested" so the caller gates
     * on picks first in persistNotInterested.
     */
    private fun notInterestedReasonsFromForm(): List<String> =
        niPicksFromForm().map { it.reason }

    /**
     * Companion to notInterestedReasonsFromForm — returns the
     * { reason, detail } pairs for the SV mutation. The backend stores
     * this on siteVisits.notInterestedDetails so the web admin's
     * detail-display reads identically.
     */
    private fun notInterestedDetailsFromForm(): List<SvNotInterestedDetail> =
        niPicksFromForm().map { SvNotInterestedDetail(reason = it.reason, detail = it.detail) }

    private fun finishCtaSave(error: String) {
        btnSubmit?.isClickable = true
        btnSubmit?.text = "Save"
        showError(error)
    }

    /**
     * Parse the Booking-tab date row into yyyy-MM-dd for the API.
     * The picker writes "dd/MM/yyyy"; we just rewrite. Returns null
     * when the field is empty or holds the placeholder so callers
     * can fall back to today.
     */
    // ── Client Image (web parity: optional client photo) ──

    private fun openClientImageCamera() {
        val ctx = context ?: return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showClientImageCamera()
        } else {
            clientImageCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun showClientImageCamera() {
        // Plain local, NOT .apply{} — inside apply the camera sheet becomes
        // the implicit receiver and unqualified view/context resolve to IT
        // (the documented crash pitfall from the daily-log form).
        val camera = com.manjugroups.m_connect.ui.chat.CustomCameraBottomSheet()
        camera.setListener(object :
            com.manjugroups.m_connect.ui.chat.CustomCameraBottomSheet.CameraResultListener {
            override fun onMediaCaptured(uri: Uri, isVideo: Boolean) {
                if (!isVideo) uploadClientImage(uri)
            }

            override fun onGalleryClicked() {
                pickClientImage.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }
        })
        camera.showOnce(childFragmentManager, "client_image_camera")
    }

    private fun uploadClientImage(uri: Uri) {
        val ctx = context ?: return
        tvClientImageAction?.text = "Uploading…"
        cardClientImageUpload?.isEnabled = false
        btnClientImageAction?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            // Off the main thread: copying a multi-MB camera photo through
            // the ContentResolver on Main is an ANR risk.
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val cr = ctx.contentResolver
                    val mime = cr.getType(uri) ?: "image/jpeg"
                    val ext = when {
                        mime.contains("png") -> "png"
                        mime.contains("webp") -> "webp"
                        else -> "jpg"
                    }
                    val tmp = java.io.File.createTempFile("client_", ".$ext", ctx.cacheDir)
                    cr.openInputStream(uri).use { input ->
                        tmp.outputStream().use { output -> input?.copyTo(output) }
                    }
                    val uploaded = StorageUploader.upload(
                        api, session.bearerToken, tmp, contentType = mime,
                    )
                    tmp.delete()
                    uploaded
                }
            }.getOrNull()
            if (!isAdded) return@launch
            cardClientImageUpload?.isEnabled = true
            btnClientImageAction?.isEnabled = true
            val storageId = result?.storageId
            if (storageId.isNullOrBlank()) {
                Toast.makeText(
                    requireContext(),
                    result?.errorMessage ?: "Couldn't upload the client image",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                clientImageStorageId = storageId
                clientImageFileName = "client-photo.jpg"
                clientImageLocalUri = uri
            }
            renderClientImage()
        }
    }

    private fun renderClientImage() {
        val hasImage = clientImageStorageId != null
        tvClientImageAction?.text = if (hasImage) "Change Client Pic" else "Upload Client Pic"
        rowClientImagePreview?.visibility = if (hasImage) View.VISIBLE else View.GONE
        tvClientImageName?.text = clientImageFileName ?: "Client image uploaded"
        val img = imgClientPhoto ?: return
        img.clipToOutline = true
        val local = clientImageLocalUri
        if (hasImage && local != null) {
            img.load(local)
        } else {
            img.load(R.drawable.ic_outcome_person)
        }
    }

    private fun chooseBookingDocument(kind: BookingDocumentKind) {
        pendingBookingDocumentKind = kind
        pickBookingDocument.launch(arrayOf("application/pdf", "image/*"))
    }

    private fun uploadBookingDocument(uri: Uri, kind: BookingDocumentKind) {
        val ctx = context ?: return
        val target = when (kind) {
            BookingDocumentKind.ADVANCE_PROOF -> btnPayProofUpload
            BookingDocumentKind.AADHAAR -> btnStaffAadhaarUpload
            BookingDocumentKind.PAN -> btnStaffPanUpload
        }
        target?.text = "Uploading…"
        target?.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val displayName = resolveDocumentName(uri)
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            val uploaded = runCatching {
                withContext(Dispatchers.IO) {
                    val suffix = displayName.substringAfterLast('.', "bin").take(8)
                    val temp = java.io.File.createTempFile("booking_doc_", ".$suffix", ctx.cacheDir)
                    try {
                        ctx.contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Unable to read selected file" }
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        StorageUploader.upload(api, session.bearerToken, temp, contentType = mime)
                    } finally {
                        temp.delete()
                    }
                }
            }.getOrNull()
            if (!isAdded) return@launch
            target?.isEnabled = true
            val storageId = uploaded?.storageId
            if (storageId.isNullOrBlank()) {
                target?.text = "Choose file"
                Toast.makeText(
                    requireContext(),
                    uploaded?.errorMessage ?: "Couldn't upload the selected file",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            when (kind) {
                BookingDocumentKind.ADVANCE_PROOF -> {
                    advanceProofStorageId = storageId
                    advanceProofFileName = displayName
                }
                BookingDocumentKind.AADHAAR -> {
                    aadhaarDocumentStorageId = storageId
                    aadhaarDocumentFileName = displayName
                }
                BookingDocumentKind.PAN -> {
                    panDocumentStorageId = storageId
                    panDocumentFileName = displayName
                }
            }
            target?.text = "✓ $displayName"
            scheduleDraftPushIfActive()
        }
    }

    private fun resolveDocumentName(uri: Uri): String {
        val cursor = context?.contentResolver?.query(uri, null, null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && it.moveToFirst()) {
                it.getString(index)?.takeIf(String::isNotBlank)?.let { name -> return name }
            }
        }
        return "booking-document"
    }

    private fun bookingDateForApi(): String? {
        val raw = tvBookDate?.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("dd/mm/yyyy", ignoreCase = true)) return null
        return dateTextForApi(raw)
    }

    // ── Payment plan (web parity: booking-new-page PAYMENT_PLAN_DAYS) ──

    /** Day window for the scheduled payment dates under [plan]. */
    private fun paymentPlanDays(plan: String = payPlan): Int = when (plan) {
        "Flexi" -> 60
        "Special" -> 180
        else -> 30
    }

    private fun planLabel(plan: String): String = "$plan (max ${paymentPlanDays(plan)} days)"

    private fun planFromLabel(label: String): String = label.substringBefore(" (").trim()

    private fun specialPaymentAllowed(): Boolean =
        bookingProject?.specialPaymentEnabled == true ||
            plotPrefillSpecialPayment ||
            projectDetailSpecialPayment

    /** The trimmed marketing-projects / plot-prefill responses only carry
     *  specialPaymentEnabled on newer backends — /api/projects/get returns
     *  the raw project doc on ANY deploy, so resolve the flag there whenever
     *  the trimmed sources leave it unknown. Retries, because a single
     *  silent failure on a flaky field network would permanently hide the
     *  Special plan for this sheet. */
    private fun resolveProjectSpecialPaymentFlag(projectId: String?) {
        if (projectId.isNullOrBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            repeat(3) { attempt ->
                val resp = runCatching {
                    api.getProjectDetail(session.bearerToken, projectId)
                }.getOrNull()
                val enabled = resp?.project?.specialPaymentEnabled
                if (enabled != null) {
                    if (!isAdded) return@launch
                    // A late response for a project the user switched away
                    // from must not gate the currently selected one.
                    val current = bookingProject?.id
                    if (current != null && current != projectId) return@launch
                    projectDetailSpecialPayment = enabled
                    ensurePaymentPlanAllowed()
                    return@launch
                }
                if (attempt < 2) delay(1200L * (attempt + 1))
            }
        }
    }

    /** Regular and Flexi always; Special only for enabled projects. */
    private fun paymentPlanOptions(): List<String> = buildList {
        add(planLabel("Regular"))
        add(planLabel("Flexi"))
        if (specialPaymentAllowed()) add(planLabel("Special"))
    }

    /** Web parity: a project without the flag can't keep a Special plan. */
    private fun ensurePaymentPlanAllowed() {
        if (payPlan == "Special" && !specialPaymentAllowed()) {
            payPlan = "Regular"
            tvPayPlan?.text = planLabel(payPlan)
            clampPaymentDatesToPlan()
        }
    }

    /** bookingDate + [days] as a DatePicker max; null without a booking date. */
    private fun paymentDateLimitMillis(days: Int): Long? {
        val iso = bookingDateForApi() ?: return null
        val parsed = runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
        }.getOrNull() ?: return null
        return Calendar.getInstance().apply {
            time = parsed
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis
    }

    /** Days from the booking date to [raw]; null when either is unparsable. */
    private fun daysFromBooking(raw: CharSequence?): Int? {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val booking = bookingDateForApi()
            ?.let { runCatching { fmt.parse(it) }.getOrNull() } ?: return null
        val target = dateTextForApi(raw)
            ?.let { runCatching { fmt.parse(it) }.getOrNull() } ?: return null
        return ((target.time - booking.time) / 86_400_000L).toInt()
    }

    /** Snap any scheduled date beyond the current plan's window back to the
     *  window edge — same as the web's clamp on plan/booking-date changes. */
    private fun clampPaymentDatesToPlan() {
        val out = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        fun clamp(tv: TextView?, maxDays: Int) {
            val days = daysFromBooking(tv?.text) ?: return
            if (days > maxDays) {
                paymentDateLimitMillis(maxDays)?.let {
                    tv?.text = out.format(java.util.Date(it))
                }
            }
        }
        clamp(tvPayAllotDate, 10)
        val cap = paymentPlanDays()
        clamp(tvPay2Date, cap)
        clamp(tvPay3Date, cap)
        clamp(tvPay4Date, cap)
    }

    /** Save-time guard mirroring the web's validation messages. */
    private fun validatePaymentSchedule(): String? {
        daysFromBooking(tvPayAllotDate?.text)?.let { days ->
            if (days > 10) {
                return "Allotment Due Date cannot exceed 10 days from booking date"
            }
        }
        val cap = paymentPlanDays()
        listOf("2nd" to tvPay2Date, "3rd" to tvPay3Date, "4th" to tvPay4Date)
            .forEach { (label, tv) ->
                daysFromBooking(tv?.text)?.let { days ->
                    if (days > cap) {
                        return "$payPlan plan $label payment date cannot exceed $cap days"
                    }
                }
            }
        return null
    }

    private fun dateTextForApi(raw: CharSequence?): String? {
        val value = raw?.toString()?.trim().orEmpty()
        if (value.isBlank() || value.contains("dd/", ignoreCase = true)) return null
        val out = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy").forEach { pattern ->
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(value)
            }.getOrNull()
            if (parsed != null) return out.format(parsed)
        }
        return null
    }

    private fun textOrNull(value: CharSequence?): String? =
        value?.toString()?.trim()?.takeIf {
            it.isNotBlank() &&
                !it.equals("select", ignoreCase = true) &&
                !it.startsWith("select ", ignoreCase = true) &&
                !it.equals("dd/mm/yyyy", ignoreCase = true)
        }

    private fun numberOrNull(value: CharSequence?): Double? =
        value?.toString()?.trim()?.replace(",", "")?.takeIf { it.isNotBlank() }?.toDoubleOrNull()

    private fun composeAddress(vararg parts: CharSequence?): String? =
        parts.mapNotNull(::textOrNull).joinToString(", ").takeIf { it.isNotBlank() }

    private fun buildBookingRequest(
        sourceType: String,
        cpVisitId: String? = null,
        siteVisitId: String? = null,
    ): CreateBookingRequest? {
        // Match web's /marketing/bookings/new — only 3 hard requirements:
        // Mobile Number, Client Name, Booking Date. Everything else is
        // optional and the row lands in `bookings` with whatever the
        // operator filled. Project / Plot / staff routing / charges are
        // all encouraged (the labels still hint placeholder text) but
        // not enforced — same as the web. Operators on the field can
        // capture a booking with partial info and the office completes
        // it later.
        val phone = textOrNull(etClientMobile?.text) ?: textOrNull(tvFormPhone?.text)
        val name = textOrNull(etFormName?.text)
        val project = bookingProject
        val unit = bookingUnit
        if (phone.isNullOrBlank()) {
            finishCta(error = "Mobile Number is required")
            return null
        }
        if (name.isNullOrBlank()) {
            finishCta(error = "Client Name is required")
            return null
        }
        if (bookingDateForApi() == null) {
            finishCta(error = "Booking Date is required")
            return null
        }
        // Web-parity payment-schedule windows (allotment ≤ 10 days; the
        // plan's 30/60/180-day cap on the scheduled payment dates).
        validatePaymentSchedule()?.let { message ->
            finishCta(error = message)
            return null
        }
        val bookingCost = numberOrNull(etChargeBookingCost?.text)
        val advanceAmount = numberOrNull(etPayAdvanceAmount?.text)
        val bookingType = textOrNull(tvBookType?.text)
        val isExchange = bookingType == "EXCHANGE" || bookingType == "INTERNAL EXCHANGE"
        val specialConsideration = numberOrNull(etChargeSpecialConsideration?.text) ?: 0.0
        val agreedAmount = bookingCost?.minus(specialConsideration)
        val exchangeValue = numberOrNull(etBookExchangeValue?.text)
        val totalPayable = calculatedTotalPayableAmount()
        return CreateBookingRequest(
            clientName = name,
            mobileNumber = phone,
            clientImageStorageId = clientImageStorageId,
            clientImageFileName = if (clientImageStorageId != null) clientImageFileName else null,
            bookingDate = bookingDateForApi() ?: SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(Calendar.getInstance().time),
            leadId = prefilledLeadId,
            title = textOrNull(tvFormTitle?.text),
            fatherSpouseName = textOrNull(etFormFather?.text),
            dateOfBirth = dateTextForApi(tvFormDob?.text),
            anniversaryDate = dateTextForApi(tvFormAnniversary?.text),
            alternateNumbers = textOrNull(etFormAltNumber?.text),
            whatsappNumber = textOrNull(etFormWhatsApp?.text),
            email = textOrNull(etFormEmail?.text),
            pincode = textOrNull(etFormPincode?.text),
            homeAddress = composeAddress(
                etFormHomeDoorNo?.text,
                etFormHomeStreet?.text,
                etFormHomeAddress?.text,
                etFormHomeAddressLine2?.text,
            ),
            profession = textOrNull(tvProfProfession?.text),
            designation = textOrNull(etProfDesignation?.text),
            department = if (textOrNull(tvProfProfession?.text) == "Salaried") {
                if (textOrNull(tvProfDepartment?.text) == "Other") {
                    textOrNull(etProfOtherDepartment?.text)
                } else {
                    textOrNull(tvProfDepartment?.text)
                }
            } else null,
            incomePerAnnum = textOrNull(etProfIncome?.text),
            officeName = textOrNull(etOfficeName?.text),
            officeAddress = composeAddress(
                etOfficeDoorNo?.text,
                etOfficeStreet?.text,
                etOfficeAddress?.text,
                etOfficeAddressLine2?.text,
            ),
            officeArea = textOrNull(etOfficeArea?.text),
            officePincode = textOrNull(etOfficePincode?.text),
            state = textOrNull(etFormState?.text),
            district = textOrNull(etFormDistrict?.text),
            location = textOrNull(etFormLocation?.text),
            officeMobile = textOrNull(etOfficeMobile?.text),
            officePhone = textOrNull(etOfficePhone?.text),
            officeEmail = textOrNull(etOfficeEmail?.text),
            nationality = textOrNull(tvFormNationality?.text),
            projectId = project?.id,
            plotId = unit?.id,
            plotNo = unit?.unitNumber,
            bookingType = bookingType,
            conversionManualEntry = if (bookingType == "CONVERSION") bookConversionManualEntry else null,
            manualConversionProjectName = if (bookingType == "CONVERSION" && bookConversionManualEntry)
                textOrNull(etBookConversionProject?.text) else null,
            manualConversionPlotNo = if (bookingType == "CONVERSION" && bookConversionManualEntry)
                textOrNull(etBookConversionPlot?.text) else null,
            manualConversionCredit = if (bookingType == "CONVERSION" && bookConversionManualEntry)
                numberOrNull(etBookConversionCredit?.text) else null,
            conversionNotes = if (bookingType == "CONVERSION" && bookConversionManualEntry)
                textOrNull(etBookConversionNotes?.text) else null,
            sourceExchangeBookingId = when {
                bookingType == "CONVERSION" && !bookConversionManualEntry ->
                    textOrNull(etBookConversionSourceBooking?.text)
                isExchange && !bookExchangeManualEntry ->
                    textOrNull(etBookExchangeSourceBooking?.text)
                else -> null
            },
            exchangeManualEntry = if (isExchange) bookExchangeManualEntry else null,
            exchangeLookupProjectId = if (bookingType == "INTERNAL EXCHANGE" && !bookExchangeManualEntry)
                textOrNull(etBookExchangeLookupProject?.text) else null,
            exchangeLookupPlotNo = if (bookingType == "INTERNAL EXCHANGE" && !bookExchangeManualEntry)
                textOrNull(etBookExchangeLookupPlot?.text) else null,
            exchangeConnectedMobileNumber = if (bookingType == "INTERNAL EXCHANGE" && !bookExchangeManualEntry)
                textOrNull(etBookExchangeMobile?.text)?.filter(Char::isDigit) else null,
            manualExchangeProjectName = if (isExchange && bookExchangeManualEntry)
                textOrNull(etBookExchangeProject?.text) else null,
            manualExchangePlotNo = if (isExchange && bookExchangeManualEntry)
                textOrNull(etBookExchangePlot?.text) else null,
            manualExchangeExtentSqft = if (isExchange && bookExchangeManualEntry)
                numberOrNull(etBookExchangeExtent?.text) else null,
            exchangeOldRegisteredValue = if (isExchange) exchangeValue else null,
            exchangeNewValue = if (bookingType == "EXCHANGE") agreedAmount else null,
            exchangeBalancePayable = if (bookingType == "EXCHANGE") {
                ((totalPayable ?: 0.0) - (exchangeValue ?: 0.0)).coerceAtLeast(0.0)
            } else null,
            exchangeNotes = if (isExchange) textOrNull(etBookExchangeNotes?.text) else null,
            cefNo = textOrNull(etBookCef?.text),
            isDuplicateBooking = bookDuplicate,
            isAgainstSV = bookIsAgainstVisit == YesNo.YES,
            svName = if (bookIsAgainstVisit == YesNo.YES) textOrNull(etBookSvName?.text) else null,
            svMobileNo = if (bookIsAgainstVisit == YesNo.YES) textOrNull(etBookSvMobile?.text) else null,
            propertyType = textOrNull(tvBookProperty?.text),
            bookingMode = textOrNull(tvBookMode?.text),
            clientSource = textOrNull(tvBookSource?.text),
            clientSourceName = textOrNull(etBookSourceName?.text),
            clientSourceMobile = textOrNull(etBookSourceMobile?.text),
            referralBenefit = textOrNull(etBookReferralBenefit?.text),
            bookingCost = bookingCost,
            guidelineValue = numberOrNull(etChargeGuidelineValue?.text),
            specialConsideration = numberOrNull(etChargeSpecialConsideration?.text),
            specialConsiderationReason = textOrNull(etChargeScReason?.text),
            discountApprovedBy = textOrNull(etChargeDiscountApprovedBy?.text),
            specialConsiderationValidity = numberOrNull(etChargeScValidity?.text),
            promotionalOffers = textOrNull(etChargePromoOffers?.text),
            promotionalOffersTnC = textOrNull(tvChargePromoTnc?.text),
            promotionalOfferValue = numberOrNull(etChargePromoValue?.text),
            offerValidityPeriod = numberOrNull(etChargeOfferValidity?.text),
            agreedAmount = agreedAmount,
            registrationCharges = numberOrNull(etPayRegCharges?.text),
            gstAmount = numberOrNull(etPayGstAmount?.text),
            gstApplicable = payGstApplicable,
            documentCharges = numberOrNull(etPayDocCharges?.text),
            pattaCharges = numberOrNull(etPayPattaCharges?.text),
            otherCharges = numberOrNull(etPayOtherCharges?.text),
            otherChargesApplicable = payOtherApplicable,
            advanceAmount = advanceAmount,
            balanceAmount = if (bookingCost != null && advanceAmount != null) bookingCost - advanceAmount else null,
            paymentMode = textOrNull(tvBookMode?.text),
            advanceTransactionId = textOrNull(etPayTransactionId?.text),
            advancePaymentProofStorageId = advanceProofStorageId,
            advancePaymentProofFileName = advanceProofFileName,
            advanceInstrumentNo = textOrNull(etPayInstrumentNo?.text),
            advanceBankName = textOrNull(etPayBankName?.text),
            advanceBankBranch = textOrNull(etPayBankBranch?.text),
            advanceInstrumentDate = dateTextForApi(tvPayInstrumentDate?.text),
            customerPaymentCategory = parseCustomerPaymentCategory(tvPayPaymentMode?.text),
            loanAmountRequested = if (parseCustomerPaymentCategory(tvPayPaymentMode?.text) == "B")
                numberOrNull(etPayLoanAmount?.text)
            else null,
            paymentPlan = payPlan,
            freePayment = payPlan == "Flexi",
            allotmentDueAmount = numberOrNull(etPayAllotDue?.text),
            allotmentDueDate = dateTextForApi(tvPayAllotDate?.text),
            secondPaymentAmount = numberOrNull(etPay2Mode?.text),
            secondPaymentDate = dateTextForApi(tvPay2Date?.text),
            thirdPaymentAmount = numberOrNull(etPay3Mode?.text),
            thirdPaymentDate = dateTextForApi(tvPay3Date?.text),
            fourthPaymentAmount = numberOrNull(etPay4Mode?.text),
            fourthPaymentDate = dateTextForApi(tvPay4Date?.text),
            preferredRegistrationDate = dateTextForApi(tvPayPrefReg?.text),
            originalAvpStaffId = bookingStaffAvp?.id,
            originalGmStaffId = bookingStaffGm?.id,
            originalSeniorManagerStaffId = bookingStaffSm?.id,
            originalBdoStaffId = bookingStaffBdo?.id,
            originalTelecallerStaffId = bookingStaffTelecaller?.id,
            aadhaar = textOrNull(etStaffAadhar?.text),
            aadhaarDocumentStorageId = aadhaarDocumentStorageId,
            aadhaarDocumentFileName = aadhaarDocumentFileName,
            pan = textOrNull(etStaffPancard?.text),
            panDocumentStorageId = panDocumentStorageId,
            panDocumentFileName = panDocumentFileName,
            referenceName1 = textOrNull(etStaffRefName1?.text),
            referenceMobile1 = textOrNull(etStaffRefMobile1?.text),
            referenceProfession1 = textOrNull(etStaffRefProf1?.text),
            referenceName2 = textOrNull(etStaffRefName2?.text),
            referenceMobile2 = textOrNull(etStaffRefMobile2?.text),
            referenceProfession2 = textOrNull(etStaffRefProf2?.text),
            docPreparedIn = textOrNull(tvStaffDocPrep?.text),
            status = if (staffSaveAs == SaveAs.CONFIRMED) "pending_confirmation" else "draft",
            sourceType = sourceType,
            sourceClientPlaceVisitId = cpVisitId,
            sourceSiteVisitId = siteVisitId,
        )
    }

    private fun confirmationMissingFields(bookingCost: Double?, advanceAmount: Double?): List<String> {
        val missing = mutableListOf<String>()
        fun hasText(value: CharSequence?): Boolean = textOrNull(value) != null
        fun requireText(label: String, value: CharSequence?) {
            if (!hasText(value)) missing += label
        }
        fun requirePositive(label: String, value: CharSequence?) {
            if ((numberOrNull(value) ?: 0.0) <= 0.0) missing += label
        }

        requireText("Mobile Number", textOrNull(tvFormPhone?.text) ?: textOrNull(etClientMobile?.text))
        requireText("Title", tvFormTitle?.text)
        requireText("Client Name", etFormName?.text)
        requireText("Father / Spouse Name", etFormFather?.text)
        requireText("Date of Birth", tvFormDob?.text)
        requireText("Alternate Numbers", etFormAltNumber?.text)
        requireText("WhatsApp Number", etFormWhatsApp?.text)
        requireText("Email", etFormEmail?.text)
        requireText("Nationality", tvFormNationality?.text)
        requireText("Door No", etFormHomeDoorNo?.text)
        requireText("Street Name", etFormHomeStreet?.text)
        requireText("Address Line 1", etFormHomeAddress?.text)
        requireText("Pincode", etFormPincode?.text)
        requireText("District", etFormDistrict?.text)

        requireText("Profession", tvProfProfession?.text)
        requireText("Designation", etProfDesignation?.text)
        if (textOrNull(tvProfProfession?.text).equals("Salaried", ignoreCase = true)) {
            requireText("Department", tvProfDepartment?.text)
            if (textOrNull(tvProfDepartment?.text) == "Other") {
                requireText("Other Department", etProfOtherDepartment?.text)
            }
        }
        requireText("Income Per Annum", etProfIncome?.text)
        requireText("Office Name", etOfficeName?.text)
        requireText("Office Door No", etOfficeDoorNo?.text)
        requireText("Office Street Name", etOfficeStreet?.text)
        requireText("Office Address Line 1", etOfficeAddress?.text)

        requireText("Booking Type", tvBookType?.text)
        when (textOrNull(tvBookType?.text)) {
            "CONVERSION" -> {
                if (bookConversionManualEntry) {
                    requireText("Previous Project", etBookConversionProject?.text)
                    requireText("Previous Plot", etBookConversionPlot?.text)
                    requirePositive("Conversion Credit", etBookConversionCredit?.text)
                } else {
                    requireText("Previous Booking ID", etBookConversionSourceBooking?.text)
                }
            }
            "EXCHANGE", "INTERNAL EXCHANGE" -> {
                if (bookExchangeManualEntry) {
                    requireText("Old Project Name", etBookExchangeProject?.text)
                    requireText("Old Plot Number", etBookExchangePlot?.text)
                } else {
                    if (textOrNull(tvBookType?.text) == "INTERNAL EXCHANGE") {
                        requireText("Old Project ID", etBookExchangeLookupProject?.text)
                        requireText("Old Plot Number", etBookExchangeLookupPlot?.text)
                        requireText("Connected Mobile Number", etBookExchangeMobile?.text)
                    }
                    requireText("Source Booking ID", etBookExchangeSourceBooking?.text)
                }
                if (textOrNull(tvBookType?.text) == "EXCHANGE") {
                    requirePositive("Exchange Value", etBookExchangeValue?.text)
                }
            }
        }
        requireText("CEF No", etBookCef?.text)
        requireText("Booking Date", tvBookDate?.text)
        if (bookingProject == null) missing += "Project"
        if (bookingUnit == null && textOrNull(tvBookPlot?.text) == null) missing += "Plot"
        requireText("Property Type", tvBookProperty?.text)
        requireText("Advance Booking Payment", tvBookMode?.text)
        if (bookIsAgainstVisit == YesNo.YES) {
            requireText("SV Name", etBookSvName?.text)
            requireText("SV Mobile No.", etBookSvMobile?.text)
        }

        if (bookingCost == null || bookingCost <= 0) missing += "Booking Cost"
        requireText("Guideline Value", etChargeGuidelineValue?.text)
        if ((numberOrNull(etChargeSpecialConsideration?.text) ?: 0.0) > 0.0) {
            requireText("Discount Approved By", etChargeDiscountApprovedBy?.text)
            requireText("SC Reason", etChargeScReason?.text)
            requirePositive("SC Validity", etChargeScValidity?.text)
        }
        requireText("Promotional Offer", etChargePromoOffers?.text)
        requireText("Offer Value", etChargePromoValue?.text)
        requireText("Terms & Conditions", tvChargePromoTnc?.text)
        requireText("Registration Charges", etPayRegCharges?.text)
        requireText("GST Amount", etPayGstAmount?.text)
        requireText("Document Charges", etPayDocCharges?.text)
        requireText("Patta Charges", etPayPattaCharges?.text)
        requireText("Other Charges", etPayOtherCharges?.text)
        requireText("Customer Payment Category", tvPayPaymentMode?.text)
        if (advanceAmount == null || advanceAmount <= 0) missing += "Advance Amount"

        val bookingMode = textOrNull(tvBookMode?.text)?.uppercase(Locale.US).orEmpty()
        if (bookingMode in setOf("UPI", "NEFT", "RTGS")) {
            requireText("Transaction ID", etPayTransactionId?.text)
            if (advanceProofStorageId.isNullOrBlank()) missing += "Payment Proof"
        }
        if (bookingMode in setOf("CHEQUE", "DD")) {
            requireText(if (bookingMode == "DD") "DD No" else "Cheque No", etPayInstrumentNo?.text)
            requireText("Bank", etPayBankName?.text)
            requireText("Branch", etPayBankBranch?.text)
            requireText("Date", tvPayInstrumentDate?.text)
        }
        if (parseCustomerPaymentCategory(tvPayPaymentMode?.text) == "B") {
            requirePositive("Bank Loan Amount", etPayLoanAmount?.text)
        }

        requireText("Payment Plan", tvPayPlan?.text)
        requireText("Allotment Due Amount", etPayAllotDue?.text)
        requireText("Allotment Due Date", tvPayAllotDate?.text)
        if (payPlan != "Flexi") {
            requireText("2nd Payment Amount", etPay2Mode?.text)
            requireText("2nd Payment Date", tvPay2Date?.text)
            requireText("3rd Payment Amount", etPay3Mode?.text)
            requireText("3rd Payment Date", tvPay3Date?.text)
            requireText("4th Payment Amount", etPay4Mode?.text)
            requireText("4th Payment Date", tvPay4Date?.text)
        }
        requireText("Preferred Registration Date", tvPayPrefReg?.text)

        if (bookingStaffAvp == null) missing += "Original AVP"
        if (bookingStaffGm == null) missing += "Original General Manager"
        if (bookingStaffSm == null) missing += "Original Senior Manager"
        if (bookingStaffBdo == null) missing += "Original BDO"
        if (bookingStaffTelecaller == null) missing += "Original Telecaller"
        requireText("Aadhaar Number", etStaffAadhar?.text)
        if (aadhaarDocumentStorageId.isNullOrBlank()) missing += "Aadhaar Upload"
        requireText("PAN Number", etStaffPancard?.text)
        if (panDocumentStorageId.isNullOrBlank()) missing += "PAN Upload"
        requireText("Reference 1 — Name", etStaffRefName1?.text)
        requireText("Reference 1 — Relation", etStaffRefProf1?.text)
        requireText("Reference 1 — Mobile", etStaffRefMobile1?.text)
        requireText("Reference 2 — Name", etStaffRefName2?.text)
        requireText("Reference 2 — Relation", etStaffRefProf2?.text)
        requireText("Reference 2 — Mobile", etStaffRefMobile2?.text)
        requireText("Document to be Prepared In", tvStaffDocPrep?.text)
        return missing
    }

    private fun confirmationValidationError(bookingCost: Double?, advanceAmount: Double?): String? {
        fun digits(value: CharSequence?): String =
            value?.filter(Char::isDigit)?.toString().orEmpty()
        listOf(
            "Mobile Number" to (textOrNull(tvFormPhone?.text) ?: textOrNull(etClientMobile?.text)),
            "Alternate Numbers" to textOrNull(etFormAltNumber?.text),
            "WhatsApp Number" to textOrNull(etFormWhatsApp?.text),
            "Reference 1 Mobile" to textOrNull(etStaffRefMobile1?.text),
            "Reference 2 Mobile" to textOrNull(etStaffRefMobile2?.text),
        ).firstOrNull { (_, value) -> value != null && digits(value).length != 10 }
            ?.let { return "${it.first} must be exactly 10 digits" }
        if (bookIsAgainstVisit == YesNo.YES && digits(etBookSvMobile?.text).length != 10) {
            return "SV Mobile No. must be exactly 10 digits"
        }
        textOrNull(etBookSourceMobile?.text)?.let {
            if (digits(it).length != 10) return "Source / Reference Mobile must be exactly 10 digits"
        }
        if (textOrNull(tvBookType?.text) == "INTERNAL EXCHANGE" &&
            !bookExchangeManualEntry && digits(etBookExchangeMobile?.text).length != 10
        ) {
            return "Connected Mobile Number must be exactly 10 digits"
        }
        if (digits(etFormPincode?.text).length != 6) return "Pincode must be exactly 6 digits"
        textOrNull(etOfficePincode?.text)?.let {
            if (digits(it).length != 6) return "Office Pincode must be exactly 6 digits"
        }
        if (digits(etStaffAadhar?.text).length != 12) return "Aadhaar Number must be exactly 12 digits"
        if (textOrNull(etStaffPancard?.text)?.length != 10) return "PAN Number must be exactly 10 characters"

        val specialConsideration = numberOrNull(etChargeSpecialConsideration?.text) ?: 0.0
        if (bookingCost != null && specialConsideration > bookingCost) {
            return "Special Consideration cannot exceed the Booking Cost"
        }
        val minimumAdvance = bookingProject?.minimumAdvanceAmount
        if (minimumAdvance != null && (advanceAmount ?: 0.0) < minimumAdvance) {
            return "Advance must be at least ₹${minimumAdvance.toLong()} as set in Project Details"
        }
        val basePayable = calculatedTotalPayableAmount() ?: 0.0
        val totalPayable = if (textOrNull(tvBookType?.text) == "EXCHANGE") {
            (basePayable - (numberOrNull(etBookExchangeValue?.text) ?: 0.0)).coerceAtLeast(0.0)
        } else {
            basePayable
        }
        if ((advanceAmount ?: 0.0) > totalPayable) {
            return "Advance cannot exceed the total payable amount"
        }
        val loanAmount = if (parseCustomerPaymentCategory(tvPayPaymentMode?.text) == "B") {
            numberOrNull(etPayLoanAmount?.text) ?: 0.0
        } else 0.0
        if (loanAmount > totalPayable) return "Bank Loan Amount cannot exceed the Total Property Cost"
        if ((advanceAmount ?: 0.0) > totalPayable - loanAmount) {
            return "Advance cannot exceed the Customer Payable Amount after excluding the bank loan"
        }
        val conversionCredit = if (textOrNull(tvBookType?.text) == "CONVERSION" &&
            bookConversionManualEntry
        ) numberOrNull(etBookConversionCredit?.text) ?: 0.0 else 0.0
        val remainingAfterAdvance = totalPayable - loanAmount -
            (advanceAmount ?: 0.0) - conversionCredit
        val allotment = numberOrNull(etPayAllotDue?.text) ?: 0.0
        if (allotment > remainingAfterAdvance) {
            return "Allotment payment cannot exceed the remaining Customer Payable Amount"
        }
        val scheduled = listOf(etPay2Mode, etPay3Mode, etPay4Mode)
            .sumOf { numberOrNull(it?.text) ?: 0.0 }
        if (payPlan != "Flexi" && scheduled > remainingAfterAdvance - allotment) {
            return "Payment schedule cannot exceed the remaining Customer Payable Amount"
        }
        return validatePaymentSchedule()
    }

    // ---- Persistence ------------------------------------------------
    private fun persistBooking() {
        val met = true
        btnSubmit?.isClickable = false
        btnSubmit?.text = "Saving…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Standalone booking from the Bookings + button — no
                // CP visit / SV row to attach an outcome to. POST
                // straight to /api/bookings (the same web flow uses)
                // so the row lands in `bookings` and the office-side
                // approval workflow picks up from pending_gm.
                if (isStandaloneBookingMode) {
                    val request = buildBookingRequest(sourceType = "walk_in") ?: return@launch
                    val createResp = api.createBooking(
                        session.bearerToken,
                        request,
                    )
                    if (!createResp.success) {
                        finishCta(error = createResp.error ?: "Failed to create booking")
                        return@launch
                    }
                    // Push any edits the user made on the prefilled
                    // client form back to the lead so the next person
                    // sees the corrected profile.
                    pushClientEditsToLeadIfAny()
                    // Booking row landed — wipe the auto-save scratchpad
                    // so the next time this operator opens a booking
                    // form for a DIFFERENT source they start with a
                    // clean slate instead of inheriting these values.
                    clearDraftAfterSubmit()
                    setFragmentResult(
                        RESULT_KEY,
                        bundleOf(
                            KEY_CLIENT_MET to met,
                            KEY_OUTCOME to OUTCOME_BOOKING,
                        ),
                    )
                    dismissAllowingStateLoss()
                    return@launch
                }

                // SV-mode booking — now uses the same booking-create
                // endpoint as the web app, with sourceSiteVisitId set so
                // the server can mark the SV converted.
                if (isSiteVisitMode) {
                    val svId = argSiteVisitId
                        ?: run {
                            finishCta(error = "Missing site visit id")
                            return@launch
                        }
                    val request = buildBookingRequest(
                        sourceType = "site_visit",
                        siteVisitId = svId,
                    ) ?: return@launch
                    val resp = api.createBooking(
                        session.bearerToken,
                        request,
                    )
                    if (!resp.success) {
                        finishCta(error = resp.error ?: "Failed to save booking")
                        return@launch
                    }
                    // Mirror the CP-mode lead push for SV-mode too —
                    // same Edit-toggle semantics, same target lead row.
                    pushClientEditsToLeadIfAny()
                    clearDraftAfterSubmit()
                    setFragmentResult(
                        RESULT_KEY,
                        bundleOf(KEY_CLIENT_MET to met, KEY_OUTCOME to OUTCOME_BOOKING),
                    )
                    dismissAllowingStateLoss()
                    return@launch
                }

                val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
                    ?: run {
                        finishCta(error = "Missing CP visit id")
                        return@launch
                    }
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(id = cpVisitId, clientMet = met),
                )
                if (!metResp.success) {
                    finishCta(error = metResp.error ?: "Failed to record client met")
                    return@launch
                }

                val request = buildBookingRequest(
                    sourceType = "cp_visit",
                    cpVisitId = cpVisitId,
                ) ?: return@launch
                val outcomeResp = api.createBooking(
                    session.bearerToken,
                    request,
                )
                if (!outcomeResp.success) {
                    finishCta(error = outcomeResp.error ?: "Failed to save booking")
                    return@launch
                }

                // Push any field-staff edits to the prefilled client
                // form back to the lead's manualProfile. No-op when
                // the user didn't tap Edit. Best-effort — booking
                // already saved, so failures here just get logged.
                pushClientEditsToLeadIfAny()
                clearDraftAfterSubmit()

                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to met,
                        KEY_OUTCOME to OUTCOME_BOOKING,
                    ),
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                // Both surfaces (the inline tvError row and the Toast)
                // are fed the SAME pre-humanized message so the operator
                // doesn't see the raw stack trace anywhere. showError
                // runs humanizeServerError internally; we mirror that
                // for the Toast which writes its text directly.
                val raw = e.message ?: "Network error"
                finishCta(error = raw)
                Toast.makeText(requireContext(), humanizeServerError(raw), Toast.LENGTH_LONG)
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
        row("Payment Plan", planLabel(payPlan))
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
        tvError?.text = humanizeServerError(msg)
        tvError?.visibility = View.VISIBLE
    }

    private fun clearError() {
        tvError?.visibility = View.GONE
    }

    /**
     * Cleans up server-side error messages before showing them to the
     * operator. Convex throws a single big string that includes:
     *   • An "Uncaught Error: " prefix
     *   • The actual human-readable message
     *   • A stack trace ("at fnName (../convex/file.ts:LINE:COL)")
     *
     * Showing the whole blob — with code paths, line numbers, and the
     * scary "Uncaught Error" header — looks like a crash to a field
     * operator. This helper strips the noise, keeps the message, and
     * reformats the most common "missing fields" case as something a
     * non-technical user can act on.
     *
     * If the input doesn't match any known noise pattern (e.g. a
     * straightforward "Network error" Toast), it's returned unchanged.
     */
    private fun humanizeServerError(raw: String): String {
        if (raw.isBlank()) return "Something went wrong. Please try again."

        // 1) Drop everything from the first stack-trace marker onwards.
        //    Convex stack lines look like:  at name (../convex/x.ts:1:2)
        //    Splitting on a newline followed by whitespace + "at " strips
        //    them in one shot without nibbling at legitimate "at "
        //    occurrences in the message body.
        val noStack = raw.split(Regex("(?m)\\n\\s*at\\s")).firstOrNull()?.trim().orEmpty()

        // 2) Strip the "Uncaught Error:" / "Error:" / "ConvexError:" prefix.
        val noPrefix = noStack
            .replace(Regex("^(Uncaught\\s+)?(Convex)?Error:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        // 3) Strip trailing punctuation noise so the next sentence we
        //    might append doesn't look weird.
        val clean = noPrefix.trimEnd('.', ' ', '\n')

        if (clean.isEmpty()) return "Something went wrong. Please try again."

        // 4) Reformat the most common case — "Cannot submit booking for
        //    confirmation. Missing: A, B, C." — into a clearer two-line
        //    message so the operator sees the gap at a glance instead of
        //    parsing a comma-soup string.
        val missingMatch = Regex(
            "^Cannot submit booking for confirmation\\.\\s*Missing:\\s*(.+)$",
            RegexOption.IGNORE_CASE,
        ).find(clean)
        if (missingMatch != null) {
            val fields = missingMatch.groupValues[1]
                .trimEnd('.', ' ')
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            // These fields aren't actually required to SAVE the row —
            // they're approval-workflow asks the office can fill in
            // later. Frame it that way so the operator doesn't think
            // the booking failed entirely.
            val list = if (fields.size <= 4) fields.joinToString(", ")
            else fields.take(4).joinToString(", ") + " +" + (fields.size - 4) + " more"
            return "Save the booking as Draft, or fill in: $list. The office can add these during approval."
        }

        return clean
    }

    private fun outcomeFromArg(value: String): Outcome? = when (value) {
        OUTCOME_BOOKING -> Outcome.BOOKING
        OUTCOME_SITE_VISIT -> Outcome.SITE_VISIT
        OUTCOME_POSTPONED -> Outcome.POSTPONE
        OUTCOME_NOT_INTERESTED -> Outcome.NOT_INTERESTED
        // Map rejected → NOT_INTERESTED for the UI enum since both
        // are terminal-decline tabs; the actual outcome string saved
        // server-side stays distinct ("rejected"). This only matters
        // for the visual highlight on a resumed sheet — the
        // server-truth value isn't overwritten.
        OUTCOME_REJECTED -> Outcome.NOT_INTERESTED
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
                // Single-visit get — replaces the historical hack of
                // pulling the full 200-row /my list just to find one row.
                // Cuts latency on sheet open from ~1-2s on a slow link
                // down to one round-trip (~100ms) AND removes the visible
                // glitch where the Booking tab body painted for one frame
                // before async detect resolved and snapped the UI to
                // locked SV. The /api/marketing/clientPlaceVisits/get
                // route was wired in a later patch — keeping the helper
                // shape identical so the rest of this method is unchanged.
                val detailResp = geoApi.getCpVisitDetail(
                    session.bearerToken,
                    cpVisitId,
                )
                if (!detailResp.success) {
                    android.util.Log.d(
                        LOG_TAG,
                        "detect: get call failed: ${detailResp.error ?: "(no error)"}",
                    )
                    return@launch
                }
                val visit = detailResp.visit
                if (visit == null) {
                    android.util.Log.d(
                        LOG_TAG,
                        "detect: cpVisitId=$cpVisitId not returned by get",
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

                // Always seed the SV-form caches from CP context so
                // the visitor row, project picker and pickup address
                // pre-fill even on a *manually*-created CP with no
                // SV-lock signal. Locked-SV mode (below) layers on
                // top of these defaults.
                seedSvDefaultsFromCpVisit(visit)

                // Lock the sheet if ANY signal fires:
                //   1. proposedSiteVisit has at least one populated field
                //   2. The lead's followUpStatus is already "sv_fixed"
                //   3. Party data is on the CP visit row (SV-fix-only)
                if (!proposedMeaningful && !leadFlaggedSvFixed && !hasSvFixParty) {
                    android.util.Log.d(
                        LOG_TAG,
                        "detect: cpVisitId=$cpVisitId no SV-fix signal -> normal mode (defaults seeded)",
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

    /**
     * Seed the SV outcome form with whatever the CP visit already
     * knows — client name (first visitor = client / Self), attendees
     * the telecaller pre-set, the CP's project, and the place's
     * pickup address. Fires unconditionally on every CP detail load
     * so a manually-created CP gets the same head-start the
     * locked-SV path enjoys. applyLockedSvMode runs AFTER this and
     * can layer telecaller-pre-fixed overrides on top.
     */
    private suspend fun seedSvDefaultsFromCpVisit(visit: CpVisitDetail) {
        if (!isAdded) return

        // Client display name → first visitor name + "Self" relation.
        cachedLeadDisplayName = visit.client?.clientName?.takeIf { it.isNotBlank() }
            ?: visit.lead?.contactName?.takeIf { it.isNotBlank() }
            ?: visit.clientPlace?.name?.takeIf { it.isNotBlank() }

        // Telecaller-pre-set attendees (rare on a pure manual CP,
        // common on the SV-fixed path) — used by renderVisitorRows.
        cachedPrefilledAttendees = visit.attendees

        // Visitor count: prefer the field the telecaller set, then
        // attendees.size, then default to 1 so the user always sees
        // at least the client/Self card pre-filled.
        val visitorCount = visit.expectedAttendeeCount ?: visit.attendees?.size ?: 0
        val effectiveCount = if (visitorCount > 0) visitorCount else 1
        // Only stamp the field if it's blank — preserves user edits
        // when they reopen the sheet.
        if (etSvVisitorCount?.text?.toString().isNullOrBlank()) {
            etSvVisitorCount?.setText(effectiveCount.toString())
        }

        // Pickup address: pull from the resolved clientPlace row.
        if (etSvPickupAddress?.text?.toString().isNullOrBlank()) {
            etSvPickupAddress?.setText(
                visit.clientPlace?.address
                    ?: visit.clientPlace?.formattedAddress
                    ?: visit.lead?.preferredArea
                    ?: "",
            )
        }

        // Project picker: prefer the CP's own projectId (set by the
        // mobile/web Create CP form's Project picker), then fall back
        // to the proposedSiteVisit.projectId path that
        // applyLockedSvMode also uses. Lets a manually-created CP
        // arrive at the SV form with the project already selected.
        val cpProjectId = visit.projectId ?: visit.proposedSiteVisit?.projectId
        prefillProjectIfPossible(cpProjectId)
    }

    private suspend fun applyLockedSvMode(visit: CpVisitDetail, proposed: ProposedSiteVisit) {
        if (!isAdded) return
        lockedFromProposedSv = true
        lockedCpVisit = visit

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

        // Cache lead + attendee context for the visitor-row auto-fill.
        // Order: client.clientName (canonical, manually entered) →
        // lead.contactName (telecaller-typed during dialer flow) →
        // clientPlace.name (fallback). renderVisitorRows reads this
        // cache when expanding cards so the first row is pre-filled
        // with the lead's name + Self relation — the common case for
        // 1-visitor meetings.
        cachedLeadDisplayName = visit.client?.clientName?.takeIf { it.isNotBlank() }
            ?: visit.lead?.contactName?.takeIf { it.isNotBlank() }
            ?: visit.clientPlace?.name?.takeIf { it.isNotBlank() }
        cachedPrefilledAttendees = visit.attendees

        val visitorCount = visit.expectedAttendeeCount ?: visit.attendees?.size ?: 0
        // Default to 1 visitor when the telecaller didn't specify and the
        // SV-fix flow doesn't carry attendees — the most common reality
        // is the lead alone, and pre-populating saves a tap.
        val effectiveVisitorCount = if (visitorCount > 0) visitorCount else 1
        etSvVisitorCount?.setText(effectiveVisitorCount.toString())
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

        // 4. SV form fields stay EDITABLE in locked mode — the CP visit
        //    staff often needs to adjust telecaller-fixed details (e.g.
        //    swap the BDO if they're not available, tweak pickup address
        //    after talking to the client, change schedule). The locked
        //    aspect is the *outcome path* — only Reject / Confirm exits
        //    are allowed (no Postpone / Booking tabs). Earlier we used
        //    applyReadOnlyToSvBody() here but that contradicted the
        //    field-staff workflow.

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
        // 1. Explicitly NULL the click listeners on every interactive row.
        //    bindSiteVisitFields wired these in onViewCreated, and the row
        //    LinearLayouts use OutcomeFieldPillClickable which sets
        //    clickable=true in the style — so just toggling isClickable
        //    off later was racing the style. Clearing the listener is the
        //    only bulletproof block.
        val rowIds = intArrayOf(
            R.id.rowSvProject,
            R.id.rowSvDate,
            R.id.rowSvTime,
            R.id.rowSvIncharge,
            R.id.rowSvHod,
            R.id.rowSvAvp,
            R.id.rowSvGm,
            R.id.rowSvSm,
        )
        for (id in rowIds) {
            body.findViewById<View>(id)?.apply {
                setOnClickListener(null)
                isClickable = false
                isFocusable = false
                alpha = 0.7f
            }
        }

        // 2. Walk the body to disable every EditText so the address /
        //    visitor-count inputs can't be typed into either.
        fun walk(v: View) {
            if (v is EditText) {
                v.isFocusable = false
                v.isFocusableInTouchMode = false
                v.isCursorVisible = false
                v.isEnabled = false
                v.alpha = 0.7f
                v.setOnClickListener(null)
            } else if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(body)

        // 3. Pickup From segmented control — kill the travel-mode toggles.
        btnSvTravelOwn?.apply {
            setOnClickListener(null)
            isClickable = false
            isFocusable = false
            alpha = 0.7f
        }
        btnSvTravelCab?.apply {
            setOnClickListener(null)
            isClickable = false
            isFocusable = false
            alpha = 0.7f
        }

    }

    private fun onLockedConfirmTap() {
        // Two confirmation paths depending on whether the SV is already
        // materialized:
        //
        // 1. SV-cum-CP (the telecaller pre-created the SV via
        //    siteVisits.create with clientPlaceVisitId; the CP row carries
        //    `convertedSiteVisitId` pointing at the pending SV).
        //    convertCpVisitToSiteVisit is a no-op here — its early-return
        //    guard at clientPlaceVisits.ts:1363 returns the existing SV
        //    id without touching its confirmationStatus, so the linked SV
        //    stays "pending" forever and never leaves the Fixed tab on
        //    web. The correct call is setCpVisitOutcome(outcome =
        //    "interested"): the backend's setOutcome path at
        //    clientPlaceVisits.ts:1149-1168 sees outcome === "interested"
        //    AND visit.convertedSiteVisitId set, and patches the linked
        //    SV's confirmationStatus to "confirmed".
        //
        // 2. Proposed-only CP (CP carries proposedSiteVisit but no
        //    convertedSiteVisitId yet — older flows or direct CP create).
        //    Here the SV doesn't exist yet, so we keep the legacy
        //    persistSiteVisit path which calls convertCpVisitToSiteVisit
        //    to materialize the SV from the proposed payload.
        clearError()
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")
        val convertedSvId = lockedCpVisit?.convertedSiteVisitId?.takeIf { it.isNotBlank() }
        if (convertedSvId != null) {
            persistSvCumCpConfirm(cpVisitId)
        } else {
            persistSiteVisit()
        }
    }

    /**
     * SV-cum-CP confirmation: the SV already exists (with
     * confirmationStatus="pending"), the CP needs its outcome recorded
     * as "interested" so the backend's clientPlaceVisits.setOutcome
     * gate flips the linked SV to confirmed. Distinct from the
     * proposed-only confirm path (which materializes a brand-new SV via
     * convertCpVisitToSiteVisit) because that mutation short-circuits
     * when convertedSiteVisitId is already set.
     */
    private fun persistSvCumCpConfirm(cpVisitId: String) {
        btnCpLockedConfirm?.isClickable = false
        btnCpLockedConfirm?.text = "Saving…"
        btnCpLockedReject?.isClickable = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val metResp = geoApi.markClientMet(
                    session.bearerToken,
                    MarkClientMetRequest(id = cpVisitId, clientMet = true),
                )
                if (!metResp.success) {
                    finishCtaLockedConfirm(metResp.error ?: "Failed to record client met")
                    return@launch
                }
                val outcomeResp = geoApi.setCpVisitOutcome(
                    session.bearerToken,
                    SetOutcomeRequest(
                        id = cpVisitId,
                        outcome = OUTCOME_INTERESTED,
                        notes = "Confirmed by field staff",
                    ),
                )
                if (!outcomeResp.success) {
                    finishCtaLockedConfirm(outcomeResp.error ?: "Failed to save outcome")
                    return@launch
                }
                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to true,
                        KEY_OUTCOME to OUTCOME_INTERESTED,
                    ),
                )
                dismissAllowingStateLoss()
            } catch (e: Exception) {
                val serverMessage = extractHttpErrorMessage(e)
                finishCtaLockedConfirm(serverMessage ?: e.message ?: "Network error")
            }
        }
    }

    private fun finishCtaLockedConfirm(error: String) {
        btnCpLockedConfirm?.isClickable = true
        btnCpLockedConfirm?.text = "Confirm"
        btnCpLockedReject?.isClickable = true
        showError(error)
    }

    private fun onLockedRejectTap() {
        // Hand off to the Rejection Case sub-sheet so the field staff
        // can capture a free-text reason ("client backed out due to
        // budget", "site location mismatch", etc.). That sheet fires
        // the same markClientMet + setCpVisitOutcome(not_interested)
        // chain we used to invoke directly here, but with the reason
        // shipped as the outcome notes so back-office surfaces have
        // context. On success it re-emits this sheet's RESULT_KEY so
        // the upstream TripNavigationFragment flow continues as
        // before — no caller change needed.
        clearError()
        val cpVisitId = arguments?.getString(ARG_CP_VISIT_ID)
            ?: return showError("Missing CP visit id")

        // Listen on the reason sheet's RESULT_KEY (a distinct key from
        // this sheet's own RESULT_KEY so re-broadcasting can't loop).
        // When the reason sheet succeeds we forward the result upstream
        // on our own key so the TripNavigationFragment listener — which
        // expects CompleteCpVisitBottomSheet.RESULT_KEY — fires
        // exactly as it always did, and then dismiss this sheet too.
        parentFragmentManager.setFragmentResultListener(
            RejectReasonBottomSheet.RESULT_KEY,
            this,
        ) { _, bundle ->
            val outcome = bundle.getString(RejectReasonBottomSheet.KEY_OUTCOME)
            if (outcome == OUTCOME_REJECTED) {
                setFragmentResult(
                    RESULT_KEY,
                    bundleOf(
                        KEY_CLIENT_MET to true,
                        KEY_OUTCOME to OUTCOME_REJECTED,
                    ),
                )
                dismissAllowingStateLoss()
            }
        }

        RejectReasonBottomSheet
            .newInstance(cpVisitId)
            .showOnce(parentFragmentManager, "cp_reject_reason")
    }

    companion object {
        private const val LOG_TAG = "CpOutcomeSheet"
        const val RESULT_KEY = "cp_visit_complete_result"
        const val KEY_CLIENT_MET = "clientMet"
        const val KEY_OUTCOME = "outcome"
        private const val RESULT_KEY_GENERIC_DATE = "cp_visit_generic_date"
        private const val ARG_CP_VISIT_ID = "arg_cp_visit_id"
        private const val ARG_CP_CLIENT_MET = "arg_cp_client_met"
        private const val ARG_CP_OUTCOME = "arg_cp_outcome"
        // Pre-pass from TripNavigationFragment when the upstream
        // reconcile already determined this CP came from a telecaller-
        // fixed SV. Lets us avoid the brief Booking-tab flash while the
        // sheet's own detect call resolves.
        private const val ARG_IS_SV_FIXED_HINT = "arg_is_sv_fixed_hint"
        // Pure-SV outcome mode — set by [forSiteVisit] when the sheet
        // is opened from the SV Trip Details "Complete Outcome" CTA.
        // Mutually exclusive with the CP path; presence of this arg
        // flips isSiteVisitMode true.
        private const val ARG_SITE_VISIT_ID = "arg_site_visit_id"
        private const val ARG_SITE_VISIT_LOCKED_OUTCOME = "arg_site_visit_locked_outcome"
        // Standalone booking creation mode — set by [forStandaloneBooking]
        // when the sheet is opened from the Bookings list + button.
        // No visit to attach to; persistBooking POSTs to /api/bookings
        // instead of the CP / SV outcome paths.
        private const val ARG_STANDALONE_BOOKING = "arg_standalone_booking"

        private const val OUTCOME_BOOKING = "converted_to_booking"
        private const val OUTCOME_SITE_VISIT = "converted_to_site_visit"
        // SV-cum-CP confirm path: emitted when the field staff taps the
        // locked-mode Confirm button on a CP that already has a linked
        // SV (convertedSiteVisitId set). Triggers the web's
        // clientPlaceVisits.setOutcome path to flip the linked SV's
        // confirmationStatus from "pending" to "confirmed" so it leaves
        // the Fixed tab.
        private const val OUTCOME_INTERESTED = "interested"
        private const val OUTCOME_POSTPONED = "postponed"
        private const val OUTCOME_NOT_INTERESTED = "not_interested"
        // SV-cum-CP rejection (distinct from not_interested). Set by
        // the RejectReasonBottomSheet sub-flow with the captured reason
        // shipped as notes; Convex outcomeValidator was extended to
        // accept this value.
        private const val OUTCOME_REJECTED = "rejected"

        // siteVisits.setOutcome accepts a subset of CP outcome strings.
        // Booking outcome translates to the SV's "converted_to_booking"
        // value — the actual booking row creation is deferred to the
        // office side until the mobile plot picker is real.
        private const val OUTCOME_SV_CONVERTED_TO_BOOKING = "converted_to_booking"

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

        /**
         * Factory for the pure-SV outcome flow. The sheet renders the
         * same Booking / Postpone / Not Interested tabs but locks the
         * Site Visit tab (this row IS already a site visit), and all
         * persistence routes to /api/marketing/siteVisits/... endpoints.
         */
        fun forSiteVisit(
            siteVisitId: String,
            initialOutcome: String? = null,
        ): CompleteCpVisitBottomSheet =
            CompleteCpVisitBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_SITE_VISIT_ID, siteVisitId)
                    if (!initialOutcome.isNullOrBlank()) {
                        putString(ARG_SITE_VISIT_LOCKED_OUTCOME, initialOutcome)
                    }
                }
            }

        /**
         * Factory for standalone booking creation from the Bookings
         * list + button. Sheet locks to the Booking outcome (Site
         * Visit / Postpone / Not Interested top tabs are faded and
         * disabled), and Save Booking POSTs to /api/bookings via
         * api.createBooking — same endpoint the web booking form
         * hits, so the new row syncs through the existing approval
         * workflow.
         */
        fun forStandaloneBooking(): CompleteCpVisitBottomSheet =
            CompleteCpVisitBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_STANDALONE_BOOKING, true)
                }
            }
    }
}
