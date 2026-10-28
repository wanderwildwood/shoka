package com.wanderwildwood.shoka

import com.wanderwildwood.shoka.cloud.Account
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The catalogue sync end to end against a stand-in server, including a folder not yet made. */
class CloudSyncTest {
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() = server.shutdown()

    @Test fun firstSyncOnAServerWithNoFolderMakesItAndWritesBothFiles() {
        val cloud = Cloud(Account(server.url("/").toString().trimEnd('/'), "ann", "pw"), "Books")
        server.enqueue(MockResponse().setResponseCode(404))                      // GET catalogue: none yet
        server.enqueue(MockResponse().setResponseCode(409))                      // PUT: no folder
        server.enqueue(MockResponse().setResponseCode(201))                      // MKCOL Books
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"t1\"")) // PUT again
        server.enqueue(MockResponse().setResponseCode(201))                      // PUT csv
        val synced = cloud.sync(emptyList(), CallNumber.System.DEWEY) { it }
        assertEquals("\"t1\"", synced.etag)
        val methods = List(5) { server.takeRequest().method }
        assertEquals(listOf("GET", "PUT", "MKCOL", "PUT", "PUT"), methods)
    }

    @Test fun unchangedCatalogueWritesNothing() {
        val cloud = Cloud(Account(server.url("/").toString().trimEnd('/'), "ann", "pw"), "Books")
        server.enqueue(MockResponse().setBody("""{"version":1,"books":[]}""").setHeader("ETag", "\"t9\""))
        val synced = cloud.sync(emptyList(), CallNumber.System.DEWEY) { it }
        assertEquals("\"t9\"", synced.etag)
        assertTrue(synced.books.isEmpty())
        assertEquals(1, server.requestCount)
    }
}
