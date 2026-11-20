package com.wanderwildwood.shoka

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTest {
    @Test fun sameEtagIsLeftAlone() = assertFalse(Watch.moved("\"abc\"", "\"abc\""))

    @Test fun anotherPhoneWroteSoItSyncs() = assertTrue(Watch.moved("\"abc\"", "\"def\""))

    @Test fun neverSeenSyncsOnce() = assertTrue(Watch.moved(null, "\"abc\""))

    @Test fun catalogGoneFromTheServerSyncs() = assertTrue(Watch.moved("\"abc\"", ""))
    @Test fun aCompressedFormOfTheSameEtagIsNotAMove() = assertFalse(Watch.moved("\"abc\"", "W/\"abc-gzip\""))

    // Home signed out while the app was away: its stale password is never sent before Home answers.
    @Test fun theFirstLookWaitsForHome() = runBlocking {
        val home = Job()
        var looks = 0
        val watch = launch { Watch.loop(home, 60_000) { looks++ } }
        repeat(3) { yield() }
        assertEquals(0, looks)
        home.complete()
        repeat(3) { yield() }
        assertEquals(1, looks)
        watch.cancel()
    }

    @Test fun withNothingToAskItLooksAtOnce() = runBlocking {
        var looks = 0
        val watch = launch { Watch.loop(null, 60_000) { looks++ } }
        repeat(3) { yield() }
        assertEquals(1, looks)
        watch.cancel()
    }
}
