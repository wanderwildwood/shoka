package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogsTest {
    @Test fun nationalLibraryByIsbnPrefix() {
        assertEquals(Match.National.NORWAY, Match.nationalLibrary("9788200000001"))
        assertEquals(Match.National.GERMANY, Match.nationalLibrary("9783000000001"))
        assertEquals(Match.National.SWEDEN, Match.nationalLibrary("9789100000001"))
        // 978-87 is Denmark, 978-0 English: no national library of theirs is asked.
        assertNull(Match.nationalLibrary("9788700000001"))
        assertNull(Match.nationalLibrary("9780000000002"))
        // 979-10 is France.
        assertNull(Match.nationalLibrary("9791000000001"))
    }

    @Test fun norwegianSearchGivesTheFirstId() {
        val json = """{"_embedded":{"items":[{"id":"abc123","metadata":{"title":"Fjellvann"}},{"id":"def456"}]},"page":{"totalElements":2}}"""
        assertEquals("abc123", Match.nbId(json))
        assertNull(Match.nbId("""{"page":{"totalElements":0}}"""))
        assertNull(Match.nbId("not json"))
    }

    @Test fun googleBooksTitleAuthorYear() {
        val json = """{"totalItems":1,"items":[{"volumeInfo":{"title":"Salt and Lanterns","subtitle":"A Year on the Coast","authors":["Ada Whitlock","Tomas Reyes"],"publishedDate":"2019-04-02"}}]}"""
        val f = Match.googleBooks(json)!!
        assertEquals("Salt and Lanterns: A Year on the Coast", f.title)
        assertEquals("Ada Whitlock", f.author)
        assertEquals("2019", f.year)
        assertEquals("", f.ddc)
        assertEquals("Google Books", f.source)
    }

    @Test fun googleBooksNothingFound() {
        assertNull(Match.googleBooks("""{"kind":"books#volumes","totalItems":0}"""))
        // The answer when the keyless allowance is used up: not a book, and not a crash.
        assertNull(Match.googleBooks("""{"error":{"code":429,"message":"Quota exceeded"}}"""))
        assertNull(Match.googleBooks(""))
    }

    @Test fun dnbSubjectGroups() {
        assertEquals(true, Match.groupsSayFiction(listOf("B")))
        assertNull(Match.groupsSayFiction(listOf("333.7", "360")))
        assertNull(Match.groupsSayFiction(emptyList()))
    }

    @Test fun tidyCatalogText() {
        assertEquals("Die Gartenstunde", Match.tidy("\u0098Die\u009C Gartenstunde"))
        assertEquals("En liten bok om fjell", Match.tidy("En  liten bok om fjell "))
        assertEquals("Plain title", Match.tidy("Plain title"))
    }
}
