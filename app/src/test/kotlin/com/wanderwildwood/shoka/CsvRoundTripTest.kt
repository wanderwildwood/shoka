package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvRoundTripTest {
    private val day = 1_761_955_200_000L // 2025-11-01T00:00:00Z

    private fun ids(): () -> Long { var n = 0L; return { ++n } }

    private fun placed(vararg books: Book) = books.mapIndexed { i, b -> Placed(b, i + 1, classified = true) }

    @Test fun `status and added date survive a round trip`() {
        val books = listOf(
            Book(id = 7, title = "The Quiet Orchard", author = "Ada Whitlock", ddc = "813.6",
                status = Status.FOUND, added = day + 3_600_000L),
            Book(id = 8, title = "Notes on Rivers", author = "Tomas Reyes",
                status = Status.NOT_FOUND, added = day + 86_400_000L, source = "by hand"),
            Book(id = 9, isbn = "9780000000002", status = Status.LOOKING, added = day),
            Book(id = 10, title = "Field Lanterns", author = "Ada Whitlock",
                status = Status.OFFLINE, added = day + 1_000L),
        )
        val text = Csv.write(placed(*books.toTypedArray()), CallNumber.System.DEWEY)
        val back = Csv.read(text, ids())
        assertEquals(books.map { it.status }, back.map { it.status })
        assertEquals(books.map { it.added }, back.map { it.added })
    }

    @Test fun `a file from an older version reads as before`() {
        val old = "position,section,lc,dewey,title,author,year,isbn,lccn,copies,source,fiction,genre\n" +
            "1,,,813.6,The Quiet Orchard,Ada Whitlock,2019,,,2,loc,yes,\n" +
            "2,,,551.48,Notes on Rivers,Tomas Reyes,2021,,,1,,,\n"
        val before = System.currentTimeMillis()
        val back = Csv.read(old, ids())
        val after = System.currentTimeMillis()
        assertEquals(2, back.size)
        assertTrue(back.all { it.status == Status.FOUND })
        assertTrue(back.all { it.added in before..after })
        assertEquals(listOf(2, 1), back.map { it.copies })
        assertEquals("imported", back[1].source)
    }

    @Test fun `a blank or strange status or date falls back to today's behaviour`() {
        val text = "title,author,status,added\n" +
            "The Quiet Orchard,Ada Whitlock,,\n" +
            "Notes on Rivers,Tomas Reyes,borrowed,someday\n"
        val before = System.currentTimeMillis()
        val back = Csv.read(text, ids())
        assertTrue(back.all { it.status == Status.FOUND })
        assertTrue(back.all { it.added >= before })
    }

    @Test fun `a status and date typed in a spreadsheet are understood`() {
        val text = "title,author,status,added\n" +
            "The Quiet Orchard,Ada Whitlock,Not found,2025-11-01\n" +
            "Notes on Rivers,Tomas Reyes,LOOKING,2025-11-01T01:00:00Z\n"
        val back = Csv.read(text, ids())
        assertEquals(listOf(Status.NOT_FOUND, Status.LOOKING), back.map { it.status })
        assertEquals(listOf(day, day + 3_600_000L), back.map { it.added })
    }

    @Test fun `the new columns come after the old ones`() {
        val old = listOf(
            "position", "section", "lc", "dewey", "title", "author", "year", "isbn", "lccn", "copies", "source", "fiction", "genre",
        )
        assertEquals(old, Csv.header.take(old.size))
        assertEquals(listOf("status", "added"), Csv.header.drop(old.size))
    }
}
