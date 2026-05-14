package com.manjugroups.m_connect.ui.library.loans

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LoansFragment : Fragment() {

    private var _binding: FragmentLoansBinding? = null
    private val binding get() = _binding!!

    private val api = ApiService.create()
    private val active = mutableListOf<Loan>()
    private val previous = mutableListOf<Loan>()
    private var loaded = false

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

        binding.btnLoansBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnLoansEmptyBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.tvPreviousLoansViewAll.setOnClickListener {
            // No separate full-list screen yet — focus the list by scrolling it
            // into view. Future work could open a dedicated /loans/previous.
            binding.rvLoans.smoothScrollToPosition(0)
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
                    active.clear()
                    previous.clear()
                    active.addAll(LoanMapper.mapLoanList(response.pending, LoanStatus.PENDING))
                    active.addAll(LoanMapper.mapLoanList(response.active, LoanStatus.ACTIVE))
                    previous.addAll(LoanMapper.mapLoanList(response.previous, LoanStatus.REPAID))
                    loaded = true
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
                    render()
                }
        }
    }

    private fun render() {
        if (!loaded) return
        if (active.isEmpty() && previous.isEmpty()) {
            binding.loansContent.visibility = View.GONE
            binding.loansEmptyState.visibility = View.VISIBLE
            playEmptyStateEntryAnim()
            return
        }
        binding.loansEmptyState.visibility = View.GONE
        binding.loansContent.visibility = View.VISIBLE

        val hero = active.firstOrNull()
        if (hero != null) {
            bindHeroCard(hero)
            binding.heroActiveCard.visibility = View.VISIBLE
            binding.heroActiveCard.setOnClickListener {
                openRepaymentHistory(hero)
            }
        } else {
            binding.heroActiveCard.visibility = View.GONE
            binding.heroActiveCard.setOnClickListener(null)
        }

        binding.previousLoansHeaderRow.visibility =
            if (previous.isEmpty()) View.GONE else View.VISIBLE
        adapter.submit(previous)

        playContentEntryAnim()
    }

    private fun bindHeroCard(loan: Loan) {
        binding.tvHeroLoanTitle.text = loan.title
        binding.tvHeroLoanId.text = loan.loanId.ifBlank { "—" }

        when (loan.type) {
            LoanType.HOME -> binding.ivHeroLoanIcon.setImageResource(R.drawable.ic_loan_home)
            LoanType.EDUCATION -> binding.ivHeroLoanIcon.setImageResource(R.drawable.ic_loan_education)
            LoanType.OTHER -> binding.ivHeroLoanIcon.setImageResource(R.drawable.ic_loan_home)
        }

        when (loan.status) {
            LoanStatus.PENDING -> {
                binding.tvHeroBadge.text = "Pending"
                binding.tvHeroBadge.setBackgroundResource(R.drawable.bg_loan_status_pending)
                binding.tvHeroBadge.setTextColor(android.graphics.Color.parseColor("#F79009"))
            }
            else -> {
                binding.tvHeroBadge.text = "Active Loan"
                binding.tvHeroBadge.setBackgroundResource(R.drawable.bg_loan_active_pill)
                binding.tvHeroBadge.setTextColor(android.graphics.Color.parseColor("#0B61CA"))
            }
        }

        binding.tvHeroOutstanding.text = LoansAdapter.formatRupees(loan.outstandingBalance)
        binding.tvHeroNextEmi.text = if (loan.nextEmiDueMillis > 0L) {
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(loan.nextEmiDueMillis))
        } else {
            "—"
        }
    }

    private fun playContentEntryAnim() {
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

        val hero = binding.heroActiveCard
        if (hero.visibility == View.VISIBLE) {
            hero.animate().cancel()
            hero.alpha = 0f
            hero.translationY = 24f * density
            hero.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120L)
                .setDuration(480L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
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

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.let { main ->
            main.setTabBarVisible(false)
            main.setTopBarAppearance(
                android.graphics.Color.parseColor("#0B61CA"),
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
