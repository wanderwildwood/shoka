package com.wanderwildwood.shoka

/** Plain RFC 4180 CSV: enough for a spreadsheet to open, and for this app to read back. */
object Csv {
    val header = listOf(
        "position", "section", "lc", "dewey", "title", "author", "year", "isbn", "lccn", "copies", "source", "fiction",
    )

    /** [sectionName] turns a [Placed.section] into the heading the shelf list shows. */
    fun write(rows: List<Placed>, system: CallNumber.System, sectionName: (String) -> String = { it }): String = buildString {
        appendLine(header.joinToString(","))
        for (p in rows) {
            val b = p.book
            appendLine(
                listOf(
                    p.position.toString(), if (system == CallNumber.System.SECTIONS) sectionName(p.section) else "",
                    b.lc, b.ddc, b.title, b.author, b.year, b.isbn, b.lccn, b.copies.toString(), b.source,
                    when (b.fiction) { true -> "yes"; false -> "no"; null -> "" },
                ).joinToString(",", transform = ::quote)
            )
        }
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
                title = col(r, "title"),
                author = col(r, "author"),
                year = col(r, "year"),
                lc = CallNumber.cleanLc(col(r, "lc")),
                ddc = CallNumber.cleanDdc(col(r, "dewey")),
                source = col(r, "source").ifEmpty { "imported" },
                status = Status.FOUND,
                copies = col(r, "copies").toIntOrNull()?.coerceAtLeast(1) ?: 1,
                added = System.currentTimeMillis(),
                fiction = when (col(r, "fiction").lowercase()) { "yes" -> true; "no" -> false; else -> null },
            )
        }.filter { it.title.isNotEmpty() || it.isbn.isNotEmpty() }
    }
}
