package com.manjugroups.m_connect.ui.library.loans

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
    
    // Filtered lists for the active tab
    private val active = mutableListOf<Loan>()
    private val previous = mutableListOf<Loan>()
    
    private var loaded = false

    private val TAB_LOANS = 0
    private val TAB_SALARY = 1
    private var selectedTab = TAB_LOANS

    private lateinit var adapter: LoansAdapter
    private lateinit var requestedLoansAdapter: RequestedLoansAdapter

    private var mockPendingList: MutableList<com.manjugroups.m_connect.network.LoanData>? = null

    /** Tracks which role the user selected from the dropdown (0=User, 1=Nominee1, 2=Nominee2, 3=GM, 4=AVP, 5=HR). */
    private var selectedRoleId = 0

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
                if (selectedRoleId >= 3) {
                    // GM / AVP / HR — show the approval sheet with track progress & signatures
                    val gmSheet = GmApprovalBottomSheet(
                        loan = loan,
                        onAccepted = {
                            val idx = mockPendingList?.indexOfFirst { it.id == loan.id } ?: -1
                            if (idx != -1) {
                                mockPendingList!![idx] = mockPendingList!![idx].copy(status = "APPROVED")
                            }
                            loadPendingApprovals()
                        },
                        onRejected = {
                            val idx = mockPendingList?.indexOfFirst { it.id == loan.id } ?: -1
                            if (idx != -1) {
                                mockPendingList!!.removeAt(idx)
                            }
                            loadPendingApprovals()
                        }
                    )
                    gmSheet.show(childFragmentManager, "GmApprovalBottomSheet")
                } else {
                    // Nominee 1 / 2 — show the e-signature pad
                    val bottomSheet = AcceptLoanBottomSheet(loan) {
                        val idx = mockPendingList?.indexOfFirst { it.id == loan.id } ?: -1
                        if (idx != -1) {
                            mockPendingList!![idx] = mockPendingList!![idx].copy(status = "APPROVED")
                        }
                        loadPendingApprovals()
                    }
                    bottomSheet.show(childFragmentManager, "AcceptLoanBottomSheet")
                }
            },
            onRejectClick = { loan ->
                rejectLoanRequest(loan)
            }
        )
        binding.rvRequestedLoans.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequestedLoans.adapter = requestedLoansAdapter
        binding.rvRequestedLoans.itemAnimator = null

        binding.btnUserDropdown.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(requireContext(), view)
            popup.menu.add(0, 0, 0, "User")
            popup.menu.add(0, 1, 1, "Nominee 1")
            popup.menu.add(0, 2, 2, "Nominee 2")
            popup.menu.add(0, 3, 3, "GM")
            popup.menu.add(0, 4, 4, "AVP")
            popup.menu.add(0, 5, 5, "HR")
            popup.setOnMenuItemClickListener { item ->
                binding.btnUserDropdown.text = item.title
                selectedRoleId = item.itemId
                if (item.itemId == 0) {
                    binding.layoutRequestedLoans.visibility = View.GONE
                    binding.layoutPreviousLoans.visibility = View.VISIBLE
                    binding.heroActiveCard.visibility = View.VISIBLE
                    loadFromApi()
                } else {
                    binding.layoutRequestedLoans.visibility = View.VISIBLE
                    binding.layoutPreviousLoans.visibility = View.GONE
                    binding.heroActiveCard.visibility = View.VISIBLE
                    binding.loansEmptyState.visibility = View.GONE
                    loadPendingApprovals()
                }
                true
            }
            popup.show()
        }

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
            binding.tvHeroOutstandingLabel.text = "Available Salary"
            binding.tvHeroNextEmiLabel.text = "Next Due Date"
            binding.tvPreviousLoansLabel.text = "Previous Advances"
        }
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

        val bothEmpty = active.isEmpty() && previous.isEmpty()
        if (bothEmpty) {
            binding.inlineLoansEmptyState.visibility = View.VISIBLE
            binding.inlineLoansEmptyState.setTitle(
                if (selectedTab == TAB_LOANS) "No Loans Yet" else "No Advances Yet"
            )
            binding.inlineLoansEmptyState.setDescription(
                if (selectedTab == TAB_LOANS) {
                    "When your finance team disburses a loan, you'll see it grouped here with EMI dates and a full repayment history."
                } else {
                    "When your finance team disburses a salary advance, you'll see it grouped here with due dates."
                },
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
                
                binding.btnCancelLoan.visibility = View.VISIBLE
                binding.btnCancelLoan.setOnClickListener {
                    cancelLoan(loan.id)
                }
                
                updateTrackerState(loan)
            }
            else -> {
                binding.tvHeroBadge.text = if (loan.isAdvance) "Active Advance" else "Active Loan"
                binding.tvHeroBadge.setBackgroundResource(R.drawable.bg_loan_active_pill)
                binding.tvHeroBadge.setTextColor(Color.parseColor("#0B61CA"))
                binding.heroActiveDetails.visibility = View.VISIBLE
                binding.heroPendingTracker.visibility = View.GONE
                
                binding.btnCancelLoan.visibility = View.GONE
            }
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
        val nominee1Signed = loan.nominee1Status.equals("approved", ignoreCase = true)
        val nominee2Signed = loan.nominee2Status.equals("approved", ignoreCase = true)

        // A nominee dot lights when the chain has moved past nominees
        // (rank >= 1, i.e. gm_pending or later) OR that specific nominee
        // has individually signed while still in nominee_pending.
        val n1Done = rank >= 1 || nominee1Signed
        val n2Done = rank >= 1 || nominee2Signed
        val gmDone = rank >= 2
        val avpDone = rank >= 3
        val hrDone = rank >= 4

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
                    // Use response.pending for requested loans
                    requestedLoansAdapter.submitList(response.pending)
                }
                .onFailure { err ->
                    if (_binding == null) return@onFailure
                    if (err.message?.contains("404") == true) {
                        // Mock data for UI demonstration since backend endpoint is missing
                        if (mockPendingList == null) {
                            mockPendingList = mutableListOf(
                                com.manjugroups.m_connect.network.LoanData(
                                    id = "mock_1",
                                    loanId = "LN000021",
                                    staffId = "s1",
                                    staffName = "Manju",
                                    employeeId = "EMP001",
                                    principalAmount = 250000.0,
                                    purpose = "Home Loan",
                                    disbursedDate = "25 Apr 2024",
                                    status = "PENDING"
                                ),
                                com.manjugroups.m_connect.network.LoanData(
                                    id = "mock_2",
                                    loanId = "LN000022",
                                    staffId = "s2",
                                    staffName = "Siva",
                                    employeeId = "EMP002",
                                    principalAmount = 15000.0,
                                    interestType = "Salary Advance",
                                    purpose = "Salary Advance",
                                    disbursedDate = "25 Apr 2024",
                                    status = "PENDING"
                                )
                            )
                        }
                        requestedLoansAdapter.submitList(mockPendingList?.toList())
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Failed to load requests: ${err.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
        }
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
                    if (err.message?.contains("404") == true) {
                        android.widget.Toast.makeText(requireContext(), "Loan rejected (Mock)", android.widget.Toast.LENGTH_SHORT).show()
                        val idx = mockPendingList?.indexOfFirst { it.id == loan.id } ?: -1
                        if (idx != -1) {
                            mockPendingList!!.removeAt(idx)
                        }
                        loadPendingApprovals()
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Failed to reject loan: ${err.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
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
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
