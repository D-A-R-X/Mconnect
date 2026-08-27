package com.manjugroups.m_connect.ui.chat

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan

/**
 * Renders `**bold**` inside a chat message.
 *
 * The web client already renders message bodies as markdown, so a message
 * written there — or generated server-side, like the OTP-assist request that
 * puts the code in bold — arrived on Android showing literal asterisks.
 *
 * Deliberately NOT a markdown parser: it handles the one marker the app
 * actually produces, leaves everything else exactly as typed, and returns the
 * original string untouched when there is nothing to format, so no existing
 * message can change appearance unless it really contains a matched pair.
 *
 * The parsing is split out as pure Kotlin ([parse]) because Android's span
 * classes aren't available in plain unit tests — this way the logic that can
 * actually be wrong is the part under test.
 */
object ChatTextFormat {

    private val BOLD = Regex("""\*\*(.+?)\*\*""", RegexOption.DOT_MATCHES_ALL)

    /** Text with the markers removed, plus where the bold runs ended up. */
    data class Parsed(val text: String, val boldRanges: List<IntRange>)

    /** Pure: null when there is nothing to format. */
    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrEmpty() || !raw.contains("**")) return null
        val matches = BOLD.findAll(raw).toList()
        if (matches.isEmpty()) return null

        val out = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var cursor = 0
        for (match in matches) {
            out.append(raw, cursor, match.range.first)
            val start = out.length
            // Group 1 is the text between the markers; the markers are dropped.
            out.append(match.groupValues[1])
            if (out.length > start) ranges.add(start until out.length)
            cursor = match.range.last + 1
        }
        out.append(raw, cursor, raw.length)
        return Parsed(out.toString(), ranges)
    }

    fun format(raw: CharSequence?): CharSequence? {
        val parsed = parse(raw?.toString()) ?: return raw
        val span = SpannableString(parsed.text)
        for (range in parsed.boldRanges) {
            span.setSpan(
                StyleSpan(Typeface.BOLD),
                range.first,
                range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return span
    }
}
