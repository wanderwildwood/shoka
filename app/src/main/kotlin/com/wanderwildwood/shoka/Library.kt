package com.wanderwildwood.shoka

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException

/**
 * The catalogue: every book, kept in one JSON file and written after each change, plus the
 * queue that looks them up. Scanning never waits on the network -- a book goes in at once
 * and fills itself in when the catalogues answer.
 */
class Library(app: Application) : AndroidViewModel(app) {

    private val file = File(app.filesDir, "books.json")
    private val prefs = app.getSharedPreferences("shelf", 0)
    private val writeLock = Mutex()
    private val queue = Channel<Long>(Channel.UNLIMITED)

    val books = MutableStateFlow<List<Book>>(emptyList())
    val system = MutableStateFlow(
        runCatching { CallNumber.System.valueOf(prefs.getString("system", "LC")!!) }
            .getOrDefault(CallNumber.System.LC)
    )

    val shelf: StateFlow<List<Placed>> = combine(books, system) { b, s -> Shelf.order(b, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var lastId = 0L

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { Book.listFromJson(JSONArray(file.readText())) }.getOrDefault(emptyList())
            }
            lastId = loaded.maxOfOrNull { it.id } ?: 0L
            books.value = loaded
            loaded.filter { it.status == Status.LOOKING || it.status == Status.OFFLINE }
                .forEach { queue.trySend(it.id) }
        }
        // Two at a time: quick enough to keep up with a scanning hand, gentle on the LoC.
        repeat(2) { viewModelScope.launch { work() } }
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                books.value.filter { it.status == Status.OFFLINE }.forEach { queue.trySend(it.id) }
            }
        }
    }

    private fun newId(): Long {
        val now = System.currentTimeMillis()
        lastId = maxOf(lastId + 1, now)
        return lastId
    }

    fun setSystem(s: CallNumber.System) {
        system.value = s
        prefs.edit().putString("system", s.name).apply()
    }

    fun find(id: Long) = books.value.firstOrNull { it.id == id }
    fun findIsbn(isbn: String) = books.value.firstOrNull { it.isbn == isbn }
    fun findLccn(lccn: String) = books.value.firstOrNull { it.lccn == lccn }

    fun addIsbn(isbn13: String): Book = add(Book(id = newId(), isbn = isbn13, added = now()))

    fun addLccn(lccn: String): Book = add(Book(id = newId(), lccn = lccn, added = now()))

    fun addFound(f: Found): Book = add(
        Book(
            id = newId(), isbn = f.isbn, lccn = f.lccn, title = f.title, author = f.author,
            year = f.year, lc = f.lc, ddc = f.ddc, source = f.source, status = Status.FOUND,
            added = now(),
        )
    )

    fun addByHand(title: String, author: String): Book = add(
        Book(
            id = newId(), title = title.trim(), author = author.trim(), source = "by hand",
            status = Status.NOT_FOUND, added = now(),
        )
    )

    private fun add(b: Book): Book {
        books.update { it + b }
        save()
        if (b.status == Status.LOOKING) queue.trySend(b.id)
        return b
    }

    fun addCopy(id: Long) = change(id) { it.copy(copies = it.copies + 1) }

    fun update(b: Book) = change(b.id) { b }

    fun delete(id: Long) {
        books.update { list -> list.filterNot { it.id == id } }
        save()
    }

    fun retry(id: Long) {
        change(id) { it.copy(status = Status.LOOKING) }
        queue.trySend(id)
    }

    fun retryAll() {
        books.value.filter { it.status == Status.OFFLINE || it.status == Status.NOT_FOUND }
            .forEach { retry(it.id) }
    }

    private fun change(id: Long, f: (Book) -> Book) {
        books.update { list -> list.map { if (it.id == id) f(it) else it } }
        save()
    }

    private suspend fun work() {
        for (id in queue) {
            val b = find(id) ?: continue
            if (b.status == Status.FOUND) continue
            val answer = try {
                when {
                    b.isbn.isNotEmpty() -> Lookup.byIsbn(b.isbn)
                    b.lccn.isNotEmpty() -> Lookup.byLccn(b.lccn)
                    else -> Answer.Miss
                }
            } catch (e: IOException) {
                Answer.Offline
            } catch (e: Exception) {
                // A catalogue answering with something unreadable is a miss, not a crash.
                Answer.Miss
            }
            change(id) { cur ->
                when (answer) {
                    is Answer.Hit -> {
                        val f = answer.found
                        cur.copy(
                            title = cur.title.ifEmpty { f.title },
                            author = cur.author.ifEmpty { f.author },
                            year = cur.year.ifEmpty { f.year },
                            lc = cur.lc.ifEmpty { f.lc },
                            ddc = cur.ddc.ifEmpty { f.ddc },
                            lccn = cur.lccn.ifEmpty { f.lccn },
                            isbn = cur.isbn.ifEmpty { f.isbn },
                            source = f.source,
                            status = Status.FOUND,
                        )
                    }
                    Answer.Miss -> cur.copy(status = Status.NOT_FOUND)
                    Answer.Offline -> cur.copy(status = Status.OFFLINE)
                }
            }
        }
    }

    private fun save() {
        viewModelScope.launch(Dispatchers.IO) {
            writeLock.withLock {
                // Read inside the lock, so whichever write lands last carries the newest list.
                val snapshot = books.value
                // Written beside and renamed over, so a kill mid-write can't leave half a file.
                val tmp = File(file.parentFile, "books.json.tmp")
                tmp.writeText(Book.listToJson(snapshot).toString())
                tmp.renameTo(file)
            }
        }
    }

    suspend fun export(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = Csv.write(shelf.value, system.value)
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!
                .use { it.write(text.toByteArray()) }
        }.isSuccess
    }

    /** Adds what the file has that the catalogue doesn't. Returns how many, or null on failure. */
    suspend fun import(uri: Uri): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val text = getApplication<Application>().contentResolver.openInputStream(uri)!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val have = books.value
            val fresh = Csv.read(text, ::newId).filter { n ->
                have.none { h ->
                    (n.isbn.isNotEmpty() && h.isbn == n.isbn) ||
                        (n.isbn.isEmpty() && h.title == n.title && h.author == n.author)
                }
            }
            withContext(Dispatchers.Main) {
                books.update { it + fresh }
                save()
            }
            fresh.size
        }.getOrNull()
    }

    private fun now() = System.currentTimeMillis()
}
