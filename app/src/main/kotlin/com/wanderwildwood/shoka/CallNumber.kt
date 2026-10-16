package com.wanderwildwood.shoka

/**
 * Turns call numbers into keys that sort the way a shelf does, which plain text does not:
 * "QA76.9" goes before "QA76.73" as a string, and after it on a shelf.
 */
object CallNumber {

    private val lcBase = Regex("""^([A-Z]{1,3})\s*(\d{1,5})(?:\.(\d+))?(.*)$""")
    private val lcToken = Regex("""([A-Z])(\d+)|(\d+)|([A-Z]+)""")
    private val ddcBase = Regex("""^(\d{3})(?:\.(\d+))?(.*)$""")

    /** A tidy LC call number for display, or "" if [raw] is not one. */
    fun cleanLc(raw: String): String {
        // Case kept as catalogued: LoC writes work marks in lower case ("Ho"), and sorting
        // upper-cases for itself.
        val s = raw.trim().replace(Regex("""\s+"""), " ")
        return if (lcKey(s) != null) s else ""
    }

    /** A tidy Dewey number: LoC writes segmentation marks ("813/.54") that shelves don't. */
    fun cleanDdc(raw: String): String {
        val s = raw.trim().replace("/", "").replace("'", "")
        return if (ddcBase.find(s) != null) s else ""
    }

    /**
     * Class letters, class number (as a decimal), then each Cutter as a decimal fraction,
     * then dates and volume numbers numerically. Null when it isn't an LC number at all
     * ("[Fic]", or an empty field).
     */
    fun lcKey(raw: String): String? {
        val s = raw.trim().uppercase()
        val m = lcBase.find(s) ?: return null
        val (letters, whole, frac, rest) = m.destructured
        val sb = StringBuilder()
        sb.append(letters.padEnd(3, ' '))
        sb.append(whole.padStart(5, '0')).append('.').append(frac.padEnd(8, '0'))
        for (t in lcToken.findAll(rest.replace(".", " "))) {
            val (cl, cd, num, word) = t.destructured
            sb.append(' ')
            when {
                cl.isNotEmpty() -> sb.append('C').append(cl).append(cd.padEnd(8, '0'))
                num.isNotEmpty() -> sb.append('N').append(num.padStart(8, '0'))
                else -> sb.append('W').append(word)
            }
        }
        return sb.toString()
    }

    fun ddcKey(raw: String): String? {
        val s = cleanDdc(raw)
        val m = ddcBase.find(s) ?: return null
        val (whole, frac, rest) = m.destructured
        return whole + "." + frac.padEnd(10, '0') + " " + rest.trim().uppercase()
    }

    /**
     * The two shelf orders, both Dewey: the full number, or SECTIONS -- the ten hundreds,
     * Fiction on its own, and author within each (see [Shelf]). LC numbers are still looked
     * up and kept, but nothing is shelved by them.
     */
    enum class System { DEWEY, SECTIONS }
}
