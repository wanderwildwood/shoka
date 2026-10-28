package com.wanderwildwood.shoka

import com.wanderwildwood.shoka.cloud.Account
import com.wanderwildwood.shoka.cloud.DavFolder
import com.wanderwildwood.shoka.cloud.LoginFlow
import com.wanderwildwood.shoka.cloud.Refused
import com.wanderwildwood.shoka.cloud.Unreachable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DavFolderTest {
    private lateinit var server: MockWebServer
    private lateinit var dav: DavFolder

    @Before fun start() {
        server = MockWebServer().also { it.start() }
        val account = Account(server.url("/").toString().trimEnd('/'), "ann", "app-password")
        dav = DavFolder(account, "Books/Home")
    }

    @After fun stop() = server.shutdown()

    private val base = "/remote.php/dav/files/ann/Books/Home"

    @Test fun missingFolderIsMadeLevelByLevelThenTheWriteRepeats() {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(201)) // Books
        server.enqueue(MockResponse().setResponseCode(201)) // Books/Home
        server.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"abc\""))
        val put = dav.put("catalogue.json", "{}", "application/json", ifNone = true)
        assertTrue(put.ok)
        assertEquals("\"abc\"", put.etag)
        val seen = List(4) { server.takeRequest() }
        assertEquals(listOf("PUT", "MKCOL", "MKCOL", "PUT"), seen.map { it.method })
        assertEquals("/remote.php/dav/files/ann/Books", seen[1].path)
        assertEquals("/remote.php/dav/files/ann/Books/Home", seen[2].path)
        assertEquals("$base/catalogue.json", seen[3].path)
        assertEquals("*", seen[3].getHeader("If-None-Match"))
    }

    @Test fun folderThatIsAlreadyThereDoesNotStopTheWrite() {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(405)) // Books exists
        server.enqueue(MockResponse().setResponseCode(301)) // slash form
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(dav.put("a.csv", "x", "text/csv").ok)
    }

    @Test fun folderThatCannotBeMadeIsRefusedAndNotRetriedForever() {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(403))
        try { dav.put("a.csv", "x", "text/csv"); fail() } catch (e: Refused) { assertEquals("HTTP 403", e.message) }
        assertEquals(2, server.requestCount)
    }

    @Test fun stillMissingAfterTheFoldersWereMadeIsRefused() {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(409))
        try { dav.put("a.csv", "x", "text/csv"); fail() } catch (_: Refused) { }
        assertEquals(4, server.requestCount)
    }

    @Test fun movedEtagIsNotAnErrorButAFalse() {
        server.enqueue(MockResponse().setResponseCode(412))
        val put = dav.put("catalogue.json", "{}", "application/json", ifMatch = "\"old\"")
        assertFalse(put.ok)
        assertEquals("\"old\"", server.takeRequest().getHeader("If-Match"))
    }

    @Test fun getAnswersTextAndEtagOrNullWhenAbsent() {
        server.enqueue(MockResponse().setBody("{\"books\":[]}").setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals("{\"books\":[]}" to "\"e1\"", dav.get("catalogue.json"))
        assertNull(dav.get("catalogue.json"))
    }

    @Test fun etagByHeadIsEmptyWhenAbsentAndRefusedOnAuthFailure() {
        server.enqueue(MockResponse().setHeader("ETag", "\"e2\""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals("\"e2\"", dav.etag("catalogue.json"))
        assertEquals("HEAD", server.takeRequest().method)
        assertEquals("", dav.etag("catalogue.json"))
        try { dav.etag("catalogue.json"); fail() } catch (_: Refused) { }
    }

    @Test fun signsWithBasicAndAgent() {
        server.enqueue(MockResponse().setResponseCode(404))
        dav.get("catalogue.json")
        val r = server.takeRequest()
        assertEquals("Basic YW5uOmFwcC1wYXNzd29yZA==", r.getHeader("Authorization"))
        assertEquals("Shelf (shoka)", r.getHeader("User-Agent"))
    }

    @Test fun noServerIsUnreachable() {
        val gone = DavFolder(Account("http://127.0.0.1:1", "ann", "x"), "Books")
        try { gone.etag("catalogue.json"); fail() } catch (_: Unreachable) { }
    }

    @Test fun loginFlowStartAndPoll() {
        server.enqueue(MockResponse().setBody("""{"login":"https://l/","poll":{"endpoint":"${server.url("/poll")}","token":"tk"}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"server":"https://cloud.example/","loginName":"ann","appPassword":"pw"}"""))
        val started = LoginFlow.start(server.url("/").toString())
        assertEquals("tk", started.token)
        assertEquals("/index.php/login/v2", server.takeRequest().path)
        assertNull(LoginFlow.poll(started))
        assertEquals(Account("https://cloud.example", "ann", "pw"), LoginFlow.poll(started))
    }
}
