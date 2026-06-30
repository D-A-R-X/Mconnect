package com.manjugroups.m_connect.ui.library.loans

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.manjugroups.m_connect.network.WorkflowStepData
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.FragmentLoansBinding
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.ui.common.navigateUp
import com.manjugroups.m_connect.ui.common.applySmoothTransitions
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoansFragment : Fragment() {

    private var _binding: FragmentLoansBinding? = null
    private val binding get() = _binding!!

    private val api = ApiService.create()
    
    // Raw lists loaded from API
    private val allActive = mutableListOf<Loan>()
    private val allPrevious = mutableListOf<Loan>()
    // Raw pending-approvals list (loans + advances both, exactly as
    // returned by the backend). The visible adapter gets a per-tab
    // slice in render(); this is the source of truth.
    private val allPending = mutableListOf<com.manjugroups.m_connect.network.LoanData>()

    // Filtered lists for the active tab
    private val active = mutableListOf<Loan>()
    private val previous = mutableListOf<Loan>()

    private var loaded = false

    private val TAB_LOANS = 0
    private val TAB_SALARY = 1
    private var selectedTab = TAB_LOANS

    private lateinit var adapter: LoansAdapter
    private lateinit var requestedLoansAdapter: RequestedLoansAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoansBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applySystemInsets()

        adapter = LoansAdapter(onPreviousClick = { loan -> openRepaymentHistory(loan) })
        binding.rvLoans.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLoans.adapter = adapter
        binding.rvLoans.itemAnimator = null

        requestedLoansAdapter = RequestedLoansAdapter(
            onAcceptClick = { loan ->
                val isAdvance = loan.requestType?.lowercase()?.trim() == "salary_advance"
                when {
                    // Salary advances skip the popup — Accept directly forwards
                    // (HR -> Accounts) or finalises if Accounts is the actor.
                    isAdvance -> approveLoanInline(loan)
                    // Loans on nominee_pending need an e-signature, so open
                    // that pad. Other loan stages still go through the
                    // approval sheet for the tracker + nominee context.
                    loan.currentStage?.lowercase()?.trim() == "nominee_pending" ->
                        AcceptLoanBottomSheet(loan) { loadPendingApprovals() }
                            .show(childFragmentManager, "AcceptLoanBottomSheet")
                    else ->
                        GmApprovalBottomSheet(
                            loan = loan,
                            onAccepted = { loadPendingApprovals() },
                            onRejected = { loadPendingApprovals() },
                        ).show(childFragmentManager, "GmApprovalBottomSheet")
                }
            },
            onRejectClick = { loan ->
                rejectLoanRequest(loan)
            }
        )
        binding.rvRequestedLoans.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequestedLoans.adapter = requestedLoansAdapter
        binding.rvRequestedLoans.itemAnimator = null

        // Role dropdown removed. The backend's pending-approvals endpoint
        // returns exactly the loans THIS user can act on (resolved by their
        // role + each loan's stage), so the screen always shows My Loans
        // plus a Pending Approvals section that only appears when the user
        // actually has something to approve.
        binding.btnUserDropdown.visibility = View.GONE
        binding.layoutPreviousLoans.visibility = View.VISIBLE

        binding.btnLoansBack.setOnClickListener { navigateUp() }
        binding.btnLoansEmptyBack.setOnClickListener { navigateUp() }
        binding.tvPreviousLoansViewAll.setOnClickListener {
            binding.rvLoans.smoothScrollToPosition(0)
        }

        binding.tabLoans.setOnClickListener {
            if (selectedTab != TAB_LOANS) {
                selectedTab = TAB_LOANS
                updateTabSelection()
                render()
            }
        }
        binding.tabSalary.setOnClickListener {
            if (selectedTab != TAB_SALARY) {
                selectedTab = TAB_SALARY
                updateTabSelection()
                render()
            }
        }
        binding.btnCreateLoanOrAdvance.setOnClickListener {
            if (selectedTab == TAB_LOANS) {
                openCreateLoanSheet()
            } else {
                openCreateSalaryAdvanceSheet()
            }
        }

        // Listen for creation results to reload
        parentFragmentManager.setFragmentResultListener(
            CreateLoanBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            loadFromApi()
        }
        parentFragmentManager.setFragmentResultListener(
            CreateSalaryAdvanceBottomSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            loadFromApi()
        }

        loadFromApi()
        loadPendingApprovals()
    }

    private fun applySystemInsets() {
        val headerBasePadding = binding.loansHeader.paddingTop
        val emptyHeaderBasePadding = binding.loansEmptyHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.loansHeader.updatePadding(top = headerBasePadding + sys.top)
            binding.loansEmptyHeader.updatePadding(top = emptyHeaderBasePadding + sys.top)
            binding.loansContent.updatePadding(
                bottom = sys.bottom + (16f * resources.displayMetrics.density).toInt()
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun loadFromApi() {
        val session = SessionManager(requireContext())
        val token = session.bearerToken
        val staffId = session.staffId?.takeIf { it.isNotBlank() }
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.getMyLoans(token, staffId = staffId) }
                .onSuccess { response ->
                    if (_binding == null) return@onSuccess
                    allActive.clear()
                    allPrevious.clear()
                    allActive.addAll(LoanMapper.mapLoanList(response.pending, LoanStatus.PENDING))
                    allActive.addAll(LoanMapper.mapLoanList(response.active, LoanStatus.ACTIVE))
                    allPrevious.addAll(LoanMapper.mapLoanList(response.previous, LoanStatus.REPAID))
                    loaded = true
                    updateTabSelection()
                    render()
                }
                .onFailure { err ->
                    if (_binding == null) return@onFailure
                    loaded = true
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Couldn't load loans (staffId=${staffId ?: "null"}): " +
                            "${err.message ?: "network error"}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    updateTabSelection()
                    render()
                }
        }
    }

    private fun updateTabSelection() {
        if (_binding == null) return
        if (selectedTab == TAB_LOANS) {
            binding.tabLoans.setBackgroundResource(R.drawable.bg_loans_segment_active)
            binding.tabLoans.setTextColor(Color.WHITE)
            binding.tabLoans.setTypeface(null, Typeface.BOLD)

            binding.tabSalary.setBackgroundResource(0)
            binding.tabSalary.setTextColor(Color.parseColor("#475467"))
            binding.tabSalary.setTypeface(null, Typeface.NORMAL)

            binding.tvLoansTitle.text = "My Loans"
            binding.tvLoansSubtitle.text = "Manage Loans and Salary Advance"
            binding.tvHeroOutstandingLabel.text = "Outstanding Amount"
            binding.tvHeroNextEmiLabel.text = "Next EMI Due"
            binding.tvPreviousLoansLabel.text = "Previous Loans"
        } else {
            binding.tabSalary.setBackgroundResource(R.drawable.bg_loans_segment_active)
            binding.tabSalary.setTextColor(Color.WHITE)
            binding.tabSalary.setTypeface(null, Typeface.BOLD)

            binding.tabLoans.setBackgroundResource(0)
            binding.tabLoans.setTextColor(Color.parseColor("#475467"))
            binding.tabLoans.setTypeface(null, Typeface.NORMAL)

            binding.tvLoansTitle.text = "My Advances"
            binding.tvLoansSubtitle.text = "Manage Loans and Salary Advance"
            binding.tvHeroOutstandingLabel.text = "Available Salary"
            binding.tvHeroNextEmiLabel.text = "Next Due Date"
            binding.tvPreviousLoansLabel.text = "Previous Advances"
        }
        // Rename the Requested Loans section so a salary-advance request
        // doesn't show under a "Requested Loans" header on the Advance
        // tab — matches the iOS UX and the rest of this screen's labels.
        binding.tvRequestedLoansTitle.text =
            if (selectedTab == TAB_LOANS) "Requested Loans" else "Requested Advances"
    }

    private fun render() {
        if (!loaded) return

        active.clear()
        previous.clear()

        // Real data only — the previous "if empty, seed dummy Home Loan
        // LN00123 / Medical Expenses LN00115 rows" fallback was removed.
        // Those values looked like real disbursements on screen and were
        // making employees think they owed money they'd never borrowed.
        // When both buckets are empty the proper "No Loans Yet" /
        // "No Advances Yet" empty state below takes over instead.
        if (selectedTab == TAB_LOANS) {
            active.addAll(allActive.filter { !it.isAdvance })
            previous.addAll(allPrevious.filter { !it.isAdvance })
        } else {
            active.addAll(allActive.filter { it.isAdvance })
            previous.addAll(allPrevious.filter { it.isAdvance })
        }

        // Keep loansContent always visible — the blue header, tabs,
        // and create-loan/advance + button live inside it, and the
        // user needs all three to be reachable even when they have
        // zero loans yet (they need a path to APPLY for their first
        // loan or advance). The legacy `loansEmptyState` container
        // hid everything; we now use an `inlineLoansEmptyState`
        // EmptyStateView that sits below the tabs+button row instead.
        binding.loansEmptyState.visibility = View.GONE
        binding.loansContent.visibility = View.VISIBLE

        // Re-slice pending approvals against the new tab so a loan
        // request never lingers on the Advance tab (and vice versa).
        renderPendingApprovals()

        // Empty state shows ONLY when there's truly nothing on this
        // tab — neither user-owned loans NOR a pending-approval card
        // belonging to this tab. Showing the empty graphic below an
        // already-visible request card was misleading.
        val pendingOnThisTab = allPending.any {
            val isAdv = it.requestType?.lowercase()?.trim() == "salary_advance"
            if (selectedTab == TAB_LOANS) !isAdv else isAdv
        }
        val bothEmpty = active.isEmpty() && previous.isEmpty() && !pendingOnThisTab
        if (bothEmpty) {
            binding.inlineLoansEmptyState.visibility = View.VISIBLE
            binding.inlineLoansEmptyState.setTitle(
                if (selectedTab == TAB_LOANS) "No Loans Yet" else "No Advances Yet"
            )
            // Subtitle copy mirrors the iOS empty-state design the user
            // pinned as canonical — same wording on both tabs so the
            // visual feels consistent when the staff toggles between
            // Loans and Advance without records on either side.
            binding.inlineLoansEmptyState.setDescription(
                "Stay organized by creating or joining teams. " +
                    "Groups help you manage tasks, track progress, " +
                    "and collaborate with your team in one place.",
            )
            binding.heroActiveCard.visibility = View.GONE
            binding.heroActiveCard.setOnClickListener(null)
            binding.previousLoansHeaderRow.visibility = View.GONE
            binding.rvLoans.visibility = View.GONE
            adapter.submit(emptyList())
            playEmptyStateEntryAnim()
            return
        }
        binding.inlineLoansEmptyState.visibility = View.GONE
        binding.rvLoans.visibility = View.VISIBLE

        val hero = active.firstOrNull()
        if (hero != null) {
            bindHeroCard(hero)
            binding.heroActiveCard.visibility = View.VISIBLE
            binding.heroActiveCard.setOnClickListener(null)
        } else {
            binding.heroActiveCard.visibility = View.GONE
            binding.heroActiveCard.setOnClickListener(null)
        }

        binding.previousLoansHeaderRow.visibility =
            if (previous.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(previous)

        playContentEntryAnim(hero)
    }

    private fun bindHeroCard(loan: Loan) {
        binding.tvHeroLoanTitle.text = loan.title
        binding.tvHeroLoanId.text = loan.loanId.ifBlank { "—" }

        binding.ivHeroLoanIcon.imageTintList = null
        binding.ivHeroLoanIcon.setImageResource(R.drawable.ic_vuesax_linear_coin)
        binding.heroIconTile.setBackgroundResource(R.drawable.bg_loan_icon_circle_blue)

        when (loan.status) {
            LoanStatus.PENDING -> {
                binding.tvHeroBadge.text = if (loan.isAdvance) "Pending Advance" else "Pending"
                binding.tvHeroBadge.setBackgroundResource(R.drawable.bg_loan_status_pending)
                binding.tvHeroBadge.setTextColor(Color.parseColor("#F79009"))
                binding.heroActiveDetails.visibility = View.GONE
                binding.heroPendingTracker.visibility = View.VISIBLE
                // Fixed nominee/GM/AVP/HR tracker renders immediately as the
                // default; if this row is workflow-routed, loadWorkflowTracker
                // swaps in the configured chain once the steps come back.
                updateTrackerState(loan)
                loadWorkflowTracker(loan)
            }
            else -> {
                binding.tvHeroBadge.text = if (loan.isAdvance) "Active Advance" else "Active Loan"
                binding.tvHeroBadge.setBackgroundResource(R.drawable.bg_loan_active_pill)
                binding.tvHeroBadge.setTextColor(Color.parseColor("#0B61CA"))
                if (loan.isAdvance) {
                    // Active advances show only the HR -> Acc's tracker (both
                    // green) — no "Available Salary / Next Due Date" row.
                    // Matches the iOS design and reads as "fully approved".
                    binding.heroActiveDetails.visibility = View.GONE
                    binding.heroPendingTracker.visibility = View.VISIBLE
                    updateTrackerState(loan)
                } else {
                    binding.heroActiveDetails.visibility = View.VISIBLE
                    binding.heroPendingTracker.visibility = View.GONE
                }
            }
        }

        // Delete affordance — surfaced on loans so staff can withdraw a
        // request or retract a record. The design hides it on the active
        // advance card (the tracker fills the space), so we only show it
        // on the loan hero, plus the pending hero for both (where withdraw
        // is the primary action).
        binding.btnCancelLoan.visibility =
            if (loan.isAdvance && loan.status != LoanStatus.PENDING) View.GONE
            else View.VISIBLE
        binding.btnCancelLoan.setOnClickListener {
            showCancelLoanDialog(loan.id, loan.isAdvance)
        }

        binding.tvHeroOutstanding.text = LoansAdapter.formatRupees(loan.outstandingBalance)
        binding.tvHeroNextEmi.text = if (loan.nextEmiDueMillis > 0L) {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(loan.nextEmiDueMillis))
        } else {
            "—"
        }
    }

    private fun updateTrackerState(loan: Loan) {
        // The web drives the loan approval chain through `currentStage`,
        // NOT `approvalStatus` (which is only pending/approved/rejected).
        // Stages, in order:
        //   nominee_pending → gm_pending → avp_pending → hr_pending
        //   → accountant_pending → disbursed   (or "rejected")
        // A stage's dot is "done" once the loan has advanced to a LATER
        // stage. Nominee 1 / Nominee 2 are additionally lit independently
        // off their per-nominee signature status while still in
        // nominee_pending, so the operator sees one nominee sign before
        // the other. Salary advances start at hr_pending (they skip the
        // nominee/GM/AVP chain), so those earlier dots read as done —
        // matching "it's already past those" semantics.
        val stage = loan.currentStage?.lowercase()?.trim().orEmpty()
        // Numeric rank of the current stage; -1 for unknown/blank so an
        // un-stamped legacy row lights nothing rather than everything.
        val rank = when (stage) {
            "nominee_pending" -> 0
            "gm_pending" -> 1
            "avp_pending" -> 2
            "hr_pending" -> 3
            "accountant_pending", "accounts_pending" -> 4
            "disbursed", "completed", "active", "approved" -> 5
            else -> if (loan.status == LoanStatus.ACTIVE) 5 else -1
        }
        val hrDone = rank >= 4

        if (loan.isAdvance) {
            binding.loanTrackerLine.visibility = View.GONE
            binding.layoutLoanTracker.visibility = View.GONE
            binding.advanceTrackerLine.visibility = View.VISIBLE
            binding.layoutAdvanceTracker.visibility = View.VISIBLE

            // HR is always green (active) for advances
            binding.advanceStepHr.setBackgroundResource(R.drawable.bg_advance_step_green)
            binding.advanceIconHr.setColorFilter(Color.parseColor("#1BCA0B"))
            binding.advanceTextHr.setTextColor(Color.parseColor("#1BCA0B"))
            binding.advanceTextHr.setTypeface(null, Typeface.BOLD)

            if (hrDone) {
                binding.advanceStepAccs.setBackgroundResource(R.drawable.bg_advance_step_green)
                binding.advanceIconAccs.setColorFilter(Color.parseColor("#1BCA0B"))
                binding.advanceTextAccs.setTextColor(Color.parseColor("#1BCA0B"))
                binding.advanceTextAccs.setTypeface(null, Typeface.BOLD)
            } else {
                binding.advanceStepAccs.setBackgroundResource(R.drawable.bg_advance_step_pending)
                binding.advanceIconAccs.setColorFilter(Color.parseColor("#98A2B3"))
                binding.advanceTextAccs.setTextColor(Color.parseColor("#98A2B3"))
                binding.advanceTextAccs.setTypeface(null, Typeface.NORMAL)
            }
        } else {
            binding.loanTrackerLine.visibility = View.VISIBLE
            binding.layoutLoanTracker.visibility = View.VISIBLE
            binding.advanceTrackerLine.visibility = View.GONE
            binding.layoutAdvanceTracker.visibility = View.GONE

            val nominee1Signed = loan.nominee1Status.equals("approved", ignoreCase = true)
            val nominee2Signed = loan.nominee2Status.equals("approved", ignoreCase = true)

            // A nominee dot lights when the chain has moved past nominees
            // (rank >= 1, i.e. gm_pending or later) OR that specific nominee
            // has individually signed while still in nominee_pending.
            val n1Done = rank >= 1 || nominee1Signed
            val n2Done = rank >= 1 || nominee2Signed
            val gmDone = rank >= 2
            val avpDone = rank >= 3

            fun setDone(frame: View, icon: android.widget.ImageView, text: TextView) {
                frame.setBackgroundResource(R.drawable.bg_loan_track_active)
                icon.setImageResource(R.drawable.ic_loan_track_check)
                text.setTextColor(Color.parseColor("#0B61CA"))
                text.setTypeface(null, Typeface.BOLD)
            }
            fun setPending(frame: View, icon: android.widget.ImageView, text: TextView, defaultIcon: Int) {
                frame.setBackgroundResource(R.drawable.bg_loan_icon_tile)
                icon.setImageResource(defaultIcon)
                text.setTextColor(Color.parseColor("#98A2B3"))
                text.setTypeface(null, Typeface.NORMAL)
            }

            if (n1Done) setDone(binding.trackFrameNominee1, binding.trackIconNominee1, binding.trackTextNominee1)
            else setPending(binding.trackFrameNominee1, binding.trackIconNominee1, binding.trackTextNominee1, R.drawable.ic_track_shield)

            if (n2Done) setDone(binding.trackFrameNominee2, binding.trackIconNominee2, binding.trackTextNominee2)
            else setPending(binding.trackFrameNominee2, binding.trackIconNominee2, binding.trackTextNominee2, R.drawable.ic_track_shield)

            if (gmDone) setDone(binding.trackFrameGm, binding.trackIconGm, binding.trackTextGm)
            else setPending(binding.trackFrameGm, binding.trackIconGm, binding.trackTextGm, R.drawable.ic_track_gm)

            if (avpDone) setDone(binding.trackFrameAvp, binding.trackIconAvp, binding.trackTextAvp)
            else setPending(binding.trackFrameAvp, binding.trackIconAvp, binding.trackTextAvp, R.drawable.ic_track_avp)

            if (hrDone) setDone(binding.trackFrameHr, binding.trackIconHr, binding.trackTextHr)
            else setPending(binding.trackFrameHr, binding.trackIconHr, binding.trackTextHr, R.drawable.ic_track_hr)

            // ACC'S is never technically "done" while pending, as if Accounts approves, it becomes Active.
            setPending(binding.trackFrameAccs, binding.trackIconAccs, binding.trackTextAccs, R.drawable.ic_track_accs)
        }
    }

    /**
     * Fetch the configured Approval Workflow (Settings → Workflows) for this
     * loan/advance and, when the row is workflow-routed, replace the fixed
     * nominee/GM/AVP/HR/Accounts tracker with the real configured chain. Rows
     * still on the legacy code-stage path return no workflow and keep the
     * fixed tracker that updateTrackerState() already drew.
     */
    private fun loadWorkflowTracker(loan: Loan) {
        binding.workflowTrackerScroll.visibility = View.GONE
        val loanId = loan.id.takeIf { it.isNotBlank() } ?: return
        val token = SessionManager(requireContext()).bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            val steps = runCatching { api.getLoanWorkflow(token, loanId) }
                .getOrNull()
                ?.workflow
                ?.steps
                ?.filter { (it.stepOrder ?: 0) > 0 }
                ?.sortedBy { it.stepOrder ?: 0 }
                .orEmpty()
            if (_binding == null) return@launch
            // Empty → legacy code-stage row; leave the fixed tracker in place.
            if (steps.isNotEmpty()) renderWorkflowSteps(steps)
        }
    }

    private fun renderWorkflowSteps(steps: List<WorkflowStepData>) {
        val b = _binding ?: return
        // Hide the fixed loan/advance trackers + their connector lines.
        b.layoutLoanTracker.visibility = View.GONE
        b.layoutAdvanceTracker.visibility = View.GONE
        b.loanTrackerLine.visibility = View.GONE
        b.advanceTrackerLine.visibility = View.GONE
        b.workflowTrackerScroll.visibility = View.VISIBLE

        val container = b.workflowTrackerContainer
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        steps.forEachIndexed { index, step ->
            val status = step.status?.lowercase()?.trim().orEmpty()
            val done = status == "approved" || status == "skipped"
            val rejected = status == "rejected"

            val item = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    dp(78), LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            val dotSize = dp(40)
            val dot = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize)
                setBackgroundResource(
                    if (done) R.drawable.bg_loan_track_active
                    else R.drawable.bg_loan_icon_tile,
                )
            }
            if (done) {
                dot.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_loan_track_check)
                    layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
                })
            } else {
                dot.addView(TextView(requireContext()).apply {
                    text = (step.stepOrder ?: (index + 1)).toString()
                    setTextColor(
                        if (rejected) Color.parseColor("#D92D20")
                        else Color.parseColor("#98A2B3"),
                    )
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                })
            }
            item.addView(dot)

            item.addView(TextView(requireContext()).apply {
                text = step.name?.takeIf { it.isNotBlank() }
                    ?: step.approverRole?.takeIf { it.isNotBlank() }
                    ?: step.approverDesignation?.takeIf { it.isNotBlank() }
                    ?: "Step ${step.stepOrder ?: (index + 1)}"
                setTextColor(
                    when {
                        done -> Color.parseColor("#0B61CA")
                        rejected -> Color.parseColor("#D92D20")
                        else -> Color.parseColor("#98A2B3")
                    },
                )
                textSize = 10f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, if (done) Typeface.BOLD else Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) }
            })

            step.resolvedStaffName?.takeIf { it.isNotBlank() }?.let { who ->
                item.addView(TextView(requireContext()).apply {
                    text = who
                    setTextColor(Color.parseColor("#98A2B3"))
                    textSize = 9f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                })
            }

            container.addView(item)
        }
    }

    /** Mirrors the My Permissions cancel flow: reuses
     *  dialog_cancel_leave for the confirmation, then routes to
     *  cancelLoan() which calls the backend. */
    private fun showCancelLoanDialog(loanId: String, isAdvance: Boolean) {
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_cancel_leave, null)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<TextView?>(R.id.tvDialogTitle)?.text =
            if (isAdvance) "Delete this advance?" else "Delete this loan?"

        dialogView.findViewById<View>(R.id.btnDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.btnDialogConfirm).setOnClickListener {
            dialog.dismiss()
            cancelLoan(loanId)
        }
        dialog.show()
    }

    private fun cancelLoan(loanId: String) {
        val session = SessionManager(requireContext())
        val token = session.bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.cancelLoan(token, com.manjugroups.m_connect.network.IdRequest(loanId)) }
                .onSuccess {
                    android.widget.Toast.makeText(requireContext(), "Loan cancelled", android.widget.Toast.LENGTH_SHORT).show()
                    loadFromApi()
                }
                .onFailure { err ->
                    android.widget.Toast.makeText(requireContext(), "Failed to cancel loan: ${err.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun loadPendingApprovals() {
        val session = SessionManager(requireContext())
        val token = session.bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.getPendingLoanApprovals(token) }
                .onSuccess { response ->
                    if (_binding == null) return@onSuccess
                    allPending.clear()
                    allPending.addAll(response.pending)
                    renderPendingApprovals()
                }
                .onFailure {
                    if (_binding == null) return@onFailure
                    // No approvals to show (or a transient error) — hide the
                    // section silently; most staff never approve loans.
                    allPending.clear()
                    renderPendingApprovals()
                }
        }
    }

    /** Push the per-tab slice of pending approvals into the adapter.
     *  A loan request goes on the Loans tab, a salary-advance request
     *  on the Advance tab — driven by LoanData.requestType the same
     *  way the user's own active/previous lists are split. Section
     *  hides when the slice is empty so the tab doesn't show a
     *  request that belongs to the other tab. */
    private fun renderPendingApprovals() {
        if (_binding == null) return
        val slice = allPending.filter { isAdvance ->
            val isAdv = isAdvance.requestType?.lowercase()?.trim() == "salary_advance"
            if (selectedTab == TAB_LOANS) !isAdv else isAdv
        }
        requestedLoansAdapter.submitList(slice)
        binding.layoutRequestedLoans.visibility =
            if (slice.isEmpty()) View.GONE else View.VISIBLE
        // If pending approvals arrived AFTER the initial empty-state render
        // (the approvals fetch is async; the tab can render first with
        // allPending still empty), tear down the inline empty state so it
        // doesn't sit underneath the Requested Loans card.
        if (slice.isNotEmpty() && binding.inlineLoansEmptyState.visibility == View.VISIBLE) {
            binding.inlineLoansEmptyState.visibility = View.GONE
            binding.rvLoans.visibility = View.VISIBLE
        }
    }

    /** Direct, no-popup advance approval. Calls the same endpoint the
     *  GmApprovalBottomSheet would call; the backend routes the action
     *  through the configured workflow (or legacy hardcoded chain if none).
     *  On final accountant approval the backend creates the disbursement
     *  request — same as if the user clicked Accept inside the sheet. */
    private fun approveLoanInline(loan: com.manjugroups.m_connect.network.LoanData) {
        val id = loan.id ?: return
        val isAdvance = loan.requestType?.lowercase()?.trim() == "salary_advance"
        val session = SessionManager(requireContext())
        val token = session.bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.approveLoan(token, com.manjugroups.m_connect.network.ApproveLoanRequest(id))
            }.onSuccess {
                android.widget.Toast.makeText(
                    requireContext(),
                    if (isAdvance) "Advance approved" else "Loan approved",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                loadPendingApprovals()
            }.onFailure { err ->
                android.widget.Toast.makeText(
                    requireContext(),
                    extractHttpErrorMessage(err)
                        ?: "Failed to approve: ${err.message ?: "network error"}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Pulls the `{error: "..."}` JSON the Convex HTTP routes return so the
     *  user sees the real reason ("You are not nominated as a guarantor on
     *  this loan", etc.) instead of an opaque "HTTP 500". */
    private fun extractHttpErrorMessage(e: Throwable): String? {
        val httpEx = e as? retrofit2.HttpException ?: return null
        val raw = runCatching { httpEx.response()?.errorBody()?.string() }.getOrNull()
            ?: return null
        val msg = runCatching {
            val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
            obj.get("error")?.asString ?: obj.get("message")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        // Convex wraps thrown errors as "Uncaught Error: <message>\n  at ...".
        // Show just the first line minus the wrapper so the toast is clean.
        return msg.substringBefore('\n')
            .replace(Regex("^Uncaught \\w*Error:\\s*"), "")
            .trim()
            .ifBlank { null }
    }

    private fun rejectLoanRequest(loan: com.manjugroups.m_connect.network.LoanData) {
        val session = SessionManager(requireContext())
        val token = session.bearerToken
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.rejectLoan(token, com.manjugroups.m_connect.network.RejectRequest(loan.id!!, reason = "Rejected")) }
                .onSuccess {
                    android.widget.Toast.makeText(requireContext(), "Loan rejected", android.widget.Toast.LENGTH_SHORT).show()
                    loadPendingApprovals()
                }
                .onFailure { err ->
                    android.widget.Toast.makeText(
                        requireContext(),
                        extractHttpErrorMessage(err)
                            ?: "Failed to reject loan: ${err.message ?: "network error"}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun playContentEntryAnim(activeHero: Loan?) {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        val art = binding.ivLoansHeaderArt
        art.animate().cancel()
        art.alpha = 0f
        art.translationY = -8f * density
        art.translationX = 20f * density
        art.animate()
            .alpha(1f)
            .translationY(0f)
            .translationX(0f)
            .setStartDelay(80L)
            .setDuration(420L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()

        val heroView = binding.heroActiveCard
        if (heroView.visibility == View.VISIBLE) {
            heroView.animate().cancel()
            heroView.alpha = 0f
            heroView.translationY = 24f * density
            heroView.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120L)
                .setDuration(480L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
                .withEndAction {
                    if (_binding != null && activeHero != null) {
                        binding.heroActiveCard.setOnClickListener {
                            openRepaymentHistory(activeHero)
                        }
                    }
                }
                .start()
        }

        binding.previousLoansHeaderRow.alpha = 0f
        binding.previousLoansHeaderRow.translationY = 18f * density
        binding.previousLoansHeaderRow.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(220L)
            .setDuration(420L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()

        binding.rvLoans.alpha = 0f
        binding.rvLoans.translationY = 32f * density
        binding.rvLoans.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(280L)
            .setDuration(520L)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()
    }

    private fun playEmptyStateEntryAnim() {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        val empty = binding.loansEmptyState
        empty.animate().cancel()
        empty.alpha = 0f
        empty.translationY = 16f * density
        empty.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(420L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun openRepaymentHistory(loan: Loan) {
        parentFragmentManager.beginTransaction()
            .applySmoothTransitions()
            .replace(
                R.id.fragmentContainer,
                RepaymentHistoryFragment.newInstance(loan.id, loan.status)
            )
            .addToBackStack(null)
            .commit()
    }

    private fun openCreateLoanSheet() {
        CreateLoanBottomSheet.newInstance()
            .show(parentFragmentManager, "create_loan")
    }

    private fun openCreateSalaryAdvanceSheet() {
        CreateSalaryAdvanceBottomSheet.newInstance()
            .show(parentFragmentManager, "create_salary_advance")
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(
                Color.parseColor("#0B61CA"),
                false,
                fullBleed = true
            )
        }
        // Re-fetch when the user comes back to this screen so the pending
        // tracker reflects any approval an approver made on the web while
        // they were away. Guarded on `loaded` so this doesn't double-fire
        // alongside the initial onViewCreated load (loaded is still false
        // until that first fetch returns). Only refreshes the default
        // "User" view; the role-filter dropdown drives its own load.
        if (loaded && _binding != null) {
            loadFromApi()
            loadPendingApprovals()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
