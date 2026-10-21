package com.wanderwildwood.shoka

import org.junit.Assert.assertEquals
import org.junit.Test

class PullTest {
    @Test
    fun aSyncThatWentThroughIsUpToDate() {
        assertEquals(Pulled.UpToDate, pulledAfter(Library.SyncState.DONE))
    }

    @Test
    fun aSyncThatDidNotIsSaidSo() {
        assertEquals(Pulled.Failed, pulledAfter(Library.SyncState.FAILED))
    }
}
