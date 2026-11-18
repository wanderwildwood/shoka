package com.wanderwildwood.shoka

/**
 * What [Book.source] holds: catalog names, which are proper names and shown as they are, and
 * these few words, kept in books.json as they always were and shown on the book's page in the
 * words of strings.xml. Parts are joined with " + ".
 */
object Source {
    const val BY_HAND = "by hand"
    const val EDITED = "edited"
    const val IMPORTED = "imported"
    const val OTHER_EDITION = "another edition"
    private const val JOIN = " + "

    /** [source] with "another edition" after it. */
    fun withOtherEdition(source: String): String = source + JOIN + OTHER_EDITION

    /**
     * Whether a book of this [source] came from catalogs alone, none of them the Library of
     * Congress ("Library of Congress", or "LoC" in "LoC + Open Library"), so LoC may not have
     * been reached when it was found. Books by hand, edited or imported are the reader's own.
     */
    fun withoutLoc(source: String): Boolean {
        if (source.isEmpty()) return false
        val parts = source.split(JOIN)
        return parts.none { it == "Library of Congress" || it == "LoC" || it == BY_HAND || it == EDITED || it == IMPORTED }
    }

    /**
     * Whether a found book may lack only the Dewey number LoC would have given, and so is
     * worth asking again: found by ISBN from catalogs other than LoC, with no Dewey number.
     */
    fun recheckable(b: Book): Boolean =
        b.status == Status.FOUND && b.ddc.isEmpty() && b.isbn.isNotEmpty() && withoutLoc(b.source)

    /** The first [n] [recheckable] books of [books] not in [asked]. */
    fun toRecheck(books: List<Book>, asked: Set<Long>, n: Int): List<Book> =
        books.asSequence().filter { recheckable(it) && it.id !in asked }.take(n).toList()

    /** [source] for the page: each part through [word], which gives a word's text or null for a name. */
    fun shown(source: String, word: (String) -> String?): String =
        source.split(JOIN).joinToString(JOIN) { part -> word(part) ?: part }
}
