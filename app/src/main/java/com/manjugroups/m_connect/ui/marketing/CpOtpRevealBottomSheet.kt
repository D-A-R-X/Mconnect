package com.manjugroups.m_connect.ui.marketing

import android.app.Dialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.CpOtpRevealCopiedRequest
import com.manjugroups.m_connect.network.CpOtpRevealRequest
import com.manjugroups.m_connect.network.CpOtpRevealResponse
import com.manjugroups.m_connect.network.GeoTrackApi
import com.manjugroups.m_connect.ui.home.arrivalOtpFailureMessage
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Reads a team member's live CP arrival OTP back to their manager.
 *
 * Opened only from a row the viewer is already allowed to reveal — see
 * [CpOtpRevealAccess]. The reveal call fires as soon as the sheet opens: the
 * manager asking for the sheet IS the decision, and the audit record is written
 * server-side either way, so a second "Reveal" tap would add ceremony without
 * adding a choice.
 *
 * The OTP is never cached, never logged and never carried into a fragment
 * result — it lives in this sheet's view only, and dies with it.
 */
class CpOtpRevealBottomSheet : BottomSheetDialogFragment() {

    private val api by lazy { GeoTrackApi.create() }
    private val session by lazy { SessionManager(requireContext()) }

    private var progress: ProgressBar? = null
    private var resultBlock: View? = null
    private var codeText: TextView? = null
    private var metaText: TextView? = null
    private var errorText: TextView? = null
    private var copyBtn: TextView? = null

    /** Held only to attribute the copy audit; cleared with the view. */
    private var revealedFieldVisitId: String? = null
    private var revealedOtp: String? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        BottomSheetDialog(requireContext(), theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.sheet_cp_otp_reveal, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val cpVisitId = args.getString(ARG_CP_VISIT_ID).orEmpty()
        val staffName = args.getString(ARG_STAFF_NAME)?.takeIf { it.isNotBlank() }
        val placeName = args.getString(ARG_PLACE_NAME)?.takeIf { it.isNotBlank() }

        progress = view.findViewById(R.id.pbCpOtpReveal)
        resultBlock = view.findViewById(R.id.llCpOtpRevealResult)
        codeText = view.findViewById(R.id.tvCpOtpRevealCode)
        metaText = view.findViewById(R.id.tvCpOtpRevealMeta)
        errorText = view.findViewById(R.id.tvCpOtpRevealError)
        copyBtn = view.findViewById(R.id.btnCpOtpRevealCopy)

        view.findViewById<TextView>(R.id.tvCpOtpRevealSubtitle)?.text = buildString {
            append(staffName ?: "This staff member")
            append("'s active arrival OTP")
            if (placeName != null) append(" at ").append(placeName)
            append(". Read it out to them — do not share it with the client.")
        }
        view.findViewById<TextView>(R.id.btnCpOtpRevealClose)?.setOnClickListener {
            dismissAllowingStateLoss()
        }
        copyBtn?.setOnClickListener { copyRevealedOtp() }

        if (cpVisitId.isBlank()) {
            showError("This CP visit is missing its id. Reopen the list and try again.")
            return
        }
        reveal(cpVisitId)
    }

    override fun onDestroyView() {
        revealedOtp = null
        revealedFieldVisitId = null
        progress = null
        resultBlock = null
        codeText = null
        metaText = null
        errorText = null
        copyBtn = null
        super.onDestroyView()
    }

    private fun reveal(cpVisitId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = runCatching {
                api.revealCpArrivalOtp(
                    session.bearerToken,
                    CpOtpRevealRequest(sourceId = cpVisitId),
                )
            }
            if (!isAdded || view == null) return@launch
            progress?.visibility = View.GONE

            val response = outcome.getOrNull()
            if (response != null) {
                if (response.success && !response.otp.isNullOrBlank()) {
                    showOtp(response)
                } else {
                    // A refusal, not a failure: the server explains what is
                    // missing ("Generate OTP first", "already verified").
                    showError(response.error ?: "No active OTP is available for this visit.")
                }
                return@launch
            }

            val error = outcome.exceptionOrNull() ?: return@launch
            if (isRouteMissing(error)) {
                // The capability is backend-gated and this deployment has not
                // shipped the mobile route yet. Remember it so the action stops
                // being offered for the rest of the session instead of leading
                // every manager into the same dead end.
                CpOtpRevealSupport.markUnsupported()
                showError(
                    "OTP reveal is not enabled on this server yet. " +
                        "Ask the client for the code, or use Request GM from the staff's trip.",
                )
                return@launch
            }
            showError(arrivalOtpFailureMessage(error, "Couldn't reveal the OTP. Please try again."))
        }
    }

    private fun showOtp(response: CpOtpRevealResponse) {
        revealedOtp = response.otp
        revealedFieldVisitId = response.fieldVisitId
        codeText?.text = response.otp
        metaText?.text = buildList {
            response.contactPhoneMasked?.takeIf { it.isNotBlank() }?.let { add("Sent to $it") }
            response.attempts?.takeIf { it > 0 }?.let { add("$it failed ${plural(it, "attempt")}") }
            response.resendCount?.takeIf { it > 0 }?.let { add("resent $it ${plural(it, "time")}") }
        }.joinToString(" • ")
        metaText?.visibility = if (metaText?.text.isNullOrBlank()) View.GONE else View.VISIBLE
        resultBlock?.visibility = View.VISIBLE
        errorText?.visibility = View.GONE
    }

    private fun showError(message: String) {
        progress?.visibility = View.GONE
        resultBlock?.visibility = View.GONE
        errorText?.text = message
        errorText?.visibility = View.VISIBLE
    }

    private fun copyRevealedOtp() {
        val otp = revealedOtp ?: return
        val clipboard = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = ClipData.newPlainText("CP arrival OTP", otp).apply {
            // Keep the code out of the system clipboard preview toast/overlay —
            // it is a client's verification code, not ordinary copied text.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "OTP copied", Toast.LENGTH_SHORT).show()

        // Best effort: the copy already happened locally, so a failed audit
        // write must not be reported as a failed copy. The server records the
        // reveal itself regardless, so nothing goes unlogged.
        val fieldVisitId = revealedFieldVisitId?.takeIf { it.isNotBlank() } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                api.recordCpArrivalOtpCopied(
                    session.bearerToken,
                    CpOtpRevealCopiedRequest(fieldVisitId),
                )
            }
        }
    }

    private fun plural(count: Int, word: String): String = if (count == 1) word else "${word}s"

    /**
     * A route this build knows about but the deployment does not serve.
     * Convex answers an unregistered path with 404 and a wrong method with 405.
     */
    private fun isRouteMissing(error: Throwable): Boolean {
        val code = (error as? HttpException)?.code() ?: return false
        return code == 404 || code == 405
    }

    companion object {
        private const val ARG_CP_VISIT_ID = "cpVisitId"
        private const val ARG_STAFF_NAME = "staffName"
        private const val ARG_PLACE_NAME = "placeName"

        fun newInstance(
            cpVisitId: String,
            staffName: String?,
            placeName: String?,
        ): CpOtpRevealBottomSheet = CpOtpRevealBottomSheet().apply {
            arguments = bundleOf(
                ARG_CP_VISIT_ID to cpVisitId,
                ARG_STAFF_NAME to staffName,
                ARG_PLACE_NAME to placeName,
            )
        }
    }
}

/**
 * Whether this deployment serves the reveal route at all.
 *
 * Process-lifetime only, and it only ever narrows: a 404 hides the action, and
 * a restart re-offers it. That way the app never shows a manager a button that
 * cannot work twice, and picks the capability up automatically once the backend
 * route is deployed — no app release needed to turn it on.
 */
internal object CpOtpRevealSupport {
    @Volatile
    private var unsupported = false

    val isSupported: Boolean get() = !unsupported

    fun markUnsupported() {
        unsupported = true
    }

    /** Test seam — production code only ever calls [markUnsupported]. */
    fun resetForTests() {
        unsupported = false
    }
}
