package com.wanderwildwood.shoka

import org.json.JSONArray
import org.json.JSONObject

enum class Status { LOOKING, FOUND, NOT_FOUND, OFFLINE }

data class Book(
    val id: Long,
    val isbn: String = "",
    val lccn: String = "",
    val title: String = "",
    val author: String = "",
    val year: String = "",
    val lc: String = "",
    val ddc: String = "",
    val source: String = "",
    val status: Status = Status.LOOKING,
    val copies: Int = 1,
    val added: Long = 0,
    /** When this record last changed on any phone; the newer of two copies wins a sync. */
    val modified: Long = 0,
    /** Kept, not dropped, so a sync tells the other phones it was deleted. */
    val deleted: Boolean = false,
    /** Set by hand when [fictionGuess] is wrong; null means go by the guess. */
    val fiction: Boolean? = null,
    /** One of [Shelf.genres] for a novel, from the catalogue or set by hand; "" when unknown. */
    val genre: String = "",
) {
    /**
     * Novels and stories, from the numbers: Dewey's fiction numbers (813 American, 823
     * English, 891.73 Russian, 895.63 Japanese, ...), fiction anthologies, and LC's PZ.
     * Neither scheme says "fiction" for every novel, so [fiction] can overrule it.
     */
    val fictionGuess: Boolean
        get() = Regex("""^(8[1-8]3|89\d\.\d3|808\.83)""").containsMatchIn(ddc.trim()) ||
            lc.trim().uppercase().startsWith("PZ")

    val isFiction: Boolean get() = fiction ?: fictionGuess

    /** "Tolkien, J. R. R." stays; "J.R.R. Tolkien" becomes "Tolkien J.R.R." for sorting. */
    val authorSort: String
        get() {
            val a = author.trim().lowercase()
            if (a.isEmpty() || a.contains(',')) return a
            val parts = a.split(' ')
            return (listOf(parts.last()) + parts.dropLast(1)).joinToString(" ")
        }

    val titleSort: String
        get() = title.trim().lowercase().replace(Regex("""^(the|a|an)\s+"""), "")

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("isbn", isbn); put("lccn", lccn); put("title", title)
        put("author", author); put("year", year); put("lc", lc); put("ddc", ddc)
        put("source", source); put("status", status.name); put("copies", copies)
        put("added", added); put("modified", modified); put("deleted", deleted)
        if (fiction != null) put("fiction", fiction)
        if (genre.isNotEmpty()) put("genre", genre)
    }

    companion object {
        fun fromJson(o: JSONObject) = Book(
            id = o.getLong("id"),
            isbn = o.optString("isbn"),
            lccn = o.optString("lccn"),
            title = o.optString("title"),
            author = o.optString("author"),
            year = o.optString("year"),
            lc = o.optString("lc"),
            ddc = o.optString("ddc"),
            source = o.optString("source"),
            status = runCatching { Status.valueOf(o.optString("status")) }
                .getOrDefault(Status.FOUND),
            copies = o.optInt("copies", 1),
            added = o.optLong("added"),
            modified = o.optLong("modified", o.optLong("added")),
            deleted = o.optBoolean("deleted"),
            fiction = if (o.has("fiction")) o.optBoolean("fiction") else null,
            genre = o.optString("genre"),
        )

        fun listToJson(books: List<Book>) = JSONArray().apply { books.forEach { put(it.toJson()) } }

        fun listFromJson(a: JSONArray) = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

/**
 * A book with its place on the shelf. [position] counts from 1. [section] is set only in
 * the Sections order: a Dewey hundred ("5" for the 500s), [Shelf.FICTION] or one of
 * [Shelf.genres], or "" for books with neither.
 */
data class Placed(val book: Book, val position: Int, val classified: Boolean, val section: String = "")

object Shelf {
    const val FICTION = "fiction"

    /** Fiction's own shelves, after general fiction, in this order. Set by hand as [FICTION]. */
    val genres = listOf("crime", "scifi", "fantasy", "horror", "romance", "historical", "children")

    /** Dewey's ten main classes, 000 to 900, by their first digit. */
    val deweyClasses = (0..9).map { it.toString() }

    /** Where a book stands in the Sections order: a fiction shelf, its Dewey hundred, or "". */
    fun section(b: Book): String = when {
        b.isFiction -> b.genre.takeIf { it in genres } ?: FICTION
        CallNumber.ddcKey(b.ddc) != null -> b.ddc.trim().take(1)
        else -> ""
    }

    /**
     * Every book in shelf order. In Dewey, books with no number go after all the numbered
     * ones, by author and then title, so they still have a place to stand; books with the
     * same number go by author, as a library's would.
     */
    fun order(books: List<Book>, system: CallNumber.System): List<Placed> {
        if (system == CallNumber.System.SECTIONS) return bySection(books)
        val keyed = books.filterNot { it.deleted }.map { it to CallNumber.ddcKey(it.ddc) }
        val classified = keyed.filter { it.second != null }
            .sortedWith(compareBy<Pair<Book, String?>> { it.second }.thenBy(byName) { it.first })
            .map { it.first }
        val rest = keyed.filter { it.second == null }.map { it.first }.sortedWith(byName)
        return classified.mapIndexed { i, b -> Placed(b, i + 1, true) } +
            rest.mapIndexed { i, b -> Placed(b, classified.size + i + 1, false) }
    }

    /**
     * Fiction first, general and then each genre, then each Dewey hundred from 000 to 900, then books with no number; by
     * author and title within each. Coarse enough to keep by eye, with a label on each shelf.
     */
    private fun bySection(books: List<Book>): List<Placed> {
        val rank = (listOf(FICTION) + genres + deweyClasses + "").withIndex().associate { it.value to it.index }
        return books.filterNot { it.deleted }
            .map { it to section(it) }
            .sortedWith(compareBy<Pair<Book, String>> { rank.getValue(it.second) }.thenBy(byName) { it.first })
            .mapIndexed { i, (b, sec) -> Placed(b, i + 1, sec.isNotEmpty(), sec) }
    }

    private val byName = compareBy<Book>({ it.authorSort }, { it.titleSort }, { it.id })
}
