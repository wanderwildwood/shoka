package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogsTest {
    @Test fun nationalLibraryByIsbnPrefix() {
        assertEquals(Match.National.NORWAY, Match.nationalLibrary("9788200000001"))
        assertEquals(Match.National.GERMANY, Match.nationalLibrary("9783000000001"))
        assertEquals(Match.National.SWEDEN, Match.nationalLibrary("9789100000001"))
        assertEquals(Match.National.FINLAND, Match.nationalLibrary("9789510425442"))
        assertEquals(Match.National.FINLAND, Match.nationalLibrary("9789526753430"))
        assertEquals(Match.National.NETHERLANDS, Match.nationalLibrary("9789041414540"))
        assertEquals(Match.National.NETHERLANDS, Match.nationalLibrary("9789400000001"))
        assertEquals(Match.National.FRANCE, Match.nationalLibrary("9782072895098"))
        // 979-10 is France too.
        assertEquals(Match.National.FRANCE, Match.nationalLibrary("9791000000001"))
        assertEquals(Match.National.POLAND, Match.nationalLibrary("9788308039861"))
        // 978-87 is Denmark, 978-0 English, 978-953 Croatia: no national library of theirs is asked.
        assertNull(Match.nationalLibrary("9788700000001"))
        assertNull(Match.nationalLibrary("9780000000002"))
        assertNull(Match.nationalLibrary("9789530000001"))
    }

    private fun sample(name: String) =
        javaClass.classLoader!!.getResourceAsStream("catalogs/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    // Saved answers from each catalog to a lookup of a well-known book of that country.

    @Test fun finnaKeepsTheRecordWithThisIsbn() {
        val json = sample("finna-puhdistus.json")
        val f = Match.finna(json, "9789510425442")!!
        assertEquals("Puhdistus", f.title)
        assertEquals("Oksanen, Sofi", f.author)
        assertEquals("2017", f.year)
        assertEquals(true, f.fiction)
        assertEquals("Finna", f.source)
        // The same search brings the e-book; asked for its ISBN, that record is the one.
        assertEquals("2025", Match.finna(json, "9789510459942")!!.year)
        // A record that only shares the work is not the book.
        assertNull(Match.finna(json, "9789510000007"))
        assertNull(Match.finna("""{"resultCount":0,"status":"OK"}""", "9789510425442"))
        assertNull(Match.finna("<html>busy</html>", "9789510425442"))
    }

    @Test fun kbDublinCore() {
        val f = Match.kb(sample("kb-het-diner.xml"))!!
        assertEquals("Het diner", f.title)
        assertEquals("Koch, Herman", f.author)
        assertEquals("2009", f.year)
        assertEquals("Koninklijke Bibliotheek", f.source)
        assertNull(Match.kb("""<srw:searchRetrieveResponse xmlns:srw="http://www.loc.gov/zing/srw/"><srw:numberOfRecords>0</srw:numberOfRecords></srw:searchRetrieveResponse>"""))
        assertNull(Match.kb("not xml"))
    }

    @Test fun kbFictionDocumentType() {
        val xml = """<r xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><srw_dc:dc xmlns:srw_dc="info:srw/schema/1/dc-v1.1">
            <dc:type>boek</dc:type><dc:title xsi:type="dcx:maintitle">Salt en lantaarns</dc:title>
            <dc:subject xsi:type="dcx:doctype">Fictie</dc:subject><dc:creator>Whitlock, Ada</dc:creator><dc:date>[2019]</dc:date>
            </srw_dc:dc></r>"""
        val f = Match.kb(xml)!!
        assertEquals(true, f.fiction)
        assertEquals("Whitlock, Ada", f.author)
        assertEquals("2019", f.year)
    }

    @Test fun bnfUnimarc() {
        val f = Match.unimarc(sample("bnf-anomalie.xml"), "BnF").single()
        // 200 $e "roman" only names the form: no subtitle, and it says fiction.
        assertEquals("L'anomalie", f.title)
        assertEquals("Le Tellier, Hervé", f.author)
        assertEquals("2020", f.year)
        assertEquals(true, f.fiction)
        assertEquals("", f.genre)
        assertEquals("BnF", f.source)
        assertEquals(emptyList<Found>(), Match.unimarc("not xml", "BnF"))
    }

    @Test fun unimarcSubtitleDeweyAndChildren() {
        val xml = """<mxc:record xmlns:mxc="info:lc/xmlns/marcxchange-v2" format="UNIMARC">
            <mxc:datafield tag="100"><mxc:subfield code="a">20190402d2019    c  y0frey50      ba</mxc:subfield></mxc:datafield>
            <mxc:datafield tag="105"><mxc:subfield code="a">||||z   00|y|</mxc:subfield></mxc:datafield>
            <mxc:datafield tag="200"><mxc:subfield code="a">Sel et lanternes</mxc:subfield><mxc:subfield code="e">une année sur la côte</mxc:subfield></mxc:datafield>
            <mxc:datafield tag="676"><mxc:subfield code="a">914.4</mxc:subfield></mxc:datafield>
            <mxc:datafield tag="701"><mxc:subfield code="a">Reyes</mxc:subfield><mxc:subfield code="b">Tomas</mxc:subfield></mxc:datafield>
            </mxc:record>"""
        val f = Match.unimarc(xml, "BnF").single()
        assertEquals("Sel et lanternes: une année sur la côte", f.title)
        assertEquals("Reyes, Tomas", f.author)
        // No 210 or 214: the year comes from the coded data in 100.
        assertEquals("2019", f.year)
        assertEquals("914.4", f.ddc)
        assertEquals(false, f.fiction)
        assertEquals("children", f.genre)
    }

    @Test fun bnMarcJson() {
        val records = Match.bnRecords(sample("bn-bieguni.json"))
        assertEquals(1, records.size)
        val f = Lookup.fromMarc(records.single(), "Biblioteka Narodowa")
        assertEquals("Bieguni", f.title)
        assertEquals("Tokarczuk, Olga", f.author)
        assertEquals("2007", f.year)
        assertEquals(true, f.fiction)
        assertEquals("Biblioteka Narodowa", f.source)
        assertEquals(emptyList<Any>(), Match.bnRecords("""{"bibs":[],"nextPage":""}"""))
        assertEquals(emptyList<Any>(), Match.bnRecords("busy"))
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
