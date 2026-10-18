package com.wanderwildwood.shoka

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * A book's recording in Audio Reading (com.wanderwildwood.mimidoku), for "Listen" on the book's
 * page. Audio Reading says whether its library - the card and any Audiobookshelf server - has
 * the book, answering this app alone and about that one book; without it, or without the book,
 * there is no "Listen".
 */
object Listen {

    private const val AUDIO_READING = "com.wanderwildwood.mimidoku"
    private const val ACTION_OPEN_BOOK = "com.wanderwildwood.mimidoku.OPEN_BOOK"

    /** Audio Reading's key for [book], or null. Reads another app: call it off the main thread. */
    fun find(context: Context, book: Book): String? {
        if (book.title.isBlank()) return null
        val uri = Uri.parse("content://$AUDIO_READING.books/find").buildUpon()
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
        val intent = Intent(ACTION_OPEN_BOOK).setPackage(AUDIO_READING)
            .putExtra("book", key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
