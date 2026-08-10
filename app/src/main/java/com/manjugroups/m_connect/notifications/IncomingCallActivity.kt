package com.manjugroups.m_connect.notifications

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.manjugroups.m_connect.R

class IncomingCallActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareLockScreenDisplay()

        when (intent.action) {
            ModernDialerCallController.ACTION_PICKUP,
            ModernDialerCallController.ACTION_REJECT -> {
                startService(
                    ModernDialerCallController.serviceIntent(this, intent, intent.action!!)
                )
                finish()
                return
            }
        }

        val displayName = intent.getStringExtra(ModernDialerCallController.EXTRA_DISPLAY_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "Incoming call"
        val number = intent.getStringExtra(ModernDialerCallController.EXTRA_FROM_NUMBER)
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown number"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(40), dp(28), dp(40))
            setBackgroundColor(resolveAttr(R.attr.colorSurfacePrimary))
        }

        val title = TextView(this).apply {
            text = displayName
            textSize = 28f
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
            gravity = Gravity.CENTER
            setPadding(0, dp(38), 0, 0)
        }

        val reject = MaterialButton(this).apply {
            text = "Reject"
            setTextColor(resolveAttr(R.attr.colorError))
            strokeColor = android.content.res.ColorStateList.valueOf(resolveAttr(R.attr.colorError))
            strokeWidth = dp(1)
            setOnClickListener {
                startService(
                    ModernDialerCallController.serviceIntent(
                        this@IncomingCallActivity,
                        intent,
                        ModernDialerCallController.ACTION_REJECT,
                    )
                )
                finish()
            }
        }
        val accept = MaterialButton(this).apply {
            text = "Accept"
            setTextColor(android.graphics.Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(resolveAttr(R.attr.colorSuccess))
            setOnClickListener {
                startService(
                    ModernDialerCallController.serviceIntent(
                        this@IncomingCallActivity,
                        intent,
                        ModernDialerCallController.ACTION_PICKUP,
                    )
                )
                finish()
            }
        }

        actions.addView(reject, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginEnd = dp(8)
        })
        actions.addView(accept, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginStart = dp(8)
        })
        root.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(subtitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) })
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        setContentView(root)
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
}
