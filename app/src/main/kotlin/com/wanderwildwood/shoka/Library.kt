package com.wanderwildwood.shoka

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The catalogue: every book, kept in one JSON file and written after each change, plus the
 * queue that looks them up and, when signed in, the sync to Nextcloud. Scanning never waits
 * on the network -- a book goes in at once and fills itself in when the catalogues answer.
 */
class Library(app: Application) : AndroidViewModel(app) {

    private val file = File(app.filesDir, "books.json")
    private val prefs = app.getSharedPreferences("shelf", 0)
    private val writeLock = Mutex()
    private val syncLock = Mutex()
    private val queue = Channel<Long>(Channel.UNLIMITED)

    /** Everything, deleted records included, because a sync has to pass deletions on. */
    private val all = MutableStateFlow<List<Book>>(emptyList())

    val books: StateFlow<List<Book>> = all.map { list -> list.filterNot { it.deleted } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val system = MutableStateFlow(
        // A phone last set to LC, which is gone, comes back to Dewey.
        runCatching { CallNumber.System.valueOf(prefs.getString("system", "DEWEY")!!) }
            .getOrDefault(CallNumber.System.DEWEY)
    )

    val shelf: StateFlow<List<Placed>> = combine(books, system) { b, s -> Shelf.order(b, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Nextcloud ----

    enum class SyncState { OFF, SYNCING, DONE, FAILED }

    val account = MutableStateFlow(
        prefs.getString("account", null)?.let { runCatching { JSONObject(it).toAccount() }.getOrNull() }
    )
    val folder = MutableStateFlow(prefs.getString("folder", "Books") ?: "Books")
    val syncState = MutableStateFlow(if (account.value == null) SyncState.OFF else SyncState.DONE)
    val lastSync = MutableStateFlow(prefs.getLong("lastSync", 0L))
    private var pendingSync: Job? = null

    private var lastId = 0L

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { Book.listFromJson(JSONArray(file.readText())) }.getOrDefault(emptyList())
            }
            lastId = loaded.maxOfOrNull { it.id } ?: 0L
            all.value = loaded
            loaded.filter { !it.deleted && (it.status == Status.LOOKING || it.status == Status.OFFLINE) }
                .forEach { queue.trySend(it.id) }
            syncNow()
            fillGenres()
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

    /** The heading for a [Placed.section]: "500 · Science", "Fiction", or the no-number heading. */
    fun sectionName(code: String): String {
        val res = getApplication<Application>().resources
        return when (code) {
            Shelf.FICTION -> res.getString(R.string.section_fiction)
            in Shelf.genres -> res.getStringArray(R.array.genres)[Shelf.genres.indexOf(code)]
            "" -> res.getString(R.string.section_none)
            else -> res.getStringArray(R.array.dewey_classes)[Shelf.deweyClasses.indexOf(code)]
        }
    }

    fun find(id: Long) = books.value.firstOrNull { it.id == id }
    fun findIsbn(isbn: String) = books.value.firstOrNull { it.isbn == isbn }
    fun findLccn(lccn: String) = books.value.firstOrNull { it.lccn == lccn }

    fun addIsbn(isbn13: String): Book = add(Book(id = newId(), isbn = isbn13))

    fun addLccn(lccn: String): Book = add(Book(id = newId(), lccn = lccn))

    fun addFound(f: Found): Book = add(
        Book(
            id = newId(), isbn = f.isbn, lccn = f.lccn, title = f.title, author = f.author,
            year = f.year, lc = f.lc, ddc = f.ddc, source = f.source, status = Status.FOUND, fiction = f.fiction,
            genre = f.genre,
        )
    )

    fun addByHand(title: String, author: String): Book = add(
        Book(id = newId(), title = title.trim(), author = author.trim(), source = "by hand", status = Status.NOT_FOUND)
    )

    private fun add(b: Book): Book {
        val stamped = b.copy(added = now(), modified = now())
        all.update { it + stamped }
        changed()
        if (stamped.status == Status.LOOKING) queue.trySend(stamped.id)
        return stamped
    }

    fun addCopy(id: Long) = change(id) { it.copy(copies = it.copies + 1) }

    fun update(b: Book) = change(b.id) { b }

    fun delete(id: Long) = change(id) { it.copy(deleted = true) }

    fun retry(id: Long) {
        change(id) { it.copy(status = Status.LOOKING) }
        queue.trySend(id)
    }

    fun retryAll() {
        books.value.filter { it.status == Status.OFFLINE || it.status == Status.NOT_FOUND }
            .forEach { retry(it.id) }
    }

    private fun change(id: Long, f: (Book) -> Book) {
        all.update { list -> list.map { if (it.id == id) f(it).copy(modified = now()) else it } }
        changed()
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
                            // Set by hand wins; otherwise what the catalogue says, if it says.
                            fiction = cur.fiction ?: f.fiction,
                            genre = cur.genre.ifEmpty { f.genre },
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

    /**
     * Novels catalogued before Fiction had genres get theirs looked up, once, a book at a time
     * and quietly: the status stays as it was, and a book found to have none keeps none.
     * Offline, it stops and tries again on the next start.
     */
    private suspend fun fillGenres() {
        // An empty catalogue has nothing to fill; books synced in later come from a phone that does.
        if (prefs.getBoolean("genres_filled", false) || books.value.isEmpty()) return
        val todo = books.value.filter {
            it.status == Status.FOUND && it.isFiction && it.genre.isEmpty() && (it.isbn.isNotEmpty() || it.lccn.isNotEmpty())
        }
        for (b in todo) {
            val answer = try {
                if (b.isbn.isNotEmpty()) Lookup.byIsbn(b.isbn) else Lookup.byLccn(b.lccn)
            } catch (e: IOException) {
                Answer.Offline
            } catch (e: Exception) {
                Answer.Miss
            }
            if (answer == Answer.Offline) return
            val genre = (answer as? Answer.Hit)?.found?.genre.orEmpty()
            if (genre.isNotEmpty()) change(b.id) { if (it.genre.isEmpty()) it.copy(genre = genre) else it }
        }
        prefs.edit().putBoolean("genres_filled", true).apply()
    }

    /** Saved at once; synced a few seconds after the last change, so a run of scans is one write. */
    private fun changed() {
        save()
        if (account.value == null) return
        pendingSync?.cancel()
        pendingSync = viewModelScope.launch {
            delay(8_000)
            syncNow()
        }
    }

    private fun save() {
        viewModelScope.launch(Dispatchers.IO) {
            writeLock.withLock {
                // Read inside the lock, so whichever write lands last carries the newest list.
                val snapshot = all.value
                // Written beside, flushed to disk and renamed over, so neither a kill mid-write nor
                // a power cut just after can leave half a file -- or an empty one, which the
                // rename alone allows if the battery dies before the data is on disk.
                val tmp = File(file.parentFile, "books.json.tmp")
                FileOutputStream(tmp).use { out ->
                    out.write(Book.listToJson(snapshot).toString().toByteArray())
                    out.fd.sync()
                }
                tmp.renameTo(file)
            }
        }
    }

    fun syncNow() {
        val acct = account.value ?: return
        viewModelScope.launch {
            syncLock.withLock {
                syncState.value = SyncState.SYNCING
                val sent = all.value
                val result = withContext(Dispatchers.IO) {
                    runCatching { Cloud(acct, folder.value).sync(sent, system.value, ::sectionName) }
                }
                val merged = result.getOrNull()
                if (merged == null) {
                    syncState.value = SyncState.FAILED
                    return@withLock
                }
                // Anything changed here while the sync was out is newer, and wins.
                all.value = Merge.merge(merged, all.value)
                lastId = maxOf(lastId, all.value.maxOfOrNull { it.id } ?: 0L)
                save()
                all.value.filter { !it.deleted && it.status == Status.LOOKING }.forEach { queue.trySend(it.id) }
                lastSync.value = now()
                prefs.edit().putLong("lastSync", lastSync.value).apply()
                syncState.value = SyncState.DONE
            }
        }
    }

    fun signIn(a: Account) {
        prefs.edit().putString("account", a.toJson().toString()).apply()
        account.value = a
        syncNow()
    }

    fun signOut() {
        prefs.edit().remove("account").remove("lastSync").apply()
        account.value = null
        lastSync.value = 0L
        syncState.value = SyncState.OFF
    }

    fun setFolder(f: String) {
        val clean = f.trim().trim('/').ifEmpty { "Books" }
        folder.value = clean
        prefs.edit().putString("folder", clean).apply()
        syncNow()
    }

    suspend fun export(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = Csv.write(shelf.value, system.value, ::sectionName)
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!
                .use { it.write(text.toByteArray()) }
        }.isSuccess
    }

    /** Adds what the file has that the catalogue doesn't. Returns how many, or null on failure. */
    suspend fun import(uri: Uri): Int? {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        } ?: return null
        val have = books.value
        val fresh = runCatching { Csv.read(text, ::newId) }.getOrNull() ?: return null
        val adding = fresh.filter { n ->
            have.none { h ->
                (n.isbn.isNotEmpty() && h.isbn == n.isbn) ||
                    (n.isbn.isEmpty() && h.title == n.title && h.author == n.author)
            }
        }.map { it.copy(modified = now()) }
        all.update { it + adding }
        changed()
        return adding.size
    }

    private fun now() = System.currentTimeMillis()
}
