package com.example.callbridge.utils

/**
 * Normalizes phone numbers for comparison and duplicate detection while preserving
 * the caller's original formatted string.
 */
object BridgePhoneNumberUtils {

    fun normalizeForMatching(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()
        val hasPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.isEmpty()) return ""
        return if (hasPlus) "+$digits" else digits
    }

    /**
     * Compares two phone numbers using normalized digits or matching last-10 local suffix
     * when one includes a country code and the other uses local dialing format.
     */
    fun areLikelySameNumber(a: String?, b: String?): Boolean {
        val na = normalizeForMatching(a).removePrefix("+")
        val nb = normalizeForMatching(b).removePrefix("+")
        if (na.isEmpty() || nb.isEmpty()) return false
        if (na == nb) return true
        if (na.length >= 10 && nb.length >= 10) {
            return na.takeLast(10) == nb.takeLast(10)
        }
        return false
    }
}
