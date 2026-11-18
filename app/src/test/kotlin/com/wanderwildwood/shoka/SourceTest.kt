package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
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
}
