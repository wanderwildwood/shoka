package com.wanderwildwood.shoka.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.shoka.Book
import com.wanderwildwood.shoka.CallNumber
import com.wanderwildwood.shoka.Isbn
import com.wanderwildwood.shoka.Library
import com.wanderwildwood.shoka.Placed
import com.wanderwildwood.shoka.R
import com.wanderwildwood.shoka.Shelf
import com.wanderwildwood.shoka.Status
import zxingcpp.BarcodeReader

/** What the last scan did, shown under the camera until the next one. */
sealed interface Event {
    data class Added(val id: Long) : Event
    data class Duplicate(val id: Long, val copyAdded: Boolean = false) : Event
    data class Place(val id: Long) : Event
    data class Unknown(val isbn: String) : Event
    data object NotIsbn : Event
}

@Composable
fun ShelfApp(lib: Library) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var event by remember { mutableStateOf<Event?>(null) }
    var menu by remember { mutableStateOf(false) }
    val books by lib.books.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Tab(stringResource(R.string.tab_scan), tab == 0) { tab = 0 }
            Tab(stringResource(R.string.tab_shelf), tab == 1) { tab = 1 }
            Spacer(Modifier.weight(1f))
            Tab(stringResource(R.string.menu), false) { menu = true }
        }
        HorizontalDividerMMD(thickness = 2.dp)
        when (tab) {
            // The menu's QR sign-in needs the camera, and only one screen can hold it at a time.
            0 -> ScanScreen(lib, books, event, { event = it }, { editing = it }, cameraOn = !menu)
            else -> ShelfScreen(lib) { editing = it }
        }
    }

    if (menu) MenuDialog(lib) { menu = false }

    editing?.let { id ->
        val book = books.firstOrNull { it.id == id }
        if (book == null) editing = null
        else EditDialog(lib, book) { editing = null }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp)) {
        TextMMD(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (selected) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        )
    }
}

/** Two or more choices side by side: the chosen one filled, the rest outlined. */
@Composable
fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val m = modifier.height(44.dp)
    if (selected) {
        ButtonMMD(onClick = onClick, modifier = m) { TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    } else {
        OutlinedButtonMMD(onClick = onClick, modifier = m) { TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    }
}

/** A plain pressable line of text, for actions that don't need a button's weight. */
@Composable
fun Link(label: String, modifier: Modifier = Modifier, bold: Boolean = true, onClick: () -> Unit) {
    TextMMD(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        modifier = modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun Small(text: String, modifier: Modifier = Modifier, mono: Boolean = false, maxLines: Int = 1) {
    TextMMD(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = if (mono) FontFamily.Monospace else null,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

// ---------------------------------------------------------------- Scan

@Composable
private fun ScanScreen(
    lib: Library,
    books: List<Book>,
    event: Event?,
    setEvent: (Event?) -> Unit,
    open: (Long) -> Unit,
    cameraOn: Boolean,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var findMode by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    val system by lib.system.collectAsStateWithLifecycle()

    val feedback = remember { Feedback(context) }
    DisposableEffect(Unit) { onDispose { feedback.release() } }

    fun take(isbn: String) {
        val have = lib.findIsbn(isbn)
        if (findMode) {
            if (have != null) { feedback.good(); setEvent(Event.Place(have.id)) }
            else { feedback.again(); setEvent(Event.Unknown(isbn)) }
        } else {
            if (have != null) { feedback.again(); setEvent(Event.Duplicate(have.id)) }
            else { feedback.good(); setEvent(Event.Added(lib.addIsbn(isbn).id)) }
        }
    }

    // The Kompakt's screen is 610dp tall, the Pixel's 850: the camera takes what is spare.
    val tall = LocalConfiguration.current.screenHeightDp > 700
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(if (tall) 230.dp else 150.dp).clipToBounds().background(Color.Black)) {
            if (granted && cameraOn) {
                Scanner(Modifier.fillMaxSize(), torch) { code ->
                    val isbn = if (code.format == BarcodeReader.Format.EAN_13) Isbn.normalize(code.text) else null
                    if (isbn == null) { feedback.bad(); setEvent(Event.NotIsbn) } else take(isbn)
                }
                // A guide line, so the barcode goes where the reader looks hardest.
                Box(Modifier.align(Alignment.Center).fillMaxWidth(0.7f).height(1.dp).background(Color.White))
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(8.dp)
                        .background(if (torch) Color.White else Color.Black)
                        .border(1.dp, Color.White)
                        .clickable { torch = !torch }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    TextMMD(text = stringResource(R.string.light), color = if (torch) Color.Black else Color.White)
                }
            } else if (!granted) {
                Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextMMD(text = stringResource(R.string.camera_needed), color = Color.White, textAlign = TextAlign.Center)
                    TextMMD(
                        text = stringResource(R.string.camera_allow), color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { ask.launch(Manifest.permission.CAMERA) }.padding(12.dp),
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.mode_add), !findMode, Modifier.weight(1f)) { findMode = false; setEvent(null) }
            Choice(stringResource(R.string.mode_find), findMode, Modifier.weight(1f)) { findMode = true; setEvent(null) }
        }

        Box(Modifier.fillMaxWidth().height(if (tall) 150.dp else 112.dp).padding(horizontal = 16.dp)) {
            EventCard(lib, books, event, findMode, setEvent, open)
        }

        Row(Modifier.fillMaxWidth()) {
            Link(stringResource(R.string.type_number)) { manual = true }
            Link(stringResource(R.string.search_title)) { search = true }
        }

        HorizontalDividerMMD(thickness = 1.dp)
        val recent = remember(books) { books.sortedByDescending { it.added }.take(60) }
        LazyColumnMMD(Modifier.weight(1f), isScrollbarVisible = false) {
            items(recent.size, key = { recent[it].id }) { i ->
                val b = recent[i]
                Column(Modifier.fillMaxWidth().clickable { open(b.id) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    TextMMD(text = b.title.ifEmpty { b.isbn.ifEmpty { b.lccn } }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Small(statusLine(b, system), mono = hasCall(b, system))
                }
                HorizontalDividerMMD(thickness = 1.dp)
            }
        }
    }

    if (manual) ManualDialog(lib, { manual = false }) { isbn, id ->
        if (isbn != null) take(isbn) else if (id != null) setEvent(Event.Added(id))
    }
    if (search) SearchDialog(lib, { search = false }) { id, byHand ->
        setEvent(Event.Added(id))
        if (byHand) open(id)
    }
}

@Composable
private fun statusLine(b: Book, system: CallNumber.System): String = when (b.status) {
    Status.LOOKING -> stringResource(R.string.looking_up)
    Status.OFFLINE -> stringResource(R.string.offline)
    Status.NOT_FOUND -> b.callNumber(system).ifEmpty { stringResource(R.string.not_found) }
    Status.FOUND -> listOf(
        if (system == CallNumber.System.SECTIONS) sectionName(Shelf.section(b))
        else b.callNumber(system).ifEmpty { stringResource(R.string.no_call_number) },
        b.author,
    ).filter { it.isNotEmpty() }.joinToString("  ·  ")
}

private fun hasCall(b: Book, system: CallNumber.System) =
    b.status == Status.FOUND && system != CallNumber.System.SECTIONS && b.callNumber(system).isNotEmpty()

/** The heading for a [Placed.section]; [Library.sectionName] is the same outside Compose. */
@Composable
private fun sectionName(code: String): String = when (code) {
    Shelf.FICTION -> stringResource(R.string.section_fiction)
    "" -> stringResource(R.string.section_none)
    else -> stringArrayResource(R.array.lc_classes)[Shelf.lcClasses.indexOf(code)]
}

@Composable
private fun EventCard(
    lib: Library,
    books: List<Book>,
    event: Event?,
    findMode: Boolean,
    setEvent: (Event?) -> Unit,
    open: (Long) -> Unit,
) {
    val system by lib.system.collectAsStateWithLifecycle()
    val shelf by lib.shelf.collectAsStateWithLifecycle()
    val title = MaterialTheme.typography.titleMedium

    when (event) {
        null -> Small(stringResource(if (findMode) R.string.hint_find else R.string.hint_add), maxLines = 5)

        Event.NotIsbn -> TextMMD(text = stringResource(R.string.not_isbn), style = MaterialTheme.typography.bodySmall)

        is Event.Unknown -> Column {
            TextMMD(text = stringResource(R.string.not_catalogued), style = title, fontWeight = FontWeight.Bold)
            Small(event.isbn, mono = true)
            Link(stringResource(R.string.add_it), Modifier.padding(top = 4.dp)) {
                setEvent(Event.Added(lib.addIsbn(event.isbn).id))
            }
        }

        is Event.Added -> {
            val b = books.firstOrNull { it.id == event.id } ?: return
            Column(Modifier.fillMaxSize().clickable { open(b.id) }) {
                TextMMD(
                    text = b.title.ifEmpty { b.isbn.ifEmpty { b.lccn } }, style = title,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (b.author.isNotEmpty()) Small(b.author)
                Spacer(Modifier.height(6.dp))
                TextMMD(
                    text = statusLine(b, system).substringBefore("  ·  "),
                    fontFamily = if (hasCall(b, system)) FontFamily.Monospace else null,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        is Event.Duplicate -> {
            val b = books.firstOrNull { it.id == event.id } ?: return
            Column {
                TextMMD(
                    text = if (event.copyAdded) stringResource(R.string.copy_added, b.copies)
                    else stringResource(R.string.already_have) + "  " + stringResource(R.string.copies, b.copies),
                    style = title, fontWeight = FontWeight.Bold,
                )
                TextMMD(text = b.title.ifEmpty { b.isbn }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!event.copyAdded) Link(stringResource(R.string.add_copy)) {
                    lib.addCopy(b.id); setEvent(Event.Duplicate(b.id, copyAdded = true))
                }
            }
        }

        is Event.Place -> {
            val i = shelf.indexOfFirst { it.book.id == event.id }
            if (i < 0) return
            val p = shelf[i]
            Column(Modifier.fillMaxSize().clickable { open(p.book.id) }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    TextMMD(text = stringResource(R.string.position, p.position), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    TextMMD(text = stringResource(R.string.of_total, shelf.size), modifier = Modifier.padding(bottom = 6.dp))
                }
                if (system == CallNumber.System.SECTIONS) {
                    // By author within a section, so the neighbours are named by author too.
                    val section = sectionName(p.section)
                    fun named(b: Book) = listOf(b.author.substringBefore(','), b.title).filter { it.isNotEmpty() }.joinToString(", ")
                    fun near(j: Int) = shelf.getOrNull(j)?.takeIf { it.section == p.section }
                    Small(section + "  ·  " + p.book.title)
                    Small(near(i - 1)?.let { stringResource(R.string.after, named(it.book)) } ?: stringResource(R.string.first_in_section, section))
                    Small(near(i + 1)?.let { stringResource(R.string.before, named(it.book)) } ?: stringResource(R.string.last_in_section, section))
                } else {
                    Small(p.book.callNumber(system).ifEmpty { stringResource(R.string.no_call_number) } + "  ·  " + p.book.title)
                    Small(shelf.getOrNull(i - 1)?.let { stringResource(R.string.after, it.book.title) } ?: stringResource(R.string.first_on_shelf))
                    Small(shelf.getOrNull(i + 1)?.let { stringResource(R.string.before, it.book.title) } ?: stringResource(R.string.last_on_shelf))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Shelf order

@Composable
private fun ShelfScreen(lib: Library, open: (Long) -> Unit) {
    val shelf by lib.shelf.collectAsStateWithLifecycle()
    val system by lib.system.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var attention by rememberSaveable { mutableStateOf(false) }

    val looking = shelf.count { it.book.status == Status.LOOKING }
    val missing = shelf.count { it.book.status == Status.NOT_FOUND }
    val offline = shelf.count { it.book.status == Status.OFFLINE }

    val sizes = remember(shelf) { shelf.groupingBy { it.section }.eachCount() }
    // A new order starts at its beginning, not wherever the old top book now stands.
    val list = rememberLazyListState()
    LaunchedEffect(system) { list.scrollToItem(0) }
    val shown = remember(shelf, query, attention, system) {
        val q = query.trim().lowercase()
        shelf.filter { p ->
            val b = p.book
            (!attention || b.status != Status.FOUND || !p.classified) &&
                (q.isEmpty() || listOf(b.title, b.author, b.lc, b.ddc, b.isbn).any { it.lowercase().contains(q) })
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.system_lc), system == CallNumber.System.LC, Modifier.weight(1f)) {
                lib.setSystem(CallNumber.System.LC)
            }
            Choice(stringResource(R.string.system_dewey), system == CallNumber.System.DEWEY, Modifier.weight(1f)) {
                lib.setSystem(CallNumber.System.DEWEY)
            }
            Choice(stringResource(R.string.system_sections), system == CallNumber.System.SECTIONS, Modifier.weight(1f)) {
                lib.setSystem(CallNumber.System.SECTIONS)
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Small(
                buildList {
                    add(stringResource(R.string.summary, shelf.size))
                    if (looking > 0) add(stringResource(R.string.summary_looking, looking))
                    if (missing > 0) add(stringResource(R.string.summary_missing, missing))
                    if (offline > 0) add(stringResource(R.string.summary_offline, offline))
                }.joinToString("  ·  "),
                modifier = Modifier.weight(1f), maxLines = 2,
            )
            if (missing + offline > 0) Link(stringResource(R.string.retry)) { lib.retryAll() }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextFieldMMD(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { TextMMD(text = stringResource(R.string.filter), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Choice(stringResource(R.string.needs_attention), attention, Modifier.width(120.dp)) { attention = !attention }
        }

        if (shelf.isEmpty()) {
            TextMMD(text = stringResource(R.string.empty_shelf), modifier = Modifier.padding(16.dp))
        }

        LazyColumnMMD(Modifier.weight(1f).padding(top = 8.dp), state = list, isScrollbarVisible = false) {
            items(shown.size, key = { shown[it].book.id }) { i ->
                val p = shown[i]
                if (system == CallNumber.System.SECTIONS) {
                    if (i == 0 || shown[i - 1].section != p.section) {
                        if (i > 0) HorizontalDividerMMD(thickness = 2.dp)
                        TextMMD(
                            text = stringResource(R.string.section_count, sectionName(p.section), sizes[p.section] ?: 0),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        HorizontalDividerMMD(thickness = 2.dp)
                    }
                } else if (!p.classified && (i == 0 || shown[i - 1].classified)) {
                    HorizontalDividerMMD(thickness = 2.dp)
                    TextMMD(
                        text = stringResource(if (system == CallNumber.System.LC) R.string.unclassified_lc else R.string.unclassified_dewey),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDividerMMD(thickness = 2.dp)
                }
                ShelfRow(p, system) { open(p.book.id) }
                HorizontalDividerMMD(thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun ShelfRow(p: Placed, system: CallNumber.System, onClick: () -> Unit) {
    val b = p.book
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp)) {
        TextMMD(
            text = p.position.toString(), textAlign = TextAlign.End, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp).padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            // Sections shelves by author, so a call number here would only mislead.
            val call = if (system == CallNumber.System.SECTIONS) "" else b.callNumber(system)
            if (call.isNotEmpty()) TextMMD(text = call, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            TextMMD(text = b.title.ifEmpty { b.isbn.ifEmpty { b.lccn } }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                if (b.author.isNotEmpty()) add(b.author)
                if (b.year.isNotEmpty()) add(b.year)
                if (b.copies > 1) add("×${b.copies}")
                if (b.status != Status.FOUND) add(statusLine(b, system))
            }.joinToString("  ·  ")
            if (sub.isNotEmpty()) Small(sub)
        }
    }
}
