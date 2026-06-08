package com.manjugroups.m_connect

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.manjugroups.m_connect.auth.ForcePasswordChangeActivity
import com.manjugroups.m_connect.auth.LoginActivity
import com.manjugroups.m_connect.auth.OnboardingPrefs
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.auth.WelcomeActivity
import com.manjugroups.m_connect.databinding.ActivitySplashBinding

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private var navigated = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawableResource(android.R.color.white)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // First-launch experience (logo video + onboarding screens) shows once.
        // Subsequent launches skip straight to login/main.
        if (OnboardingPrefs(this).onboardingCompleted) {
            binding.videoSplash.visibility = android.view.View.INVISIBLE
            navigateNext()
            return
        }

        val videoUri = Uri.parse("android.resource://$packageName/${R.raw.logo}")
        binding.videoSplash.setVideoURI(videoUri)

        // Hide VideoView a bit before end → white window bg shows → navigate. Eliminates the
        // black VideoView frame between video completion and the next Activity rendering.
        binding.videoSplash.setOnPreparedListener { mp ->
            mp.setOnInfoListener { _, _, _ -> false }
            val durationMs = mp.duration.coerceAtLeast(0)
            val hideAt = (durationMs - 400L).coerceAtLeast(0L)
            handler.postDelayed({
                binding.videoSplash.visibility = android.view.View.INVISIBLE
            }, hideAt)
            val navAt = (durationMs - 200L).coerceAtLeast(0L)
            handler.postDelayed({ navigateNext() }, navAt)
        }

        binding.videoSplash.setOnCompletionListener { navigateNext() }
        binding.videoSplash.setOnErrorListener { _, _, _ ->
            navigateNext()
            true
        }

        binding.videoSplash.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun navigateNext() {
        if (navigated) return
        navigated = true
        handler.removeCallbacksAndMessages(null)

        val session = SessionManager(this)
        val onboarded = OnboardingPrefs(this).onboardingCompleted
        val next = when {
            session.isLoggedIn && session.mustChangePassword -> ForcePasswordChangeActivity::class.java
            session.isLoggedIn -> MainActivity::class.java
            onboarded -> LoginActivity::class.java
            else -> WelcomeActivity::class.java
        }
        startActivity(Intent(this, next))
        overridePendingTransition(0, 0)
        finish()
    }
}
