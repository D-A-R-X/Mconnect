package com.manjugroups.m_connect.ui.common

import java.util.Locale

/**
 * The 28 states and 8 union territories, in the spellings the rest of the
 * system already uses.
 *
 * Mirrors the `STATES` table the web address parser has used in production
 * (`manjusitedevelopment/lib/address-parse.ts`), so a value picked here,
 * a value parsed out of a pasted address, and a value the India Post lookup
 * returns all end up identical. Before this, State was free text and the same
 * place arrived as "TN", "Tamilnadu", "tamil nadu" and "TAMIL NADU", which
 * quietly split every report and filter that groups by state.
 *
 * District and city are deliberately NOT modelled here. There is no source we
 * can verify as complete for them, and a dropdown missing someone's district
 * would block a real address outright - worse than typing it. Those two stay
 * free text, filled from the pincode lookup, which IS complete because every
 * serviceable Indian address has a pincode.
 */
object IndianStates {

    /** Canonical names, alphabetical; what gets stored and displayed. */
    val ALL: List<String> = listOf(
        "Andaman and Nicobar Islands",
        "Andhra Pradesh",
        "Arunachal Pradesh",
        "Assam",
        "Bihar",
        "Chandigarh",
        "Chhattisgarh",
        "Dadra and Nagar Haveli and Daman and Diu",
        "Delhi",
        "Goa",
        "Gujarat",
        "Haryana",
        "Himachal Pradesh",
        "Jammu and Kashmir",
        "Jharkhand",
        "Karnataka",
        "Kerala",
        "Ladakh",
        "Lakshadweep",
        "Madhya Pradesh",
        "Maharashtra",
        "Manipur",
        "Meghalaya",
        "Mizoram",
        "Nagaland",
        "Odisha",
        "Puducherry",
        "Punjab",
        "Rajasthan",
        "Sikkim",
        "Tamil Nadu",
        "Telangana",
        "Tripura",
        "Uttar Pradesh",
        "Uttarakhand",
        "West Bengal",
    )

    /**
     * Spellings seen in the wild that must resolve to a canonical name. Same
     * variants the web parser carries, plus the abbreviations staff type.
     */
    private val ALIASES: Map<String, String> = mapOf(
        "tamilnadu" to "Tamil Nadu",
        "tn" to "Tamil Nadu",
        "pondicherry" to "Puducherry",
        "orissa" to "Odisha",
        "uttaranchal" to "Uttarakhand",
        "ap" to "Andhra Pradesh",
        "ka" to "Karnataka",
        "kl" to "Kerala",
        "ts" to "Telangana",
        "mh" to "Maharashtra",
        "up" to "Uttar Pradesh",
        "wb" to "West Bengal",
        "j&k" to "Jammu and Kashmir",
        "jk" to "Jammu and Kashmir",
        "dadra and nagar haveli" to "Dadra and Nagar Haveli and Daman and Diu",
        "daman and diu" to "Dadra and Nagar Haveli and Daman and Diu",
        "nct of delhi" to "Delhi",
        "new delhi" to "Delhi",
        "andaman & nicobar islands" to "Andaman and Nicobar Islands",
        "jammu & kashmir" to "Jammu and Kashmir",
    )

    private fun key(value: String): String =
        value.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    private val BY_KEY: Map<String, String> = buildMap {
        ALL.forEach { put(key(it), it) }
        ALIASES.forEach { (alias, canonical) -> put(key(alias), canonical) }
    }

    /**
     * The canonical name for whatever was typed, pasted or returned by India
     * Post, or null when it matches nothing we recognise.
     *
     * Returning null rather than guessing matters: a wrong state written into
     * a client record is harder to spot than an empty one.
     */
    fun canonical(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return BY_KEY[key(raw)]
    }
}
