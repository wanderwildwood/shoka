package com.wanderwildwood.shoka

import com.wanderwildwood.shoka.ui.ScanLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLayoutTest {
    @Test fun cameraIsShortOnTheKompakt() {
        assertEquals(104, ScanLayout.cameraHeightDp(610))
        assertEquals(180, ScanLayout.cameraHeightDp(850))
    }

    @Test fun longHintOnlyOnAnEmptyShelf() {
        assertTrue(ScanLayout.fullHint(0))
        assertFalse(ScanLayout.fullHint(1))
    }
}
