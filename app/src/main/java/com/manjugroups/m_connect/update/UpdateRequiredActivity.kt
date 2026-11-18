package com.manjugroups.m_connect.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.BuildConfig
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.ApiService
import kotlinx.coroutines.launch

/**
 * The forced-update page for every screen outside MainActivity (login, OTP,
 * welcome, consent, other activities resumed from recents). MainActivity shows
 * the same page over its own window so the Play in-app flow keeps working there.
 *
 * Not dismissible: Back sends the app to the background, and every resume of
 * any other activity re-opens this page while the saved requirement stands
 * ([UpdateGate]). It closes itself only when the requirement is satisfied or
 * the server drops its minimum.
 */
class UpdateRequiredActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.manjugroups.m_connect.util.FontScale.cap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_mandatory_update)
        findViewById<View>(R.id.mandatoryUpdatePage).visibility = View.VISIBLE
        findViewById<TextView>(R.id.mandatoryUpdateButton).setOnClickListener { openStore() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No way past the page: Back just leaves the app.
                moveTaskToBack(true)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val active = RequiredUpdateStore.load(this, BuildConfig.VERSION_CODE)
        if (active == null) {
            finish()
            return
        }
        render(active)
        refreshServerPolicy()
    }

    private fun render(update: RequiredUpdate) {
        findViewById<TextView>(R.id.mandatoryUpdateTitle).text = "Update required"
        findViewById<TextView>(R.id.mandatoryUpdateMessage).text =
            "M-Connect ${update.version} is required. Please update to keep using the app."
        findViewById<TextView>(R.id.mandatoryUpdateButton).text = "Update now"
    }

    private fun openStore() {
        val url = RequiredUpdateStore.load(this, BuildConfig.VERSION_CODE)?.url
            ?: InAppUpdateManager.PLAY_STORE_URL
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .setPackage("com.android.vending")
        val opened = url.startsWith("https://play.google.com") &&
            runCatching { startActivity(market) }.isSuccess
        if (!opened) runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /** Lets the page go if the server has dropped its minimum (Play-only ones stay). */
    private fun refreshServerPolicy() {
        lifecycleScope.launch {
            val response = runCatching {
                ApiService.create().getMobileAppVersion(
                    platform = "android",
                    currentVersion = BuildConfig.VERSION_NAME,
                    buildNumber = BuildConfig.VERSION_CODE,
                    appVersionHeader = BuildConfig.VERSION_NAME,
                    appBuildHeader = BuildConfig.VERSION_CODE,
                )
            }.getOrNull() ?: return@launch
            if (!response.success || isFinishing) return@launch
            val now = System.currentTimeMillis()
            val stored = RequiredUpdateStore.load(this@UpdateRequiredActivity, BuildConfig.VERSION_CODE)
            val next = RequiredUpdatePolicy.withServer(
                stored,
                serverRequirement(response, BuildConfig.VERSION_CODE, now),
                BuildConfig.VERSION_CODE,
            )
            RequiredUpdateStore.save(this@UpdateRequiredActivity, next)
            if (next == null) finish() else render(next)
        }
    }

    companion object {
        fun intent(from: Activity): Intent =
            Intent(from, UpdateRequiredActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
}

/**
 * App-wide enforcement: whenever any activity other than MainActivity (which
 * shows its own page) and this page resumes while a requirement is saved, the
 * update page is brought on top. Covers reopening from the launcher or recents
 * on any screen, offline included (the requirement is local).
 */
object UpdateGate : android.app.Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        if (activity is UpdateRequiredActivity) return
        if (activity is com.manjugroups.m_connect.MainActivity) return
        if (activity is com.manjugroups.m_connect.SplashActivity) return
        if (activity.isFinishing) return
        if (RequiredUpdateStore.load(activity, BuildConfig.VERSION_CODE) == null) return
        activity.startActivity(UpdateRequiredActivity.intent(activity))
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
