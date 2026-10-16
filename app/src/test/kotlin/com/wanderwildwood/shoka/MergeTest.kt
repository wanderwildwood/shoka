package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeTest {
    @Test fun laterChangeWins() {
        val mine = listOf(Book(id = 1, title = "Dune", lc = "PS3558.E63 D8", modified = 10))
        val theirs = listOf(Book(id = 1, title = "Dune", lc = "PS3558.E63 D8 2005", modified = 20))
        assertEquals("PS3558.E63 D8 2005", Merge.merge(mine, theirs).single().lc)
        assertEquals("PS3558.E63 D8 2005", Merge.merge(theirs, mine).single().lc)
    }

    @Test fun bothSidesNewBooksKept() {
        val m = Merge.merge(listOf(Book(id = 1, modified = 1)), listOf(Book(id = 2, modified = 1)))
        assertEquals(listOf(1L, 2L), m.map { it.id })
    }

    @Test fun deletionTravels() {
        val mine = listOf(Book(id = 1, title = "x", modified = 5))
        val theirs = listOf(Book(id = 1, title = "x", deleted = true, modified = 9))
        assertTrue(Merge.merge(mine, theirs).single().deleted)
    }

    @Test fun sameIsbnScannedOnTwoPhonesBecomesOneBook() {
        val mine = listOf(Book(id = 100, isbn = "9780441013593", modified = 1))
        val theirs = listOf(Book(id = 200, isbn = "9780441013593", copies = 2, modified = 2))
        val m = Merge.merge(mine, theirs)
        val live = m.filterNot { it.deleted }
        assertEquals(1, live.size)
        assertEquals(100L, live.single().id)
        assertEquals(2, live.single().copies)
        // Merging again changes nothing, so two phones settle instead of rewriting forever.
        assertTrue(Merge.same(m, Merge.merge(m, m)))
    }
}
