package com.manjugroups.m_connect.ui.telecaller

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.manjugroups.m_connect.MainActivity
import com.manjugroups.m_connect.R
import com.manjugroups.m_connect.network.ApiService
import com.manjugroups.m_connect.network.DialDooctiRequest
import kotlinx.coroutines.launch

/**
 * Phone dialpad — mirrors the web Telecaller > Dialer screen at
 * `/telecaller/dialer`. Displays a 4×3 keypad, an editable Station number,
 * and a green Call button. Tapping Call hands the number off to the
 * device dialer via an ACTION_DIAL intent (no auto-call permission).
 *
 * Station is persisted in SharedPreferences keyed by `dialer.station`.
 */
class DialerFragment : Fragment() {

    private lateinit var prefs: android.content.SharedPreferences
    private val api = ApiService.create()

    private var tvNumber: TextView? = null
    private var tvStation: TextView? = null
    private var btnBackspace: View? = null
    private var btnCall: View? = null

    private var entered: String = ""
    private var station: String = DEFAULT_STATION
    private var calling: Boolean = false

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
        station = prefs.getString(KEY_STATION, DEFAULT_STATION) ?: DEFAULT_STATION

        view.findViewById<View>(R.id.btnDialerBack).setOnClickListener {
            parentFragmentManager.popBackStack()
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

        renderStation()
        renderNumber()
        buildDialpad(view.findViewById(R.id.dialpadGrid))
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
        tvStation?.text = station
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
        triggerDoocti(digits, station)
    }

    private fun triggerDoocti(phone: String, stationNumber: String) {
        calling = true
        btnCall?.isEnabled = false
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
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "mconnect_prefs"
        private const val KEY_STATION = "dialer.station"
        private const val DEFAULT_STATION = "6369487527"
        private const val DOOCTI_URL = "https://mms.aivida.in/api/doocti-call"
    }
}
