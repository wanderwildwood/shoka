package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Test

class SpokenTest {
    private fun n(said: String) = Spoken.number(said)

    @Test fun digitsWithSpaces() = assertEquals("9780143039433", n("978 0 14 303943 3"))
    @Test fun digitsWithFullStop() = assertEquals("9780143039433", n("978 0 14 303943 3."))
    @Test fun hyphenatedIsbnKeepsItsHyphens() = assertEquals("978-0-14-303943-3", n("978-0-14-303943-3."))
    @Test fun hyphenatedIsbnIsValid() = assertEquals("9780143039433", Isbn.normalize(n("978-0-14-303943-3")))
    @Test fun commasInsideNumbers() = assertEquals("9780143039433", n("9,780,143,039,433"))
    @Test fun prefixWordDropped() = assertEquals("9780441013593", n("ISBN 9780441013593"))

    @Test fun wordsOneByOne() =
        assertEquals("9780143039433", n("Nine seven eight zero one four three zero three nine four three three."))
    @Test fun ohIsZero() = assertEquals("9780140", n("nine seven eight oh one four oh"))
    @Test fun wordsWithCommas() = assertEquals("978014", n("Nine, seven, eight, zero, one, four."))
    @Test fun hyphenatedWords() = assertEquals("978", n("nine-seven-eight"))
    @Test fun mixedWordsAndDigits() = assertEquals("9780143039433", n("978 oh 14 three oh three nine four 33"))

    @Test fun doubleAndTriple() = assertEquals("4405553", n("double four oh triple five three"))
    @Test fun doubleDigit() = assertEquals("44", n("double 4"))

    @Test fun tensAndTeens() = assertEquals("97811415", n("ninety seven eighty one fourteen fifteen"))
    @Test fun tensAlone() = assertEquals("2030", n("twenty thirty"))
    @Test fun twentyOneHyphenated() = assertEquals("21", n("twenty-one"))
    @Test fun hundreds() = assertEquals("978", n("nine hundred seventy eight"))
    @Test fun hundredAnd() = assertEquals("905", n("nine hundred and five"))
    @Test fun hundredAlone() = assertEquals("300", n("three hundred"))

    @Test fun exCheckDigit() = assertEquals("043942089X", n("zero four three nine four two zero eight nine ex"))
    @Test fun xCheckDigitIsValid() = assertEquals("9780439420891", Isbn.normalize(n("0 4 3 9 4 2 0 8 9 X.")))
    @Test fun xGluedToDigits() = assertEquals("043942089X", n("043942089x"))
    @Test fun xOnlyAtTheEnd() = assertEquals("0439", n("zero x four three nine"))
    @Test fun xAloneIsNothing() = assertEquals("", n("X"))

    @Test fun lccnDash() = assertEquals("65-21385", n("sixty five dash two one three eight five"))
    @Test fun lccnDashIsValid() = assertEquals("65021385", Lccn.normalize(n("Sixty-five, dash, 21385.")))
    @Test fun lccnWritten() = assertEquals("65-21385", n("65-21385"))
    @Test fun noStrayHyphens() = assertEquals("978", n("- 978 -- "))

    @Test fun nothingNumeric() = assertEquals("", n("I don't know."))

}
