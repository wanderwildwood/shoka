package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchTest {
    @Test fun literaryFormFromMarc008() {
        assertEquals(true, Match.formIsFiction('1'))
        assertEquals(true, Match.formIsFiction('f'))
        assertEquals(false, Match.formIsFiction('0'))
        assertEquals(false, Match.formIsFiction('p')) // poetry
        assertNull(Match.formIsFiction('u'))
        assertNull(Match.formIsFiction(null))
    }

    @Test fun openLibrarySubjects() {
        assertEquals(true, Match.subjectsSayFiction(listOf("Runaway teenagers", "Fiction")))
        assertEquals(true, Match.subjectsSayFiction(listOf("post-apocalyptic fiction")))
        assertNull(Match.subjectsSayFiction(listOf("hunting", "foodies")))
        // A subject about fiction-writing is not a novel's subject.
        assertNull(Match.subjectsSayFiction(listOf("Fiction -- Authorship")))
    }

    @Test fun surnames() {
        assertEquals("Bradbury", Match.surname("Ray Bradbury"))
        assertEquals("Bradbury", Match.surname("Bradbury, Ray, 1920-2012"))
        assertEquals("Márquez", Match.surname("García Márquez, Gabriel"))
    }

    @Test fun accentsComeOffForTheSearch() {
        assertEquals("Garcia Marquez", Match.fold("García Márquez"))
        assertTrue(Match.sameWork(Found(title = "Cien años de soledad", author = "Gabriel Garcia Marquez"), Found(title = "Cien años de soledad", author = "García Márquez, Gabriel")))
    }

    @Test fun anotherEditionMustBeTheSameWork() {
        val mine = Found(title = "Fahrenheit 451", author = "Ray Bradbury")
        assertTrue(Match.sameWork(mine, Found(title = "Fahrenheit 451", author = "Bradbury, Ray, 1920-2012")))
        assertTrue(Match.sameWork(Found(title = "The road", author = "Cormac McCarthy"), Found(title = "Road", author = "McCarthy, Cormac")))
        assertFalse(Match.sameWork(mine, Found(title = "Fahrenheit 451 revisited", author = "Bradbury, Ray")))
        assertFalse(Match.sameWork(mine, Found(title = "Fahrenheit 451", author = "Smith, Jane")))
    }
}
