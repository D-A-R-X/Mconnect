package com.manjugroups.m_connect.ui.telecaller

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.MobileDialerConfigResponse
import com.manjugroups.m_connect.notifications.DialerEvent
import com.manjugroups.m_connect.notifications.ModernDialerWebViewBridge
import com.manjugroups.m_connect.notifications.ModernDialerCallController
import com.manjugroups.m_connect.ui.common.SkeletonUtils
import com.manjugroups.m_connect.ui.common.navigateUp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phone dialpad — mirrors the web Telecaller > Dialer screen. It fetches the
 * authenticated Modern Dialer mapping from Convex, then controls the embedded
 * dialer through the same postMessage protocol used by web.
 */
class DialerFragment : Fragment() {

    private val api = ApiService.create()
    private lateinit var session: SessionManager

    private var tvNumber: TextView? = null
    private var tvStation: TextView? = null
    private var tvConnection: TextView? = null
    private var btnAvailable: TextView? = null
    private var btnBreak: TextView? = null
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
    private var btnPhoneAudio: TextView? = null
    private var btnSpeakerAudio: TextView? = null
    private var btnBluetoothAudio: TextView? = null
    private var historyList: LinearLayout? = null
    private var historyEmpty: TextView? = null

    private var entered: String = ""
    private var calling: Boolean = false
    // Fails a call out of the "Connecting" state if the softphone never reports
    // progress (ringing/answered/error) — otherwise a call that can't originate
    // hangs on "Connecting" forever with no feedback.
    private var connectingTimeoutJob: Job? = null
    private var dialerConfig: MobileDialerConfigResponse? = null
    private var pendingModernDialerPhone: String? = null
    private var callStage: CallStage = CallStage.IDLE
    private var activeNumber: String? = null
    private var muted: Boolean = false
    private var held: Boolean = false
    private var agentStatus: String = "unknown"
    private var audioRoute: AudioRoute = AudioRoute.PHONE
    private lateinit var recentCallStore: DialerRecentCallStore

    private enum class CallStage { IDLE, INCOMING, CONNECTING, IN_CALL }
    private enum class AudioRoute { PHONE, SPEAKER, BLUETOOTH }

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
            triggerModernDialer(phone, dialerConfig ?: return@registerForActivityResult)
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
        session = SessionManager(requireContext())
        recentCallStore = DialerRecentCallStore(requireContext(), session.staffId)
        entered = arguments?.getString(ARG_PHONE)
            ?.filter { it.isDigit() }
            ?.take(15)
            .orEmpty()

        view.findViewById<View>(R.id.btnDialerBack).setOnClickListener {
            navigateUp()
        }
        tvNumber = view.findViewById(R.id.tvDialerNumber)
        tvStation = view.findViewById(R.id.tvDialerStation)
        tvConnection = view.findViewById(R.id.tvDialerConnection)
        btnAvailable = view.findViewById(R.id.btnDialerAvailable)
        btnBreak = view.findViewById(R.id.btnDialerBreak)
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
        btnPhoneAudio = view.findViewById(R.id.btnDialerPhoneAudio)
        btnSpeakerAudio = view.findViewById(R.id.btnDialerSpeakerAudio)
        btnBluetoothAudio = view.findViewById(R.id.btnDialerBluetoothAudio)
        historyList = view.findViewById(R.id.dialerHistoryList)
        historyEmpty = view.findViewById(R.id.tvDialerHistoryEmpty)

        btnPickup?.setOnClickListener {
            ModernDialerWebViewBridge.pickup()
            callStage = CallStage.CONNECTING
            renderCallPanel()
        }
        btnHangup?.setOnClickListener {
            ModernDialerWebViewBridge.hangup()
            resetCallState(if (callStage == CallStage.INCOMING) "missed" else "completed")
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
        btnPhoneAudio?.setOnClickListener { selectAudioRoute(AudioRoute.PHONE) }
        btnSpeakerAudio?.setOnClickListener { selectAudioRoute(AudioRoute.SPEAKER) }
        btnBluetoothAudio?.setOnClickListener {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                requestBluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                selectAudioRoute(AudioRoute.BLUETOOTH)
            }
        }
        btnAvailable?.setOnClickListener { setAgentStatus("available") }
        btnBreak?.setOnClickListener { setAgentStatus("break") }
        ModernDialerWebViewBridge.addListener(dialerEventListener)

        if (ModernDialerWebViewBridge.hasActiveCall()) {
            activeNumber = ModernDialerWebViewBridge.activeCallNumber()
            callStage = if (ModernDialerWebViewBridge.isCallAnswered()) {
                CallStage.IN_CALL
            } else {
                CallStage.CONNECTING
            }
        }

        renderDialerIdentity()
        renderAgentStatus()
        renderNumber()
        renderCallPanel()
        renderRecentCalls()
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
        if (!ModernDialerWebViewBridge.hasActiveCall()) {
            ModernDialerWebViewBridge.detachFromActivity(requireActivity())
        }
        tvNumber = null
        tvStation = null
        tvConnection = null
        btnAvailable = null
        btnBreak = null
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
        btnPhoneAudio = null
        btnSpeakerAudio = null
        btnBluetoothAudio = null
        historyList = null
        historyEmpty = null
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

    private fun renderDialerIdentity() {
        val extension = dialerConfig?.mapping?.extension?.takeIf { it.isNotBlank() }
        val staffName = dialerConfig?.staff?.name?.takeIf { it.isNotBlank() }
        tvStation?.text = when {
            dialerConfig == null -> "Checking your dialer access..."
            dialerConfig?.configured != true -> "Modern Dialer is not configured for this account"
            else -> listOfNotNull(staffName, extension?.let { "Extension $it" }).joinToString("  •  ")
        }
        tvConnection?.text = when {
            dialerConfig?.configured != true -> "Unavailable"
            ModernDialerWebViewBridge.isPhoneReady() -> "Ready"
            else -> "Connecting"
        }
        val enabled = dialerConfig?.configured == true
        btnAvailable?.isEnabled = enabled
        btnBreak?.isEnabled = enabled
        btnAvailable?.alpha = if (enabled) 1f else 0.5f
        btnBreak?.alpha = if (enabled) 1f else 0.5f
    }

    private val requestBluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) selectAudioRoute(AudioRoute.BLUETOOTH)
        else Toast.makeText(requireContext(), "Bluetooth permission is required to use a headset", Toast.LENGTH_LONG).show()
    }

    private fun renderAgentStatus() {
        val available = agentStatus == "available"
        val onBreak = agentStatus == "break"
        val configured = dialerConfig?.configured == true
        btnAvailable?.setBackgroundResource(if (available && configured) R.drawable.bg_attendance_segment_active else 0)
        btnBreak?.setBackgroundResource(if (onBreak && configured) R.drawable.bg_attendance_segment_active else 0)
        btnAvailable?.setTextColor(resolveAttr(if (available && configured) R.attr.colorForegroundPrimary else R.attr.colorForegroundMuted))
        btnBreak?.setTextColor(resolveAttr(if (onBreak && configured) R.attr.colorForegroundPrimary else R.attr.colorForegroundMuted))
    }

    private fun setAgentStatus(status: String) {
        if (dialerConfig?.configured != true) {
            Toast.makeText(requireContext(), "Modern Dialer is not configured for this account", Toast.LENGTH_LONG).show()
            return
        }
        ModernDialerWebViewBridge.setAgentStatus(status)
    }

    private fun renderCallPanel() {
        val visible = callStage != CallStage.IDLE
        callPanel?.visibility = if (visible) View.VISIBLE else View.GONE
        tvNumber?.visibility = if (visible) View.GONE else View.VISIBLE
        btnBackspace?.visibility = if (visible || entered.isEmpty()) View.INVISIBLE else View.VISIBLE
        btnCall?.visibility = if (visible) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.dialpadGrid)?.visibility = if (visible) View.GONE else View.VISIBLE
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
        renderAudioRoute()
    }

    private fun onCall() {
        if (calling) return
        if (agentStatus != "available") {
            Toast.makeText(
                requireContext(),
                "Switch to Available before placing a call",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val digits = entered.filter { it.isDigit() }
        if (digits.length < 10) {
            Toast.makeText(
                requireContext(),
                "Enter a valid phone number (min 10 digits)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        triggerModernDialerWhenReady(digits)
    }

    private fun loadDialerConfig() {
        if (!session.isLoggedIn) return
        tvStation?.text = "Checking your dialer access..."
        viewLifecycleOwner.lifecycleScope.launch {
            val cfg = fetchDialerConfigWithRetry()
            dialerConfig = cfg
            renderDialerIdentity()
            renderAgentStatus()
            if (cfg?.configured == true) {
                ModernDialerWebViewBridge.ensureLoaded(requireContext(), cfg)
                ModernDialerWebViewBridge.requestState()
                promptForFullScreenCallAccessIfNeeded()
            }
        }
    }

    private fun promptForFullScreenCallAccessIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val manager = requireContext().getSystemService(NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Allow incoming call screen")
            .setMessage(
                "Enable full-screen notifications so Modern Dialer calls can ring and open when this phone is locked.",
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                            data = android.net.Uri.parse("package:${requireContext().packageName}")
                        },
                    )
                }.onFailure {
                    Toast.makeText(
                        requireContext(),
                        "Open app settings and allow full-screen notifications.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .show()
    }

    /** The modern-dialer config is essential: the extension + WebRTC token come
     *  from it. A transient failure (the prod backend was returning 27s
     *  responses, past OkHttp's 30s timeout) must NOT be treated as "no mapping"
     *  Retry a few times before giving up. */
    private suspend fun fetchDialerConfigWithRetry(
        attempts: Int = 3,
    ): MobileDialerConfigResponse? {
        repeat(attempts) { i ->
            val r = runCatching { api.getMobileDialerConfig(session.bearerToken) }
                .getOrNull()
            if (r != null) return r
            if (i < attempts - 1) delay(1500)
        }
        return null
    }

    private fun triggerModernDialerWhenReady(phone: String) {
        val config = dialerConfig
        if (config != null) {
            routeCall(phone, config)
            return
        }
        // Config hasn't loaded yet (or a prior fetch failed transiently). Fetch
        // it inline before deciding, so a transient failure is not mistaken for
        // a missing account mapping.
        calling = true
        btnCall?.isEnabled = false
        tvStation?.text = "Checking your dialer access..."
        viewLifecycleOwner.lifecycleScope.launch {
            val fetched = fetchDialerConfigWithRetry()
            calling = false
            btnCall?.isEnabled = true
            if (fetched != null) {
                dialerConfig = fetched
                renderDialerIdentity()
                renderAgentStatus()
            }
            routeCall(phone, fetched)
        }
    }

    private fun routeCall(phone: String, config: MobileDialerConfigResponse?) {
        val token = config?.mapping?.token?.takeIf { it.isNotBlank() }
        val extension = config?.mapping?.extension?.takeIf { it.isNotBlank() }
        if (config?.configured == true && token != null && extension != null) {
            if (!hasMicrophonePermission()) {
                pendingModernDialerPhone = phone
                requestMicrophonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            triggerModernDialer(phone, config)
        } else if (config == null) {
            // Couldn't reach the config service at all; tell the user to retry.
            Toast.makeText(
                requireContext(),
                "Couldn't reach the dialer service. Check your connection and try again.",
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(
                requireContext(),
                "Modern Dialer is not configured for this account. Contact an administrator.",
                Toast.LENGTH_LONG,
            ).show()
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
                keepCallProcessActive(phone)
                ModernDialerWebViewBridge.ensureLoaded(requireContext(), config)
                if (!ModernDialerWebViewBridge.call(phone)) {
                    Toast.makeText(
                        requireContext(),
                        "Another dialer call is already active",
                        Toast.LENGTH_LONG,
                    ).show()
                    resetCallState()
                    return@launch
                }
                ModernDialerWebViewBridge.requestState()
                Toast.makeText(requireContext(), "Calling $phone...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Modern Dialer could not start. Please retry.", Toast.LENGTH_LONG).show()
                resetCallState()
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
            "ready", "phone:registered" -> tvConnection?.text = "Ready"
            "phone:unregistered" -> tvConnection?.text = "Offline"
            "phone:state", "phone:status" -> {
                val state = event.stringPayload("state") ?: event.stringPayload("status")
                tvConnection?.text = if (state == "registered") "Ready" else "Connecting"
            }
            "agent:status" -> {
                agentStatus = event.stringPayload("status") ?: agentStatus
                renderAgentStatus()
            }
            "agent:status-error" -> Toast.makeText(requireContext(), "Could not update dialer status", Toast.LENGTH_LONG).show()
            "call:incoming" -> {
                activeNumber = event.stringPayload("from") ?: "Incoming call"
                callStage = CallStage.INCOMING
                muted = false
                held = false
                renderCallPanel()
            }
            "call:ringing-out" -> {
                // The provider supplies the real remote caller tune. Do not
                // layer a synthetic ringback over it.
                activeNumber = event.stringPayload("to") ?: activeNumber
                callStage = CallStage.CONNECTING
                renderCallPanel()
            }
            "call:picked-up" -> {
                cancelConnectingTimeout()
                callStage = CallStage.CONNECTING
                renderCallPanel()
            }
            "call:answered" -> {
                cancelConnectingTimeout()
                activeNumber = event.stringPayload("from")
                    ?: event.stringPayload("to")
                    ?: activeNumber
                callStage = CallStage.IN_CALL
                renderCallPanel()
            }
            "call:progress" -> {
                when ((event.stringPayload("status") ?: event.stringPayload("state"))?.lowercase()) {
                    "answered", "connected", "in-call", "active" -> {
                        cancelConnectingTimeout()
                        callStage = CallStage.IN_CALL
                        renderCallPanel()
                    }
                    "ended", "completed", "hangup", "hung-up" -> resetCallState("completed")
                    "failed", "busy", "rejected", "unavailable" -> resetCallState("failed")
                }
            }
            "call:ended" -> resetCallState("completed")
            "call:error" -> {
                event.stringPayload("message")?.let { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
                resetCallState("failed")
            }
            "call:incoming-suppressed" -> resetCallState("missed")
            "media:restarting" -> {
                tvConnection?.text = "Reconnecting audio"
                Toast.makeText(requireContext(), "Reconnecting call audio...", Toast.LENGTH_SHORT).show()
            }
            "media:diagnostic-server" -> {
                val state = event.stringPayload("connectionState")
                tvConnection?.text = when (state?.lowercase()) {
                    "connected", "completed" -> "Audio connected"
                    else -> "Audio reconnecting"
                }
            }
            "media:error" -> {
                event.stringPayload("message")?.let { message ->
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
                tvConnection?.text = "Audio unavailable"
            }
        }
    }

    private fun resetCallState(finalStatus: String? = null) {
        cancelConnectingTimeout()
        callStage = CallStage.IDLE
        activeNumber = null
        muted = false
        held = false
        renderRecentCalls()
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
                    "Call didn't connect. Check your network and dialer status, then try again.",
                    Toast.LENGTH_LONG,
                ).show()
                ModernDialerWebViewBridge.cancelPendingCalls()
                resetCallState("failed")
            }
        }
    }

    private fun cancelConnectingTimeout() {
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = null
    }

    private fun selectAudioRoute(route: AudioRoute) {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (!applyAudioRoute(am, route)) {
            Toast.makeText(requireContext(), "No Bluetooth call device is connected", Toast.LENGTH_SHORT).show()
            return
        }
        audioRoute = route
        updateCallServiceAudioRoute(route)
        renderAudioRoute()
    }

    @Suppress("DEPRECATION")
    private fun applyAudioRoute(am: AudioManager, route: AudioRoute): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wantedTypes = when (route) {
                AudioRoute.PHONE -> setOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                AudioRoute.SPEAKER -> setOf(android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                AudioRoute.BLUETOOTH -> setOf(
                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                    android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
                )
            }
            val device = runCatching {
                am.availableCommunicationDevices.firstOrNull { it.type in wantedTypes }
            }.getOrNull() ?: return false
            return runCatching { am.setCommunicationDevice(device) }.getOrDefault(false)
        }
        return when (route) {
            AudioRoute.PHONE -> {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = false
                true
            }
            AudioRoute.SPEAKER -> {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.isSpeakerphoneOn = true
                true
            }
            AudioRoute.BLUETOOTH -> {
                if (!am.isBluetoothScoAvailableOffCall) return false
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                true
            }
        }
    }

    private fun renderAudioRoute() {
        listOf(
            AudioRoute.PHONE to btnPhoneAudio,
            AudioRoute.SPEAKER to btnSpeakerAudio,
            AudioRoute.BLUETOOTH to btnBluetoothAudio,
        ).forEach { (route, button) ->
            val selected = route == audioRoute
            button?.setBackgroundResource(if (selected) R.drawable.bg_attendance_segment_active else 0)
            button?.setTextColor(
                resolveAttr(if (selected) R.attr.colorForegroundPrimary else R.attr.colorForegroundMuted),
            )
        }
    }

    private fun renderRecentCalls() {
        val container = historyList ?: return
        val calls = recentCallStore.read()
        historyEmpty?.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
        container.removeAllViews()
        val formatter = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
        calls.take(8).forEach { call ->
            val row = TextView(requireContext()).apply {
                text = "${if (call.direction == "incoming") "Incoming" else "Outgoing"}  ${call.number}\n" +
                    "${formatter.format(Date(call.startedAt))}  •  ${call.status}  •  ${formatDuration(call.durationSeconds)}"
                setTextColor(resolveAttr(R.attr.colorForegroundPrimary))
                textSize = 13f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_task_inner_card)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    entered = call.number
                    renderNumber()
                }
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
    }

    private fun formatDuration(seconds: Long): String =
        if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

    private fun keepCallProcessActive(phone: String) {
        val source = Intent().apply {
            putExtra(ModernDialerCallController.EXTRA_CALL_ID, "outbound-${System.currentTimeMillis()}")
            putExtra(ModernDialerCallController.EXTRA_DISPLAY_NAME, "Outgoing call")
            putExtra(ModernDialerCallController.EXTRA_FROM_NUMBER, phone)
            putExtra(ModernDialerCallController.EXTRA_AUDIO_ROUTE, audioRoute.name)
        }
        ContextCompat.startForegroundService(
            requireContext(),
            ModernDialerCallController.serviceIntent(
                requireContext(),
                source,
                ModernDialerCallController.ACTION_KEEP_ACTIVE,
            ),
        )
    }

    private fun updateCallServiceAudioRoute(route: AudioRoute) {
        val intent = Intent(
            requireContext(),
            com.manjugroups.m_connect.notifications.ModernDialerCallService::class.java,
        ).apply {
            action = ModernDialerCallController.ACTION_SET_AUDIO_ROUTE
            putExtra(ModernDialerCallController.EXTRA_CALL_ID, activeNumber ?: "active-call")
            putExtra(ModernDialerCallController.EXTRA_AUDIO_ROUTE, route.name)
        }
        requireContext().startService(intent)
    }

    private fun DialerEvent.stringPayload(key: String): String? {
        return payload[key] as? String
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_PHONE = "dialer.phone"
        // How long to wait for the softphone to report call progress before
        // giving up on a stuck "Connecting".
        private const val CONNECTING_TIMEOUT_MS = 40_000L

        fun newInstance(phone: String): DialerFragment = DialerFragment().apply {
            arguments = Bundle().apply { putString(ARG_PHONE, phone) }
        }
    }
}
