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

    @Test fun fictionThenDeweyHundredsThenNoneByAuthorWithin() {
        val got = order(
            Book(id = 1, title = "Cosmos", author = "Sagan, Carl", ddc = "520"),
            Book(id = 2, title = "Dune", author = "Herbert, Frank", ddc = "813.54"),
            Book(id = 3, title = "Walden", author = "Thoreau, Henry David", ddc = "818.303"),
            Book(id = 4, title = "Emma", author = "Austen, Jane", ddc = "823.7"),
            Book(id = 5, title = "Family notes", author = "Anon"),
            Book(id = 6, title = "Ethics", author = "Spinoza, Benedictus de", ddc = "170"),
            Book(id = 7, title = "A brief history of time", author = "Hawking, Stephen", ddc = "523.1"),
            Book(id = 8, title = "Collected poems", author = "Auden, W. H.", ddc = "811.52"),
        )
        assertEquals(
            listOf(
                "Emma", "Dune", // Fiction
                "Ethics", // 100
                "A brief history of time", "Cosmos", // 500, by author not number
                "Collected poems", "Walden", // 800: poetry and essays stay in Literature
                "Family notes", // no number
            ),
            got,
        )
    }

    @Test fun sectionsAndPositions() {
        val placed = Shelf.order(
            listOf(
                Book(id = 1, title = "x", ddc = "520"),
                Book(id = 2, title = "y", ddc = "813.54"),
                Book(id = 3, title = "z"),
            ),
            CallNumber.System.SECTIONS,
        )
        assertEquals(listOf(Shelf.FICTION, "5", ""), placed.map { it.section })
        assertEquals(listOf(1, 2, 3), placed.map { it.position })
        assertEquals(listOf(true, true, false), placed.map { it.classified })
    }

    @Test fun spreadsheetCarriesTheSectionAndTheFictionChoice() {
        val placed = Shelf.order(
            listOf(Book(id = 1, title = "Zen", author = "Pirsig", ddc = "917.304", fiction = true)),
            CallNumber.System.SECTIONS,
        )
        val rows = Csv.parse(Csv.write(placed, CallNumber.System.SECTIONS) { if (it == Shelf.FICTION) "Fiction" else it })
        val head = rows[0]
        assertEquals("Fiction", rows[1][head.indexOf("section")])
        assertEquals("yes", rows[1][head.indexOf("fiction")])
        assertEquals(true, Csv.read(Csv.write(placed, CallNumber.System.SECTIONS)) { 9 }.single().fiction)
    }

    @Test fun genresFollowGeneralFictionInOrder() {
        val got = order(
            Book(id = 1, title = "Cosmos", author = "Sagan, Carl", ddc = "520"),
            Book(id = 2, title = "Dune", author = "Herbert, Frank", ddc = "813.54", genre = "scifi"),
            Book(id = 3, title = "It", author = "King, Stephen", ddc = "813.54", genre = "horror"),
            Book(id = 4, title = "Emma", author = "Austen, Jane", ddc = "823.7"),
            Book(id = 5, title = "Peril", author = "Christie, Agatha", ddc = "823.912", genre = "crime"),
            // Chosen as general by hand, and a genre on a book that isn't fiction, both count for nothing.
            Book(id = 6, title = "Beloved", author = "Morrison, Toni", ddc = "813.54", genre = Shelf.FICTION),
            Book(id = 7, title = "Ethics", author = "Spinoza", ddc = "170", genre = "horror"),
        )
        assertEquals(listOf("Emma", "Beloved", "Peril", "Dune", "It", "Ethics", "Cosmos"), got)
    }

    @Test fun spreadsheetCarriesTheGenre() {
        val placed = Shelf.order(listOf(Book(id = 1, title = "Dune", ddc = "813.54", genre = "scifi")), CallNumber.System.SECTIONS)
        assertEquals("scifi", Csv.read(Csv.write(placed, CallNumber.System.SECTIONS)) { 9 }.single().genre)
    }
}
