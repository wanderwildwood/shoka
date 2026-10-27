package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldersTest {
    private val root = "https://cloud.example/remote.php/dav/files/ann"

    @Test fun parentsComeBeforeChildren() {
        assertEquals(
            listOf("$root/Books", "$root/Books/Shelf", "$root/Books/Shelf/Home"),
            Folders.levels(root, "$root/Books/Shelf/Home"),
        )
    }

    @Test fun singleLevelAndEmptySegments() {
        assertEquals(listOf("$root/Shelf"), Folders.levels("$root/", "$root//Shelf/"))
        assertTrue(Folders.levels(root, root).isEmpty())
    }

    @Test fun encodedNamesStayEncoded() {
        assertEquals(listOf("$root/My%20Books"), Folders.levels(root, "$root/My%20Books"))
    }

    @Test fun existingFolderIsFine() {
        for (c in listOf(201, 405, 301, 308)) assertTrue(c.toString(), Folders.fine(c))
        for (c in listOf(401, 403, 404, 409, 500, 507)) assertFalse(c.toString(), Folders.fine(c))
    }
}
