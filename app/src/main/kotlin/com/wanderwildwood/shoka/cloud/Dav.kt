package com.wanderwildwood.shoka.cloud

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder

/**
 * One folder in a Nextcloud, over WebDAV: `remote.php/dav/files/<user>/<folder>/`. The calls
 * are Gallery's (an OkHttp client, Basic with the app password, the folder made level by level
 * where it is missing) with the ones a catalogue needs added: GET, HEAD and a PUT that can be
 * made conditional on the file's etag. Nothing is ever deleted or moved on the server.
 */
class DavFolder(
    private val account: Account,
    folder: String,
    private val client: OkHttpClient = http,
) {
    private val home = account.server.trimEnd('/') + "/remote.php/dav/files/" + encodePath(account.user)
    private val dir = home + folder.trim('/', ' ').split('/').filter { it.isNotEmpty() }
        .joinToString("") { "/" + encodePath(it) }

    private fun request(name: String) = Request.Builder().url("$dir/${encodePath(name)}")
        .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
        .header("User-Agent", AGENT)

    /** A file's text and its etag, or null when the file is not there. */
    fun get(name: String): Pair<String, String>? = call(request(name).get().build()) { r ->
        when {
            r.code == 404 -> null
            r.isSuccessful -> r.body!!.string() to (r.header("ETag") ?: "")
            else -> throw Refused("HTTP ${r.code}")
        }
    }

    /** A file's etag without fetching it: "" when the file is not there. */
    fun etag(name: String): String = call(request(name).head().build()) { r ->
        when {
            r.isSuccessful -> r.header("ETag") ?: ""
            r.code == 404 -> ""
            else -> throw Refused("HTTP ${r.code}")
        }
    }

    /** What a PUT came to: [ok] false when the etag condition failed (someone else wrote first). */
    class Put(val ok: Boolean, val etag: String?)

    /**
     * Writes [body] as [name]. [ifMatch] makes it conditional on the file still having that etag;
     * [ifNone] on the file not existing yet. A missing folder is made, each level in turn, and
     * the write tried again once.
     */
    fun put(name: String, body: String, type: String, ifMatch: String? = null, ifNone: Boolean = false): Put {
        var madeFolders = false
        while (true) {
            val builder = request(name).put(body.toByteArray().toRequestBody(type.toMediaTypeOrNull()))
            if (ifMatch != null) builder.header("If-Match", ifMatch)
            if (ifNone) builder.header("If-None-Match", "*")
            val (code, etag) = call(builder.build()) { it.code to it.header("ETag") }
            when {
                code in 200..299 -> return Put(true, etag)
                code == 412 -> return Put(false, null)
                (code == 409 || code == 404) && !madeFolders -> { makeFolders(); madeFolders = true }
                else -> throw Refused("HTTP $code")
            }
        }
    }

    /** Every level of the folder, parent first, made where it is missing. */
    fun makeFolders() {
        // Not following redirects: a folder asked for without its slash answers with a move.
        val plain = client.newBuilder().followRedirects(false).build()
        for (level in Folders.levels(home, dir)) {
            val request = Request.Builder().url(level).method("MKCOL", null)
                .header("Authorization", Credentials.basic(account.user, account.password, Charsets.UTF_8))
                .header("User-Agent", AGENT).build()
            call(request, plain) { r -> if (!Folders.fine(r.code)) throw Refused("HTTP ${r.code}") }
        }
    }

    private fun <T> call(request: Request, using: OkHttpClient = client, read: (Response) -> T): T {
        val response = try {
            using.newCall(request).execute()
        } catch (e: IOException) {
            throw Unreachable(e.message ?: "no answer")
        }
        return response.use(read)
    }
}

/** The folders a sync folder needs, parent first, and which MKCOL answers mean "it is there". */
object Folders {
    /** Every level of [dir] below [root], in the order to create them: parent before child. */
    fun levels(root: String, dir: String): List<String> {
        var at = root.trimEnd('/')
        return dir.removePrefix(root).split('/').filter { it.isNotEmpty() }.map { at += "/$it"; at }
    }

    /** Created (201), already there (405), or moved to its slash form (301/302/307/308). */
    fun fine(code: Int): Boolean = code in 200..299 || code == 405 || code in listOf(301, 302, 307, 308)
}

/** Each segment of a path percent-encoded for a URL, the slashes kept. */
fun encodePath(path: String): String =
    path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
