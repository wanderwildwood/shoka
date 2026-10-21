package com.wanderwildwood.shoka

/**
 * The ISBN in text read off a page: a copyright page's "ISBN 0-14-303943-X", or a back cover's
 * "ISBN 978-0-14-303943-3". What comes in is OCR, so it is read loosely and accepted strictly:
 * the letters OCR mistakes for digits are taken as those digits, and nothing is accepted unless
 * its check digit is right.
 *
 * Numbers are made of hyphenated groups, and a group is never split: a misread ISBN-13's last ten
 * digits pass as an ISBN-10 one time in eleven, and that would be the wrong book, silently.
 * Spaced groups ("0 14 303943 X") are joined only when the whole run is the number, and a number
 * with a short group of digits beside it is passed over, since it may be the tail of a longer one.
 */
object PrintedIsbn {

    /** The ISBN-13 found in [text], or null. A labelled number ("ISBN …") is preferred. */
    fun find(text: String): String? = candidates(text).firstNotNullOfOrNull { Isbn.normalize(it) }

    /** Every run that could be an ISBN, labelled ones first, as digits (and X) ready to check. */
    internal fun candidates(text: String): List<String> {
        val labelled = mutableListOf<String>()
        val others = mutableListOf<String>()
        for (line in text.lines()) {
            // Mark each label, so the run after it is known to be the number it names.
            val marked = LABEL.replace(line, " $MARK ")
            var afterLabel = false
            var pos = 0
            for (m in RUN.findAll(marked)) {
                if (marked.substring(pos, m.range.first).contains(MARK)) afterLabel = true
                pos = m.range.last + 1
                val found = fromRun(m.value)
                if (afterLabel) labelled += found else others += found
                afterLabel = false
            }
        }
        return labelled + others
    }

    /** The numbers in one run: the run whole, or whole numbers made of its spaced words. */
    private fun fromRun(run: String): List<String> {
        val raw = run.trim().replace(HYPHENS, "").split(SPACES)
        val words = raw.map(::digits)
        val out = mutableListOf<String>()
        for (from in words.indices) {
            for (to in words.size downTo from + 1) {
                val joined = words.subList(from, to).joinToString("")
                if (joined.length != 10 && joined.length != 13) continue
                // Mostly real digits: a word of look-alikes alone is a word, not a number.
                if (raw.subList(from, to).sumOf { w -> w.count { it.isDigit() } } < MIN_DIGITS) continue
                // A short group either side may be the rest of a longer number.
                if ((words.getOrNull(from - 1)?.length ?: 99) <= 5) continue
                if ((words.getOrNull(to)?.length ?: 99) <= 5) continue
                out += joined
            }
        }
        return out
    }

    /** One group with OCR's look-alikes taken as the digits they stand for. */
    private fun digits(group: String): String = buildString {
        for (c in group) append(LOOKALIKE[c] ?: c.uppercaseChar())
    }

    private const val MARK = '#'
    private const val MIN_DIGITS = 7

    // "ISBN", "ISBN-13:", "ISBN 10", and how OCR mangles it: 1SBN, lSBN, I5BN, ISB N, 158N.
    // A hyphened -10/-13 after the label is always the label's ("ISBN-13979-…" once OCR has
    // dropped the colon and the space); a bare 10/13 only when no digit follows it at once.
    private val LABEL = Regex("""[I1l|!][S5][B8]\s?N(?:\s?-\s?1[03]|\s?1[03](?![0-9]))?\s*[:.]?""", RegexOption.IGNORE_CASE)

    // Groups of digits and look-alikes, joined by hyphens, dashes or single spaces.
    private const val CH = """[0-9OoDQIl|!SsBZzXx]"""
    private val RUN = Regex("""$CH+(?:(?: ?[-‐‑‒–—] ?| {1,2})$CH+)*""")
    private val SPACES = Regex(""" +""")
    private val HYPHENS = Regex("""\s*[-‐‑‒–—]\s*""")

    private val LOOKALIKE = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'I' to '1', 'l' to '1', '|' to '1', '!' to '1',
        'S' to '5', 's' to '5',
        'B' to '8',
        'Z' to '2', 'z' to '2',
        'x' to 'X',
    )
}
