package com.wanderwildwood.shoka

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTest {
    @Test fun sameEtagIsLeftAlone() = assertFalse(Watch.moved("\"abc\"", "\"abc\""))

    @Test fun anotherPhoneWroteSoItSyncs() = assertTrue(Watch.moved("\"abc\"", "\"def\""))

    @Test fun neverSeenSyncsOnce() = assertTrue(Watch.moved(null, "\"abc\""))

    @Test fun catalogueGoneFromTheServerSyncs() = assertTrue(Watch.moved("\"abc\"", ""))
}
