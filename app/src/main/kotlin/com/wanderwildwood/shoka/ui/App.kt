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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.platform.LocalDensity
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
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.shoka.Book
import com.wanderwildwood.shoka.CallNumber
import com.wanderwildwood.shoka.Isbn
import com.wanderwildwood.shoka.Library
import com.wanderwildwood.shoka.pulledWords
import com.wanderwildwood.shoka.rememberPull
import com.wanderwildwood.shoka.Placed
import com.wanderwildwood.shoka.R
import com.wanderwildwood.shoka.Shelf
import com.wanderwildwood.shoka.Status
import zxingcpp.BarcodeReader

/** What the last scan did, shown under the camera until the next one. */
sealed interface Event {
    data class Added(val id: Long) : Event
    /** A book already catalogued: most likely going back on the shelf, or a second copy. */
    data class Place(val id: Long, val copyAdded: Boolean = false) : Event
    data object NotIsbn : Event
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ShelfApp(lib: Library) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Long?>(null) }
    var event by remember { mutableStateOf<Event?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var byHand by remember { mutableStateOf(false) }
    val books by lib.books.collectAsStateWithLifecycle()

    if (settings) {
        // A screen of its own, so the Scan page and its camera are let go while it is up: the
        // Nextcloud sign-in behind it can read its QR code with the camera.
        SettingsScreen(lib, onBack = { settings = false }, onAbout = { about = true })
    } else Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().imePadding()
    ) {
        // Scan and Shelf order are the app's two views; adding by hand, the settings and About
        // are the bar's.
        TopAppBarMMD(
            title = { TextMMD(text = stringResource(R.string.app_name)) },
            actions = {
                BarButton(Icons.Add, stringResource(R.string.by_hand)) { byHand = true }
                BarButton(Icons.Settings, stringResource(R.string.settings)) { settings = true }
                BarButton(Icons.Info, stringResource(R.string.about)) { about = true }
            },
        )
        StoneTabs(listOf(stringResource(R.string.tab_scan), stringResource(R.string.tab_shelf)), tab, { tab = it })
        when (tab) {
            0 -> ScanScreen(lib, books, event, { event = it }, { editing = it })
            else -> ShelfScreen(lib) { editing = it }
        }
    }

    if (about) AboutDialog(onDismiss = { about = false })
    if (byHand) ByHandDialog(lib, { byHand = false }) { id ->
        event = Event.Added(id)
        editing = id
    }

    editing?.let { id ->
        val book = books.firstOrNull { it.id == id }
        if (book == null) editing = null
        else EditDialog(lib, book) { editing = null }
    }
}

/** An icon in a top bar, with a target a thumb can find. */
@Composable
fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Two or more choices side by side: the chosen one filled, the rest outlined. */
@Composable
fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val m = modifier.height(48.dp)
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
    if (selected) {
        ButtonMMD(onClick = onClick, modifier = m, contentPadding = pad) { TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    } else {
        OutlinedButtonMMD(onClick = onClick, modifier = m, contentPadding = pad) { TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    }
}

/** A press that takes no more room than its words: bordered, 48dp tall, as every button here. */
@Composable
fun SmallButton(label: String, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val m = modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
    if (filled) {
        ButtonMMD(onClick = onClick, modifier = m, contentPadding = pad) { TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    } else {
        OutlinedButtonMMD(onClick = onClick, modifier = m, contentPadding = pad) { TextMMD(text = label, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
    }
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
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var torch by rememberSaveable { mutableStateOf(false) }
    // Reading the ISBN printed as text, for a book with no barcode or a damaged one.
    var readText by rememberSaveable { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }
    val system by lib.system.collectAsStateWithLifecycle()

    val feedback = remember { Feedback(context) }
    DisposableEffect(Unit) { onDispose { feedback.release() } }

    // No modes: a book the catalogue has is going back on the shelf, any other is new.
    fun take(isbn: String) {
        val have = lib.findIsbn(isbn)
        if (have != null) { feedback.again(); setEvent(Event.Place(have.id)) }
        else { feedback.good(); setEvent(Event.Added(lib.addIsbn(isbn).id)) }
    }

    // The Kompakt's screen is 610dp tall, the Pixel's 850: the camera takes what is spare.
    val tall = LocalConfiguration.current.screenHeightDp > 700
    // A tablet with no camera (Supernote's) starts with the card and the ways to add by hand.
    val camera = remember { hasCamera(context) }
    Column(Modifier.fillMaxSize()) {
        if (camera) Box(Modifier.fillMaxWidth().height(if (tall) 230.dp else 150.dp).clipToBounds().background(Color.Black)) {
            if (granted) {
                val boxHeightPx = with(LocalDensity.current) { TextBox.HEIGHT.roundToPx() }
                Scanner(
                    Modifier.fillMaxSize(), torch,
                    readText = readText, textBoxHeightPx = boxHeightPx,
                    // A number read off the page is taken just as a scanned one is.
                    onNumber = { take(it) },
                ) { code ->
                    val isbn = if (code.format == BarcodeReader.Format.EAN_13) Isbn.normalize(code.text) else null
                    if (isbn == null) { feedback.bad(); setEvent(Event.NotIsbn) } else take(isbn)
                }
                if (readText) {
                    // A box for one line of print, and what goes in it.
                    TextMMD(
                        text = stringResource(R.string.read_number_hint), color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                    )
                    Box(
                        Modifier.align(Alignment.Center).fillMaxWidth(TextBox.WIDTH).height(TextBox.HEIGHT)
                            .border(1.dp, Color.White)
                    )
                } else {
                    // A guide line, so the barcode goes where the reader looks hardest.
                    Box(Modifier.align(Alignment.Center).fillMaxWidth(0.7f).height(1.dp).background(Color.White))
                }
                CameraButton(stringResource(R.string.read_number), readText, Modifier.align(Alignment.BottomStart)) {
                    readText = !readText
                }
                CameraButton(stringResource(R.string.light), torch, Modifier.align(Alignment.BottomEnd)) {
                    torch = !torch
                }
            } else if (!granted) {
                Column(
                    Modifier.align(Alignment.Center).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TextMMD(text = stringResource(R.string.camera_needed), color = Color.White, textAlign = TextAlign.Center)
                    Box(
                        Modifier.padding(top = 8.dp).heightIn(min = 48.dp).border(1.dp, Color.White)
                            .clickable { ask.launch(Manifest.permission.CAMERA) }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextMMD(text = stringResource(R.string.camera_allow), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(if (tall) 210.dp else 172.dp).padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            EventCard(lib, books, event, setEvent, open)
        }

        // Two ways in when the barcode won't do: its number typed, or a title searched for. The
        // third, the book written in by hand, is the + in the bar.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallButton(stringResource(R.string.type_number), Modifier.weight(1f)) { manual = true }
            SmallButton(stringResource(R.string.search_title), Modifier.weight(1f)) { search = true }
        }

        HorizontalDividerMMD(thickness = 1.dp)
        val pull = rememberPull(lib)
        pulledWords(pull.pulled)?.let { line ->
            Small(line, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
            HorizontalDividerMMD(thickness = 1.dp)
        }
        val recent = remember(books) { books.sortedByDescending { it.added }.take(60) }
        if (recent.isEmpty()) Small(stringResource(R.string.recent_empty), Modifier.padding(16.dp), maxLines = 3)
        // The newest scan goes on top; without this the list keeps hold of the old top book.
        val recentList = rememberLazyListState()
        LaunchedEffect(recent.firstOrNull()?.id) { recentList.scrollToItem(0) }
        PagedColumn(Modifier.weight(1f).fillMaxWidth(), state = recentList, onPullAtTop = if (pull.available) pull::pull else null) {
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
    if (search) SearchDialog(lib, { search = false }) { id, typedIn ->
        setEvent(Event.Added(id))
        if (typedIn) open(id)
    }
}

@Composable
private fun statusLine(b: Book, system: CallNumber.System): String = when (b.status) {
    Status.LOOKING -> stringResource(R.string.looking_up)
    Status.OFFLINE -> stringResource(R.string.offline)
    Status.NOT_FOUND -> b.ddc.ifEmpty { stringResource(R.string.not_found) }
    Status.FOUND -> listOf(
        if (system == CallNumber.System.SECTIONS) sectionName(Shelf.section(b))
        else b.ddc.ifEmpty { stringResource(R.string.no_call_number) },
        b.author,
    ).filter { it.isNotEmpty() }.joinToString("  ·  ")
}

private fun hasCall(b: Book, system: CallNumber.System) =
    b.status == Status.FOUND && system != CallNumber.System.SECTIONS && b.ddc.isNotEmpty()

/** The heading for a [Placed.section]; [Library.sectionName] is the same outside Compose. */
@Composable
private fun sectionName(code: String): String = when (code) {
    Shelf.FICTION -> stringResource(R.string.section_fiction)
    in Shelf.genres -> stringArrayResource(R.array.genres)[Shelf.genres.indexOf(code)]
    "" -> stringResource(R.string.section_none)
    else -> stringArrayResource(R.array.dewey_classes)[Shelf.deweyClasses.indexOf(code)]
}

@Composable
private fun EventCard(
    lib: Library,
    books: List<Book>,
    event: Event?,
    setEvent: (Event?) -> Unit,
    open: (Long) -> Unit,
) {
    val system by lib.system.collectAsStateWithLifecycle()
    val title = MaterialTheme.typography.titleMedium

    when (event) {
        null -> Small(stringResource(R.string.hint_scan), maxLines = 6)

        Event.NotIsbn -> TextMMD(text = stringResource(R.string.not_isbn), style = MaterialTheme.typography.bodySmall)

        is Event.Added -> {
            val b = books.firstOrNull { it.id == event.id } ?: return
            // Once its number is known a new book has a place too, so it can go straight up.
            if (b.status == Status.FOUND) {
                PlaceCard(lib, b.id, open) { Small(stringResource(R.string.just_added)) }
                return
            }
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

        is Event.Place -> PlaceCard(lib, event.id, open) {
            val b = books.firstOrNull { it.id == event.id } ?: return@PlaceCard
            if (event.copyAdded) {
                Small(stringResource(R.string.copy_added, b.copies), Modifier.padding(vertical = 10.dp))
            } else {
                SmallButton(stringResource(R.string.add_copy), Modifier.padding(top = 6.dp)) {
                    lib.addCopy(b.id); setEvent(Event.Place(b.id, copyAdded = true))
                }
            }
        }
    }
}

/** Where a book stands: its number in shelf order and the books either side, then [foot]. */
@Composable
private fun PlaceCard(lib: Library, id: Long, open: (Long) -> Unit, foot: @Composable () -> Unit) {
    val system by lib.system.collectAsStateWithLifecycle()
    val shelf by lib.shelf.collectAsStateWithLifecycle()
    val i = shelf.indexOfFirst { it.book.id == id }
    if (i < 0) return
    val p = shelf[i]
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().clickable { open(p.book.id) }) {
            Row(verticalAlignment = Alignment.Bottom) {
                TextMMD(text = stringResource(R.string.position, p.position), fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                TextMMD(text = stringResource(R.string.of_total, shelf.size), modifier = Modifier.padding(bottom = 6.dp))
            }
            if (system == CallNumber.System.SECTIONS) {
                // By author within a section, so the neighbours are named by author too.
                val section = sectionName(p.section)
                // The no-number heading reads badly inside a sentence, so it has its own lines.
                val first = if (p.section.isEmpty()) stringResource(R.string.first_unnumbered) else stringResource(R.string.first_in_section, section)
                val last = if (p.section.isEmpty()) stringResource(R.string.last_unnumbered) else stringResource(R.string.last_in_section, section)
                fun named(b: Book) = listOf(b.author.substringBefore(','), b.title).filter { it.isNotEmpty() }.joinToString(", ")
                fun near(j: Int) = shelf.getOrNull(j)?.takeIf { it.section == p.section }
                Small(if (p.section.isEmpty()) p.book.title else section + "  ·  " + p.book.title)
                Small(near(i - 1)?.let { stringResource(R.string.after, named(it.book)) } ?: first)
                Small(near(i + 1)?.let { stringResource(R.string.before, named(it.book)) } ?: last)
            } else {
                Small(p.book.ddc.ifEmpty { stringResource(R.string.no_call_number) } + "  ·  " + p.book.title)
                Small(shelf.getOrNull(i - 1)?.let { stringResource(R.string.after, it.book.title) } ?: stringResource(R.string.first_on_shelf))
                Small(shelf.getOrNull(i + 1)?.let { stringResource(R.string.before, it.book.title) } ?: stringResource(R.string.last_on_shelf))
            }
        }
        foot()
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
                (q.isEmpty() || listOf(b.title, b.author, b.ddc, b.isbn).any { it.lowercase().contains(q) })
        }
    }

    val pull = rememberPull(lib)
    Column(Modifier.fillMaxSize()) {
        // The two orders and the books still to fix, side by side: the chosen one is filled.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.system_dewey), system == CallNumber.System.DEWEY, Modifier.weight(1f)) {
                lib.setSystem(CallNumber.System.DEWEY)
            }
            Choice(stringResource(R.string.system_sections), system == CallNumber.System.SECTIONS, Modifier.weight(1f)) {
                lib.setSystem(CallNumber.System.SECTIONS)
            }
            Choice(stringResource(R.string.needs_attention), attention, Modifier.weight(1f)) { attention = !attention }
        }

        TextFieldMMD(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { TextMMD(text = stringResource(R.string.filter), style = MaterialTheme.typography.labelSmall) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Small(
                pulledWords(pull.pulled) ?: buildList {
                    add(stringResource(R.string.summary, shelf.size))
                    if (looking > 0) add(stringResource(R.string.summary_looking, looking))
                    if (missing > 0) add(stringResource(R.string.summary_missing, missing))
                    if (offline > 0) add(stringResource(R.string.summary_offline, offline))
                }.joinToString("  ·  "),
                modifier = Modifier.weight(1f), maxLines = 2,
            )
            if (missing + offline > 0) SmallButton(stringResource(R.string.retry)) { lib.retryAll() }
        }

        when {
            shelf.isEmpty() -> TextMMD(text = stringResource(R.string.empty_shelf), modifier = Modifier.padding(16.dp))
            shown.isEmpty() && query.isNotBlank() -> TextMMD(text = stringResource(R.string.filter_none, query.trim()), modifier = Modifier.padding(16.dp))
            shown.isEmpty() -> TextMMD(text = stringResource(R.string.attention_none), modifier = Modifier.padding(16.dp))
        }

        PagedColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), state = list, onPullAtTop = if (pull.available) pull::pull else null) {
            items(shown.size, key = { shown[it].book.id }) { i ->
                val p = shown[i]
                if (system == CallNumber.System.SECTIONS) {
                    if (i == 0 || shown[i - 1].section != p.section) {
                        if (i > 0) HorizontalDividerMMD(thickness = 1.dp)
                        TextMMD(
                            text = stringResource(R.string.section_count, sectionName(p.section), sizes[p.section] ?: 0),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        HorizontalDividerMMD(thickness = 1.dp)
                    }
                } else if (!p.classified && (i == 0 || shown[i - 1].classified)) {
                    HorizontalDividerMMD(thickness = 1.dp)
                    TextMMD(
                        text = stringResource(R.string.unclassified_dewey),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    HorizontalDividerMMD(thickness = 1.dp)
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
            val call = if (system == CallNumber.System.SECTIONS) "" else b.ddc
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

/** A button over the camera picture: white on black, and black on white while it is on. */
@Composable
private fun CameraButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(4.dp)
            .heightIn(min = 48.dp)
            .background(if (on) Color.White else Color.Black)
            .border(1.dp, Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextMMD(text = label, color = if (on) Color.Black else Color.White)
    }
}
