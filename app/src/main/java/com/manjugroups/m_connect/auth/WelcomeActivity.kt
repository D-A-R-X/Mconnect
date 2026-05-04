package com.manjugroups.m_connect.auth

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var session: SessionManager

    private var currentPage = 0

    private val subtitles = arrayOf(
        "Manage Projects, engage customers, and streamline operations - all in one place.",
        "Monitor sales, project progress and team activity in real time.",
        "Track tasks, manage site visits, and close deals efficiently."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.statusBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        session = SessionManager(this)
        if (session.isLoggedIn) {
            goMain()
            return
        }

        updatePage(0)

        binding.btnOnboardNext.setOnClickListener {
            if (currentPage < 2) {
                currentPage++
                updatePage(currentPage)
            } else {
                goToLogin()
            }
        }

        binding.btnOnboardSkip.setOnClickListener {
            goToLogin()
        }
    }

    private fun updatePage(page: Int) {
        binding.tvOnboardTitle.text = buildTitle(page)
        binding.tvOnboardSubtitle.text = subtitles[page]
        binding.tvBtnNextLabel.text = if (page == 2) "Get Started" else "Next"

        binding.root.findViewById<View>(R.id.illOnboard1)?.visibility = if (page == 0) View.VISIBLE else View.GONE
        binding.root.findViewById<View>(R.id.illOnboard2)?.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.root.findViewById<View>(R.id.illOnboard3)?.visibility = if (page == 2) View.VISIBLE else View.GONE

        updateDots(page)
    }

    private fun buildTitle(page: Int): SpannableString {
        return when (page) {
            0 -> spanBlue("Welcome to Manju Groups", "Manju Groups")
            1 -> spanBlue("Track Performance Easily", "Performance Easily")
            else -> spanBlue("Manage Your Workflow", "Workflow")
        }
    }

    private fun spanBlue(full: String, highlight: String): SpannableString {
        val spannable = SpannableString(full)
        val start = full.indexOf(highlight)
        if (start >= 0) {
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor("#0B61CA")),
                start, start + highlight.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun updateDots(page: Int) {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index == page) R.drawable.bg_onboard_dot_active else R.drawable.bg_onboard_dot_inactive
            )
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).also { it.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        overridePendingTransition(0, 0)
        finish()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(0, 0)
        finish()
    }
}
