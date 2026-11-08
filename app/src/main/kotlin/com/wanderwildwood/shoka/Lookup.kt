package com.wanderwildwood.shoka

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource

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
    /** One of [Shelf.genres] when the subject headings say which; "" otherwise. */
    val genre: String = "",
)

sealed interface Answer {
    data class Hit(val found: Found) : Answer
    data object Miss : Answer
    data object Offline : Answer
}

/**
 * The Library of Congress catalogue first, for the Dewey number in its 082 field and its own
 * word on whether a book is fiction; Open Library after it, for books LC never catalogued
 * and for the gaps; then, for a book from Norway, Germany, Sweden, Finland, the Netherlands,
 * France, Poland or Portugal that still has no Dewey number, that country's national catalog;
 * then K10plus, the German libraries' union catalog, which holds books from everywhere, and
 * Google Books for a book no one else knows;
 * then another LC edition of the same work when there is still no Dewey number. No account
 * and no key for any of it.
 */
object Lookup {

    private const val LOC = "http://lx2.loc.gov:210/lcdb"
    private const val LOC_NAME = "Library of Congress"
    private const val NB_NAME = "Nasjonalbiblioteket"
    private const val DNB_NAME = "Deutsche Nationalbibliothek"
    private const val LIBRIS_NAME = "LIBRIS"
    private const val BNF_NAME = "Bibliothèque nationale de France"
    private const val BN_NAME = "Biblioteka Narodowa"
    private const val K10PLUS_NAME = "K10plus"
    private const val AGENT = "Shelf/0.1 (personal book catalogue; Android)"
    const val SEARCH_MAX = 10
    private val initialStop = Regex("""(^|[\s.])\p{L}\.$""")
    private val fourDigits = Regex("""\d{4}""")

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
        // A national library knows its own country's books, Dewey number and all, where the
        // two above often know nothing of them. One that fails or is busy is passed over, and
        // soon: it is asked with shorter timeouts than LoC, so a slow one doesn't hold the scan.
        if (merged == null || merged!!.ddc.isEmpty()) {
            Match.nationalLibrary(isbn13)?.let { which ->
                quietly { national(which, isbn13) }?.let { merged = fill(merged, it.copy(isbn = isbn13)) }
            }
        }
        // K10plus, the union catalog of German libraries, holds books from every country, and
        // its ISBN search finds a record filed under the ISBN-10 too.
        if (merged == null) {
            merged = quietly { askK10plus(isbn13) }?.copy(isbn = isbn13)
        }
        // Google Books last, and only for title and author: it gives no call number, and its
        // keyless allowance is shared by everyone and is often used up for the day.
        if (merged == null) {
            merged = quietly { googleBooks(isbn13) }?.copy(isbn = isbn13)
        }
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
                    genre = merged!!.genre.ifEmpty { other.genre },
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
        // Ten is a screenful and a quarter of the download: twenty-five full MARC records ran
        // to 130 KB, and every one was read through on the phone before any showed.
        if (terms.isEmpty()) emptyList() else sru(terms.joinToString(" and "), SEARCH_MAX)
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

    /** A catalog asked only for what the first ones lacked: null for any failure, never "offline". */
    private fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }

    /** [extra]'s fields where [first] has none; [extra] alone when there is no [first]. */
    private fun fill(first: Found?, extra: Found): Found {
        if (first == null) return extra
        return first.copy(
            title = first.title.ifEmpty { extra.title },
            author = first.author.ifEmpty { extra.author },
            year = first.year.ifEmpty { extra.year },
            lc = first.lc.ifEmpty { extra.lc },
            ddc = first.ddc.ifEmpty { extra.ddc },
            fiction = first.fiction ?: extra.fiction,
            genre = first.genre.ifEmpty { extra.genre },
            source = if (first.ddc.isEmpty() && extra.ddc.isNotEmpty()) "${first.source} + ${extra.source}" else first.source,
        )
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
            genre = loc.genre.ifEmpty { ol.genre },
            source = if ((loc.lc.isEmpty() && ol.lc.isNotEmpty()) || (loc.ddc.isEmpty() && ol.ddc.isNotEmpty())) {
                "LoC + Open Library"
            } else {
                loc.source
            },
        )
    }

    private fun get(url: String, connect: Int = 10_000, read: Int = 20_000): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = connect
        c.readTimeout = read
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
        return get(url).use { parseMarc(it) }
    }

    private fun parseMarc(input: InputStream, source: String = LOC_NAME): List<Found> {
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
                        fields?.let { out += fromMarc(it, source) }
                        fields = null
                    }
                }
            }
        }
        return out
    }

    /** A catalog asked only for what the first ones lacked, read whole, with short timeouts. */
    private fun spare(url: String): String =
        get(url, connect = 6_000, read = 10_000).use { it.readBytes().toString(Charsets.UTF_8) }

    internal fun fromMarc(f: Map<String, List<Map<Char, String>>>, source: String): Found {
        fun first(tag: String, code: Char) = f[tag]?.firstOrNull()?.get(code)?.trim() ?: ""
        // MARC ends fields with punctuation for the next one; an initial's full stop stays.
        fun strip(s: String): String {
            val t = Match.tidy(s).trimEnd('/', ':', ';', ',', ' ', '=').trim()
            return if (t.endsWith('.') && !initialStop.containsMatchIn(t)) t.dropLast(1).trim() else t
        }

        val title = listOf(first("245", 'a'), first("245", 'b'))
            .map(::strip).filter { it.isNotEmpty() }.joinToString(": ")
        val author = strip(
            first("100", 'a').ifEmpty { first("110", 'a') }.ifEmpty { first("111", 'a') }
                // A book by several hands has no 100 field; the national libraries then name
                // the first of them in 700 as "Surname, Given", which sorts better than 245 $c.
                .ifEmpty { if (source == LOC_NAME) "" else first("700", 'a') }
                .ifEmpty { first("245", 'c') }
        )
        // The Deutsche Nationalbibliothek marks a novel with the subject group "B" and a
        // children's book with "K" in its 082 and 084 fields, where Dewey gives no number.
        val groups = if (source == DNB_NAME) {
            listOf("082", "084").flatMap { t ->
                f[t].orEmpty().filter { it['2']?.contains("sdnb") == true }.mapNotNull { it['a']?.trim() }
            }
        } else {
            emptyList()
        }
        val year = fourDigits.find(first("264", 'c').ifEmpty { first("260", 'c') })
            ?.value ?: ""
        val fixed = f["008"]?.firstOrNull()?.get(' ')
        val lcRaw = listOf(first("050", 'a'), first("050", 'b')).filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { listOf(first("090", 'a'), first("090", 'b')).joinToString(" ").trim() }
        return Found(
            title = title,
            author = author,
            year = year,
            lc = CallNumber.cleanLc(lcRaw),
            // The DNB can give a subject group ("B") in its first 082 and the number in a later one.
            ddc = f["082"].orEmpty().map { CallNumber.cleanDdc(it['a'] ?: "") }.firstOrNull { it.isNotEmpty() } ?: "",
            lccn = first("010", 'a').trim(),
            isbn = first("020", 'a').split(' ').firstOrNull()?.let { Isbn.normalize(it) } ?: "",
            source = source,
            fiction = Match.formIsFiction(fixed?.getOrNull(33)) ?: Match.groupsSayFiction(groups),
            genre = if ("K" in groups) "children" else Match.genre(
                listOf("650", "655").flatMap { t -> f[t].orEmpty().flatMap { listOfNotNull(it['a'], it['v'], it['x']) } },
                // 008/22, the audience: a-c and j are children; d, adolescents, keep their genre.
                juvenile = fixed?.getOrNull(22) in listOf('a', 'b', 'c', 'j'),
            ),
        )
    }

    // ---- National libraries, by the ISBN's country ----

    private fun national(which: Match.National, isbn13: String): Found? = when (which) {
        // The Norwegian catalogue finds the record by ISBN in JSON, and serves it as MARC. A
        // record from before 2007 carries only the ISBN-10, and its search doesn't turn one
        // form into the other, so a book it doesn't know by ISBN-13 is asked again by ISBN-10.
        Match.National.NORWAY -> {
            fun ask(isbn: String) = Match.nbId(spare("https://api.nb.no/catalog/v1/items?q=" + URLEncoder.encode("isbn:$isbn", "UTF-8")))
            (ask(isbn13) ?: Isbn.to10(isbn13)?.let(::ask))?.let { id ->
                get("https://api.nb.no/catalog/v1/metadata/$id/marcxml", 6_000, 10_000).use { parseMarc(it, NB_NAME) }.firstOrNull()
            }
        }
        Match.National.GERMANY -> get(
            "https://services.dnb.de/sru/dnb?version=1.1&operation=searchRetrieve&recordSchema=MARC21-xml" +
                "&maximumRecords=3&query=" + URLEncoder.encode("num=$isbn13", "UTF-8"),
            6_000, 10_000,
        ).use { parseMarc(it, DNB_NAME) }.maxByOrNull { if (it.ddc.isNotEmpty()) 1 else 0 }
        Match.National.SWEDEN -> get(
            "https://libris.kb.se/xsearch?format=marcxml&n=3&query=" + URLEncoder.encode("isbn:$isbn13", "UTF-8"),
            6_000, 10_000,
        ).use { parseMarc(it, LIBRIS_NAME) }.maxByOrNull { if (it.ddc.isNotEmpty()) 1 else 0 }
        // Finna, the National Library of Finland's search over the country's libraries. Its
        // ISBN search also brings other editions of the work, so the parser keeps only a
        // record that carries this ISBN.
        Match.National.FINLAND -> Match.finna(
            spare(
                "https://api.finna.fi/v1/search?type=ISN&limit=5&field%5B%5D=title&field%5B%5D=isbns" +
                    "&field%5B%5D=nonPresenterAuthors&field%5B%5D=year&field%5B%5D=classifications&lookfor=$isbn13",
            ),
            isbn13,
        )
        // The KB's catalog by SRU, in Dublin Core; an older book is filed under its ISBN-10.
        Match.National.NETHERLANDS -> Match.kb(
            spare(
                "https://jsru.kb.nl/sru/sru?version=1.2&operation=searchRetrieve&x-collection=GGC" +
                    "&recordSchema=dcx&maximumRecords=3&query=" +
                    URLEncoder.encode(listOfNotNull(isbn13, Isbn.to10(isbn13)).joinToString(" or ") { "dc.identifier=$it" }, "UTF-8"),
            ),
        )
        // The BnF's catalog by SRU, in UNIMARC, the French form of MARC.
        Match.National.FRANCE -> Match.unimarc(
            spare(
                "https://catalogue.bnf.fr/api/SRU?version=1.2&operation=searchRetrieve" +
                    "&recordSchema=unimarcXchange&maximumRecords=3&query=" +
                    URLEncoder.encode(listOfNotNull(isbn13, Isbn.to10(isbn13)).joinToString(" or ") { "bib.isbn all \"$it\"" }, "UTF-8"),
            ),
            BNF_NAME,
        ).maxByOrNull { if (it.ddc.isNotEmpty()) 1 else 0 }
        // The Biblioteka Narodowa's open data, MARC 21 written as JSON. Its ISBN search
        // matches the form a record was filed under, so an older book is asked again by ISBN-10.
        Match.National.POLAND -> {
            fun ask(isbn: String) = Match.bnRecords(spare("https://data.bn.org.pl/api/institutions/bibs.json?limit=3&isbnIssn=$isbn"))
            val records = ask(isbn13).ifEmpty { Isbn.to10(isbn13)?.let(::ask).orEmpty() }
            records.map { fromMarc(it, BN_NAME) }.filter { it.title.isNotEmpty() }
                .maxByOrNull { if (it.ddc.isNotEmpty()) 1 else 0 }
        }
        // PORBASE, the Portuguese union catalog the Biblioteca Nacional de Portugal keeps, by
        // its URN service: one record by ISBN, in UNIMARC. It matches the form a record was
        // filed under, so an older book is asked again by ISBN-10.
        Match.National.PORTUGAL -> {
            fun ask(isbn: String) = Match.porbase(spare("https://urn.porbase.org/isbn/unimarc/marcxchange?id=$isbn"))
            ask(isbn13).ifEmpty { Isbn.to10(isbn13)?.let(::ask).orEmpty() }
                .maxByOrNull { if (it.ddc.isNotEmpty()) 1 else 0 }
        }
    }

    // ---- K10plus, the German union catalog, by SRU in MARCXML ----

    private fun askK10plus(isbn13: String): Found? = k10plus(
        spare(
            "https://sru.k10plus.de/opac-de-627?version=1.1&operation=searchRetrieve&recordSchema=marcxml" +
                "&maximumRecords=3&query=" + URLEncoder.encode("pica.isb=$isbn13", "UTF-8"),
        ),
    )

    /** K10plus's answer: the record with a Dewey number if one has it, else the first; null for none. */
    internal fun k10plus(xml: String): Found? =
        Match.marcRecords(xml).map { fromMarc(it, K10PLUS_NAME) }.filter { it.title.isNotEmpty() }
            .maxByOrNull { if (it.ddc.isNotEmpty()) 1 else 0 }

    // ---- Google Books, title and author only ----

    private fun googleBooks(isbn13: String): Found? {
        val url = "https://www.googleapis.com/books/v1/volumes?maxResults=1&q=" +
            URLEncoder.encode("isbn:$isbn13", "UTF-8")
        return Match.googleBooks(spare(url))
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
            year = fourDigits.find(o.optString("publish_date"))?.value ?: "",
            lc = strings(classes, "lc_classifications").map(CallNumber::cleanLc)
                .firstOrNull { it.isNotEmpty() } ?: "",
            ddc = strings(classes, "dewey_decimal_class").map(CallNumber::cleanDdc)
                .firstOrNull { it.isNotEmpty() } ?: "",
            lccn = strings(o.optJSONObject("identifiers"), "lccn").firstOrNull() ?: "",
            source = "Open Library",
            fiction = Match.subjectsSayFiction(subjects),
            // Open Library tags too many grown-up novels "juvenile" to take its word for it.
            genre = Match.genre(subjects, juvenile = false),
        )
    }
}

/** The plain rules behind a lookup, apart from the network so they can be tested. */
object Match {
    enum class National { NORWAY, GERMANY, SWEDEN, FINLAND, NETHERLANDS, FRANCE, POLAND, PORTUGAL }

    /**
     * The national library to ask about an ISBN-13, by its country prefix; null for the rest.
     * 978-2 and 979-10 are French-speaking books (Belgium's and Switzerland's too), 978-90
     * and 978-94 Dutch-speaking ones, 978-951 and 978-952 Finland's, 978-972 and 978-989
     * Portugal's.
     */
    fun nationalLibrary(isbn13: String): National? = when {
        isbn13.startsWith("97882") -> National.NORWAY
        isbn13.startsWith("9783") -> National.GERMANY
        isbn13.startsWith("97891") -> National.SWEDEN
        isbn13.startsWith("978951") || isbn13.startsWith("978952") -> National.FINLAND
        isbn13.startsWith("97890") || isbn13.startsWith("97894") -> National.NETHERLANDS
        isbn13.startsWith("9782") || isbn13.startsWith("97910") -> National.FRANCE
        isbn13.startsWith("97883") -> National.POLAND
        isbn13.startsWith("978972") || isbn13.startsWith("978989") -> National.PORTUGAL
        else -> null
    }

    /**
     * Finna's first record that carries [isbn13] itself (its ISBN search also brings other
     * editions of the work), or null. Finnish libraries class by YKL, whose 84 is fiction.
     */
    fun finna(json: String, isbn13: String): Found? {
        val records = try {
            JSONObject(json).optJSONArray("records")
        } catch (e: JSONException) {
            null
        } ?: return null
        for (i in 0 until records.length()) {
            val r = records.optJSONObject(i) ?: continue
            val isbns = r.optJSONArray("isbns") ?: continue
            val carries = (0 until isbns.length()).any { j ->
                Isbn.normalize(isbns.optString(j).trim().substringBefore(' ')) == isbn13
            }
            val title = tidy(r.optString("title")).trimEnd('/', ':', ';', ' ')
            if (!carries || title.isEmpty()) continue
            val authors = r.optJSONArray("nonPresenterAuthors")
            val author = authors?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                ?.let { a -> a.firstOrNull { it.optString("type") == "Personal Name" } ?: a.firstOrNull() }
                ?.optString("name") ?: ""
            val ykl = r.optJSONObject("classifications")?.optJSONArray("ykl")
            val fiction = ykl?.let { a -> (0 until a.length()).any { a.optString(it).let { c -> c == "84" || c.startsWith("84.") } } }
            return Found(
                title = title,
                author = tidy(author),
                year = year.find(r.optString("year"))?.value ?: "",
                source = "Finna",
                fiction = if (fiction == true) true else null,
            )
        }
        return null
    }

    /**
     * The Koninklijke Bibliotheek's answer in Dublin Core: the first printed book in it, or
     * its first record, as title, author and year. Its "Fictie" document type says fiction.
     */
    fun kb(xml: String): Found? {
        val records = elements(xml).filter { local(it) == "dc" }.map { dc ->
            children(dc).groupBy({ local(it) }, { it })
        }
        fun texts(r: Map<String, List<Element>>, name: String) = r[name].orEmpty().map { tidy(it.textContent) }.filter { it.isNotEmpty() }
        val r = records.firstOrNull { r -> texts(r, "title").isNotEmpty() && "online resource" !in texts(r, "type") }
            ?: records.firstOrNull { texts(it, "title").isNotEmpty() }
            ?: return null
        val doctypes = r["subject"].orEmpty().filter { it.getAttribute("xsi:type") == "dcx:doctype" }.map { it.textContent.trim() }
        return Found(
            title = texts(r, "title").first().trimEnd('/', ':', ';', ' '),
            author = texts(r, "creator").firstOrNull() ?: texts(r, "contributor").firstOrNull() ?: "",
            year = texts(r, "date").firstNotNullOfOrNull { year.find(it)?.value } ?: "",
            source = "Koninklijke Bibliotheek",
            fiction = if (doctypes.any { it.equals("Fictie", true) }) true else null,
        )
    }

    /**
     * UNIMARC records, as the BnF gives them: 200 the title, 700 the author, 210 or 214 the
     * year, 676 the Dewey number. 105 position 11 is the literary form, and 100 position 17
     * the readers a book is for.
     */
    fun unimarc(xml: String, source: String): List<Found> = marcRecords(xml).map { f ->
        fun first(tag: String, code: Char) = tidy(f[tag]?.firstOrNull()?.get(code) ?: "")
        fun name(tag: String) = listOf(first(tag, 'a'), first(tag, 'b')).filter { it.isNotEmpty() }.joinToString(", ")
        // 200 $e is other title information: a real subtitle, or only the form ("roman").
        val other = first("200", 'e')
        val formOnly = other.lowercase() in formWords
        val title = listOf(first("200", 'a'), if (formOnly) "" else other)
            .map { it.trimEnd('/', ':', ';', ',', ' ', '=') }.filter { it.isNotEmpty() }.joinToString(": ")
        val coded = f["100"]?.firstOrNull()?.get('a') ?: ""
        val form = f["105"]?.firstOrNull()?.get('a')?.getOrNull(11)
        Found(
            title = title,
            author = name("700").ifEmpty { name("701") }.ifEmpty { first("710", 'a') }.ifEmpty { first("200", 'f') },
            year = year.find(first("210", 'd').ifEmpty { first("214", 'd') })?.value
                ?: coded.substring(9.coerceAtMost(coded.length), 13.coerceAtMost(coded.length)).takeIf { it.length == 4 && it.all(Char::isDigit) }
                ?: "",
            ddc = f["676"].orEmpty().map { CallNumber.cleanDdc(it['a'] ?: "") }.firstOrNull { it.isNotEmpty() } ?: "",
            source = source,
            fiction = unimarcFiction(form) ?: if (other.lowercase() in fictionWords || other.lowercase().startsWith("roman ")) true else null,
            genre = if (coded.getOrNull(17) in listOf('a', 'b', 'c', 'd')) "children" else "",
        )
    }

    /**
     * UNIMARC 105 position 11, the literary form: a fiction, f short stories; b drama,
     * c essays, e letters, g poetry, h speeches, i libretti and y "not literature" are not
     * fiction; d (humor), z (several) and the rest don't say.
     */
    fun unimarcFiction(form: Char?): Boolean? = when (form) {
        'a', 'f' -> true
        'b', 'c', 'e', 'g', 'h', 'i', 'y' -> false
        else -> null
    }

    /**
     * PORBASE's answer to an ISBN: its records, or none for "Registo inexistente" (no such
     * record), which comes back as a small urn-response instead of a record.
     */
    fun porbase(xml: String): List<Found> =
        if (xml.contains("<urn-response")) emptyList() else unimarc(xml, "PORBASE")

    /** Form words in 200 $e that say a book is a novel or stories (French and Portuguese). */
    private val fictionWords = setOf("roman", "nouvelles", "romance", "contos", "novela")

    /** Words a French or Portuguese catalog gives as the "subtitle" that only name the form. */
    private val formWords = setOf(
        "roman", "romans", "nouvelle", "nouvelles", "récit", "récits", "conte", "contes", "essai", "essais",
        "poèmes", "poésie", "théâtre", "roman policier", "roman graphique", "bande dessinée", "témoignage",
        "romance", "romances", "conto", "contos", "novela", "novelas", "poesia", "poemas", "teatro", "ensaio", "ensaios",
        "crónica", "crónicas",
    )

    /** The Biblioteka Narodowa's records: MARC 21 written as JSON, read into MARC fields. */
    fun bnRecords(json: String): List<Map<String, List<Map<Char, String>>>> {
        val bibs = try {
            JSONObject(json).optJSONArray("bibs")
        } catch (e: JSONException) {
            null
        } ?: return emptyList()
        return (0 until bibs.length()).mapNotNull { bibs.optJSONObject(it)?.optJSONObject("marc")?.optJSONArray("fields") }
            .map { fields ->
                val out = mutableMapOf<String, MutableList<Map<Char, String>>>()
                for (i in 0 until fields.length()) {
                    val field = fields.optJSONObject(i) ?: continue
                    for (tag in field.keys()) {
                        val v = field.opt(tag)
                        val sub = when (v) {
                            // 008 is fixed-length, read by position: kept whole under a blank code.
                            is String -> if (tag == "008") mapOf(' ' to v) else continue
                            is JSONObject -> {
                                val subs = v.optJSONArray("subfields") ?: continue
                                val m = mutableMapOf<Char, String>()
                                for (j in 0 until subs.length()) {
                                    val s = subs.optJSONObject(j) ?: continue
                                    for (code in s.keys()) {
                                        code.firstOrNull()?.let { c -> if (c !in m) m[c] = s.optString(code) }
                                    }
                                }
                                m
                            }
                            else -> continue
                        }
                        out.getOrPut(tag) { mutableListOf() }.add(sub)
                    }
                }
                out
            }
    }

    /** MARC-shaped records in XML, whatever their prefix: tag to fields, code to text. */
    fun marcRecords(xml: String): List<Map<String, List<Map<Char, String>>>> =
        elements(xml).filter { e -> local(e) == "record" && children(e).any { local(it) == "datafield" } }.map { record ->
            val out = mutableMapOf<String, MutableList<Map<Char, String>>>()
            for (field in children(record)) {
                val tag = field.getAttribute("tag")
                when (local(field)) {
                    "controlfield" -> out.getOrPut(tag) { mutableListOf() }.add(mapOf(' ' to field.textContent))
                    "datafield" -> {
                        val m = mutableMapOf<Char, String>()
                        for (s in children(field)) {
                            val c = s.getAttribute("code").firstOrNull() ?: continue
                            if (c !in m) m[c] = s.textContent
                        }
                        out.getOrPut(tag) { mutableListOf() }.add(m)
                    }
                }
            }
            out
        }

    private fun local(e: Element) = e.nodeName.substringAfter(':')

    private fun children(e: Element): List<Element> {
        val n = e.childNodes
        return (0 until n.length).mapNotNull { n.item(it) as? Element }
    }

    /** Every element of an XML answer, in document order; none when it isn't XML. */
    private fun elements(xml: String): List<Element> = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        factory.isExpandEntityReferences = false
        // An answer has no business declaring a DTD; where the parser can refuse one, it does.
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // Android's parser knows no such feature, and reads no DTD anyway.
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val all = doc.getElementsByTagName("*")
        (0 until all.length).mapNotNull { all.item(it) as? Element }
    } catch (e: Exception) {
        emptyList()
    }

    /** The first record id in the Norwegian catalogue's search answer, or null. */
    fun nbId(json: String): String? = try {
        JSONObject(json).optJSONObject("_embedded")?.optJSONArray("items")?.optJSONObject(0)
            ?.optString("id")?.takeIf { it.isNotBlank() }
    } catch (e: JSONException) {
        null
    }

    /** Google Books' first volume as title, author and year; null when it found none. */
    fun googleBooks(json: String): Found? {
        val v = try {
            JSONObject(json).optJSONArray("items")?.optJSONObject(0)?.optJSONObject("volumeInfo")
        } catch (e: JSONException) {
            null
        } ?: return null
        val title = listOf(v.optString("title"), v.optString("subtitle"))
            .filter { it.isNotBlank() }.joinToString(": ")
        if (title.isEmpty()) return null
        return Found(
            title = title,
            author = v.optJSONArray("authors")?.optString(0) ?: "",
            year = year.find(v.optString("publishedDate"))?.value ?: "",
            source = "Google Books",
        )
    }

    private val year = Regex("""\d{4}""")

    /** The DNB's subject groups: "B" is fiction. Anything else doesn't say. */
    fun groupsSayFiction(groups: List<String>): Boolean? = if ("B" in groups) true else null

    /**
     * Catalog text as it should read: the DNB wraps a leading article in the control
     * characters that tell sorting to skip it, and the Norwegian catalogue leaves two spaces
     * after one ("En  klimabok"); both go.
     */
    fun tidy(s: String): String = s.replace(sortMarks, "").replace(spaces, " ").trim()

    private val sortMarks = Regex("[\u0088\u0089\u0098\u009C]")
    private val spaces = Regex("""\s+""")
    /** LoC's search finds "Marquez" and not "Márquez", so accents come off what is asked. */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "")

    private val marks = Regex("""\p{Mn}+""")

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

    /**
     * A novel's shelf from its subject headings ("Detective and mystery stories", "Science
     * fiction", "Horror fiction" ...), or "". Headings often name two; the first rule here
     * wins, so a dystopia shelves as science fiction even where it is also called fantasy or
     * horror.
     * "Love stories" is left out: catalogues give it to half the classics.
     */
    fun genre(subjects: List<String>, juvenile: Boolean): String {
        if (juvenile) return "children"
        val s = subjects.map { fold(it).lowercase() }
        return genreRules.firstOrNull { (_, words) -> s.any { h -> words.any { it.containsMatchIn(h) } } }?.first ?: ""
    }

    /**
     * The rules in the order they win, each word made into its pattern once rather than again
     * for every heading of every record a search brings back.
     */
    private val genreRules: List<Pair<String, List<Regex>>> = listOf(
        "crime" to listOf("detective", "mystery", "crime fiction", "thrillers", "suspense fiction", "spy stories", "espionage fiction"),
        "scifi" to listOf("science fiction", "dystopias", "dystopia", "dystopian", "apocalyptic fiction", "post-apocalyptic fiction"),
        "horror" to listOf("horror", "ghost stories"),
        "fantasy" to listOf("fantasy", "fantasy fiction", "fantastic fiction"),
        "romance" to listOf("romance fiction", "romance", "romantic suspense fiction"),
        "historical" to listOf("historical fiction", "fiction, historical"),
    ).map { (code, words) -> code to words.map { Regex("""\b$it\b""") } }

    fun mainTitle(t: String) = t.substringBefore(':').substringBefore(" / ").trim()

    /** "García Márquez, Gabriel" and "Gabriel García Márquez" both give "Márquez"-ish keys. */
    fun surname(author: String): String {
        val a = author.trim()
        return if (a.contains(',')) a.substringBefore(',').trim().split(' ').last() else a.split(' ').last()
    }

    private fun key(t: String) = fold(mainTitle(t)).lowercase()
        .replace(article, "")
        .replace(notWord, "")

    private val article = Regex("""^(the|a|an)\s+""")
    private val notWord = Regex("""[^\p{L}\p{N}]+""")

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
