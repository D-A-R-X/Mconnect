package com.manjugroups.m_connect.auth

import java.security.SecureRandom

/**
 * The staff password rule and generator, matching the web (lib/password-policy.ts
 * and the Security tab's generator): 8-128 characters, no spaces, at least one
 * upper case letter, lower case letter, number and special character. The
 * server applies the same rule; checking here first says what is wrong
 * before anything is sent.
 */
object PasswordPolicy {

    const val RULE_TEXT =
        "Use 8+ characters with uppercase, lowercase, number, and special character."

    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghijkmnpqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%&*?"

    /** Null when [password] is acceptable, otherwise what is missing. */
    fun problem(password: String): String? = when {
        password.length < 8 -> "Use at least 8 characters."
        password.length > 128 -> "Use at most 128 characters."
        password.any { it.isWhitespace() } -> "Don't use spaces."
        password.none { it in 'A'..'Z' } -> "Add an uppercase letter."
        password.none { it in 'a'..'z' } -> "Add a lowercase letter."
        password.none { it in '0'..'9' } -> "Add a number."
        password.all { it.isLetterOrDigit() } -> "Add a special character."
        else -> null
    }

    /** A 12-character password with every required kind of character. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val all = UPPER + LOWER + DIGITS + SYMBOLS
        val chars = mutableListOf(
            UPPER[random.nextInt(UPPER.length)],
            LOWER[random.nextInt(LOWER.length)],
            DIGITS[random.nextInt(DIGITS.length)],
            SYMBOLS[random.nextInt(SYMBOLS.length)],
        )
        repeat(8) { chars += all[random.nextInt(all.length)] }
        chars.shuffle(random)
        return chars.joinToString("")
    }
}
