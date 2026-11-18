package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceTest {
    private val words = mapOf(Source.BY_HAND to "à la main", Source.OTHER_EDITION to "autre édition")

    @Test fun catalogNamesStayAndWordsAreShownInTheirOwnText() {
        assertEquals("à la main", Source.shown(Source.BY_HAND) { words[it] })
        assertEquals("Open Library", Source.shown("Open Library") { words[it] })
        val other = Source.withOtherEdition("Open Library")
        assertEquals("Open Library + another edition", other)
        assertEquals("Open Library + autre édition", Source.shown(other) { words[it] })
    }

    @Test fun onlyCatalogBooksWithoutLocAreWithoutLoc() {
        assertTrue(Source.withoutLoc("Open Library"))
        assertTrue(Source.withoutLoc("K10plus"))
        assertTrue(Source.withoutLoc(Source.withOtherEdition("Open Library")))
        assertFalse(Source.withoutLoc("Library of Congress"))
        assertFalse(Source.withoutLoc("LoC + Open Library"))
        assertFalse(Source.withoutLoc(Source.withOtherEdition("Library of Congress")))
        assertFalse(Source.withoutLoc(Source.BY_HAND))
        assertFalse(Source.withoutLoc(Source.EDITED))
        assertFalse(Source.withoutLoc(Source.IMPORTED))
        assertFalse(Source.withoutLoc(""))
    }

    @Test fun aFewFoundBooksWithoutDeweyAreAskedAgainEachOnce() {
        fun found(id: Long, source: String = "Open Library", ddc: String = "", isbn: String = "9780000000002") =
            Book(id = id, isbn = isbn, ddc = ddc, source = source, status = Status.FOUND)
        val books = listOf(
            found(1),
            found(2, ddc = "823.914"),
            found(3, source = "LoC + Open Library"),
            found(4, isbn = ""),
            Book(id = 5, isbn = "9780000000002", source = "Open Library", status = Status.LOOKING),
            found(6, source = "K10plus"),
            found(7),
            found(8),
        )
        assertEquals(listOf(1L, 6L), Source.toRecheck(books, emptySet(), 2).map { it.id })
        assertEquals(listOf(6L, 7L), Source.toRecheck(books, setOf(1L), 2).map { it.id })
        assertEquals(listOf(8L), Source.toRecheck(books, setOf(1L, 6L, 7L), 2).map { it.id })
        assertTrue(Source.toRecheck(books, setOf(1L, 6L, 7L, 8L), 2).isEmpty())
    }
}
