package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvSafeTest {
    @Test fun `a title that would run as a formula is written as text`() {
        assertEquals("'=SUM(A1)", Csv.safe("=SUM(A1)"))
        assertEquals("'-5 Degrees", Csv.safe("-5 Degrees"))
        assertEquals("'@home", Csv.safe("@home"))
        assertEquals("Dune", Csv.safe("Dune"))
        assertEquals("", Csv.safe(""))
    }

    @Test fun `the quote comes off again on reading and only then`() {
        assertEquals("=SUM(A1)", Csv.unsafe("'=SUM(A1)"))
        assertEquals("'Tis Pity", Csv.unsafe("'Tis Pity"))
        assertEquals("Dune", Csv.unsafe("Dune"))
    }
}
