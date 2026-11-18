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

    /** [source] for the page: each part through [word], which gives a word's text or null for a name. */
    fun shown(source: String, word: (String) -> String?): String =
        source.split(JOIN).joinToString(JOIN) { part -> word(part) ?: part }
}
