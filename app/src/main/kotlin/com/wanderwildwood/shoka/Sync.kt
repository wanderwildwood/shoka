package com.wanderwildwood.shoka

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * A Nextcloud account as its login page hands it over: the server, the login name, and an
 * app password made for this phone alone, revocable from the server's security settings.
 */
data class Account(val server: String, val user: String, val password: String)

class Refused(message: String) : IOException(message)

private const val AGENT = "Shelf (shoka)"

/** Nextcloud's own sign-in: the reader signs in on the server's page, in the browser. */
object LoginFlow {

    data class Started(val loginUrl: String, val pollUrl: String, val token: String)

    fun start(server: String): Started {
        val c = open("${normalise(server)}/index.php/login/v2", "POST")
        c.doOutput = true
        c.outputStream.use { }
        val json = JSONObject(read(c))
        val poll = json.getJSONObject("poll")
        return Started(json.getString("login"), poll.getString("endpoint"), poll.getString("token"))
    }

    /** The account once the reader has signed in, or null while they have not yet. */
    fun poll(started: Started): Account? {
        val c = open(started.pollUrl, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write("token=${URLEncoder.encode(started.token, "UTF-8")}".toByteArray()) }
        if (c.responseCode == 404) { c.disconnect(); return null }
        val json = JSONObject(read(c))
        return Account(
            server = json.getString("server").trimEnd('/'),
            user = json.getString("loginName"),
            password = json.getString("appPassword"),
        )
    }

    /**
     * The QR code Nextcloud shows beside a new app password ("Show QR code for mobile apps"):
     * `nc://login/user:<login>&password:<app password>&server:<address>`. Null for anything else.
     */
    fun fromQr(text: String): Account? {
        val t = text.trim()
        if (!t.startsWith("nc://login/")) return null
        val parts = Regex("(user|password|server):(.*?)(?=&(?:user|password|server):|$)")
            .findAll(t.removePrefix("nc://login/")).associate { it.groupValues[1] to it.groupValues[2] }
        val user = parts["user"]?.takeIf { it.isNotEmpty() } ?: return null
        val password = parts["password"]?.takeIf { it.isNotEmpty() } ?: return null
        val server = parts["server"]?.takeIf { it.isNotEmpty() } ?: return null
        return Account(normalise(server), user, password)
    }

    /** "cloud.example" becomes "https://cloud.example". */
    fun normalise(server: String): String {
        val t = server.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }
}

private fun open(url: String, method: String, account: Account? = null): HttpURLConnection {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 15_000
    c.readTimeout = 30_000
    c.instanceFollowRedirects = true
    c.setRequestProperty("User-Agent", AGENT)
    if (account != null) {
        val token = Base64.getEncoder().encodeToString("${account.user}:${account.password}".toByteArray())
        c.setRequestProperty("Authorization", "Basic $token")
    }
    return c
}

private fun read(c: HttpURLConnection): String {
    val code = c.responseCode
    if (code !in 200..299) { c.disconnect(); throw Refused("HTTP $code") }
    return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * The catalogue as one file in the reader's Nextcloud, `<folder>/catalogue.json`, with a
 * spreadsheet of the shelf order written beside it for reading on a computer. Every phone
 * signed in to the same folder -- or to a folder shared with it -- keeps the same list.
 */
class Cloud(private val account: Account, folder: String) {

    private val dir = account.server.trimEnd('/') + "/remote.php/dav/files/" +
        path(account.user) + "/" + folder.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/", transform = ::path)

    private fun path(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** The books on the server and the etag to write back against, or null if none yet. */
    private fun fetch(): Pair<List<Book>, String>? {
        val c = open("$dir/catalogue.json", "GET", account)
        if (c.responseCode == 404) { c.disconnect(); return null }
        val etag = c.getHeaderField("ETag") ?: ""
        val text = read(c)
        val books = Book.listFromJson(JSONObject(text).getJSONArray("books"))
        return books to etag
    }

    /**
     * The catalogue's etag on the server without fetching it: "" when there is no catalogue yet.
     * Throws [IOException] when the server can't be reached.
     */
    fun etag(): String {
        val c = open("$dir/catalogue.json", "HEAD", account)
        val code = c.responseCode
        val tag = c.getHeaderField("ETag") ?: ""
        c.disconnect()
        return when (code) {
            in 200..299 -> tag
            404 -> ""
            else -> throw Refused("HTTP $code")
        }
    }

    /** What a sync left on the server: the books, and the etag they were written or read under (null when not said). */
    class Synced(val books: List<Book>, val etag: String?)

    /** The etag a successful write answered with, if it said. */
    private var written: String? = null

    /** False when someone else wrote first (the etag moved), so the caller merges again. */
    private fun put(name: String, body: String, type: String, ifMatch: String?, ifNone: Boolean): Boolean {
        val c = open("$dir/$name", "PUT", account)
        c.doOutput = true
        c.setRequestProperty("Content-Type", type)
        if (ifMatch != null) c.setRequestProperty("If-Match", ifMatch)
        if (ifNone) c.setRequestProperty("If-None-Match", "*")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        written = c.getHeaderField("ETag")
        c.disconnect()
        return when (code) {
            in 200..299 -> true
            412 -> false
            409, 404 -> { makeFolder(); put(name, body, type, ifMatch, ifNone) }
            else -> throw Refused("HTTP $code")
        }
    }

    private var madeFolder = false

    private fun makeFolder() {
        if (madeFolder) throw Refused("folder")
        madeFolder = true
        // Each level in turn; an existing one answers 405, which is fine.
        val root = account.server.trimEnd('/') + "/remote.php/dav/files/" + path(account.user)
        var at = root
        for (part in dir.removePrefix(root).split('/').filter { it.isNotEmpty() }) {
            at += "/$part"
            val c = open(at, "MKCOL", account)
            c.responseCode
            c.disconnect()
        }
    }

    /**
     * Brings [local] and the server's copy together and writes the result back. Returns the
     * merged list for this phone to keep.
     */
    fun sync(local: List<Book>, system: CallNumber.System, sectionName: (String) -> String): Synced {
        repeat(4) {
            val remote = fetch()
            val merged = Merge.merge(local, remote?.first ?: emptyList())
            val unchanged = remote != null && Merge.same(merged, remote.first)
            val wrote = unchanged || put(
                "catalogue.json",
                JSONObject().put("version", 1).put("books", Book.listToJson(merged)).toString(),
                "application/json",
                ifMatch = remote?.second?.takeIf { it.isNotEmpty() },
                ifNone = remote == null,
            )
            if (wrote) {
                if (unchanged) return Synced(merged, remote!!.second.takeIf { it.isNotEmpty() })
                val tag = written
                put("shelf-order.csv", Csv.write(Shelf.order(merged, system), system, sectionName), "text/csv", null, false)
                return Synced(merged, tag)
            }
        }
        throw Refused("busy")
    }
}

object Merge {
    /**
     * Every record from both sides; where both have one, the later change wins. Two phones
     * that each scanned the same ISBN end up with one book, not two: the earlier record stays
     * and the other is marked deleted.
     */
    fun merge(a: List<Book>, b: List<Book>): List<Book> {
        val byId = LinkedHashMap<Long, Book>()
        for (book in a + b) {
            val have = byId[book.id]
            if (have == null || book.modified > have.modified) byId[book.id] = book
        }
        val firstForIsbn = HashMap<String, Long>()
        for (book in byId.values.sortedBy { it.id }) {
            if (book.deleted || book.isbn.isEmpty()) continue
            val keep = firstForIsbn[book.isbn]
            if (keep == null) {
                firstForIsbn[book.isbn] = book.id
            } else {
                val kept = byId.getValue(keep)
                byId[keep] = kept.copy(
                    copies = maxOf(kept.copies, book.copies),
                    modified = maxOf(kept.modified, book.modified),
                )
                byId[book.id] = book.copy(deleted = true, modified = maxOf(kept.modified, book.modified))
            }
        }
        return byId.values.sortedBy { it.id }
    }

    fun same(a: List<Book>, b: List<Book>): Boolean =
        a.size == b.size && a.sortedBy { it.id } == b.sortedBy { it.id }
}

/** Kept apart from the catalogue: private preferences, never in the synced file. */
fun JSONObject.toAccount() = Account(getString("server"), getString("user"), getString("password"))
fun Account.toJson(): JSONObject = JSONObject().put("server", server).put("user", user).put("password", password)

