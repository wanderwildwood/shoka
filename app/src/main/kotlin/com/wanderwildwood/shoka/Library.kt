package com.wanderwildwood.shoka

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
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
import com.wanderwildwood.shoka.cloud.HomeAccount
import com.wanderwildwood.shoka.cloud.LoginFlow
import com.wanderwildwood.shoka.cloud.Secrets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The catalog: every book, kept in one JSON file and written after each change, plus the
 * queue that looks them up and, when signed in, the sync to Nextcloud. Scanning never waits
 * on the network -- a book goes in at once and fills itself in when the catalogs answer.
 */
class Library(app: Application) : AndroidViewModel(app) {

    private val file = File(app.filesDir, "books.json")
    private val prefs = app.getSharedPreferences("shelf", 0)
    private val writeLock = Mutex()
    private val syncLock = Mutex()
    private val queue = Channel<Long>(Channel.UNLIMITED)
    /** What is in [queue] and not yet answered, so a book is never asked about twice at once. */
    private val queued = java.util.Collections.synchronizedSet(HashSet<Long>())

    /**
     * Found books without a Dewey number and without the Library of Congress among their
     * catalogs, asked again since this start: each at most once. A book whose asking found no
     * catalog at all is not counted as asked, and the asking waits for the app to come back
     * or a connection to come back ([recheckWaits]).
     */
    private val rechecked = java.util.Collections.synchronizedSet(HashSet<Long>())
    @Volatile private var recheckWaits = false

    /**
     * Some catalog was out of reach while another answered. That may be LoC, or only Open
     * Library or the other-edition search with LoC reachable; the answer does not say which,
     * so either way no more books are asked again until the next start.
     */
    @Volatile private var catalogOutOfReach = false

    /** The few books being asked again now, apart from [queue] so a scan never waits on them. */
    private var rechecking: Job? = null

    /** A connection coming back picks up what waited for one, without a tap. */
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { resume() }
        }
    }

    /** Everything, deleted records included, because a sync has to pass deletions on. */
    private val all = MutableStateFlow<List<Book>>(emptyList())

    /**
     * Done once books.json has been read. Until then nothing is written to it, synced or
     * imported, so a book added while a large catalog is still loading neither stands in for
     * the whole catalog on disk nor is dropped when the catalog arrives.
     */
    private val loaded = CompletableDeferred<Unit>()

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

    /**
     * The phone's Nextcloud account, taken from Home ([HomeAccount.Held.who]: server and user, no
     * password), or null when the account is the app's own. Set while [account] is null too: Home
     * signed out (or is gone) and the app waits for the same account to come back. The sealed
     * [account] is then only a mirror of Home's, written from Home and let go as [followHome] says.
     */
    val homeWho = MutableStateFlow(prefs.getString(HOME_WHO, null))

    /** Counts Home's change notices, so a screen asks again whether Home has an account to offer. */
    val homeNotices = MutableStateFlow(0)
    private val homeLock = Mutex()
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
     * The catalog's etag on the server as this phone last read or wrote it. While the app is
     * open the etag is asked for every [WATCH_MS] (a few hundred bytes, no catalog), and only
     * when it has moved, because another phone wrote, is the catalog fetched and merged.
     */
    private var remoteTag: String? = null
    private var watching: Job? = null

    /** Home being asked as the app comes to the front ([resume]); the watch waits for it. */
    private var homeAsked: Job? = null

    private var lastId = 0L

    init {
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                if (!file.exists()) emptyList()
                else runCatching { Book.listFromJson(JSONArray(file.readText())) }.getOrElse {
                    // There but unreadable: kept aside under another name, never written over
                    // with an empty catalog.
                    file.renameTo(File(file.parentFile, "books.json.unreadable-${System.currentTimeMillis()}"))
                    emptyList()
                }
            }
            lastId = maxOf(lastId, read.maxOfOrNull { it.id } ?: 0L)
            // Books added while it loaded are kept beside what was read.
            all.update { added -> if (added.isEmpty()) read else Merge.merge(read, added) }
            loaded.complete(Unit)
            read.filter { !it.deleted && (it.status == Status.LOOKING || it.status == Status.OFFLINE) }
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
                recheck()
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
        recheckWaits = false
        homeAsked = if (homeWho.value != null) {
            // Home asked first: a new password there, or Home signed out, before any sync.
            viewModelScope.launch {
                if (followHome() || syncState.value == SyncState.FAILED) syncNow()
            }
        } else {
            if (account.value != null && syncState.value == SyncState.FAILED) syncNow()
            null
        }
    }

    /** Home said its account changed: asked again, and any screen showing the row asks too. */
    fun homeChanged() {
        homeNotices.update { it + 1 }
        if (homeWho.value == null) return
        viewModelScope.launch { if (followHome()) syncNow() }
    }

    /**
     * A book found while the Library of Congress was out of reach may lack only the Dewey
     * number LoC would have given. Such books are asked again quietly, at most once a start
     * and a few a minute, one after another and only while no scan waits for a lookup: the
     * status stays FOUND, and only a Dewey number found fills anything in.
     */
    private fun recheck() {
        if (catalogOutOfReach || recheckWaits || rechecking?.isActive == true || queued.isNotEmpty()) return
        val next = Source.toRecheck(books.value, rechecked, RECHECKS_PER_MINUTE)
        if (next.isEmpty()) return
        next.forEach { rechecked.add(it.id) }
        rechecking = viewModelScope.launch {
            for (b in next) {
                val cur = find(b.id)?.takeIf { Source.recheckable(it) } ?: continue
                // Waiting or stopped since it was picked: not asked, so not used up.
                if (catalogOutOfReach || recheckWaits) rechecked.remove(cur.id) else again(cur)
            }
        }
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
        Book(id = newId(), title = title.trim(), author = author.trim(), source = Source.BY_HAND, status = Status.NOT_FOUND)
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
                // A catalog answering with something unreadable is a miss, not a crash.
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
                            // Set by hand wins; otherwise what the catalog says, if it says.
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
     * One quiet second asking for a [Source.recheckable] book. Nothing changes unless a Dewey
     * number comes back; then the blanks are filled and the source says where it came from.
     */
    private suspend fun again(b: Book) {
        var partial = false
        val answer = try {
            Lookup.byIsbn(b.isbn) { partial = true }
        } catch (e: IOException) {
            Answer.Offline
        } catch (e: Exception) {
            Answer.Miss
        }
        if (answer == Answer.Offline) {
            // No catalog at all: the connection, not the catalogs. Asked again later.
            rechecked.remove(b.id)
            recheckWaits = true
            return
        }
        if (partial) catalogOutOfReach = true
        val f = (answer as? Answer.Hit)?.found ?: return
        if (f.ddc.isEmpty()) return
        // Edited or deleted while it was asked: the reader's version stands.
        if (find(b.id)?.let { Source.recheckable(it) } != true) return
        change(b.id) { cur ->
            cur.copy(
                ddc = f.ddc,
                lc = cur.lc.ifEmpty { f.lc },
                lccn = cur.lccn.ifEmpty { f.lccn },
                fiction = cur.fiction ?: f.fiction,
                genre = cur.genre.ifEmpty { f.genre },
                source = f.source,
            )
        }
    }

    /**
     * Novels cataloged before Fiction had genres get theirs looked up, once, a book at a time
     * and quietly: the status stays as it was, and a book found to have none keeps none.
     * Offline, or with a catalog out of reach and no genre from the rest, it stops and tries
     * again on the next start.
     */
    private suspend fun fillGenres() {
        val have = all.value.filterNot { it.deleted }
        // An empty catalog has nothing to fill; books synced in later come from a phone that does.
        if (prefs.getBoolean("genres_filled", false) || have.isEmpty()) return
        val todo = have.filter {
            it.status == Status.FOUND && it.isFiction && it.genre.isEmpty() && (it.isbn.isNotEmpty() || it.lccn.isNotEmpty())
        }
        for (b in todo) {
            var partial = false
            val answer = try {
                if (b.isbn.isNotEmpty()) Lookup.byIsbn(b.isbn) { partial = true } else Lookup.byLccn(b.lccn)
            } catch (e: IOException) {
                Answer.Offline
            } catch (e: Exception) {
                Answer.Miss
            }
            if (answer == Answer.Offline) return
            val genre = (answer as? Answer.Hit)?.found?.genre.orEmpty()
            // The Library of Congress's subject headings are where most genres come from.
            if (partial && genre.isEmpty()) return
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
            loaded.await()
            writeLock.withLock {
                // Read inside the lock, so whichever write lands last carries the newest list.
                val snapshot = all.value
                // Written beside, flushed to disk and renamed over, so neither a kill mid-write nor
                // a power cut just after can leave half a file -- or an empty one, which the
                // rename alone allows if the battery dies before the data is on disk.
                val tmp = File(file.parentFile, "books.json.tmp")
                // A full disk or a failed rename is not a reason to close the app: the list is
                // still in memory, and the next change writes it again.
                try {
                    FileOutputStream(tmp).use { out ->
                        out.write(Book.listToJson(snapshot).toString().toByteArray())
                        out.fd.sync()
                    }
                    if (!tmp.renameTo(file)) file.writeText(Book.listToJson(snapshot).toString())
                } catch (_: java.io.IOException) {
                }
            }
        }
    }

    /** Starts a sync with Nextcloud, or returns null when none is set up; the job ends when it has. */
    fun syncNow(): Job? {
        if (account.value == null && homeWho.value == null) return null
        return viewModelScope.launch {
            loaded.await()
            // An account taken from Home is never used past a change there.
            followHome()
            val acct = account.value ?: return@launch
            syncLock.withLock {
                // Let go while this waited (Home signed out, or Stop using it): not synced.
                if (account.value !== acct) return@withLock
                syncState.value = SyncState.SYNCING
                val sent = all.value
                val result = withContext(Dispatchers.IO) {
                    runCatching { Cloud(acct, folder.value).sync(sent, system.value, ::sectionName) }
                }
                val synced = result.getOrNull()
                if (synced == null) {
                    // Let go while it ran: signed out, quietly, not failed.
                    syncState.value = if (account.value == null) SyncState.OFF else SyncState.FAILED
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
                syncState.value = if (account.value == null) SyncState.OFF else SyncState.DONE
            }
        }
    }

    /**
     * Watches the shared catalog while the app is on screen, so books another phone scans
     * turn up here within [WATCH_MS]: started when the app comes to the front, stopped when it
     * goes. Nothing runs in the background, and there is no push server.
     */
    fun watch(on: Boolean) {
        watching?.cancel()
        // Home is heard out before the first look, so a password Home has let go is never sent.
        val first = homeAsked
        watching = if (!on) null else viewModelScope.launch { Watch.loop(first, WATCH_MS) { look() } }
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
        val user = json.optString("user")
        // Kept before the user id was: the login name stands in for it (and an own sign-in then
        // kept the user id as the login name, as it was used).
        val account = Account(json.optString("server"), user, password, json.optString("uid").ifEmpty { user })
        if (account.server.isEmpty() || account.user.isEmpty()) return null
        if (!Secrets.isSealed(raw)) saveAccount(account)
        return account
    }

    private fun saveAccount(a: Account) {
        // If the keystore will not seal, nothing is kept and the next start asks to sign in.
        val sealed = Secrets.seal(a.password) ?: return
        val json = JSONObject().put("server", a.server).put("user", a.user).put("uid", a.uid).put("password", sealed)
        prefs.edit().putString("account", json.toString()).apply()
    }

    /** The app's own sign-in: any choice of Home's account is let go. */
    fun signIn(a: Account) {
        saveAccount(a)
        prefs.edit().remove(HOME_WHO).remove(HOME_STAMP).apply()
        homeWho.value = null
        account.value = a
        syncNow()
    }

    fun signOut() {
        // Home's app password is Home's: it is never revoked from here, only let go.
        if (homeWho.value != null) { stopHome(); return }
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

    /**
     * The phone's Nextcloud account, taken at the reader's press in place of the app's own sign-in,
     * which is let go (and its app password revoked, as Sign out does). The folder stays.
     */
    fun useHome(held: HomeAccount.Held) {
        val own = account.value.takeIf { homeWho.value == null }
        if (own != null && own.password != held.password) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { LoginFlow.revoke(own) } }
        }
        pendingSync?.cancel()
        val a = Account(held.server, held.user, held.password, held.uid)
        saveAccount(a)
        prefs.edit().putString(HOME_WHO, held.who).putString(HOME_STAMP, held.stamp).remove("lastSync").apply()
        homeWho.value = held.who
        remoteTag = null
        lastSync.value = 0L
        account.value = a
        syncState.value = SyncState.DONE
        syncNow()
    }

    /**
     * Stop using it: Home's account is let go here (never revoked: it is Home's), and the app goes
     * back to its own sign-in, empty. The books stay.
     */
    fun stopHome() {
        pendingSync?.cancel()
        prefs.edit().remove("account").remove("lastSync").remove(HOME_WHO).remove(HOME_STAMP).apply()
        homeWho.value = null
        remoteTag = null
        account.value = null
        lastSync.value = 0L
        syncState.value = SyncState.OFF
    }

    /**
     * Asks Home about the account taken from it, off the main thread, and does as
     * [HomeAccount.follow] says. A new password for the same account takes the old one's place;
     * Home signed out, or gone, signs this app out quietly (no error, no retry) and it waits for the
     * same account; another account there is let go for good. The books stay either way. True when
     * an account was taken and a sync should follow.
     */
    suspend fun followHome(): Boolean = homeLock.withLock {
        val who = homeWho.value ?: return@withLock false
        // Nothing held (Home signed out, or a keystore that would not seal): the stamp is empty.
        val stamp = if (account.value == null) "" else prefs.getString(HOME_STAMP, null).orEmpty()
        val answer = withContext(Dispatchers.IO) { HomeAccount.ask(getApplication()) }
        // Stop using it, or the app's own sign-in, while Home was asked.
        if (homeWho.value != who) return@withLock false
        when (val step = HomeAccount.follow(who, stamp, answer)) {
            is HomeAccount.Step.Keep -> {
                // The same password, but Home has since learned the user id (an older mirror
                // kept the login name in its place): the paths take the id from now on.
                val held = (answer as? HomeAccount.Answer.Has)?.held
                val now = account.value
                if (held != null && now != null && held.who == HomeAccount.who(now.server, now.user) && held.uid != now.uid) {
                    val a = now.copy(uid = held.uid)
                    saveAccount(a)
                    remoteTag = null
                    account.value = a
                    true
                } else false
            }
            is HomeAccount.Step.Take -> {
                val a = Account(step.held.server, step.held.user, step.held.password, step.held.uid)
                saveAccount(a)
                prefs.edit().putString(HOME_STAMP, step.held.stamp).apply()
                account.value = a
                if (syncState.value == SyncState.OFF) syncState.value = SyncState.DONE
                true
            }
            is HomeAccount.Step.Drop -> {
                pendingSync?.cancel()
                prefs.edit().remove("account").putString(HOME_STAMP, "")
                    .apply { if (!step.following) remove(HOME_WHO) }.apply()
                if (!step.following) homeWho.value = null
                remoteTag = null
                account.value = null
                syncState.value = SyncState.OFF
                false
            }
        }
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

    /** Adds what the file has that the catalog doesn't. Null when the file can't be read. */
    suspend fun import(uri: Uri): Imported? {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)!!
                    .use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
        } ?: return null
        loaded.await()
        val have = all.value.filterNot { it.deleted }
        val fresh = runCatching { Csv.read(text, ::newId) }.getOrNull() ?: return null
        val adding = fresh.filter { n -> have.none { h -> Csv.alreadyHere(h, n) } }.map { it.copy(modified = now()) }
        if (adding.isNotEmpty()) {
            all.update { it + adding }
            changed()
            // A book still waiting for its catalog record when it was saved goes on waiting here.
            adding.filter { it.status == Status.LOOKING || it.status == Status.OFFLINE }.forEach { enqueue(it.id) }
        }
        return Imported(read = fresh.size, added = adding.size)
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val HOME_WHO = "home_who"
        private const val HOME_STAMP = "home_stamp"
        /** How often an open app asks whether the shared catalog has changed. */
        const val WATCH_MS = 15_000L
        /** How long after the last change this phone writes, so a run of scans is one write. */
        const val SETTLE_MS = 3_000L
        /** How many found books are asked again each minute, at most. */
        const val RECHECKS_PER_MINUTE = 2
    }
}

/** The rule behind [Library.watch], apart from the network so it can be tested. */
object Watch {
    /** Whether the server's [now] etag means another phone wrote since this one last synced. */
    fun moved(seen: String?, now: String): Boolean = seen == null || !com.wanderwildwood.shoka.cloud.Etag.same(seen, now)

    /** The watch: waits for [first] (Home being asked), then [look]s every [every] ms until cancelled. */
    suspend fun loop(first: Job?, every: Long, look: suspend () -> Unit): Nothing {
        first?.join()
        while (true) {
            look()
            delay(every)
        }
    }
}
