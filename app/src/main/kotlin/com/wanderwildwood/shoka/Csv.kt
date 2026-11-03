package com.wanderwildwood.shoka

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Plain RFC 4180 CSV: enough for a spreadsheet to open, and for this app to read back. */
object Csv {
    val header = listOf(
        "position", "section", "lc", "dewey", "title", "author", "year", "isbn", "lccn", "copies", "source", "fiction", "genre",
        // Added later, at the end, so files from older versions still read: a missing column
        // means what it always did (found, added today).
        "status", "added",
    )

    /** [sectionName] turns a [Placed.section] into the heading the shelf list shows. */
    fun write(rows: List<Placed>, system: CallNumber.System, sectionName: (String) -> String = { it }): String = buildString {
        appendLine(header.joinToString(","))
        for (p in rows) {
            val b = p.book
            appendLine(
                listOf(
                    p.position.toString(), if (system == CallNumber.System.SECTIONS) sectionName(p.section) else "",
                    b.lc, b.ddc, safe(b.title), safe(b.author), b.year, b.isbn, b.lccn, b.copies.toString(), b.source,
                    when (b.fiction) { true -> "yes"; false -> "no"; null -> "" },
                    b.genre,
                    b.status.name.lowercase(), if (b.added > 0) Instant.ofEpochMilli(b.added).toString() else "",
                ).joinToString(",", transform = ::quote)
            )
        }
    }

    /**
     * A spreadsheet runs a cell that starts with = + - @ (or a tab or return) as a formula, and a
     * title is whatever a catalogue or a person typed. A single quote in front makes it text; the
     * reader below takes it off again, so a round trip returns the title as it was.
     */
    internal fun safe(s: String): String =
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'$s" else s

    internal fun unsafe(s: String): String =
        if (s.length > 1 && s[0] == '\'' && s[1] in "=+-@\t\r") s.substring(1) else s

    /** "found", "not found", "NOT_FOUND" ...; null for a blank or unknown word. */
    internal fun status(s: String): Status? {
        val k = s.trim().uppercase().replace(' ', '_').replace('-', '_')
        return Status.entries.firstOrNull { it.name == k }
    }

    /** An instant as written above, or a plain date typed in a spreadsheet (midnight UTC); null otherwise. */
    internal fun added(s: String): Long? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun quote(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"' && i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ }
                else if (c == '"') quoted = false
                else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.clear() }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf() }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    /** Books from a file this app (or a spreadsheet keeping its columns) wrote. */
    fun read(text: String, nextId: () -> Long): List<Book> {
        val rows = parse(text).filter { r -> r.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()
        val names = rows.first().map { it.trim().lowercase() }
        fun col(r: List<String>, name: String) = names.indexOf(name).let { if (it in r.indices) r[it].trim() else "" }
        return rows.drop(1).map { r ->
            Book(
                id = nextId(),
                isbn = Isbn.normalize(col(r, "isbn")) ?: "",
                lccn = col(r, "lccn"),
                title = unsafe(col(r, "title")),
                author = unsafe(col(r, "author")),
                year = col(r, "year"),
                lc = CallNumber.cleanLc(col(r, "lc")),
                ddc = CallNumber.cleanDdc(col(r, "dewey")),
                source = col(r, "source").ifEmpty { "imported" },
                status = status(col(r, "status")) ?: Status.FOUND,
                copies = col(r, "copies").toIntOrNull()?.coerceAtLeast(1) ?: 1,
                added = added(col(r, "added")) ?: System.currentTimeMillis(),
                fiction = when (col(r, "fiction").lowercase()) { "yes" -> true; "no" -> false; else -> null },
                genre = col(r, "genre").lowercase().takeIf { it in Shelf.genres || it == Shelf.FICTION } ?: "",
            )
        }.filter { it.title.isNotEmpty() || it.isbn.isNotEmpty() }
    }
}
