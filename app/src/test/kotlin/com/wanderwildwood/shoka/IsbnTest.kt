package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsbnTest {
    @Test fun thirteenPassesThrough() = assertEquals("9780441013593", Isbn.normalize("978-0-441-01359-3"))
    @Test fun tenBecomesThirteen() = assertEquals("9780441013593", Isbn.normalize("0441013597"))
    @Test fun badCheckDigit() = assertNull(Isbn.normalize("9780441013594"))
    @Test fun issnIsNotABook() = assertNull(Isbn.normalize("9770028006003"))
    @Test fun backToTen() = assertEquals("0441013597", Isbn.to10("9780441013593"))
    @Test fun xCheckDigitRoundTrip() {
        val ten = "043942089X"
        assertEquals(ten, Isbn.to10(Isbn.normalize(ten)!!))
    }
    @Test fun lccnHyphenated() = assertEquals("65021385", Lccn.normalize("65-21385"))
    @Test fun lccnModern() = assertEquals("2006274831", Lccn.normalize("2006274831"))
    @Test fun notAnLccn() = assertNull(Lccn.normalize("hello"))
}
