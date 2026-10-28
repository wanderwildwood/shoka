package com.wanderwildwood.shoka

import com.wanderwildwood.shoka.cloud.Account
import com.wanderwildwood.shoka.cloud.DavFolder
import com.wanderwildwood.shoka.cloud.Refused
import com.wanderwildwood.shoka.cloud.http
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException

/**
 * The catalogue as one file in the reader's Nextcloud, `<folder>/catalogue.json`, with a
 * spreadsheet of the shelf order written beside it for reading on a computer. Every phone
 * signed in to the same folder -- or to a folder shared with it -- keeps the same list.
 */
class Cloud(account: Account, folder: String, client: OkHttpClient = http) {

    private val dav = DavFolder(account, folder, client)

    /** The books on the server and the etag to write back against, or null if none yet. */
    private fun fetch(): Pair<List<Book>, String>? {
        val (text, etag) = dav.get("catalogue.json") ?: return null
        return Book.listFromJson(JSONObject(text).getJSONArray("books")) to etag
    }

    /**
     * The catalogue's etag on the server without fetching it: "" when there is no catalogue yet.
     * Throws [IOException] when the server can't be reached.
     */
    fun etag(): String = dav.etag("catalogue.json")

    /** What a sync left on the server: the books, and the etag they were written or read under (null when not said). */
    class Synced(val books: List<Book>, val etag: String?)

    /**
     * Brings [local] and the server's copy together and writes the result back. Returns the
     * merged list for this phone to keep.
     */
    fun sync(local: List<Book>, system: CallNumber.System, sectionName: (String) -> String): Synced {
        repeat(4) {
            val remote = fetch()
            val merged = Merge.merge(local, remote?.first ?: emptyList())
            if (remote != null && Merge.same(merged, remote.first)) {
                return Synced(merged, remote.second.takeIf { it.isNotEmpty() })
            }
            val wrote = dav.put(
                "catalogue.json",
                JSONObject().put("version", 1).put("books", Book.listToJson(merged)).toString(),
                "application/json",
                ifMatch = remote?.second?.takeIf { it.isNotEmpty() },
                ifNone = remote == null,
            )
            if (wrote.ok) {
                dav.put("shelf-order.csv", Csv.write(Shelf.order(merged, system), system, sectionName), "text/csv")
                return Synced(merged, wrote.etag)
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
