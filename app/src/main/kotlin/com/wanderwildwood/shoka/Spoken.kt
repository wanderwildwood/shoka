package com.wanderwildwood.shoka

/**
 * A number said rather than typed. Voice typing (Gboard's, FUTO's) writes an ISBN however it
 * heard it: "978 0 14 303943 3", "978-0-14-303943-3", or as words, "nine seven eight oh one
 * four…". This turns that back into what would have been typed.
 */
object Spoken {

    private val units = mapOf(
        "zero" to 0, "oh" to 0, "o" to 0, "nought" to 0, "naught" to 0, "nil" to 0,
        "one" to 1, "two" to 2, "to" to 2, "too" to 2, "three" to 3, "four" to 4, "for" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val teens = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val tens = mapOf(
        "twenty" to 2, "thirty" to 3, "forty" to 4, "fifty" to 5,
        "sixty" to 6, "seventy" to 7, "eighty" to 8, "ninety" to 9,
    )
    private val repeats = mapOf("double" to 2, "triple" to 3)
    private val dashes = setOf("-", "dash", "hyphen")
    private val checkX = setOf("x", "ex", "eks")

    /**
     * An ISBN or LCCN said aloud, as the characters it stands for: digits, an X only as the
     * very last character (an ISBN-10's check digit), and a hyphen where one was said or
     * written, since an LCCN's hyphen ("65-21385") is part of it. Anything else (spaces, full
     * stops, "ISBN", "and") is dropped.
     */
    fun number(text: String): String {
        // Hyphens become words of their own, except inside "twenty-one", which is one number.
        val tokens = text.lowercase()
            .replace(Regex("(?<=[a-z])-(?=[a-z])"), " ")
            .replace("-", " - ")
            .split(Regex("[\\s,.;:!?\"'()]+"))
            .filter { it.isNotEmpty() }

        val out = StringBuilder()
        var i = 0
        fun peek(k: Int = 0) = tokens.getOrNull(i + k)

        /** One spoken number at [i] as its digits, or null if the word is not a number. */
        fun group(): String? {
            val t = peek() ?: return null
            units[t]?.let { i++; return "$it" }
            teens[t]?.let { i++; return "$it" }
            tens[t]?.let { ten ->
                i++
                val unit = peek()?.let { units[it] }?.takeIf { it != 0 && peek() !in setOf("o", "oh", "to", "too", "for") }
                return if (unit != null) { i++; "$ten$unit" } else "${ten}0"
            }
            return null
        }

        while (i < tokens.size) {
            val t = tokens[i]
            val last = i == tokens.size - 1
            when {
                t in dashes -> { out.append('-'); i++ }
                t in repeats && i + 1 < tokens.size -> {
                    val n = repeats.getValue(t)
                    i++
                    val next = peek()!!
                    val digit = units[next]?.toString() ?: next.takeIf { it.length == 1 && it[0].isDigit() }
                    if (digit != null) { i++; repeat(n) { out.append(digit) } }
                }
                t == "hundred" -> {
                    // "nine hundred seventy eight": the hundreds digit is already written.
                    i++
                    if (peek() == "and") i++
                    val rest = group()
                    out.append(when (rest?.length) { 2 -> rest; 1 -> "0$rest"; else -> "00" })
                }
                t.all { it.isDigit() } -> { out.append(t); i++ }
                last && t.length > 1 && t.dropLast(1).all { it.isDigit() } && t.last() == 'x' -> {
                    out.append(t.dropLast(1)).append('X'); i++
                }
                last && t in checkX && out.isNotEmpty() -> { out.append('X'); i++ }
                else -> group()?.let { out.append(it) } ?: i++
            }
        }
        return out.toString()
            .replace(Regex("-+"), "-")
            .trim('-')
    }
}
