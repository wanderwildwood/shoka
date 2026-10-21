package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintedIsbnTest {
    private val penguin13 = "9780143039433"  // 978-0-14-303943-3
    private val tenX = "9780804429573"        // 0-8044-2957-X

    @Test fun backCover() = assertEquals(penguin13, PrintedIsbn.find("ISBN 978-0-14-303943-3"))
    @Test fun labelWithThirteen() = assertEquals(penguin13, PrintedIsbn.find("ISBN-13: 978-0-14-303943-3"))
    @Test fun labelWithThirteenSpaced() = assertEquals(penguin13, PrintedIsbn.find("ISBN 13 978 0 14 303943 3"))
    @Test fun copyrightPageTen() = assertEquals(tenX, PrintedIsbn.find("ISBN 0-8044-2957-X"))
    @Test fun lowerCaseX() = assertEquals(tenX, PrintedIsbn.find("isbn 0-8044-2957-x"))
    @Test fun labelWithTen() = assertEquals("9780140283297", PrintedIsbn.find("ISBN-10: 0-14-028329-3"))
    // As Tesseract gives them back once only digits, X, hyphens and ISBN are allowed it.
    @Test fun colonAndSpaceDropped() = assertEquals("9798866451234", PrintedIsbn.find("- ISBN-13979-8-8664-5123-4"))
    @Test fun spaceDropped() = assertEquals("9780553380163", PrintedIsbn.find("0 -- \nISBN0-553-38016-8"))
    @Test fun noLabel() = assertEquals(penguin13, PrintedIsbn.find("978-0-14-303943-3"))
    @Test fun undashed() = assertEquals(penguin13, PrintedIsbn.find("ISBN 9780143039433"))
    @Test fun enDashes() = assertEquals(penguin13, PrintedIsbn.find("ISBN 978–0–14–303943–3"))
    @Test fun spacesRoundHyphens() = assertEquals(penguin13, PrintedIsbn.find("ISBN 978 - 0 - 14 - 303943 - 3"))
    @Test fun barcodeDigits() = assertEquals(penguin13, PrintedIsbn.find("9 780143 039433"))
    @Test fun nineSevenNine() = assertEquals("9798866451234", PrintedIsbn.find("ISBN 979-8-8664-5123-4"))

    // OCR's look-alikes, in the label and in the number.
    @Test fun oneForI() = assertEquals(penguin13, PrintedIsbn.find("1SBN 978-0-14-303943-3"))
    @Test fun lForI() = assertEquals(penguin13, PrintedIsbn.find("lSBN 978-0-14-303943-3"))
    @Test fun fiveAndEightInLabel() = assertEquals(penguin13, PrintedIsbn.find("I58N 978-0-14-303943-3"))
    @Test fun oForZero() = assertEquals(penguin13, PrintedIsbn.find("ISBN 978-O-14-3O3943-3"))
    @Test fun iAndLForOne() = assertEquals(penguin13, PrintedIsbn.find("ISBN 978-0-I4-303943-3") ?: "")
        .also { assertEquals(penguin13, PrintedIsbn.find("ISBN 978-0-l4-303943-3")) }
    @Test fun sForFive() = assertEquals("9780553380163", PrintedIsbn.find("ISBN 0-SS3-380l6-8"))
    @Test fun pipeForOne() = assertEquals(penguin13, PrintedIsbn.find("ISBN 978-0-|4-303943-3"))

    // Only a right check digit gets through.
    @Test fun wrongCheckDigit() = assertNull(PrintedIsbn.find("ISBN 978-0-14-303943-4"))
    @Test fun wrongTenCheck() = assertNull(PrintedIsbn.find("1SBN 0-14-303943-X"))
    @Test fun misreadDigit() = assertNull(PrintedIsbn.find("ISBN 978-0-14-308943-3"))
    @Test fun notABookPrefix() = assertNull(PrintedIsbn.find("ISBN 977-0-14-303943-3"))
    @Test fun xInTheMiddle() = assertNull(PrintedIsbn.find("ISBN 0-14-3X3943-1"))
    @Test fun tooShort() = assertNull(PrintedIsbn.find("ISBN 978-0-14-30394"))
    @Test fun nothing() = assertNull(PrintedIsbn.find(""))
    @Test fun words() = assertNull(PrintedIsbn.find("Printed in the United States of America"))

    // A misread ISBN-13 is not cut down to an ISBN-10 that happens to check.
    @Test fun tailOfThirteenNotTakenAsTen() {
        // 0-14-303943-1 is a valid ISBN-10, and it is the tail of this (misread) ISBN-13.
        assertNull(PrintedIsbn.find("ISBN 978-0-14-303943-1"))
        assertNull(PrintedIsbn.find("ISBN 978 0 14 303943 1"))
    }

    @Test fun labelledPreferred() =
        assertEquals("9780140283297", PrintedIsbn.find("9780143039433\nISBN 0-14-028329-3"))
    @Test fun twoOnALine() =
        assertEquals("9780140283297", PrintedIsbn.find("ISBN 0-14-028329-3 (pbk) ISBN 978-0-14-303943-3"))
    @Test fun spacedPair() =
        assertEquals(penguin13, PrintedIsbn.find("9780143039433 0140283293"))
    @Test fun noiseAround() = assertEquals(penguin13, PrintedIsbn.find("— ISBN 978-0-14-303943-3 —"))
    @Test fun noiseLines() = assertEquals(tenX, PrintedIsbn.find("11 1 B\n1SBN 0-8044-2957-X\nN 5"))
    @Test fun badThenGood() =
        assertEquals("9780140283297", PrintedIsbn.find("ISBN 978-0-14-303943-4\nISBN 0-14-028329-3"))
}
