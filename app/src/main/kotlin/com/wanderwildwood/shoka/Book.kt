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
) {
    fun callNumber(system: CallNumber.System) = if (system == CallNumber.System.LC) lc else ddc

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
        put("added", added)
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
        )

        fun listToJson(books: List<Book>) = JSONArray().apply { books.forEach { put(it.toJson()) } }

        fun listFromJson(a: JSONArray) = (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }
}

/** A book with its place on the shelf. [position] counts from 1. */
data class Placed(val book: Book, val position: Int, val classified: Boolean)

object Shelf {
    /**
     * Every book in shelf order. Books with no number in [system] go after all the
     * numbered ones, by author and then title, so they still have a place to stand.
     */
    fun order(books: List<Book>, system: CallNumber.System): List<Placed> {
        val keyed = books.map { it to CallNumber.key(it.callNumber(system), system) }
        val byName = compareBy<Book>({ it.authorSort }, { it.titleSort }, { it.id })
        val classified = keyed.filter { it.second != null }
            .sortedWith(compareBy<Pair<Book, String?>> { it.second }.thenBy(byName) { it.first })
            .map { it.first }
        val rest = keyed.filter { it.second == null }.map { it.first }.sortedWith(byName)
        return classified.mapIndexed { i, b -> Placed(b, i + 1, true) } +
            rest.mapIndexed { i, b -> Placed(b, classified.size + i + 1, false) }
    }
}
