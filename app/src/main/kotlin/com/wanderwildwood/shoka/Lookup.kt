package com.wanderwildwood.shoka

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** What a catalogue said about one book. Empty strings for what it didn't say. */
data class Found(
    val title: String = "",
    val author: String = "",
    val year: String = "",
    val lc: String = "",
    val ddc: String = "",
    val lccn: String = "",
    val isbn: String = "",
    val source: String = "",
    /** Whether the catalogue itself calls it fiction; null when it doesn't say. */
    val fiction: Boolean? = null,
)

sealed interface Answer {
    data class Hit(val found: Found) : Answer
    data object Miss : Answer
    data object Offline : Answer
}

/**
 * The Library of Congress catalogue first, for the Dewey number in its 082 field and its own
 * word on whether a book is fiction; Open Library after it, for books LC never catalogued
 * and for the gaps; then another LC edition of the same work when there is still no Dewey
 * number. No account and no key for any of it.
 */
object Lookup {

    private const val LOC = "http://lx2.loc.gov:210/lcdb"
    private const val AGENT = "Shelf/0.1 (personal book catalogue; Android)"

    suspend fun byIsbn(isbn13: String): Answer = withContext(Dispatchers.IO) {
        var offline = false
        fun <T> attempt(block: () -> T): T? = try {
            block()
        } catch (e: IOException) {
            offline = true; null
        }

        val forms = listOfNotNull(isbn13, Isbn.to10(isbn13))
        val loc = forms.firstNotNullOfOrNull { form ->
            attempt { sru("bath.isbn=$form", 3) }?.maxByOrNull { if (it.ddc.isNotEmpty()) 1 else 0 }
        }
        val ol = if (loc == null || loc.lc.isEmpty() || loc.ddc.isEmpty()) {
            attempt { openLibrary("ISBN:$isbn13") }
        } else {
            null
        }
        var merged = merge(loc, ol)?.copy(isbn = isbn13)
        // Many books have no Dewey number on their own record -- often no LoC record at all --
        // but another edition of the same work does, and Dewey numbers the work. Asked too
        // when only Open Library knew the ISBN, for LoC's form of the author's name.
        if (merged != null && merged!!.title.isNotEmpty() && (loc == null || merged!!.ddc.isEmpty())) {
            attempt { otherEdition(merged!!) }?.let { other ->
                merged = merged!!.copy(
                    ddc = merged!!.ddc.ifEmpty { other.ddc },
                    // Open Library writes "Ray Bradbury"; LoC's "Bradbury, Ray" sorts and
                    // reads like the rest of the shelf, and gets double surnames right.
                    author = if (loc == null) other.author.ifEmpty { merged!!.author } else merged!!.author,
                    fiction = merged!!.fiction ?: other.fiction,
                    source = merged!!.source + " + another edition",
                )
            }
        }
        when {
            merged != null -> Answer.Hit(merged!!)
            offline -> Answer.Offline
            else -> Answer.Miss
        }
    }

    suspend fun byLccn(lccn: String): Answer = withContext(Dispatchers.IO) {
        try {
            val loc = sru("bath.lccn=$lccn", 1).firstOrNull()
            val ol = if (loc == null || loc.ddc.isEmpty()) openLibrary("LCCN:$lccn") else null
            merge(loc, ol)?.let { Answer.Hit(it.copy(lccn = lccn)) } ?: Answer.Miss
        } catch (e: IOException) {
            Answer.Offline
        }
    }

    /** For books with no ISBN at all. Throws [IOException] when there is no network. */
    suspend fun search(title: String, author: String): List<Found> = withContext(Dispatchers.IO) {
        val terms = buildList {
            if (title.isNotBlank()) add("bath.title=\"${title.trim().replace("\"", "")}\"")
            if (author.isNotBlank()) add("bath.author=\"${author.trim().replace("\"", "")}\"")
        }
        if (terms.isEmpty()) emptyList() else sru(terms.joinToString(" and "), 25)
    }

    /** An LoC record for the same title and author, one with a Dewey number if any has. */
    private fun otherEdition(f: Found): Found? {
        val title = Match.mainTitle(f.title)
        val surname = Match.surname(f.author)
        if (title.isEmpty()) return null
        val terms = buildList {
            add("bath.title=\"${title.replace("\"", "")}\"")
            if (surname.isNotEmpty()) add("bath.author=\"${surname.replace("\"", "")}\"")
        }
        val same = sru(terms.joinToString(" and "), 10).filter { Match.sameWork(f, it) }
        return same.firstOrNull { it.ddc.isNotEmpty() } ?: same.firstOrNull()
    }

    private fun merge(loc: Found?, ol: Found?): Found? {
        if (loc == null) return ol
        if (ol == null) return loc
        return loc.copy(
            title = loc.title.ifEmpty { ol.title },
            author = loc.author.ifEmpty { ol.author },
            year = loc.year.ifEmpty { ol.year },
            lc = loc.lc.ifEmpty { ol.lc },
            ddc = loc.ddc.ifEmpty { ol.ddc },
            lccn = loc.lccn.ifEmpty { ol.lccn },
            fiction = loc.fiction ?: ol.fiction,
            source = if ((loc.lc.isEmpty() && ol.lc.isNotEmpty()) || (loc.ddc.isEmpty() && ol.ddc.isNotEmpty())) {
                "LoC + Open Library"
            } else {
                loc.source
            },
        )
    }

    private fun get(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", AGENT)
        if (c.responseCode != 200) {
            c.disconnect()
            throw IOException("HTTP ${c.responseCode}")
        }
        return c.inputStream
    }

    // ---- Library of Congress, SRU returning MARCXML ----

    private fun sru(query: String, max: Int): List<Found> {
        val url = "$LOC?version=1.1&operation=searchRetrieve&recordSchema=marcxml" +
            "&maximumRecords=$max&query=" + URLEncoder.encode(Match.fold(query), "UTF-8")
        return get(url).use(::parseMarc)
    }

    private fun parseMarc(input: InputStream): List<Found> {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, "UTF-8")
        val out = mutableListOf<Found>()
        var fields: MutableMap<String, MutableList<Map<Char, String>>>? = null
        var tag = ""
        var sub: MutableMap<Char, String>? = null
        var code = ' '
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "record" -> fields = mutableMapOf()
                    // 008 is fixed-length, read by position: kept whole under a blank code.
                    "controlfield" -> if (p.getAttributeValue(null, "tag") == "008") {
                        val text = p.nextText()
                        fields?.getOrPut("008") { mutableListOf() }?.add(mapOf(' ' to text))
                    }
                    "datafield" -> {
                        tag = p.getAttributeValue(null, "tag") ?: ""
                        sub = mutableMapOf()
                    }
                    "subfield" -> {
                        code = p.getAttributeValue(null, "code")?.firstOrNull() ?: ' '
                        val text = p.nextText()
                        // Keep the first of each code; 050 can repeat $a with alternatives.
                        if (sub != null && code !in sub!!) sub!![code] = text
                    }
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "datafield" -> {
                        sub?.let { fields?.getOrPut(tag) { mutableListOf() }?.add(it) }
                        sub = null
                    }
                    "record" -> {
                        fields?.let { out += fromMarc(it) }
                        fields = null
                    }
                }
            }
        }
        return out
    }

    private fun fromMarc(f: Map<String, List<Map<Char, String>>>): Found {
        fun first(tag: String, code: Char) = f[tag]?.firstOrNull()?.get(code)?.trim() ?: ""
        // MARC ends fields with punctuation for the next one; an initial's full stop stays.
        fun strip(s: String): String {
            val t = s.trim().trimEnd('/', ':', ';', ',', ' ', '=').trim()
            return if (t.endsWith('.') && !Regex("""(^|[\s.])\p{L}\.$""").containsMatchIn(t)) t.dropLast(1).trim() else t
        }

        val title = listOf(first("245", 'a'), first("245", 'b'))
            .map(::strip).filter { it.isNotEmpty() }.joinToString(": ")
        val author = strip(
            first("100", 'a').ifEmpty { first("110", 'a') }.ifEmpty { first("111", 'a') }
                .ifEmpty { first("245", 'c') }
        )
        val year = Regex("""\d{4}""").find(first("264", 'c').ifEmpty { first("260", 'c') })
            ?.value ?: ""
        val lcRaw = listOf(first("050", 'a'), first("050", 'b')).filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { listOf(first("090", 'a'), first("090", 'b')).joinToString(" ").trim() }
        return Found(
            title = title,
            author = author,
            year = year,
            lc = CallNumber.cleanLc(lcRaw),
            ddc = CallNumber.cleanDdc(first("082", 'a')),
            lccn = first("010", 'a').trim(),
            isbn = first("020", 'a').split(' ').firstOrNull()?.let { Isbn.normalize(it) } ?: "",
            source = "Library of Congress",
            fiction = Match.formIsFiction(f["008"]?.firstOrNull()?.get(' ')?.getOrNull(33)),
        )
    }

    // ---- Open Library ----

    private fun openLibrary(bibkey: String): Found? {
        val url = "https://openlibrary.org/api/books?jscmd=data&format=json&bibkeys=" +
            URLEncoder.encode(bibkey, "UTF-8")
        val body = get(url).use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(body)
        val key = root.keys().asSequence().firstOrNull() ?: return null
        val o = root.getJSONObject(key)
        val classes = o.optJSONObject("classifications")
        fun strings(obj: JSONObject?, name: String): List<String> {
            val a = obj?.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { a.optString(it) }
        }
        val title = listOf(o.optString("title"), o.optString("subtitle"))
            .filter { it.isNotBlank() }.joinToString(": ")
        val author = o.optJSONArray("authors")?.optJSONObject(0)?.optString("name") ?: ""
        val subjects = o.optJSONArray("subjects")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }
        } ?: emptyList()
        return Found(
            title = title,
            author = author,
            year = Regex("""\d{4}""").find(o.optString("publish_date"))?.value ?: "",
            lc = strings(classes, "lc_classifications").map(CallNumber::cleanLc)
                .firstOrNull { it.isNotEmpty() } ?: "",
            ddc = strings(classes, "dewey_decimal_class").map(CallNumber::cleanDdc)
                .firstOrNull { it.isNotEmpty() } ?: "",
            lccn = strings(o.optJSONObject("identifiers"), "lccn").firstOrNull() ?: "",
            source = "Open Library",
            fiction = Match.subjectsSayFiction(subjects),
        )
    }
}

/** The plain rules behind a lookup, apart from the network so they can be tested. */
object Match {
    /** LoC's search finds "Marquez" and not "Márquez", so accents come off what is asked. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")

    /**
     * MARC 008 position 33, a book's literary form: 1 fiction, f novels, j short stories;
     * 0 not fiction, and d e i p s are drama, essays, letters, poetry and speeches, which
     * shelve with literature but not as fiction. Anything else doesn't say.
     */
    fun formIsFiction(form: Char?): Boolean? = when (form) {
        '1', 'f', 'j' -> true
        '0', 'd', 'e', 'i', 'p', 's' -> false
        else -> null
    }

    /** Open Library's subjects are loose; "Fiction" or "... fiction" is the reliable part. */
    fun subjectsSayFiction(subjects: List<String>): Boolean? =
        if (subjects.any { s -> s.trim().let { it.equals("fiction", true) || it.endsWith(" fiction", true) || it.contains("fictional works", true) } }) true
        else null

    fun mainTitle(t: String) = t.substringBefore(':').substringBefore(" / ").trim()

    /** "García Márquez, Gabriel" and "Gabriel García Márquez" both give "Márquez"-ish keys. */
    fun surname(author: String): String {
        val a = author.trim()
        return if (a.contains(',')) a.substringBefore(',').trim().split(' ').last() else a.split(' ').last()
    }

    private fun key(t: String) = fold(mainTitle(t)).lowercase()
        .replace(Regex("""^(the|a|an)\s+"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    /** Same main title, and the same surname when both say one. */
    fun sameWork(a: Found, b: Found): Boolean {
        val ka = key(a.title)
        val kb = key(b.title)
        if (ka.isEmpty() || ka != kb) return false
        val sa = fold(surname(a.author)).lowercase()
        val sb = fold(surname(b.author)).lowercase()
        return sa.isEmpty() || sb.isEmpty() || fold(b.author).lowercase().contains(sa) || fold(a.author).lowercase().contains(sb)
    }
}
