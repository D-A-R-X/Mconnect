package com.manjugroups.m_connect.ui.library.loans

import com.manjugroups.m_connect.network.LoanData as RemoteLoan
import com.manjugroups.m_connect.network.LoanRepaymentData as RemoteRepayment
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * UI-side loan model. Built from the server's `/api/hr/loans/my` payload.
 */
data class Loan(
    val id: String,
    val title: String,
    val loanId: String,
    val type: LoanType,
    val status: LoanStatus,
    val outstandingBalance: Long = 0L,
    val nextEmiAmount: Long = 0L,
    val nextEmiDueMillis: Long = 0L,
    val principal: Long = 0L,
    val disbursedMillis: Long = 0L,
    val repayments: List<Repayment> = emptyList(),
    val isAdvance: Boolean = false,
    val approvalStatus: String? = null,
    // Workflow stage that drives the pending tracker (see ApiService.LoanData).
    val currentStage: String? = null,
    val nominee1Status: String? = null,
    val nominee2Status: String? = null,
    val nominee1Name: String? = null,
    val nominee2Name: String? = null,
    // Approver names for the progress tracker (assigned at submit, then the
    // acted name once each stage approves).
    val gmName: String? = null,
    val avpName: String? = null,
    val hrName: String? = null,
    val accountantName: String? = null
)

enum class LoanType { HOME, EDUCATION, OTHER }
enum class LoanStatus { ACTIVE, PENDING, REPAID, CANCELLED, REJECTED }

data class Repayment(
    val emiIndex: Int,
    val dueMillis: Long,
    val amount: Long,
    val status: RepaymentStatus,
    val paidVia: String? = null,
    val onTime: Boolean = true
)

enum class RepaymentStatus { PAID, UPCOMING, OVERDUE }

object LoanMapper {

    private val isoDayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
    private val isoDateTimeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    fun fromRemote(remote: RemoteLoan, mappedStatus: LoanStatus): Loan {
        val type = inferType(remote.purpose)
        // Derive the REAL status from the server row, not the bucket hint —
        // the "previous" bucket lumps repaid + cancelled + rejected together,
        // so a cancelled loan was wrongly shown as "Repaid".
        val mappedStatusReal = when (remote.status?.lowercase()?.trim()) {
            "cancelled", "canceled" -> LoanStatus.CANCELLED
            "rejected" -> LoanStatus.REJECTED
            "active" -> LoanStatus.ACTIVE
            "pending" -> LoanStatus.PENDING
            "completed", "repaid", "closed" -> LoanStatus.REPAID
            else -> mappedStatus
        }
        // requestType is the authoritative backend flag — no legacy
        // heuristics. A row only counts as an advance when the server
        // explicitly stamps it; everything else (including legacy
        // pre-requestType rows) defaults to LOAN so the Advance tab
        // never gets polluted by ambiguous data, which was the symptom
        // the user reported ("Loan" titled rows appearing inside the
        // Advance tab as 'Active Advance').
        val isAdvance =
            remote.requestType.equals("salary_advance", ignoreCase = true)

        // Title — advances ignore whatever the user typed as the
        // purpose ("Loan", "demo", etc.) and display a stable
        // "Salary Advance" label so the hero card on the Advance tab
        // can't say "Loan" anymore. Regular loans still surface the
        // purpose for the title.
        val title = when {
            isAdvance -> "Salary Advance"
            else -> remote.purpose?.takeIf { it.isNotBlank() }
                ?: when (type) {
                    LoanType.HOME -> "Home Loan"
                    LoanType.EDUCATION -> "Education Loan"
                    LoanType.OTHER -> "Loan"
                }
        }

        val paidEntries = remote.repayments
            .orEmpty()
            .sortedBy { parseMonth(it.month) ?: 0L }
            .mapIndexed { idx, r -> mapRepayment(idx + 1, r) }

        val repayments = when (mappedStatusReal) {
            LoanStatus.ACTIVE -> buildFullSchedule(remote, paidEntries)
            LoanStatus.PENDING -> buildPendingSchedule(remote)
            else -> paidEntries
        }

        val nextEmiMillis = if (mappedStatusReal == LoanStatus.ACTIVE) {
            nextUnpaidMonthMillis(remote, paidEntries)
        } else 0L

        // Pending loans haven't been disbursed, so remainingBalance is the
        // *expected* outstanding — surface the loanAmount instead of zero so
        // the card has something meaningful to show.
        val outstanding = when {
            mappedStatusReal == LoanStatus.PENDING -> (remote.loanAmount ?: remote.principalAmount ?: 0.0)
            else -> (remote.remainingBalance ?: 0.0)
        }

        return Loan(
            id = remote.id.orEmpty(),
            title = title,
            loanId = remote.loanId.orEmpty(),
            type = type,
            status = mappedStatusReal,
            outstandingBalance = outstanding.toLong(),
            nextEmiAmount = (remote.monthlyDeduction ?: 0.0).toLong(),
            nextEmiDueMillis = nextEmiMillis,
            principal = (remote.loanAmount ?: remote.principalAmount ?: 0.0).toLong(),
            disbursedMillis = parseDay(remote.disbursedDate) ?: 0L,
            repayments = repayments,
            isAdvance = isAdvance,
            approvalStatus = remote.approvalStatus,
            currentStage = remote.currentStage,
            nominee1Status = remote.nominee1Status,
            nominee2Status = remote.nominee2Status,
            nominee1Name = remote.nominee1Name,
            nominee2Name = remote.nominee2Name,
            // Prefer the acted/assigned name; fall back to the backend-resolved
            // role holder so a pending stage still shows who will approve.
            gmName = remote.gmName ?: remote.assignedGmName ?: remote.resolvedGmName,
            avpName = remote.avpName ?: remote.assignedAvpName ?: remote.resolvedAvpName,
            hrName = remote.hrApprovalName ?: remote.resolvedHrName,
            accountantName = remote.accountantName ?: remote.resolvedAccountantName
        )
    }

    private fun mapRepayment(emiIndex: Int, r: RemoteRepayment): Repayment {
        val dueMillis = parseMonth(r.month) ?: parseIsoDateTime(r.createdAt) ?: 0L
        val status = when {
            dueMillis == 0L -> RepaymentStatus.PAID
            dueMillis > System.currentTimeMillis() -> RepaymentStatus.UPCOMING
            else -> RepaymentStatus.PAID
        }
        val mode = r.mode
        val paidVia = when {
            status != RepaymentStatus.PAID -> null
            mode == "salary-deduction" -> "Salary"
            mode == "manual" -> "Bank"
            !mode.isNullOrBlank() -> mode.replaceFirstChar { it.uppercase() }
            else -> null
        }
        return Repayment(
            emiIndex = emiIndex,
            dueMillis = dueMillis,
            amount = (r.amount ?: 0.0).toLong(),
            status = status,
            paidVia = paidVia,
            onTime = true
        )
    }

    /**
     * For an active loan, return PAID entries for months that have a server
     * repayment record and UPCOMING entries for every other month between
     * [repaymentStartMonth] and [repaymentEndMonth]. This is what the SzkIf
     * design wants — one timeline mixing paid + upcoming EMIs.
     */
    private fun buildFullSchedule(
        remote: RemoteLoan,
        paid: List<Repayment>
    ): List<Repayment> {
        val start = parseMonth(remote.repaymentStartMonth) ?: return paid
        val end = parseMonth(remote.repaymentEndMonth) ?: return paid
        val emiAmount = (remote.monthlyDeduction ?: 0.0).toLong()
        val paidByMonth = paid.associateBy { it.dueMillis }

        val out = mutableListOf<Repayment>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        var index = 1
        while (cal.timeInMillis <= end && index <= 360) {
            val cursor = cal.timeInMillis
            val existing = paidByMonth[cursor]
            out += existing?.copy(emiIndex = index) ?: Repayment(
                emiIndex = index,
                dueMillis = cursor,
                amount = emiAmount,
                status = RepaymentStatus.UPCOMING
            )
            cal.add(Calendar.MONTH, 1)
            index++
        }
        return out
    }

    /**
     * Pending (not yet disbursed) loans have no paid history — surface the
     * full expected schedule as UPCOMING entries so the timeline still has
     * something useful to show.
     */
    private fun buildPendingSchedule(remote: RemoteLoan): List<Repayment> {
        val start = parseMonth(remote.repaymentStartMonth) ?: return emptyList()
        val end = parseMonth(remote.repaymentEndMonth) ?: return emptyList()
        val emiAmount = (remote.monthlyDeduction ?: 0.0).toLong()
        val out = mutableListOf<Repayment>()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        var index = 1
        while (cal.timeInMillis <= end && index <= 360) {
            out += Repayment(
                emiIndex = index,
                dueMillis = cal.timeInMillis,
                amount = emiAmount,
                status = RepaymentStatus.UPCOMING
            )
            cal.add(Calendar.MONTH, 1)
            index++
        }
        return out
    }

    private fun nextUnpaidMonthMillis(remote: RemoteLoan, paid: List<Repayment>): Long {
        val start = parseMonth(remote.repaymentStartMonth) ?: return 0L
        val end = parseMonth(remote.repaymentEndMonth) ?: 0L
        val paidMonths = paid.map { it.dueMillis }.toSet()
        val cal = Calendar.getInstance().apply { timeInMillis = start }
        val now = System.currentTimeMillis()
        repeat(360) {
            val cursor = cal.timeInMillis
            if (end > 0L && cursor > end) return 0L
            if (cursor !in paidMonths && cursor >= now) return cursor
            cal.add(Calendar.MONTH, 1)
        }
        return 0L
    }

    private fun parseDay(value: String?): Long? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { isoDayFormat.parse(raw)?.time }.getOrNull()
    }

    private fun parseMonth(value: String?): Long? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { monthFormat.parse(raw)?.time }.getOrNull()
    }

    private fun parseIsoDateTime(value: String?): Long? {
        val raw = value?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { isoDateTimeFormat.parse(raw)?.time }.getOrNull()
    }

    private fun inferType(purpose: String?): LoanType {
        val p = purpose?.lowercase().orEmpty()
        return when {
            p.contains("home") || p.contains("house") || p.contains("property") -> LoanType.HOME
            p.contains("educ") || p.contains("school") || p.contains("college") -> LoanType.EDUCATION
            else -> LoanType.OTHER
        }
    }

    fun mapLoanList(remoteList: List<RemoteLoan>, status: LoanStatus): List<Loan> =
        remoteList.mapNotNull {
            if (it.id.isNullOrBlank()) null else fromRemote(it, status)
        }
}
