package com.wanderwildwood.shoka

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.wanderwildwood.shoka.ui.FilesPicker
import java.security.MessageDigest

/**
 * A book's recording in Audio Reader (com.wanderwildwood.mimidoku), for "Listen" on the book's
 * page. Audio Reader says whether its library - the card and any Audiobookshelf server - has
 * the book, answering this app alone and about that one book; without it, or without the book,
 * there is no "Listen".
 *
 * A book's title and author are asked of, and a key handed to, only the real Audio Reader: the
 * app holding its provider must be it, signed with its release certificate ([CERT_SHA256]) and
 * nothing else. On a phone without Audio Reader any app could take the name or the authority.
 */
object Listen {

    private const val AUDIO_READING = "com.wanderwildwood.mimidoku"
    private const val ACTION_OPEN_BOOK = "com.wanderwildwood.mimidoku.OPEN_BOOK"
    private const val AUTHORITY = "com.wanderwildwood.mimidoku.books"

    /** SHA-256 of the certificate Audio Reader's releases are signed with, lower-case hex. */
    const val CERT_SHA256 = "dd932da2650dfc58b52ac6897648eb2a2e9ee8ae67d750c3869ddd3ca0d83f0e"

    /** Audio Reader's key for [book], or null. Reads another app: call it off the main thread. */
    fun find(context: Context, book: Book): String? {
        if (book.title.isBlank()) return null
        if (!isAudioReader(context, providerOwner = true)) return null
        val uri = Uri.parse("content://$AUTHORITY/find").buildUpon()
            .appendQueryParameter("title", book.title)
            .apply { if (book.author.isNotBlank()) appendQueryParameter("author", book.author) }
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val key = c.getColumnIndex("book")
                if (key >= 0 && c.moveToFirst()) c.getString(key) else null
            }
        } catch (_: Exception) {
            // Not installed, or too old to answer.
            null
        }
    }

    fun open(context: Context, key: String) {
        if (!isAudioReader(context, providerOwner = false)) return
        val intent = Intent(ACTION_OPEN_BOOK).setPackage(AUDIO_READING)
            .putExtra("book", key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Whether Audio Reader is the real one: signed with its release certificate and nothing
     * else, and, with [providerOwner], the app that holds its provider. A debuggable build of
     * this app also takes Audio Reader signed with a debug key.
     */
    private fun isAudioReader(context: Context, providerOwner: Boolean): Boolean = try {
        val pm = context.packageManager
        val owned = !providerOwner || pm.resolveContentProvider(AUTHORITY, 0)?.packageName == AUDIO_READING
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        owned && trusted(signers(pm, AUDIO_READING), debuggable)
    } catch (_: RuntimeException) {
        false
    }

    /** Whether certificates [signers] (SHA-256, lower-case hex) are Audio Reader's alone. */
    fun trusted(signers: List<String>, debuggable: Boolean): Boolean {
        val certs = if (debuggable) FilesPicker.DEBUG_CERTS_SHA256 + CERT_SHA256 else setOf(CERT_SHA256)
        return signers.isNotEmpty() && signers.all { it in certs }
    }

    /** The SHA-256 of each certificate [pkg] is signed with, lower-case hex; none when it cannot be read. */
    @Suppress("DEPRECATION")
    private fun signers(pm: PackageManager, pkg: String): List<String> = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        raw.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault(emptyList())
}
