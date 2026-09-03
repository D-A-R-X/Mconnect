package com.manjugroups.m_connect.notifications

import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibrationAttributes
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IncomingCallActivity : AppCompatActivity() {
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var acceptButton: MaterialButton? = null
    private var rejectButton: MaterialButton? = null
    private var callerAvatar: TextView? = null
    private var callerTitle: TextView? = null
    private var callerNumber: TextView? = null
    private val dialerListener: (DialerEvent) -> Unit = { event ->
        if (event.type in TERMINAL_CALL_EVENTS) {
            runOnUiThread {
                stopIncomingAlert()
                timeoutHandler.removeCallbacks(timeoutAction)
                ModernDialerCallController.clearIncoming(this)
                finish()
            }
        }
    }
    private val timeoutAction = Runnable {
        stopIncomingAlert()
        ModernDialerCallController.clearIncoming(this)
        finish()
    }

    override fun onDestroy() {
        ModernDialerWebViewBridge.removeListener(dialerListener)
        timeoutHandler.removeCallbacks(timeoutAction)
        stopIncomingAlert()
        ModernDialerWebViewBridge.detachFromActivity(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ModernDialerCallController.ACTION_PICKUP -> acceptButton?.let {
                performCallAction(ModernDialerCallController.ACTION_PICKUP, it)
            }
            ModernDialerCallController.ACTION_REJECT -> rejectButton?.let {
                performCallAction(ModernDialerCallController.ACTION_REJECT, it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModernDialerWebViewBridge.addListener(dialerListener)
        prepareLockScreenDisplay()

        val displayName = intent.getStringExtra(ModernDialerCallController.EXTRA_DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown caller"
        val number = intent.getStringExtra(ModernDialerCallController.EXTRA_FROM_NUMBER)
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown number"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(resolveAttr(R.attr.colorSurfacePrimary))
        }

        val appName = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 17f
            setTextColor(resolveAttr(R.attr.colorForegroundPrimary))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@IncomingCallActivity,
                R.font.inter_bold,
            )
        }
        val incomingLabel = TextView(this).apply {
            text = "Incoming call"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(resolveAttr(R.attr.colorForegroundMuted))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@IncomingCallActivity,
                R.font.inter_semibold,
            )
        }
        val avatar = TextView(this).apply {
            text = if (displayName == "Unknown caller") "?" else displayName.trim().firstOrNull()?.uppercase() ?: "?"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(resolveAttr(R.attr.colorAccentPrimary))
            }
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@IncomingCallActivity,
                R.font.inter_bold,
            )
        }
        val title = TextView(this).apply {
            text = displayName.replace('_', ' ')
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(resolveAttr(R.attr.colorForegroundPrimary))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@IncomingCallActivity,
                R.font.inter_bold,
            )
        }
        val subtitle = TextView(this).apply {
            text = number
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(resolveAttr(R.attr.colorForegroundMuted))
            typeface = androidx.core.content.res.ResourcesCompat.getFont(
                this@IncomingCallActivity,
                R.font.geist_mono_semibold,
            )
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(48), dp(12), 0)
        }

        val reject = MaterialButton(this).apply {
            text = ""
            icon = androidx.core.content.ContextCompat.getDrawable(this@IncomingCallActivity, R.drawable.ic_phone_down_white)
            iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            iconSize = dp(28)
            iconPadding = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(resolveAttr(R.attr.colorError))
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(40).toFloat()).build()
            contentDescription = "Reject call"
            setOnClickListener {
                performCallAction(ModernDialerCallController.ACTION_REJECT, this)
            }
        }
        val accept = MaterialButton(this).apply {
            text = ""
            icon = androidx.core.content.ContextCompat.getDrawable(this@IncomingCallActivity, R.drawable.ic_phone_outline)
            iconTint = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            iconSize = dp(28)
            iconPadding = 0
            backgroundTintList = android.content.res.ColorStateList.valueOf(resolveAttr(R.attr.colorSuccess))
            shapeAppearanceModel = shapeAppearanceModel.toBuilder().setAllCornerSizes(dp(40).toFloat()).build()
            contentDescription = "Accept call"
            setOnClickListener {
                performCallAction(ModernDialerCallController.ACTION_PICKUP, this)
            }
        }

        fun actionColumn(
            button: MaterialButton,
            label: String,
            horizontalGravity: Int,
        ): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = horizontalGravity or Gravity.CENTER_VERTICAL
            addView(button, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(TextView(this@IncomingCallActivity).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(resolveAttr(R.attr.colorForegroundPrimary))
                typeface = androidx.core.content.res.ResourcesCompat.getFont(this@IncomingCallActivity, R.font.inter_medium)
            }, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        actions.addView(
            actionColumn(reject, "Decline", Gravity.START),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            actionColumn(accept, "Answer", Gravity.END),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            setBackgroundColor(resolveAttr(R.attr.colorSurfaceSecondary))
            addView(android.widget.ImageView(this@IncomingCallActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                contentDescription = null
            }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@IncomingCallActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(appName, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                addView(TextView(this@IncomingCallActivity).apply {
                    text = "MMS Dialer"
                    textSize = 12f
                    setTextColor(resolveAttr(R.attr.colorForegroundMuted))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) })
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), 0, dp(28), dp(24))
        }
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.65f))
        content.addView(incomingLabel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        content.addView(avatar, LinearLayout.LayoutParams(dp(104), dp(104)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(22)
            bottomMargin = dp(24)
        })
        content.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        content.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        content.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.35f))
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(78),
        ))
        root.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        setContentView(root)
        callerAvatar = avatar
        callerTitle = title
        callerNumber = subtitle

        timeoutHandler.postDelayed(
            timeoutAction,
            ModernDialerCallController.timeoutMillis(
                intent.getStringExtra(ModernDialerCallController.EXTRA_EXPIRES_AT),
            ),
        )

        if (intent.action == ModernDialerCallController.ACTION_SHOW) {
            startIncomingAlert()
        }
        rejectButton = reject
        acceptButton = accept
        refreshCurrentCall()

        when (intent.action) {
            ModernDialerCallController.ACTION_PICKUP -> performCallAction(intent.action!!, accept)
            ModernDialerCallController.ACTION_REJECT -> performCallAction(intent.action!!, reject)
        }
    }

    private fun performCallAction(action: String, button: MaterialButton) {
        if (!button.isEnabled) return
        stopIncomingAlert()
        timeoutHandler.removeCallbacks(timeoutAction)
        acceptButton?.isEnabled = false
        rejectButton?.isEnabled = false
        button.isEnabled = false
        button.alpha = 0.65f
        ModernDialerCallController.clearIncoming(this)

        lifecycleScope.launch {
            try {
                val session = SessionManager(applicationContext)
                check(session.isLoggedIn) { "Login required" }
                val callId = intent.getStringExtra(ModernDialerCallController.EXTRA_CALL_ID)
                    ?.takeIf { it.isNotBlank() }
                    ?: error("Call ID missing")
                val eventId = intent.getStringExtra(ModernDialerCallController.EXTRA_EVENT_ID)
                val api = ApiService.create()
                val deviceId = MobileDialerApiCoordinator.deviceId(applicationContext)
                val serverAction = when (action) {
                    ModernDialerCallController.ACTION_PICKUP -> "pickup"
                    ModernDialerCallController.ACTION_REJECT -> "reject"
                    else -> error("Unsupported dialer action")
                }
                val actionResponse = withContext(Dispatchers.IO) {
                    MobileDialerApiCoordinator.performAction(
                        api = api,
                        token = session.bearerToken,
                        callId = callId,
                        action = serverAction,
                        deviceId = deviceId,
                        eventId = eventId,
                    )
                }
                check(actionResponse.success) {
                    actionResponse.error ?: "Dialer action was not accepted"
                }
                when (action) {
                    ModernDialerCallController.ACTION_PICKUP -> {
                        val config = withContext(Dispatchers.IO) {
                            api.getMobileDialerConfig(session.bearerToken)
                        }
                        check(config.configured && !config.mapping?.token.isNullOrBlank()) {
                            "Modern Dialer is not configured"
                        }
                        ModernDialerWebViewBridge.ensureLoaded(this@IncomingCallActivity, config)
                        ModernDialerWebViewBridge.pickup()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(
                                ModernDialerCallController.serviceIntent(
                                    this@IncomingCallActivity,
                                    intent,
                                    ModernDialerCallController.ACTION_KEEP_ACTIVE,
                                )
                            )
                        } else {
                            startService(
                                ModernDialerCallController.serviceIntent(
                                    this@IncomingCallActivity,
                                    intent,
                                    ModernDialerCallController.ACTION_KEEP_ACTIVE,
                                )
                            )
                        }
                    }
                    ModernDialerCallController.ACTION_REJECT -> {
                        ModernDialerWebViewBridge.reject()
                        ModernDialerCallController.clearCallNotifications(this@IncomingCallActivity)
                        stopService(
                            android.content.Intent(
                                this@IncomingCallActivity,
                                ModernDialerCallService::class.java,
                            ),
                        )
                        finish()
                    }
                }
            } catch (error: Exception) {
                acceptButton?.isEnabled = true
                rejectButton?.isEnabled = true
                acceptButton?.alpha = 1f
                rejectButton?.alpha = 1f
                if (!isFinishing) startIncomingAlert()
                android.widget.Toast.makeText(
                    this@IncomingCallActivity,
                    when (error) {
                        is java.net.UnknownHostException -> "No network. Reconnect and retry."
                        else -> error.message?.takeIf { it.isNotBlank() }
                            ?: "Dialer action failed. Check your network and retry."
                    },
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun refreshCurrentCall() {
        val callId = intent.getStringExtra(ModernDialerCallController.EXTRA_CALL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        lifecycleScope.launch {
            val session = SessionManager(applicationContext)
            if (!session.isLoggedIn) return@launch
            val response = withContext(Dispatchers.IO) {
                runCatching {
                    ApiService.create().getMobileDialerCurrentCall(
                        session.bearerToken,
                        callId,
                    )
                }.getOrNull()
            } ?: return@launch
            val call = response.call
            if (response.success && call == null) {
                stopIncomingAlert()
                ModernDialerCallController.clearCallNotifications(this@IncomingCallActivity)
                finish()
                return@launch
            }
            if (call == null || call.callId != callId) return@launch
            if (call.stage?.lowercase() in TERMINAL_CALL_STAGES) {
                stopIncomingAlert()
                ModernDialerCallController.clearCallNotifications(this@IncomingCallActivity)
                finish()
                return@launch
            }
            call.displayName?.takeIf { it.isNotBlank() }?.let { updateCallerName(it) }
            val number = call.fromNumber?.takeIf { it.isNotBlank() }
                ?: call.toNumber?.takeIf { it.isNotBlank() }
            number?.let {
                callerNumber?.text = it
                intent.putExtra(ModernDialerCallController.EXTRA_FROM_NUMBER, it)
            }
            call.expiresAt?.takeIf { it.isNotBlank() }?.let { expiresAt ->
                intent.putExtra(ModernDialerCallController.EXTRA_EXPIRES_AT, expiresAt)
                timeoutHandler.removeCallbacks(timeoutAction)
                timeoutHandler.postDelayed(
                    timeoutAction,
                    ModernDialerCallController.timeoutMillis(expiresAt),
                )
            }
        }
    }

    private fun updateCallerName(displayName: String) {
        val clean = displayName.replace('_', ' ').trim()
        if (clean.isBlank()) return
        callerTitle?.text = clean
        callerAvatar?.text = clean.firstOrNull()?.uppercase() ?: "?"
        intent.putExtra(ModernDialerCallController.EXTRA_DISPLAY_NAME, clean)
    }

    private fun startIncomingAlert() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = runCatching {
            RingtoneManager.getRingtone(applicationContext, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
                play()
            }
        }.getOrNull()

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.takeIf { it.hasVibrator() }?.let { device ->
            val pattern = longArrayOf(0, 700, 350, 700)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION),
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                device.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                device.vibrate(pattern, 0)
            }
        }
    }

    private fun stopIncomingAlert() {
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator?.cancel() }
        vibrator = null
    }

    private fun prepareLockScreenDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(KEYGUARD_SERVICE) as? KeyguardManager)
                ?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun resolveAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val TERMINAL_CALL_EVENTS = setOf(
            "call:ended",
            "call:error",
            "call:incoming-suppressed",
        )
        val TERMINAL_CALL_STAGES = setOf(
            "ended",
            "completed",
            "failed",
            "rejected",
            "expired",
            "cancelled",
        )
    }
}
