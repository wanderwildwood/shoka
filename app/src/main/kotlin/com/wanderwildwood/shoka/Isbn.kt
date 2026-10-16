package com.wanderwildwood.shoka

/** ISBN-10 and ISBN-13: checking, converting, and telling them from everything else. */
object Isbn {

    /** The ISBN-13 for [raw] (an ISBN-10 or -13, with or without hyphens), or null. */
    fun normalize(raw: String): String? {
        val s = raw.uppercase().filter { it.isDigit() || it == 'X' }
        return when {
            s.length == 13 && valid13(s) && (s.startsWith("978") || s.startsWith("979")) -> s
            s.length == 10 && valid10(s) -> from10(s)
            else -> null
        }
    }

    fun valid13(s: String): Boolean {
        if (s.length != 13 || !s.all { it.isDigit() }) return false
        val sum = s.withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 1 else 3 }
        return sum % 10 == 0
    }

    fun valid10(s: String): Boolean {
        if (s.length != 10 || !s.take(9).all { it.isDigit() }) return false
        if (!(s[9].isDigit() || s[9] == 'X')) return false
        val sum = s.withIndex().sumOf { (i, c) -> (10 - i) * if (c == 'X') 10 else c - '0' }
        return sum % 11 == 0
    }

    fun from10(s: String): String {
        val body = "978" + s.take(9)
        return body + check13(body)
    }

    /** The ISBN-10 form, which only 978- numbers have. Older catalogue records carry only this. */
    fun to10(isbn13: String): String? {
        if (!isbn13.startsWith("978") || isbn13.length != 13) return null
        val body = isbn13.substring(3, 12)
        val sum = body.withIndex().sumOf { (i, c) -> (10 - i) * (c - '0') }
        val check = (11 - sum % 11) % 11
        return body + if (check == 10) 'X' else ('0' + check)
    }

    private fun check13(body: String): Char {
        val sum = body.withIndex().sumOf { (i, c) -> (c - '0') * if (i % 2 == 0) 1 else 3 }
        return '0' + (10 - sum % 10) % 10
    }
}

/**
 * Library of Congress Control Numbers, as printed on copyright pages: "65-21385",
 * "2006274831", "sn 78-1234". Normalised the way the LoC does it -- prefix kept, the part
 * after the hyphen zero-padded to six digits.
 */
object Lccn {
    private val shape = Regex("""^([a-z]{0,3})\s*(\d{2}|\d{4})-?(\d{1,6})$""")

    fun normalize(raw: String): String? {
        val s = raw.trim().lowercase().replace(" ", "")
        val m = shape.find(s) ?: return null
        val (prefix, year, serial) = m.destructured
        if (!s.contains('-') && serial.length != 6) {
            // Without a hyphen the split is ambiguous unless the serial is already padded.
            return if ((year + serial).length in 8..10) prefix + year + serial else null
        }
        return prefix + year + serial.padStart(6, '0')
    }
}
