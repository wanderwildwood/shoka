package com.wanderwildwood.shoka

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
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
import com.wanderwildwood.shoka.cloud.Account
import com.wanderwildwood.shoka.cloud.LoginFlow
import com.wanderwildwood.shoka.cloud.Secrets
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
    /** What is in [queue] and not yet answered, so a book is never asked about twice at once. */
    private val queued = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** A connection coming back picks up what waited for one, without a tap. */
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { resume() }
        }
    }

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

    val account = MutableStateFlow(loadAccount())
    val folder = MutableStateFlow(prefs.getString("folder", "Books") ?: "Books")
    val syncState = MutableStateFlow(if (account.value == null) SyncState.OFF else SyncState.DONE)
    val lastSync = MutableStateFlow(prefs.getLong("lastSync", 0L))

    /** Whether the screen turns sideways with the phone; on unless switched off in Settings. */
    val turnWithPhone = MutableStateFlow(prefs.getBoolean("turn_with_phone", true))

    fun setTurnWithPhone(on: Boolean) {
        turnWithPhone.value = on
        prefs.edit().putBoolean("turn_with_phone", on).apply()
    }
    private var pendingSync: Job? = null

    /**
     * The catalogue's etag on the server as this phone last read or wrote it. While the app is
     * open the etag is asked for every [WATCH_MS] (a few hundred bytes, no catalogue), and only
     * when it has moved, because another phone wrote, is the catalogue fetched and merged.
     */
    private var remoteTag: String? = null
    private var watching: Job? = null

    private var lastId = 0L

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { Book.listFromJson(JSONArray(file.readText())) }.getOrDefault(emptyList())
            }
            lastId = loaded.maxOfOrNull { it.id } ?: 0L
            all.value = loaded
            loaded.filter { !it.deleted && (it.status == Status.LOOKING || it.status == Status.OFFLINE) }
                .forEach { enqueue(it.id) }
            syncNow()
            fillGenres()
        }
        // Two at a time: quick enough to keep up with a scanning hand, gentle on the LoC.
        repeat(2) { viewModelScope.launch { work() } }
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                books.value.filter { it.status == Status.OFFLINE }.forEach { enqueue(it.id) }
            }
        }
        // Called at once when there is a network already, which costs nothing: nothing waits yet.
        runCatching { connectivity?.registerDefaultNetworkCallback(network) }
    }

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(network) }
    }

    /**
     * Books that waited for a connection are looked up again, and a sync that failed is tried
     * again: when the network comes back, and each time the app comes to the front.
     */
    fun resume() {
        books.value.filter { it.status == Status.OFFLINE }.forEach { enqueue(it.id) }
        if (account.value != null && syncState.value == SyncState.FAILED) syncNow()
    }

    private fun enqueue(id: Long) {
        if (queued.add(id)) queue.trySend(id)
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
        if (stamped.status == Status.LOOKING) enqueue(stamped.id)
        return stamped
    }

    fun addCopy(id: Long) = change(id) { it.copy(copies = it.copies + 1) }

    fun update(b: Book) = change(b.id) { b }

    fun delete(id: Long) = change(id) { it.copy(deleted = true) }

    fun retry(id: Long) {
        change(id) { it.copy(status = Status.LOOKING) }
        enqueue(id)
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
            queued.remove(id)
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
            delay(SETTLE_MS)
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

    /** Starts a sync with Nextcloud, or returns null when none is set up; the job ends when it has. */
    fun syncNow(): Job? {
        val acct = account.value ?: return null
        return viewModelScope.launch {
            syncLock.withLock {
                syncState.value = SyncState.SYNCING
                val sent = all.value
                val result = withContext(Dispatchers.IO) {
                    runCatching { Cloud(acct, folder.value).sync(sent, system.value, ::sectionName) }
                }
                val synced = result.getOrNull()
                if (synced == null) {
                    syncState.value = SyncState.FAILED
                    return@withLock
                }
                remoteTag = synced.etag
                val merged = synced.books
                // Anything changed here while the sync was out is newer, and wins.
                all.value = Merge.merge(merged, all.value)
                lastId = maxOf(lastId, all.value.maxOfOrNull { it.id } ?: 0L)
                save()
                all.value.filter { !it.deleted && it.status == Status.LOOKING }.forEach { enqueue(it.id) }
                lastSync.value = now()
                prefs.edit().putLong("lastSync", lastSync.value).apply()
                syncState.value = SyncState.DONE
            }
        }
    }

    /**
     * Watches the shared catalogue while the app is on screen, so books another phone scans
     * turn up here within [WATCH_MS]: started when the app comes to the front, stopped when it
     * goes. Nothing runs in the background, and there is no push server.
     */
    fun watch(on: Boolean) {
        watching?.cancel()
        watching = if (!on) null else viewModelScope.launch {
            while (true) {
                look()
                delay(WATCH_MS)
            }
        }
    }

    /** One look at the server's etag; a sync when it differs from what this phone last saw. */
    private suspend fun look() {
        val acct = account.value ?: return
        if (syncLock.isLocked || pendingSync?.isActive == true) return
        val tag = withContext(Dispatchers.IO) {
            runCatching { Cloud(acct, folder.value).etag() }.getOrNull()
        } ?: return // Not reachable: the next look, or the network coming back, tries again.
        if (Watch.moved(remoteTag, tag)) syncNow()?.join()
    }

    /**
     * The signed-in account. The app password is kept sealed with the keystore; an account saved
     * by an earlier version, with the password in the clear, is read as it was and sealed at once,
     * so nobody is asked to sign in again.
     */
    private fun loadAccount(): Account? {
        val stored = prefs.getString("account", null) ?: return null
        val json = runCatching { JSONObject(stored) }.getOrNull() ?: return null
        val raw = json.optString("password")
        val password = Secrets.open(raw)?.takeIf { it.isNotEmpty() } ?: return null
        val account = Account(json.optString("server"), json.optString("user"), password)
        if (account.server.isEmpty() || account.user.isEmpty()) return null
        if (!Secrets.isSealed(raw)) saveAccount(account)
        return account
    }

    private fun saveAccount(a: Account) {
        // If the keystore will not seal, nothing is kept and the next start asks to sign in.
        val sealed = Secrets.seal(a.password) ?: return
        val json = JSONObject().put("server", a.server).put("user", a.user).put("password", sealed)
        prefs.edit().putString("account", json.toString()).apply()
    }

    fun signIn(a: Account) {
        saveAccount(a)
        account.value = a
        syncNow()
    }

    fun signOut() {
        val leaving = account.value
        // The app password is deleted on the server too, best effort: off the network the reader is
        // still signed out here, and it can be revoked in the server's security settings.
        if (leaving != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { LoginFlow.revoke(leaving) } }
        }
        prefs.edit().remove("account").remove("lastSync").apply()
        remoteTag = null
        account.value = null
        lastSync.value = 0L
        syncState.value = SyncState.OFF
    }

    fun setFolder(f: String) {
        val clean = f.trim().trim('/').ifEmpty { "Books" }
        folder.value = clean
        remoteTag = null
        prefs.edit().putString("folder", clean).apply()
        syncNow()
    }

    /** Writes the shelf order as a spreadsheet. Returns how many books went in, or null on failure. */
    suspend fun export(uri: Uri): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val rows = shelf.value
            val text = Csv.write(rows, system.value, ::sectionName)
            val resolver = getApplication<Application>().contentResolver
            try {
                resolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            } catch (e: Exception) {
                // A file the reader chose and that is empty is worse than none.
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, uri) }
                throw e
            }
            rows.size
        }.getOrNull()
    }

    /** How many books a spreadsheet had, and how many of them were new here. */
    data class Imported(val read: Int, val added: Int)

    /** Adds what the file has that the catalogue doesn't. Null when the file can't be read. */
    suspend fun import(uri: Uri): Imported? {
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
        if (adding.isNotEmpty()) {
            all.update { it + adding }
            changed()
        }
        return Imported(read = fresh.size, added = adding.size)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        /** How often an open app asks whether the shared catalogue has changed. */
        const val WATCH_MS = 15_000L
        /** How long after the last change this phone writes, so a run of scans is one write. */
        const val SETTLE_MS = 3_000L
    }
}

/** The rule behind [Library.watch], apart from the network so it can be tested. */
object Watch {
    /** Whether the server's [now] etag means another phone wrote since this one last synced. */
    fun moved(seen: String?, now: String): Boolean = seen == null || seen != now
}
