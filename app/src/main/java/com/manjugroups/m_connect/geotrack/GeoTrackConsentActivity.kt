package com.manjugroups.m_connect.geotrack

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.auth.SessionManager
import com.manjugroups.m_connect.databinding.ActivityGeoConsentBinding
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class GeoTrackConsentActivity : AppCompatActivity() {

    // Cap extreme system font sizes; see FontScale.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.manjugroups.m_connect.util.FontScale.cap(newBase))
    }

    private lateinit var binding: ActivityGeoConsentBinding
    private lateinit var session: SessionManager
    /**
     * The whole permission chain after "I Understand and Agree": one tap, the
     * system prompts back to back, then tracking starts. Anything the phone
     * refuses is picked up by the permissions sheet on Home.
     */
    private val permissionSetup = TrackingPermissionSetup(
        caller = this,
        activity = { this },
        onFinished = { startTrackingService() },
    )

    /**
     * Guards against leaving twice. Several tails can reach [goToMain] — the
     * setup coroutine, its timeout, and a "Not now" on a permission refusal —
     * and a second startActivity would stack another MainActivity behind the
     * one the user is already looking at.
     */
    private var navigated = false

    /** True while the post-permission setup runs, so the screen cannot be re-entered. */
    private var setupInFlight = false

    companion object {
        /**
         * True while a consent screen instance is alive. Bootstrap sync runs on
         * every MainActivity resume / punch, and each pass would otherwise launch
         * a fresh consent screen — stacking duplicates. The launcher checks this
         * flag so it never opens a second one.
         */
        @Volatile
        var isActive = false

        /**
         * Long enough for the sync to finish on a normal connection, short
         * enough that a stalled backend does not hold the user on a consent
         * screen. Whatever does not complete here is retried on Home.
         */
        private const val SETUP_TIMEOUT_MS = 8_000L
    }

    override fun onDestroy() {
        super.onDestroy()
        isActive = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isActive = true
        binding = ActivityGeoConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)

        binding.btnConsent.setOnClickListener {
            recordConsentAndRequestPermissions()
        }

        binding.btnDecline.setOnClickListener {
            session.geoConsentGiven = false
            session.geoConsentDeclined = true
            session.shouldTrackNow = false
            goToMain()
        }
    }

    private fun recordConsentAndRequestPermissions() {
        // Re-entering the chain mid-flight relaunches permission requests on top
        // of each other and can fire the setup tail twice.
        if (setupInFlight || permissionSetup.isRunning) return
        session.geoConsentGiven = true
        session.geoConsentDeclined = false
        GeoTrackConsentStore.recordAgreed(this, session.staffId)
        permissionSetup.start()
    }

    /**
     * Last step: kick tracking off, then leave.
     *
     * This used to await two attendance calls and a bootstrap sync — four
     * network round-trips at a 30s timeout each — before navigating, with the
     * button still enabled and no sign anything was happening. On a slow
     * connection the screen sat there for a minute looking dead, and an
     * exception anywhere in the chain meant `goToMain()` never ran at all and
     * the user was stranded for good.
     *
     * Now: bounded, guarded, and navigation happens in `finally` so there is no
     * path that leaves the user on this screen. Consent is already persisted
     * locally, and the same sync re-runs on every MainActivity resume and on
     * every punch, so finishing it here was never actually required — only
     * nice to have.
     */
    private fun startTrackingService() {
        if (setupInFlight) return
        setupInFlight = true
        setBusy(true)
        lifecycleScope.launch {
            try {
                withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                    val attendanceActive = runCatching {
                        AttendanceTrackingGate.isClockedInForToday(
                            session.bearerToken,
                            ApiService.create(),
                        )
                    }.getOrDefault(false)
                    runCatching {
                        GeoTrackBootstrapSync.sync(
                            context = this@GeoTrackConsentActivity,
                            allowPromptConsent = false,
                            attendanceOpenOverride = attendanceActive,
                        )
                    }
                }
            } finally {
                setupInFlight = false
                goToMain()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnConsent.isEnabled = !busy
        binding.btnDecline.isEnabled = !busy
        binding.btnConsent.text =
            if (busy) "Setting up tracking…" else "I Understand and Agree"
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        // Back to the MainActivity underneath, not a second copy: a new instance
        // repainted stale cached attendance ("Clock In") over a punch the
        // staff member had just made.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

}
