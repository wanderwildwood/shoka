package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallNumberTest {

    private fun lcOrder(vararg calls: String) =
        calls.toList().shuffled(java.util.Random(7)).sortedBy { CallNumber.lcKey(it)!! }

    @Test fun classNumbersSortAsNumbersNotText() {
        val want = listOf("QA9 .A1", "QA76 .A1", "QA76.73 .J38", "QA76.9 .D3", "QA100 .B2")
        assertEquals(want, lcOrder(*want.toTypedArray()))
    }

    @Test fun cuttersSortAsDecimals() {
        // .E63 sits before .E7 on a shelf, and .E7 before .E8.
        val want = listOf("PS3558.E6", "PS3558.E63 D8 2005", "PS3558.E7", "PS3558.E8 A1")
        assertEquals(want, lcOrder(*want.toTypedArray()))
    }

    @Test fun classLettersSortAlphabeticallyFirst() {
        val want = listOf("B105 .A1", "BF1 .A1", "BL51 .A1", "P1 .A1", "PR6039.O32 H6", "PS1 .A1", "Z1 .A1")
        assertEquals(want, lcOrder(*want.toTypedArray()))
    }

    @Test fun datesAfterCuttersSortNumerically() {
        val want = listOf("PZ7.T5744 Ho 1966", "PZ7.T5744 Ho 2007", "PZ7.T5744 Ho 2012")
        assertEquals(want, lcOrder(*want.toTypedArray()))
    }

    @Test fun spacingDoesNotMatter() {
        assertEquals(CallNumber.lcKey("PS3558.E63 D8 2005"), CallNumber.lcKey("PS3558.E63D8 2005"))
    }

    @Test fun notCallNumbers() {
        assertNull(CallNumber.lcKey("[Fic]"))
        assertNull(CallNumber.lcKey(""))
        assertEquals("", CallNumber.cleanDdc("[Fic]"))
    }

    @Test fun deweyDropsSegmentationAndSortsAsDecimal() {
        assertEquals("813.54", CallNumber.cleanDdc("813/.54"))
        val want = listOf("005.133", "005.2", "813.52", "813.54", "900")
        assertEquals(want, want.reversed().sortedBy { CallNumber.ddcKey(it)!! })
    }

    @Test fun unnumberedBooksGoLastByAuthor() {
        val books = listOf(
            Book(id = 1, title = "Walden", author = "Henry David Thoreau"),
            Book(id = 2, title = "Dune", author = "Herbert, Frank", ddc = "813.54"),
            Book(id = 3, title = "The Hobbit", author = "Tolkien, J. R. R.", ddc = "823.912"),
            Book(id = 4, title = "Emma", author = "Austen, Jane"),
        )
        val order = Shelf.order(books, CallNumber.System.DEWEY)
        assertEquals(listOf(2L, 3L, 4L, 1L), order.map { it.book.id })
        assertEquals(listOf(1, 2, 3, 4), order.map { it.position })
        assertEquals(listOf(true, true, false, false), order.map { it.classified })
    }

    @Test fun sameDeweyNumberGoesByAuthor() {
        val books = listOf(
            Book(id = 1, title = "The dispossessed", author = "Le Guin, Ursula K.", ddc = "813.54"),
            Book(id = 2, title = "Dune", author = "Herbert, Frank", ddc = "813.54"),
        )
        assertEquals(listOf(2L, 1L), Shelf.order(books, CallNumber.System.DEWEY).map { it.book.id })
    }
}
