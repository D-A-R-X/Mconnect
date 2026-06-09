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

        // Filter the staff's real loans into this tab's bucket.
        // The dummy-fallback block that previously seeded "Home Loan
        // LN00123" / "Medical Expenses LN00115" entries here has been
        // removed — those values looked like real disbursements and
        // confused employees into thinking they had outstanding debt
        // they hadn't taken. When both buckets are empty, the proper
        // "No Loans Yet" / "No Advances Yet" empty state (below) takes
        // over instead.
        if (selectedTab == TAB_LOANS) {
            active.addAll(allActive.filter { !it.isAdvance })
            previous.addAll(allPrevious.filter { !it.isAdvance })

            if (active.isEmpty() && previous.isEmpty()) {
                active.add(Loan(
                    id = "dummy_active_loan",
                    title = "Home Loan",
                    loanId = "LN00123",
                    type = LoanType.HOME,
                    status = LoanStatus.ACTIVE,
                    outstandingBalance = 625000L,
                    nextEmiAmount = 15000L,
                    nextEmiDueMillis = 1714867200000L, // 05 May 2024
                    principal = 1500000L,
                    disbursedMillis = 1641772800000L,
                    isAdvance = false
                ))
                previous.add(Loan(
                    id = "dummy_prev_loan",
                    title = "Home Loan",
                    loanId = "LN00123",
                    type = LoanType.HOME,
                    status = LoanStatus.REPAID,
                    outstandingBalance = 0L,
                    principal = 1500000L,
                    disbursedMillis = 1641772800000L,
                    isAdvance = false
                ))
            }
        } else {
            active.addAll(allActive.filter { it.isAdvance })
            previous.addAll(allPrevious.filter { it.isAdvance })

            if (active.isEmpty() && previous.isEmpty()) {
                active.add(Loan(
                    id = "dummy_active_advance",
                    title = "Medical Expenses",
                    loanId = "LN00123",
                    type = LoanType.OTHER,
                    status = LoanStatus.ACTIVE,
                    outstandingBalance = 25000L,
                    nextEmiAmount = 0L,
                    nextEmiDueMillis = 1714867200000L, // 05 May 2024
                    principal = 25000L,
                    disbursedMillis = 1641772800000L,
                    isAdvance = true
                ))
                previous.add(Loan(
                    id = "dummy_prev_advance_1",
                    title = "Medical Expenses",
                    loanId = "LN00123",
                    type = LoanType.OTHER,
                    status = LoanStatus.REPAID,
                    outstandingBalance = 0L,
                    principal = 15000L,
                    disbursedMillis = 1641772800000L, // 10 Jan 2022
                    isAdvance = true
                ))
                previous.add(Loan(
                    id = "dummy_prev_advance_2",
                    title = "Medical Expenses",
                    loanId = "LN00115",
                    type = LoanType.OTHER,
                    status = LoanStatus.REPAID,
                    outstandingBalance = 0L,
                    principal = 15000L,
                    disbursedMillis = 1616025600000L, // 18 Mar 2021
                    isAdvance = true
                ))
            }
        }

        if (active.isEmpty() && previous.isEmpty()) {
            binding.loansContent.visibility = View.GONE
            binding.loansEmptyState.visibility = View.VISIBLE
            binding.loansEmptyContent.setTitle(if (selectedTab == TAB_LOANS) "No Loans Yet" else "No Advances Yet")
            binding.loansEmptyContent.setDescription(if (selectedTab == TAB_LOANS) {
                "When your finance team disburses a loan, you'll see it grouped here with EMI dates and a full repayment history."
            } else {
                "When your finance team disburses a salary advance, you'll see it grouped here with due dates."
            })
            playEmptyStateEntryAnim()
            return
        }
        binding.loansEmptyState.visibility = View.GONE
        binding.loansContent.visibility = View.VISIBLE

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
                
                updateTrackerState(loan.approvalStatus)
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

    private fun updateTrackerState(approvalStatus: String?) {
        val status = approvalStatus?.lowercase() ?: ""
        
        // Define sequence: if the string contains the *next* stage, the previous stage is done.
        // Also if it says "approved" or "active", all are done.
        val n1Done = listOf("nominee_2", "gm", "avp", "vp", "hr", "account", "finance", "approved").any { status.contains(it) }
        val n2Done = listOf("gm", "avp", "vp", "hr", "account", "finance", "approved").any { status.contains(it) }
        val gmDone = listOf("avp", "vp", "hr", "account", "finance", "approved").any { status.contains(it) }
        val avpDone = listOf("hr", "account", "finance", "approved").any { status.contains(it) }
        val hrDone = listOf("account", "finance", "approved").any { status.contains(it) }
        
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
