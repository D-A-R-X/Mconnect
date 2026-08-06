package com.manjugroups.m_connect.ui.marketing.bookings

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.CreateBookingRequest
import com.manjugroups.m_connect.network.FlexiPaymentRow
import com.manjugroups.m_connect.network.InventoryUnit
import com.manjugroups.m_connect.network.MarketingProject
import com.manjugroups.m_connect.network.StaffData
import com.manjugroups.m_connect.network.TelecallerLeadSearchData
import com.manjugroups.m_connect.ui.common.SearchableOption
import com.manjugroups.m_connect.ui.common.SearchableSelectionDialog
import com.manjugroups.m_connect.ui.common.BookingUploadFieldView
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.showOnce
import com.manjugroups.m_connect.ui.hr.CalendarRangePickerSheet
import com.manjugroups.m_connect.util.ongoingOnly
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Full New Booking form — web parity with `booking-new-page.tsx`: client
 * details, booking info, source, financials + charges, customer funding,
 * balance payment schedule, original staff, KYC document uploads, references,
 * finalize. Calculations live in [BookingCalc]; the submit builds the complete
 * [CreateBookingRequest] payload the backend `bookings.create` mutation accepts.
 */
class BookingCreateFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager
    private lateinit var root: View

    private var selectedProject: MarketingProject? = null
    private var selectedUnit: InventoryUnit? = null
    private var selectedLead: TelecallerLeadSearchData? = null
    private var leadMatches: List<TelecallerLeadSearchData> = emptyList()
    private var lastLeadLookupPhone: String? = null

    // Enum selections (value stored; display shown in the row).
    private var bookingType = "NEW"
    private var propertyType = "Plot"
    private var title = "Mr"
    private var nationality = "Indian"
    private var profession = "Business"
    private var department = ""
    private var clientSource = ""
    private var isAgainstSv = "yes"
    private var paymentCategory = "A"
    private var bookingMode = "CASH"
    private var paymentPlan = "Flexi"
    private var promoTnc = "15days"
    private var ref1Relation = ""
    private var ref2Relation = ""
    private var docPreparedIn = "English"

    // Staff selections (id).
    private var avpId: String? = null
    private var gmId: String? = null
    private var seniorManagerId: String? = null
    private var bdoId: String? = null
    private var telecallerId: String? = null
    private var discountApproverId: String? = null
    private var staffCache: List<StaffData> = emptyList()

    // Dates (yyyy-MM-dd).
    private var bookingDate = todayYmd()
    private var dob: String? = null
    private var anniversary: String? = null
    private var advanceInstrumentDate: String? = null
    private var allotmentDate: String? = null
    private var secondDate: String? = null
    private var thirdDate: String? = null
    private var fourthDate: String? = null
    private var preferredRegDate: String? = null

    // Uploaded documents: slot -> (storageId, fileName).
    private val uploads = HashMap<String, Pair<String, String>>()
    private var activeUploadSlot: String? = null

    private var gstPercent: Double? = null

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            val slot = activeUploadSlot ?: return@registerForActivityResult
            if (uri != null) uploadFile(slot, uri)
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_booking_create, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        session = SessionManager(requireContext())

        arguments?.let { args ->
            if (args.containsKey(ARG_PROJECT_ID)) {
                selectedProject = MarketingProject(
                    id = args.getString(ARG_PROJECT_ID).orEmpty(),
                    name = args.getString(ARG_PROJECT_NAME),
                )
            }
            if (args.containsKey(ARG_UNIT_ID)) {
                selectedUnit = InventoryUnit(
                    id = args.getString(ARG_UNIT_ID).orEmpty(),
                    projectId = args.getString(ARG_PROJECT_ID),
                    unitNumber = args.getString(ARG_UNIT_NUMBER),
                    status = "available",
                )
            }
        }

        tv(R.id.tvBookingTitle).text = "New Booking"
        view.findViewById<View>(R.id.btnBookingBack).setOnClickListener { navigateUp() }

        tv(R.id.tvBookingDate).text = bookingDate
        tv(R.id.tvBookingProject).text = selectedProject?.name ?: "Select project"
        tv(R.id.tvBookingUnit).text = selectedUnit?.unitNumber?.let { "Unit $it" } ?: "Select unit"

        wireEnumPickers()
        wirePickers()
        wireDatePickers()
        wireUploads()
        wireLeadLookup()
        wireCalcWatchers()
        applyConditionalVisibility()
        loadStaff()
        if (selectedUnit != null) loadPlotPrefill()

        tv(R.id.btnBookingSaveDraft).setOnClickListener { submit("draft") }
        tv(R.id.btnBookingSubmit).setOnClickListener { submit("confirmed") }

        if (!session.hasPermission("marketing.bookings.create")) {
            toast("You don't have permission to create bookings.")
            tv(R.id.btnBookingSubmit).isEnabled = false
            tv(R.id.btnBookingSaveDraft).isEnabled = false
        }
    }

    // ── Enum pickers (static option lists) ──────────────────────────────────
    private fun wireEnumPickers() {
        // Only NEW is supported here — EXCHANGE / CONVERSION / INTERNAL EXCHANGE
        // need their old-property lookup + balance/credit math + payload, which
        // this standalone form does not implement. Offering them would create
        // bookings with wrong money and missing fields, so they're excluded;
        // those go through the web. (The CP-outcome flow implements them.)
        pick(R.id.tvBookingType, "Booking Type", listOf("NEW")) { bookingType = it }
        pick(R.id.tvBookingPropertyType, "Property Type",
            listOf("Plot", "Apartment", "Villa", "Commercial")) { propertyType = it }
        pick(R.id.tvBookingTitleField, "Title",
            listOf("Mr", "Mrs", "Ms", "Dr", "Prof")) { title = it }
        pick(R.id.tvBookingNationality, "Nationality",
            listOf("Indian", "NRI", "Foreign National")) { nationality = it }
        pick(R.id.tvBookingProfession, "Profession",
            listOf("Business", "Salaried", "Pension")) {
            profession = it; applyConditionalVisibility()
        }
        pick(R.id.tvBookingDepartment, "Department",
            listOf("Admin", "Sales", "HR", "Software Developer", "Other")) {
            department = it; applyConditionalVisibility()
        }
        pick(R.id.tvBookingClientSource, "Client Source",
            listOf("Direct / Walk-in", "Reference", "Channel Partner", "Site Visit",
                "Online / Social Media", "Other")) { clientSource = it }
        pickPairs(R.id.tvBookingIsAgainstSv, "Is Against Site Visit?",
            listOf("Yes" to "yes", "No (Online Sales)" to "no")) {
            isAgainstSv = it; applyConditionalVisibility()
        }
        pickPairs(R.id.tvPaymentCategory, "Customer Payment Category",
            listOf("A - Self Finance / Hand Cash" to "A", "B - Loan Customer" to "B",
                "C - EMI" to "C")) { paymentCategory = it; applyConditionalVisibility(); recompute() }
        pick(R.id.tvBookingMode, "Advance Payment Mode",
            listOf("CASH", "UPI", "NEFT", "RTGS", "CHEQUE", "DD")) {
            bookingMode = it; applyConditionalVisibility()
        }
        pick(R.id.tvPaymentPlan, "Payment Plan",
            listOf("Regular", "Flexi", "Special")) { paymentPlan = it }
        pickPairs(R.id.tvPromoTnc, "Terms & Conditions",
            listOf("Registration within 7 days" to "7days",
                "Registration within 15 days" to "15days",
                "Registration within 30 days" to "30days")) { promoTnc = it }
        val relations = listOf("Father", "Mother", "Spouse", "Brother", "Sister", "Son",
            "Daughter", "Friend", "Colleague", "Neighbour", "Relative", "Other")
        pick(R.id.tvRef1Relation, "Relation", relations) { ref1Relation = it }
        pick(R.id.tvRef2Relation, "Relation", relations) { ref2Relation = it }
        pick(R.id.tvDocPreparedIn, "Document to be Prepared In",
            listOf("English", "Kannada", "Tamil", "Telugu", "Hindi")) { docPreparedIn = it }
    }

    private fun wirePickers() {
        tv(R.id.tvBookingProject).setOnClickListener { pickProject() }
        tv(R.id.tvBookingUnit).setOnClickListener { pickUnit() }
        tv(R.id.tvDiscountApprovedBy).setOnClickListener { pickStaff("Discount Approved By") { s -> discountApproverId = s.id } }
        tv(R.id.tvStaffAvp).setOnClickListener { pickStaff("AVP") { s -> avpId = s.id } }
        tv(R.id.tvStaffGm).setOnClickListener { pickStaff("General Manager") { s -> gmId = s.id } }
        tv(R.id.tvStaffSeniorManager).setOnClickListener { pickStaff("Senior Manager") { s -> seniorManagerId = s.id } }
        tv(R.id.tvStaffBdo).setOnClickListener { pickStaff("BDO") { s -> bdoId = s.id } }
        tv(R.id.tvStaffTelecaller).setOnClickListener { pickStaff("Telecaller") { s -> telecallerId = s.id } }
    }

    private fun wireDatePickers() {
        dateRow(R.id.tvBookingDate) { bookingDate = it; tv(R.id.tvBookingDate).text = it }
        dateRow(R.id.tvBookingDob) { dob = it; tv(R.id.tvBookingDob).text = it }
        dateRow(R.id.tvBookingAnniversary) { anniversary = it; tv(R.id.tvBookingAnniversary).text = it }
        dateRow(R.id.tvAdvanceInstrumentDate) { advanceInstrumentDate = it; tv(R.id.tvAdvanceInstrumentDate).text = it }
        dateRow(R.id.tvAllotmentDate) { allotmentDate = it; tv(R.id.tvAllotmentDate).text = it }
        dateRow(R.id.tvSecondDate) { secondDate = it; tv(R.id.tvSecondDate).text = it }
        dateRow(R.id.tvThirdDate) { thirdDate = it; tv(R.id.tvThirdDate).text = it }
        dateRow(R.id.tvFourthDate) { fourthDate = it; tv(R.id.tvFourthDate).text = it }
        dateRow(R.id.tvPreferredRegDate) { preferredRegDate = it; tv(R.id.tvPreferredRegDate).text = it }
    }

    private fun wireUploads() {
        uploadRow(R.id.tvUploadAadhaarFront, "aadhaarFront")
        uploadRow(R.id.tvUploadAadhaarBack, "aadhaarBack")
        uploadRow(R.id.tvUploadPan, "pan")
        uploadRow(R.id.tvUploadCefFront, "cefFront")
        uploadRow(R.id.tvUploadCefBack, "cefBack")
        uploadRow(R.id.tvUploadPaymentProof, "paymentProof")
    }

    private fun wireCalcWatchers() {
        val ids = listOf(
            R.id.etBookingCost, R.id.etGuidelineValue, R.id.etSpecialConsideration,
            R.id.etRegistrationCharges, R.id.etGstAmount, R.id.etDocumentCharges,
            R.id.etPattaCharges, R.id.etOtherCharges, R.id.etBookingAdvance, R.id.etLoanAmount,
        )
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = recompute()
            override fun afterTextChanged(s: Editable?) = Unit
        }
        ids.forEach { et(it).addTextChangedListener(watcher) }
        cb(R.id.cbGstApplicable).setOnCheckedChangeListener { _, _ -> recompute() }
        cb(R.id.cbOtherApplicable).setOnCheckedChangeListener { _, _ -> recompute() }
        cb(R.id.cbWhatsappSame).setOnCheckedChangeListener { _, on ->
            if (on) et(R.id.etBookingWhatsapp).setText(et(R.id.etBookingMobile).text.toString())
            et(R.id.etBookingWhatsapp).isEnabled = !on
        }
        et(R.id.etSpecialConsideration).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = applyConditionalVisibility()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    // ── Calculations → computed displays ────────────────────────────────────
    private fun recompute() {
        val agreed = BookingCalc.agreedAmount(
            et(R.id.etBookingCost).text.toString(), et(R.id.etSpecialConsideration).text.toString())
        val guideline = BookingCalc.num(et(R.id.etGuidelineValue).text.toString())
        val gstApplicable = cb(R.id.cbGstApplicable).isChecked
        val otherApplicable = cb(R.id.cbOtherApplicable).isChecked
        // Auto-fill GST from percent when blank (user override wins otherwise).
        if (gstApplicable && gstPercent != null && et(R.id.etGstAmount).text.isNullOrBlank()) {
            val auto = BookingCalc.gstAmount(agreed, guideline, gstPercent)
            if (auto > 0) et(R.id.etGstAmount).setText(auto.toLong().toString())
        }
        val gross = BookingCalc.grossTotalPayable(
            agreed,
            BookingCalc.num(et(R.id.etRegistrationCharges).text.toString()),
            gstApplicable, BookingCalc.num(et(R.id.etGstAmount).text.toString()),
            BookingCalc.num(et(R.id.etDocumentCharges).text.toString()),
            BookingCalc.num(et(R.id.etPattaCharges).text.toString()),
            otherApplicable, BookingCalc.num(et(R.id.etOtherCharges).text.toString()),
        )
        val total = BookingCalc.totalPayable(bookingType, gross, gross)
        val loan = BookingCalc.bankLoanAmount(paymentCategory, BookingCalc.num(et(R.id.etLoanAmount).text.toString()))
        val advance = BookingCalc.num(et(R.id.etBookingAdvance).text.toString())
        val chain = BookingCalc.payableChain(total, loan, advance, 0.0)

        tv(R.id.tvAgreedAmount).text = "Agreed Amount: ₹${fmt(agreed ?: 0.0)}"
        tv(R.id.tvBalanceAmount).text = "Balance Amount: ₹${fmt(chain.balanceAmount)}"
        val summary = tv(R.id.tvClientPayableSummary)
        if (paymentCategory == "B") {
            summary.visibility = View.VISIBLE
            summary.text = "Customer Payable: ₹${fmt(chain.customerPayableAmount)}  ·  After advance: ₹${fmt(chain.customerBalanceAfterAdvance)}"
        } else {
            summary.visibility = View.GONE
        }
    }

    private fun applyConditionalVisibility() {
        show(R.id.rowDepartment, profession == "Salaried")
        show(R.id.rowOtherDepartment, profession == "Salaried" && department == "Other")
        show(R.id.rowSvDetails, isAgainstSv == "yes")
        show(R.id.rowSpecialConsideration, BookingCalc.num(et(R.id.etSpecialConsideration).text.toString()) > 0)
        show(R.id.rowBankLoan, paymentCategory == "B")
        val online = bookingMode in setOf("UPI", "NEFT", "RTGS")
        val instrument = bookingMode in setOf("CHEQUE", "DD")
        show(R.id.rowOnlinePayment, online)
        show(R.id.rowInstrumentPayment, instrument)
        if (instrument) tv(R.id.lblInstrumentNo).text = if (bookingMode == "DD") "DD No *" else "Cheque No *"
    }

    // ── Plot pricing prefill ────────────────────────────────────────────────
    private fun loadPlotPrefill() {
        val proj = selectedProject ?: return
        val unit = selectedUnit ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getBookingPlotPrefill(session.bearerToken, proj.id, unit.id)
                if (!resp.success) return@launch
                gstPercent = resp.project?.gstPercent
                resp.fields?.let { f ->
                    fillIfBlank(R.id.etBookingCost, f.bookingCost)
                    fillIfBlank(R.id.etGuidelineValue, f.guidelineValue)
                    fillIfBlank(R.id.etRegistrationCharges, f.registrationCharges)
                    fillIfBlank(R.id.etGstAmount, f.gstAmount)
                    fillIfBlank(R.id.etDocumentCharges, f.documentCharges)
                    fillIfBlank(R.id.etPattaCharges, f.pattaCharges)
                    fillIfBlank(R.id.etOtherCharges, f.otherCharges)
                    fillIfBlank(R.id.etBookingAdvance, f.advanceAmount)
                    fillIfBlank(R.id.etAllotmentAmount, f.allotmentDueAmount)
                }
                resp.project?.let { p ->
                    if (et(R.id.etPromoOffer).text.isNullOrBlank()) et(R.id.etPromoOffer).setText(p.promoOffer.orEmpty())
                    if (et(R.id.etPromoOfferValue).text.isNullOrBlank() && p.projectOfferValue != null)
                        et(R.id.etPromoOfferValue).setText(p.projectOfferValue.toLong().toString())
                }
                toast("Plot pricing filled from project settings")
                recompute()
            } catch (_: Exception) { /* prefill is best-effort */ }
        }
    }

    // ── Submit ──────────────────────────────────────────────────────────────
    private fun submit(status: String) {
        val name = et(R.id.etBookingClientName).text.toString().trim().uppercase()
        val mobile = normalizePhone(et(R.id.etBookingMobile).text.toString())
        // Ordered required-field validation (mirrors the web gate).
        if (name.isEmpty()) return fail("Client name is required")
        if (mobile.length < 10) return fail("Valid 10-digit mobile is required")
        if (et(R.id.etBookingFatherSpouse).text.isNullOrBlank()) return fail("Father / spouse name is required")
        if (dob == null) return fail("Date of birth is required")
        if (normalizePhone(et(R.id.etBookingAlternate).text.toString()).length < 10) return fail("Alternate number must be 10 digits")
        if (normalizePhone(et(R.id.etBookingWhatsapp).text.toString()).length < 10) return fail("WhatsApp number must be 10 digits")
        if (et(R.id.etBookingEmail).text.isNullOrBlank()) return fail("Email is required")
        val proj = selectedProject ?: return fail("Pick a project")
        val unit = selectedUnit
        if (unit != null && unit.status != "available") return fail("Selected unit is not available")
        if (et(R.id.etBookingCefNo).text.isNullOrBlank()) return fail("CEF No is required")
        if (et(R.id.etBookingCost).text.isNullOrBlank()) return fail("Booking cost is required")
        if (et(R.id.etGuidelineValue).text.isNullOrBlank()) return fail("Guideline value is required")
        val sc = BookingCalc.num(et(R.id.etSpecialConsideration).text.toString())
        if (sc > BookingCalc.num(et(R.id.etBookingCost).text.toString())) return fail("Special consideration can't exceed booking cost")
        if (sc > 0 && discountApproverId == null) return fail("Select who approved the discount")
        if (sc > 0 && et(R.id.etScReason).text.isNullOrBlank()) return fail("Special consideration reason is required")
        if (sc > 0 && BookingCalc.num(et(R.id.etScValidity).text.toString()) <= 0) return fail("Special consideration validity (days) is required")
        if (isAgainstSv == "yes" && et(R.id.etBookingSvName).text.isNullOrBlank()) return fail("SV name is required")
        if (isAgainstSv == "yes" && normalizePhone(et(R.id.etBookingSvMobile).text.toString()).length < 10) return fail("SV mobile must be 10 digits")
        et(R.id.etBookingSourceMobile).text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (normalizePhone(it).length < 10) return fail("Source / Reference mobile must be 10 digits")
        }
        if (paymentCategory == "B" && BookingCalc.num(et(R.id.etLoanAmount).text.toString()) <= 0) return fail("Bank loan amount is required")
        if (bookingMode in setOf("UPI", "NEFT", "RTGS") && et(R.id.etAdvanceTxnId).text.isNullOrBlank()) return fail("Transaction ID is required")
        if (bookingMode in setOf("CHEQUE", "DD") && et(R.id.etAdvanceInstrumentNo).text.isNullOrBlank()) return fail("Instrument number is required")
        if (avpId == null || gmId == null || seniorManagerId == null || bdoId == null || telecallerId == null)
            return fail("All original staff (AVP/GM/Sr.Manager/BDO/Telecaller) are required")
        val aadhaar = et(R.id.etAadhaar).text.toString().filter { it.isDigit() }
        if (aadhaar.length != 12) return fail("Aadhaar must be 12 digits")
        if (uploads["aadhaarFront"] == null) return fail("Upload Aadhaar Front")
        if (uploads["aadhaarBack"] == null) return fail("Upload Aadhaar Back")
        val pan = et(R.id.etPan).text.toString().trim().uppercase()
        if (pan.length != 10) return fail("PAN must be 10 characters")
        if (uploads["pan"] == null) return fail("Upload PAN")
        if (uploads["cefFront"] == null) return fail("Upload CEF Front")
        if (uploads["cefBack"] == null) return fail("Upload CEF Back")
        if (et(R.id.etRef1Name).text.isNullOrBlank() || ref1Relation.isEmpty() ||
            normalizePhone(et(R.id.etRef1Mobile).text.toString()).length < 10)
            return fail("Reference 1 (name, relation, 10-digit mobile) is required")
        if (et(R.id.etRef2Name).text.isNullOrBlank() || ref2Relation.isEmpty() ||
            normalizePhone(et(R.id.etRef2Mobile).text.toString()).length < 10)
            return fail("Reference 2 (name, relation, 10-digit mobile) is required")

        // Derived money.
        val agreed = BookingCalc.agreedAmount(et(R.id.etBookingCost).text.toString(), et(R.id.etSpecialConsideration).text.toString())
        val gstApplicable = cb(R.id.cbGstApplicable).isChecked
        val otherApplicable = cb(R.id.cbOtherApplicable).isChecked
        val gross = BookingCalc.grossTotalPayable(
            agreed, BookingCalc.num(et(R.id.etRegistrationCharges).text.toString()),
            gstApplicable, BookingCalc.num(et(R.id.etGstAmount).text.toString()),
            BookingCalc.num(et(R.id.etDocumentCharges).text.toString()),
            BookingCalc.num(et(R.id.etPattaCharges).text.toString()),
            otherApplicable, BookingCalc.num(et(R.id.etOtherCharges).text.toString()))
        val total = BookingCalc.totalPayable(bookingType, gross, gross)
        val advance = BookingCalc.num(et(R.id.etBookingAdvance).text.toString())
        val loan = BookingCalc.bankLoanAmount(paymentCategory, BookingCalc.num(et(R.id.etLoanAmount).text.toString()))
        val minAdvance = proj.minimumAdvanceAmount ?: 0.0
        if (minAdvance > 0 && advance < minAdvance) {
            return fail("Advance must be at least ₹${minAdvance.toLong()} as set in Project Details")
        }
        if (advance > total) return fail("Advance cannot exceed the total payable amount")
        if (loan > total) return fail("Bank Loan Amount cannot exceed the Total Property Cost")
        val payable = BookingCalc.payableChain(total, loan, advance, 0.0)
        if (advance > payable.customerPayableAmount) {
            return fail("Advance cannot exceed the Customer Payable Amount after excluding the bank loan")
        }
        val balance = payable.balanceAmount
        val allotment = BookingCalc.num(et(R.id.etAllotmentAmount).text.toString())
        if (allotment > payable.customerBalanceAfterAdvance) {
            return fail("Allotment payment cannot exceed the remaining Customer Payable Amount")
        }
        val scheduled = listOf(R.id.etSecondAmount, R.id.etThirdAmount, R.id.etFourthAmount)
            .sumOf { BookingCalc.num(et(it).text.toString()) }
        val outstandingAfterAllotment = BookingCalc.outstandingAfterAllotment(
            payable.customerPayableAmount,
            advance,
            0.0,
            allotment,
        )
        if (paymentPlan != "Flexi" && scheduled > outstandingAfterAllotment) {
            return fail("Payment schedule cannot exceed the remaining Customer Payable Amount")
        }
        // Confirmed self-cash (category A): the schedule must total EXACTLY the
        // remaining outstanding (web parity — draft may be partial).
        if (paymentCategory == "A" && status == "confirmed" &&
            kotlin.math.abs(scheduled - outstandingAfterAllotment) > 0.01
        ) {
            return fail("Payment schedule must total the remaining Customer Payable Amount")
        }

        val req = CreateBookingRequest(
            clientName = name, mobileNumber = mobile, bookingDate = bookingDate,
            leadId = selectedLead?.id,
            title = title, fatherSpouseName = et(R.id.etBookingFatherSpouse).text.toString().trim().uppercase(),
            dateOfBirth = dob, anniversaryDate = anniversary,
            alternateNumbers = normalizePhone(et(R.id.etBookingAlternate).text.toString()),
            whatsappNumber = normalizePhone(et(R.id.etBookingWhatsapp).text.toString()),
            email = et(R.id.etBookingEmail).text.toString().trim(),
            nationality = nationality,
            pincode = et(R.id.etHomePincode).text.toString().trim(),
            homeAddress = composeAddress(R.id.etHomeDoorNo, R.id.etHomeStreet, R.id.etHomeLine1, R.id.etHomeLine2),
            district = et(R.id.etHomeDistrict).text.toString().trim().uppercase(),
            profession = profession, designation = et(R.id.etBookingDesignation).text.toString().trim().uppercase(),
            department = if (profession == "Salaried") (if (department == "Other") et(R.id.etBookingOtherDepartment).text.toString().trim().uppercase() else department) else null,
            incomePerAnnum = et(R.id.etBookingIncome).text.toString().trim(),
            officeName = et(R.id.etOfficeName).text.toString().trim().uppercase(),
            officeAddress = composeAddress(R.id.etOfficeDoorNo, R.id.etOfficeStreet, R.id.etOfficeLine1, R.id.etOfficeLine2),
            officeArea = et(R.id.etOfficeArea).text.toString().trim().uppercase(),
            officePincode = et(R.id.etOfficePincode).text.toString().trim(),
            officeMobile = et(R.id.etOfficeMobile).text.toString().trim().ifBlank { null },
            officeEmail = et(R.id.etOfficeEmail).text.toString().trim().ifBlank { null },
            bookingType = bookingType, cefNo = et(R.id.etBookingCefNo).text.toString().trim(),
            projectId = proj.id, plotId = unit?.id, plotNo = unit?.unitNumber,
            propertyType = propertyType,
            isAgainstSV = isAgainstSv == "yes",
            svName = if (isAgainstSv == "yes") et(R.id.etBookingSvName).text.toString().trim().uppercase() else null,
            svMobileNo = if (isAgainstSv == "yes") et(R.id.etBookingSvMobile).text.toString().trim() else null,
            clientSource = clientSource.ifBlank { null },
            clientSourceName = et(R.id.etBookingSourceName).text.toString().trim().ifBlank { null },
            clientSourceMobile = et(R.id.etBookingSourceMobile).text.toString().trim().ifBlank { null },
            referralBenefit = et(R.id.etBookingReferralBenefit).text.toString().trim().ifBlank { null },
            bookingCost = BookingCalc.num(et(R.id.etBookingCost).text.toString()),
            guidelineValue = BookingCalc.num(et(R.id.etGuidelineValue).text.toString()),
            specialConsideration = if (sc > 0) sc else null,
            specialConsiderationReason = if (sc > 0) et(R.id.etScReason).text.toString().trim().uppercase() else null,
            discountApprovedBy = if (sc > 0) discountApproverId else null,
            specialConsiderationValidity = if (sc > 0) BookingCalc.num(et(R.id.etScValidity).text.toString()) else null,
            promotionalOffers = et(R.id.etPromoOffer).text.toString().trim().ifBlank { null },
            promotionalOfferValue = BookingCalc.num(et(R.id.etPromoOfferValue).text.toString()),
            promotionalOffersTnC = promoTnc,
            offerValidityPeriod = promoTnc.filter { it.isDigit() }.toDoubleOrNull(),
            agreedAmount = agreed,
            registrationCharges = BookingCalc.num(et(R.id.etRegistrationCharges).text.toString()),
            gstApplicable = gstApplicable, gstAmount = BookingCalc.num(et(R.id.etGstAmount).text.toString()),
            documentCharges = BookingCalc.num(et(R.id.etDocumentCharges).text.toString()),
            pattaCharges = BookingCalc.num(et(R.id.etPattaCharges).text.toString()),
            otherChargesApplicable = otherApplicable, otherCharges = BookingCalc.num(et(R.id.etOtherCharges).text.toString()),
            advanceAmount = advance, balanceAmount = balance,
            bookingMode = bookingMode, paymentMode = bookingMode,
            advanceTransactionId = et(R.id.etAdvanceTxnId).text.toString().trim().ifBlank { null },
            advancePaymentProofStorageId = uploads["paymentProof"]?.first,
            advancePaymentProofFileName = uploads["paymentProof"]?.second,
            advanceInstrumentNo = et(R.id.etAdvanceInstrumentNo).text.toString().trim().ifBlank { null },
            advanceBankName = et(R.id.etAdvanceBank).text.toString().trim().ifBlank { null },
            advanceBankBranch = et(R.id.etAdvanceBranch).text.toString().trim().ifBlank { null },
            advanceInstrumentDate = advanceInstrumentDate,
            customerPaymentCategory = paymentCategory,
            loanAmountRequested = if (paymentCategory == "B") loan else null,
            paymentPlan = paymentPlan,
            // Flexi = free-payment schedule (web parity); the backend needs this
            // flag to recognise the dynamic schedule. Was previously omitted.
            freePayment = paymentPlan == "Flexi",
            sourceType = "walk_in",
            allotmentDueAmount = allotment,
            allotmentDueDate = allotmentDate,
            secondPaymentAmount = et(R.id.etSecondAmount).text.toString().toDoubleOrNull(),
            secondPaymentDate = secondDate,
            thirdPaymentAmount = et(R.id.etThirdAmount).text.toString().toDoubleOrNull(),
            thirdPaymentDate = thirdDate,
            fourthPaymentAmount = et(R.id.etFourthAmount).text.toString().toDoubleOrNull(),
            fourthPaymentDate = fourthDate,
            preferredRegistrationDate = preferredRegDate,
            originalAvpStaffId = avpId, originalGmStaffId = gmId,
            originalSeniorManagerStaffId = seniorManagerId, originalBdoStaffId = bdoId,
            originalTelecallerStaffId = telecallerId,
            aadhaar = aadhaar,
            aadhaarDocumentStorageId = uploads["aadhaarFront"]?.first,
            aadhaarDocumentFileName = uploads["aadhaarFront"]?.second,
            aadhaarBackDocumentStorageId = uploads["aadhaarBack"]?.first,
            aadhaarBackDocumentFileName = uploads["aadhaarBack"]?.second,
            pan = pan,
            panDocumentStorageId = uploads["pan"]?.first, panDocumentFileName = uploads["pan"]?.second,
            cefFormFrontDocumentStorageId = uploads["cefFront"]?.first,
            cefFormFrontDocumentFileName = uploads["cefFront"]?.second,
            cefFormBackDocumentStorageId = uploads["cefBack"]?.first,
            cefFormBackDocumentFileName = uploads["cefBack"]?.second,
            referenceName1 = et(R.id.etRef1Name).text.toString().trim().uppercase(),
            referenceProfession1 = ref1Relation, referenceMobile1 = normalizePhone(et(R.id.etRef1Mobile).text.toString()),
            referenceName2 = et(R.id.etRef2Name).text.toString().trim().uppercase(),
            referenceProfession2 = ref2Relation, referenceMobile2 = normalizePhone(et(R.id.etRef2Mobile).text.toString()),
            docPreparedIn = docPreparedIn,
            status = status,
            flexiPaymentSchedule = buildFlexiSchedule(),
        )

        tv(R.id.btnBookingSubmit).isEnabled = false
        tv(R.id.btnBookingSaveDraft).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.createBooking(session.bearerToken, req)
                tv(R.id.btnBookingSubmit).isEnabled = true
                tv(R.id.btnBookingSaveDraft).isEnabled = true
                if (!resp.success) { toast(resp.error ?: "Failed to create booking"); return@launch }
                toast(if (status == "draft") "Booking draft saved" else "Booking saved — sent for approval")
                navigateUp()
            } catch (e: Exception) {
                tv(R.id.btnBookingSubmit).isEnabled = true
                tv(R.id.btnBookingSaveDraft).isEnabled = true
                toast("Network error: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun buildFlexiSchedule(): List<FlexiPaymentRow>? {
        // Standard 2nd/3rd/4th rows → schedule; only sent when amounts+dates present.
        val rows = mutableListOf<FlexiPaymentRow>()
        et(R.id.etSecondAmount).text.toString().toDoubleOrNull()?.let { a ->
            secondDate?.let { d -> rows.add(FlexiPaymentRow(a, d)) } }
        et(R.id.etThirdAmount).text.toString().toDoubleOrNull()?.let { a ->
            thirdDate?.let { d -> rows.add(FlexiPaymentRow(a, d)) } }
        et(R.id.etFourthAmount).text.toString().toDoubleOrNull()?.let { a ->
            fourthDate?.let { d -> rows.add(FlexiPaymentRow(a, d)) } }
        return rows.ifEmpty { null }
    }

    // ── Uploads ─────────────────────────────────────────────────────────────
    private fun uploadRow(id: Int, slot: String) {
        uploadField(id).setOnUploadClickListener {
            activeUploadSlot = slot
            filePicker.launch("*/*")
        }
    }

    private fun uploadFile(slot: String, uri: Uri) {
        val ctx = context ?: return
        val fileName = queryFileName(uri) ?: "$slot.jpg"
        uploadRowView(slot)?.setUploading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch toast("Could not read file")
                val type = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
                val body = bytes.toRequestBody(type.toMediaTypeOrNull())
                val resp = api.uploadStorageFile(session.bearerToken, body)
                if (!resp.success || resp.storageId == null) {
                    uploadRowView(slot)?.setUploading(false)
                    toast(resp.error ?: "Upload failed"); return@launch
                }
                uploads[slot] = resp.storageId!! to fileName
                uploadRowView(slot)?.setUploadedFileName(fileName)
            } catch (e: Exception) {
                uploadRowView(slot)?.setUploading(false)
                toast("Upload failed: ${e.message ?: "unknown"}")
            }
        }
    }

    private fun uploadRowView(slot: String): BookingUploadFieldView? = when (slot) {
        "aadhaarFront" -> uploadField(R.id.tvUploadAadhaarFront)
        "aadhaarBack" -> uploadField(R.id.tvUploadAadhaarBack)
        "pan" -> uploadField(R.id.tvUploadPan)
        "cefFront" -> uploadField(R.id.tvUploadCefFront)
        "cefBack" -> uploadField(R.id.tvUploadCefBack)
        "paymentProof" -> uploadField(R.id.tvUploadPaymentProof)
        else -> null
    }

    private fun uploadField(id: Int): BookingUploadFieldView = root.findViewById(id)

    private fun queryFileName(uri: Uri): String? {
        return try {
            context?.contentResolver?.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) { null }
    }

    // ── Lead lookup ─────────────────────────────────────────────────────────
    private fun wireLeadLookup() {
        val leadRow = tv(R.id.tvBookingLinkedLead)
        leadRow.setOnClickListener {
            if (leadMatches.size > 1) showLeadPicker() else selectedLead?.let { applyLead(it) }
        }
        et(R.id.etBookingMobile).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (cb(R.id.cbWhatsappSame).isChecked) et(R.id.etBookingWhatsapp).setText(s?.toString().orEmpty())
                val phone = normalizePhone(s?.toString().orEmpty())
                if (phone.length < 10) { leadRow.visibility = View.GONE; lastLeadLookupPhone = null; return }
                if (phone == lastLeadLookupPhone) return
                lastLeadLookupPhone = phone
                searchLead(phone)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun searchLead(phone: String) {
        val leadRow = tv(R.id.tvBookingLinkedLead)
        leadRow.visibility = View.VISIBLE
        leadRow.text = "Searching lead…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.searchTelecallerLeadsByPhone(session.bearerToken, phone)
                if (normalizePhone(et(R.id.etBookingMobile).text.toString()) != phone) return@launch
                if (!resp.success) { leadRow.text = resp.error ?: "Lead search failed"; return@launch }
                leadMatches = resp.leads
                if (leadMatches.isEmpty()) { leadRow.text = "No linked lead found"; return@launch }
                applyLead(leadMatches.first())
                if (leadMatches.size > 1) leadRow.text = "${leadRow.text} · tap to change"
            } catch (_: Exception) { leadRow.text = "Lead search failed" }
        }
    }

    private fun showLeadPicker() {
        val labels = leadMatches.map { l ->
            listOf(l.displayName(), l.mobileNumber ?: "").filter { it.isNotBlank() }.joinToString(" · ")
        }.toTypedArray()
        AlertDialog.Builder(requireContext()).setTitle("Select linked lead")
            .setItems(labels) { _, i -> applyLead(leadMatches[i]) }.show()
    }

    private fun applyLead(lead: TelecallerLeadSearchData) {
        selectedLead = lead
        val nameInput = et(R.id.etBookingClientName)
        val n = lead.displayName()
        if (n.isNotBlank() && nameInput.text.toString().isBlank()) nameInput.setText(n.uppercase())
        tv(R.id.tvBookingLinkedLead).apply {
            visibility = View.VISIBLE
            text = "Linked lead: ${n.ifBlank { lead.mobileNumber ?: lead.id }}"
        }
    }

    // ── Pickers ─────────────────────────────────────────────────────────────
    private fun pickProject() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getMarketingProjects(session.bearerToken)
                if (!resp.success) { toast(resp.error ?: "Failed to load projects"); return@launch }
                val projects = resp.projects.ongoingOnly()
                if (projects.isEmpty()) { toast("No ongoing projects available"); return@launch }
                if (view == null) return@launch
                val options = projects.map { SearchableOption(it, it.name ?: "Unnamed", it.status) }
                SearchableSelectionDialog.show(requireContext(), "Select project", options) { p ->
                    selectedProject = p; selectedUnit = null
                    tv(R.id.tvBookingProject).text = p.name ?: "Select project"
                    tv(R.id.tvBookingUnit).text = "Select unit"
                }
            } catch (e: Exception) { toast("Network error: ${e.message ?: "unknown"}") }
        }
    }

    private fun pickUnit() {
        val proj = selectedProject ?: return toast("Pick a project first")
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.listInventoryUnits(session.bearerToken, proj.id, "available")
                if (!resp.success) { toast(resp.error ?: "Failed to load units"); return@launch }
                val available = resp.units.filter { it.status == "available" }
                if (available.isEmpty()) { toast("No available units"); return@launch }
                val ctx = context ?: return@launch
                val options = available.map { u ->
                    val sub = listOfNotNull(u.unitType, u.area?.takeIf { it.isFinite() }?.let { "${it.toInt()} sqft" }).joinToString(" · ")
                    SearchableOption(u, u.unitNumber ?: u.id, sub)
                }
                SearchableSelectionDialog.show(ctx, "Select available unit", options) { picked ->
                    if (picked.status != "available") { toast("Unit is no longer available"); return@show }
                    selectedUnit = picked
                    tv(R.id.tvBookingUnit).text = "Unit ${picked.unitNumber ?: picked.id}"
                    loadPlotPrefill()
                }
            } catch (e: Exception) { toast("Network error: ${e.message ?: "unknown"}") }
        }
    }

    private fun loadStaff() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.getStaff(session.bearerToken, "active")
                if (resp.success) staffCache = resp.staff
            } catch (_: Exception) { /* pickers will show empty */ }
        }
    }

    private fun pickStaff(title: String, onPick: (StaffData) -> Unit) {
        if (staffCache.isEmpty()) { toast("Loading staff…"); loadStaff(); return }
        val ctx = context ?: return
        val options = staffCache.map { s ->
            SearchableOption(s, s.name ?: s.id.orEmpty(),
                listOfNotNull(s.designation, s.department).joinToString(" · "))
        }
        SearchableSelectionDialog.show(ctx, "Select $title", options) { s ->
            onPick(s)
            // reflect into the tapped row
            val label = s.name ?: s.id.orEmpty()
            when (title) {
                "AVP" -> tv(R.id.tvStaffAvp).text = label
                "General Manager" -> tv(R.id.tvStaffGm).text = label
                "Senior Manager" -> tv(R.id.tvStaffSeniorManager).text = label
                "BDO" -> tv(R.id.tvStaffBdo).text = label
                "Telecaller" -> tv(R.id.tvStaffTelecaller).text = label
                "Discount Approved By" -> tv(R.id.tvDiscountApprovedBy).text = label
            }
        }
    }

    // ── Small helpers ───────────────────────────────────────────────────────
    private fun tv(id: Int): TextView = root.findViewById(id)
    private fun et(id: Int): EditText = root.findViewById(id)
    private fun cb(id: Int): CheckBox = root.findViewById(id)
    private fun show(id: Int, visible: Boolean) { root.findViewById<View>(id).visibility = if (visible) View.VISIBLE else View.GONE }

    private fun fillIfBlank(id: Int, value: Double?) {
        if (value != null && et(id).text.isNullOrBlank()) et(id).setText(value.toLong().toString())
    }

    private fun composeAddress(door: Int, street: Int, line1: Int, line2: Int): String? {
        val parts = listOfNotNull(
            et(door).text.toString().trim().takeIf { it.isNotBlank() }?.let { "Door No: $it" },
            et(street).text.toString().trim().takeIf { it.isNotBlank() }?.let { "Street Name: $it" },
            et(line1).text.toString().trim().takeIf { it.isNotBlank() }?.let { "Address Line 1: $it" },
            et(line2).text.toString().trim().takeIf { it.isNotBlank() }?.let { "Address Line 2: $it" },
        )
        return parts.joinToString(", ").ifBlank { null }?.uppercase()
    }

    private fun pick(id: Int, title: String, options: List<String>, onPick: (String) -> Unit) {
        tv(id).setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle(title)
                .setItems(options.toTypedArray()) { _, i ->
                    onPick(options[i]); tv(id).text = options[i]
                }.show()
        }
    }

    private fun pickPairs(id: Int, title: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
        tv(id).setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle(title)
                .setItems(options.map { it.first }.toTypedArray()) { _, i ->
                    onPick(options[i].second); tv(id).text = options[i].first
                }.show()
        }
    }

    private fun dateRow(id: Int, onPick: (String) -> Unit) {
        tv(id).setOnClickListener {
            val key = "booking_date_$id"
            setFragmentResultListener(key) { _, bundle ->
                bundle.getString(CalendarRangePickerSheet.KEY_FROM)?.let(onPick)
            }
            CalendarRangePickerSheet.newInstance(
                title = "Select Date", subtitle = "Pick a date",
                initialFrom = null, initialTo = null, resultKey = key,
            ).showOnce(parentFragmentManager, "booking_date_picker_$id")
        }
    }

    private fun fail(message: String) { toast(message) }
    private fun toast(message: String) { Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show() }
    private fun normalizePhone(v: String): String = v.filter { it.isDigit() }.takeLast(10)
    private fun fmt(v: Double): String = String.format(Locale("en", "IN"), "%,d", v.toLong())
    private fun todayYmd(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

    private fun TelecallerLeadSearchData.displayName(): String =
        latestAnalysisProfile?.clientName?.trim().takeUnless { it.isNullOrBlank() }
            ?: contactName?.trim().takeUnless { it.isNullOrBlank() }
            ?: mobileNumber?.trim().orEmpty()

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTopBarAppearance(Color.parseColor("#FEFEFE"), true)
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    companion object {
        private const val ARG_PROJECT_ID = "projectId"
        private const val ARG_PROJECT_NAME = "projectName"
        private const val ARG_UNIT_ID = "unitId"
        private const val ARG_UNIT_NUMBER = "unitNumber"

        fun newEmpty(): BookingCreateFragment = BookingCreateFragment()

        fun forUnit(projectId: String, projectName: String, unitId: String, unitNumber: String?): BookingCreateFragment =
            BookingCreateFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROJECT_ID, projectId)
                    putString(ARG_PROJECT_NAME, projectName)
                    putString(ARG_UNIT_ID, unitId)
                    putString(ARG_UNIT_NUMBER, unitNumber)
                }
            }
    }
}
