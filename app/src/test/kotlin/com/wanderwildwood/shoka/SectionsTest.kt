package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {

    private fun order(vararg books: Book) =
        Shelf.order(books.toList(), CallNumber.System.SECTIONS).map { it.book.title }

    @Test fun fictionFromDeweyAndPz() {
        assertTrue(Book(id = 1, ddc = "813.54").fictionGuess)
        assertTrue(Book(id = 1, ddc = "891.734").fictionGuess)
        assertTrue(Book(id = 1, ddc = "895.635").fictionGuess)
        assertTrue(Book(id = 1, lc = "PZ7.T5744 Ho 1966").fictionGuess)
        assertFalse(Book(id = 1, ddc = "811.54").fictionGuess) // poetry
        assertFalse(Book(id = 1, ddc = "530.12", lc = "QC174.12 .F45").fictionGuess)
    }

    @Test fun setByHandOverrulesTheGuess() {
        assertFalse(Book(id = 1, ddc = "813.54", fiction = false).isFiction)
        assertTrue(Book(id = 1, lc = "PS3511.R94 P6", fiction = true).isFiction)
    }

    @Test fun fictionThenClassLettersThenNoneByAuthorWithin() {
        val got = order(
            Book(id = 1, title = "Cosmos", author = "Sagan, Carl", lc = "QB44.2 .S235"),
            Book(id = 2, title = "Dune", author = "Herbert, Frank", lc = "PS3558.E63 D8", ddc = "813.54"),
            Book(id = 3, title = "Walden", author = "Thoreau, Henry David", lc = "PS3048 .A1"),
            Book(id = 4, title = "Emma", author = "Austen, Jane", lc = "PR4034 .E5", ddc = "823.7"),
            Book(id = 5, title = "Family notes", author = "Anon"),
            Book(id = 6, title = "Ethics", author = "Spinoza, Benedictus de", lc = "B3973 .E5"),
            Book(id = 7, title = "A brief history of time", author = "Hawking, Stephen", lc = "QB981 .H377"),
        )
        assertEquals(
            listOf("Emma", "Dune", "Ethics", "Walden", "A brief history of time", "Cosmos", "Family notes"),
            got,
        )
    }

    @Test fun sectionsAndPositions() {
        val placed = Shelf.order(
            listOf(
                Book(id = 1, title = "x", lc = "QB44.2 .S235"),
                Book(id = 2, title = "y", ddc = "813.54"),
                Book(id = 3, title = "z"),
            ),
            CallNumber.System.SECTIONS,
        )
        assertEquals(listOf(Shelf.FICTION, "Q", ""), placed.map { it.section })
        assertEquals(listOf(1, 2, 3), placed.map { it.position })
        assertEquals(listOf(true, true, false), placed.map { it.classified })
    }

    @Test fun spreadsheetCarriesTheSectionAndTheFictionChoice() {
        val placed = Shelf.order(
            listOf(Book(id = 1, title = "Zen", author = "Pirsig", lc = "CT275.P648 A3", fiction = true)),
            CallNumber.System.SECTIONS,
        )
        val rows = Csv.parse(Csv.write(placed, CallNumber.System.SECTIONS) { if (it == Shelf.FICTION) "Fiction" else it })
        val head = rows[0]
        assertEquals("Fiction", rows[1][head.indexOf("section")])
        assertEquals("yes", rows[1][head.indexOf("fiction")])
        assertEquals(true, Csv.read(Csv.write(placed, CallNumber.System.SECTIONS)) { 9 }.single().fiction)
    }
}
