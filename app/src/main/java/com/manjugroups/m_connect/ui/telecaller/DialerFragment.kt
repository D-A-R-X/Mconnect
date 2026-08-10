package com.manjugroups.m_connect.ui.telecaller

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DialDooctiRequest
import com.manjugroups.m_connect.network.MobileDialerConfigResponse
import com.manjugroups.m_connect.notifications.DialerEvent
import com.manjugroups.m_connect.notifications.ModernDialerWebViewBridge
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phone dialpad — mirrors the web Telecaller > Dialer screen. It fetches the
 * authenticated Modern Dialer mapping from Convex, then controls the embedded
 * dialer through the same postMessage protocol used by web. The legacy Doocti
 * bridge remains as a temporary fallback.
 *
 * Station is persisted in SharedPreferences keyed by `dialer.station`.
 */
class DialerFragment : Fragment() {

    private lateinit var prefs: android.content.SharedPreferences
    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var tvNumber: TextView? = null
    private var tvStation: TextView? = null
    private var btnBackspace: View? = null
    private var btnCall: View? = null
    private var callIcon: View? = null
    private var callSkeleton: View? = null
    private var callPanel: View? = null
    private var tvCallStatus: TextView? = null
    private var tvCallPeer: TextView? = null
    private var btnPickup: TextView? = null
    private var btnHangup: TextView? = null
    private var btnMute: TextView? = null
    private var btnHold: TextView? = null

    private var entered: String = ""
    private var station: String = DEFAULT_STATION
    private var calling: Boolean = false
    // Fails a call out of the "Connecting" state if the softphone never reports
    // progress (ringing/answered/error) — otherwise a call that can't originate
    // hangs on "Connecting" forever with no feedback.
    private var connectingTimeoutJob: Job? = null
    // Ringback tone played while the destination is ringing (outbound), so the
    // agent hears that the call is forwarded and ringing. Stopped on answer/end.
    private var ringbackTone: ToneGenerator? = null
    private var dialerConfig: MobileDialerConfigResponse? = null
    private var pendingModernDialerPhone: String? = null
    private var callStage: CallStage = CallStage.IDLE
    private var activeNumber: String? = null
    private var muted: Boolean = false
    private var held: Boolean = false

    private enum class CallStage { IDLE, INCOMING, CONNECTING, IN_CALL }

    private val dialerEventListener: (DialerEvent) -> Unit = { event ->
        if (isAdded) {
            requireActivity().runOnUiThread { handleDialerEvent(event) }
        }
    }

    private val requestMicrophonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val phone = pendingModernDialerPhone
        pendingModernDialerPhone = null
        if (granted && phone != null) {
            triggerModernDialerOrFallback(phone)
        } else {
            Toast.makeText(
                requireContext(),
                "Microphone permission is required for Modern Dialer calls",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val keys = listOf(
        "1" to "",
        "2" to "ABC",
        "3" to "DEF",
        "4" to "GHI",
        "5" to "JKL",
        "6" to "MNO",
        "7" to "PQRS",
        "8" to "TUV",
        "9" to "WXYZ",
        "*" to "",
        "0" to "+",
        "#" to "",
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_dialer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        session = SessionManager(requireContext())
        station = prefs.getString(KEY_STATION, DEFAULT_STATION) ?: DEFAULT_STATION

        view.findViewById<View>(R.id.btnDialerBack).setOnClickListener {
            navigateUp()
        }
        view.findViewById<View>(R.id.btnDialerSettings).setOnClickListener { showStationDialog() }

        tvNumber = view.findViewById(R.id.tvDialerNumber)
        tvStation = view.findViewById(R.id.tvDialerStation)
        btnBackspace = view.findViewById(R.id.btnDialerBackspace)

        btnBackspace?.setOnClickListener { onBackspace() }
        btnBackspace?.setOnLongClickListener {
            entered = ""
            renderNumber()
            true
        }

        btnCall = view.findViewById(R.id.btnDialerCall)
        btnCall?.setOnClickListener { onCall() }
        callIcon = view.findViewById(R.id.ivDialerCallIcon)
        callSkeleton = view.findViewById(R.id.dialerCallingSkeleton)
        callPanel = view.findViewById(R.id.dialerCallPanel)
        tvCallStatus = view.findViewById(R.id.tvDialerCallStatus)
        tvCallPeer = view.findViewById(R.id.tvDialerCallPeer)
        btnPickup = view.findViewById(R.id.btnDialerPickup)
        btnHangup = view.findViewById(R.id.btnDialerHangup)
        btnMute = view.findViewById(R.id.btnDialerMute)
        btnHold = view.findViewById(R.id.btnDialerHold)

        btnPickup?.setOnClickListener {
            ModernDialerWebViewBridge.pickup()
            callStage = CallStage.CONNECTING
            renderCallPanel()
        }
        btnHangup?.setOnClickListener {
            ModernDialerWebViewBridge.hangup()
            resetCallState()
        }
        btnMute?.setOnClickListener {
            muted = !muted
            ModernDialerWebViewBridge.setMuted(muted)
            renderCallPanel()
        }
        btnHold?.setOnClickListener {
            held = !held
            ModernDialerWebViewBridge.setHold(held)
            renderCallPanel()
        }
        ModernDialerWebViewBridge.addListener(dialerEventListener)

        renderStation()
        renderNumber()
        renderCallPanel()
        buildDialpad(view.findViewById(R.id.dialpadGrid))
        loadDialerConfig()
    }

    override fun onResume() {
        super.onResume()
        // White system status bar + dark icons to match the white in-fragment header.
        (activity as? MainActivity)?.setTopBarAppearance(Color.WHITE, true)
        (activity as? MainActivity)?.setTabBarVisible(false)
    }

    override fun onPause() {
        (activity as? MainActivity)?.setTopBarAppearance(
            Color.parseColor("#FEFEFE"), true
        )
        (activity as? MainActivity)?.setTabBarVisible(true)
        super.onPause()
    }

    override fun onDestroyView() {
        ModernDialerWebViewBridge.removeListener(dialerEventListener)
        cancelConnectingTimeout()
        stopRingback()
        tvNumber = null
        tvStation = null
        btnBackspace = null
        btnCall = null
        callIcon = null
        callSkeleton = null
        callPanel = null
        tvCallStatus = null
        tvCallPeer = null
        btnPickup = null
        btnHangup = null
        btnMute = null
        btnHold = null
        super.onDestroyView()
    }

    private fun buildDialpad(grid: LinearLayout) {
        grid.removeAllViews()
        val keySizePx = dp(72)
        val rowMarginPx = dp(10)
        val keys2d = keys.chunked(3)
        for ((rowIdx, row) in keys2d.withIndex()) {
            val rowLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIdx > 0) topMargin = rowMarginPx
                }
            }
            for ((cellIdx, key) in row.withIndex()) {
                val cell = inflateKey(key.first, key.second, keySizePx)
                val params = LinearLayout.LayoutParams(0, keySizePx).apply {
                    weight = 1f
                    if (cellIdx > 0) leftMargin = rowMarginPx
                }
                val wrapper = FrameLayout(requireContext()).apply {
                    layoutParams = params
                    addView(cell)
                }
                rowLayout.addView(wrapper)
            }
            grid.addView(rowLayout)
        }
    }

    private fun inflateKey(digit: String, letters: String, sizePx: Int): View {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_dialer_key)
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx, Gravity.CENTER)
            setOnClickListener { onDigit(digit) }
        }
        val tvDigit = TextView(requireContext()).apply {
            text = digit
            setTextColor(resolveAttr(R.attr.colorForegroundPrimary))
            textSize = 24f
            try {
                typeface = androidx.core.content.res.ResourcesCompat
                    .getFont(requireContext(), R.font.inter_semibold)
            } catch (_: Exception) { /* default */ }
            gravity = Gravity.CENTER
        }
        container.addView(tvDigit)
        if (letters.isNotEmpty()) {
            val tvLetters = TextView(requireContext()).apply {
                text = letters
                setTextColor(resolveAttr(R.attr.colorForegroundMuted))
                textSize = 10f
                gravity = Gravity.CENTER
            }
            container.addView(tvLetters)
        }
        return container
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun onDigit(digit: String) {
        if (entered.length >= 15) return
        entered += digit
        renderNumber()
    }

    private fun onBackspace() {
        if (entered.isEmpty()) return
        entered = entered.dropLast(1)
        renderNumber()
    }

    private fun renderNumber() {
        tvNumber?.text = entered
        btnBackspace?.visibility = if (entered.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun renderStation() {
        val extension = dialerConfig?.mapping?.extension?.takeIf { it.isNotBlank() }
        tvStation?.text = extension ?: station
    }

    private fun renderCallPanel() {
        val visible = callStage != CallStage.IDLE
        callPanel?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        tvCallStatus?.text = when (callStage) {
            CallStage.INCOMING -> "Incoming call"
            CallStage.CONNECTING -> "Connecting"
            CallStage.IN_CALL -> "In call"
            CallStage.IDLE -> ""
        }
        tvCallPeer?.text = activeNumber?.takeIf { it.isNotBlank() } ?: "Modern Dialer"
        btnPickup?.visibility = if (callStage == CallStage.INCOMING) View.VISIBLE else View.GONE
        btnHangup?.text = if (callStage == CallStage.INCOMING) "Reject" else "Hang up"
        btnMute?.text = if (muted) "Unmute" else "Mute"
        btnHold?.text = if (held) "Resume" else "Hold"
        btnMute?.isEnabled = callStage == CallStage.IN_CALL
        btnHold?.isEnabled = callStage == CallStage.IN_CALL
        btnMute?.alpha = if (callStage == CallStage.IN_CALL) 1f else 0.45f
        btnHold?.alpha = if (callStage == CallStage.IN_CALL) 1f else 0.45f
    }

    private fun showStationDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = DEFAULT_STATION
            setText(station)
            setSelection(text.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Station number")
            .setMessage("Doocti station to bridge calls through.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val cleaned = input.text.toString().filter { it.isDigit() }.take(15)
                station = cleaned.ifBlank { DEFAULT_STATION }
                prefs.edit().putString(KEY_STATION, station).apply()
                renderStation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onCall() {
        if (calling) return
        val digits = entered.filter { it.isDigit() }
        if (digits.length < 10) {
            Toast.makeText(
                requireContext(),
                "Enter a valid phone number (min 10 digits)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        triggerModernDialerOrFallback(digits)
    }

    private fun loadDialerConfig() {
        if (!session.isLoggedIn) return
        tvStation?.text = "Checking mapping..."
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { api.getMobileDialerConfig(session.bearerToken) }
                .onSuccess {
                    dialerConfig = it
                    renderStation()
                }
                .onFailure {
                    dialerConfig = null
                    renderStation()
                }
        }
    }

    private fun triggerModernDialerOrFallback(phone: String) {
        val config = dialerConfig
        val token = config?.mapping?.token?.takeIf { it.isNotBlank() }
        val extension = config?.mapping?.extension?.takeIf { it.isNotBlank() }
        if (config?.configured == true && token != null && extension != null) {
            if (!hasMicrophonePermission()) {
                pendingModernDialerPhone = phone
                requestMicrophonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            triggerModernDialer(phone, config)
        } else {
            triggerDoocti(phone, station)
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun triggerModernDialer(
        phone: String,
        config: MobileDialerConfigResponse,
    ) {
        calling = true
        callStage = CallStage.CONNECTING
        activeNumber = phone
        renderCallPanel()
        startConnectingTimeout()
        btnCall?.isEnabled = false
        callIcon?.visibility = View.INVISIBLE
        callSkeleton?.visibility = View.VISIBLE
        callSkeleton?.let { SkeletonUtils.startSkeletonPulse(it) }
        Toast.makeText(requireContext(), "Placing call...", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                ModernDialerWebViewBridge.ensureLoaded(requireContext(), config)
                ModernDialerWebViewBridge.call(phone)
                Toast.makeText(requireContext(), "Calling $phone...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Modern Dialer failed, trying fallback...",
                    Toast.LENGTH_SHORT,
                ).show()
                val fallback = api.dialDoocti(
                    DOOCTI_URL,
                    DialDooctiRequest(phone_number = phone, station = station),
                )
                val ok = fallback.ok == true
                val msg = if (ok) {
                    "Fallback call placed - your phone will ring shortly"
                } else {
                    "Fallback failed: ${fallback.error ?: fallback.stage ?: "unknown"}"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } finally {
                calling = false
                btnCall?.isEnabled = true
                callSkeleton?.let { SkeletonUtils.stopSkeletonPulse(it) }
                callSkeleton?.visibility = View.GONE
                callIcon?.visibility = View.VISIBLE
            }
        }
    }

    private fun triggerDoocti(phone: String, stationNumber: String) {
        calling = true
        btnCall?.isEnabled = false
        callIcon?.visibility = View.INVISIBLE
        callSkeleton?.visibility = View.VISIBLE
        callSkeleton?.let { SkeletonUtils.startSkeletonPulse(it) }
        Toast.makeText(requireContext(), "Placing call…", Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = api.dialDoocti(
                    DOOCTI_URL,
                    DialDooctiRequest(phone_number = phone, station = stationNumber),
                )
                val ok = resp.ok == true
                val msg = if (ok) {
                    "Call placed — your phone will ring shortly"
                } else {
                    "Call failed: ${resp.error ?: resp.stage ?: "unknown"}"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Network error: ${e.message ?: "unknown"}",
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                calling = false
                btnCall?.isEnabled = true
                callSkeleton?.let { SkeletonUtils.stopSkeletonPulse(it) }
                callSkeleton?.visibility = View.GONE
                callIcon?.visibility = View.VISIBLE
            }
        }
    }

    private fun handleDialerEvent(event: DialerEvent) {
        when (event.type) {
            "ready" -> Unit
            "call:incoming" -> {
                activeNumber = event.stringPayload("from") ?: "Incoming call"
                callStage = CallStage.INCOMING
                muted = false
                held = false
                renderCallPanel()
            }
            "call:ringing-out" -> {
                // The softphone originated and the line is ringing — the call
                // is progressing, so stop the connect-failure timeout and play
                // the ringback so the agent hears it forwarding + ringing.
                cancelConnectingTimeout()
                startRingback()
                activeNumber = event.stringPayload("to") ?: activeNumber
                callStage = CallStage.CONNECTING
                renderCallPanel()
            }
            "call:picked-up" -> {
                cancelConnectingTimeout()
                stopRingback()
                callStage = CallStage.CONNECTING
                renderCallPanel()
            }
            "call:answered" -> {
                cancelConnectingTimeout()
                stopRingback()
                activeNumber = event.stringPayload("from")
                    ?: event.stringPayload("to")
                    ?: activeNumber
                callStage = CallStage.IN_CALL
                renderCallPanel()
            }
            "call:ended", "call:error", "call:incoming-suppressed" -> resetCallState()
        }
    }

    private fun resetCallState() {
        cancelConnectingTimeout()
        stopRingback()
        callStage = CallStage.IDLE
        activeNumber = null
        muted = false
        held = false
        renderCallPanel()
    }

    /** Guards against a call that never originates (softphone can't build the
     *  WebRTC offer / isn't registered): if no progress event arrives, fail out
     *  of "Connecting" instead of hanging there forever. Cancelled the moment
     *  any progress event (ringing/picked-up/answered/ended) is received. */
    private fun startConnectingTimeout() {
        cancelConnectingTimeout()
        connectingTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (callStage == CallStage.CONNECTING) {
                calling = false
                btnCall?.isEnabled = true
                Toast.makeText(
                    requireContext(),
                    "Call didn't connect. The dialer couldn't reach the line — check the station/registration and try again.",
                    Toast.LENGTH_LONG,
                ).show()
                resetCallState()
            }
        }
    }

    private fun cancelConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
    }

    /** Plays the standard ringback tone (looping) while the call is ringing so
     *  the agent knows it's forwarded and ringing. Safe to call repeatedly. */
    private fun startRingback() {
        if (ringbackTone != null) return
        ringbackTone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }.getOrNull()
    }

    private fun stopRingback() {
        ringbackTone?.let { runCatching { it.stopTone(); it.release() } }
        ringbackTone = null
    }

    private fun DialerEvent.stringPayload(key: String): String? {
        return payload[key] as? String
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "mconnect_prefs"
        private const val KEY_STATION = "dialer.station"
        private const val DEFAULT_STATION = "6369487527"
        // How long to wait for the softphone to report call progress before
        // giving up on a stuck "Connecting".
        private const val CONNECTING_TIMEOUT_MS = 40_000L
        private val DOOCTI_URL: String
            get() = com.manjugroups.m_connect.BuildConfig.APP_URL.trimEnd('/') + "/api/doocti-call"
    }
}
