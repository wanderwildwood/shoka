package com.wanderwildwood.shoka.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wanderwildwood.shoka.Book
import com.wanderwildwood.shoka.CallNumber
import com.wanderwildwood.shoka.Isbn
import com.wanderwildwood.shoka.Library
import com.wanderwildwood.shoka.Placed
import com.wanderwildwood.shoka.R
import com.wanderwildwood.shoka.Status
import kotlinx.coroutines.launch
import zxingcpp.BarcodeReader

private val Grey = Color(0xFF6B6B6B)
private val Faint = Color(0xFFE4E4E4)

private val colors = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    secondary = Color.Black, onSecondary = Color.White,
    background = Color.White, onBackground = Color.Black,
    surface = Color.White, onSurface = Color.Black,
    surfaceVariant = Faint, onSurfaceVariant = Grey,
    surfaceContainerHigh = Color.White, surfaceContainer = Color.White,
    outline = Color.Black,
)

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
    MaterialTheme(colorScheme = colors) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var editing by remember { mutableStateOf<Long?>(null) }
        var event by remember { mutableStateOf<Event?>(null) }
        val books by lib.books.collectAsStateWithLifecycle()

        Column(
            Modifier.fillMaxSize().background(Color.White).systemBarsPadding().imePadding()
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Tab(stringResource(R.string.tab_scan), tab == 0) { tab = 0 }
                Tab(stringResource(R.string.tab_shelf), tab == 1) { tab = 1 }
                Spacer(Modifier.weight(1f))
                Menu(lib)
            }
            HorizontalDivider(color = Color.Black)
            when (tab) {
                0 -> ScanScreen(lib, books, event, { event = it }, { editing = it })
                else -> ShelfScreen(lib) { editing = it }
            }
        }

        editing?.let { id ->
            val book = books.firstOrNull { it.id == id }
            if (book == null) editing = null
            else EditDialog(lib, book) { editing = null }
        }
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp)) {
        Text(
            label,
            fontSize = 17.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.Black else Grey,
        )
    }
}

@Composable
private fun Menu(lib: Library) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val exported = stringResource(R.string.exported)
    val exportFailed = stringResource(R.string.export_failed)
    val importFailed = stringResource(R.string.import_failed)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = lib.export(uri)
            Toast.makeText(context, if (ok) exported else exportFailed, Toast.LENGTH_SHORT).show()
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val n = lib.import(uri)
            val msg = if (n == null) importFailed else context.getString(R.string.imported, n)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.menu), color = Color.Black) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_csv)) },
                onClick = { open = false; save.launch("shelf-order.csv") },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_csv)) },
                onClick = { open = false; load.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) },
            )
        }
    }
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
    var findMode by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var manual by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf(false) }

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

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(230.dp).clipToBounds().background(Color.Black)) {
            if (granted) {
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
                    Text(stringResource(R.string.light), color = if (torch) Color.Black else Color.White)
                }
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.camera_needed), color = Color.White, textAlign = TextAlign.Center)
                    TextButton(onClick = { ask.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.camera_allow), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.mode_add), !findMode, Modifier.weight(1f)) { findMode = false; setEvent(null) }
            Choice(stringResource(R.string.mode_find), findMode, Modifier.weight(1f)) { findMode = true; setEvent(null) }
        }

        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            EventCard(lib, books, event, findMode, setEvent, open)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            TextButton(onClick = { manual = true }) { Text(stringResource(R.string.type_number), color = Color.Black) }
            TextButton(onClick = { search = true }) { Text(stringResource(R.string.search_title), color = Color.Black) }
        }

        HorizontalDivider(color = Faint)
        val system by lib.system.collectAsStateWithLifecycle()
        val recent = remember(books) { books.sortedByDescending { it.added }.take(60) }
        LazyColumn(Modifier.weight(1f)) {
            item {
                Text(
                    stringResource(R.string.recent), color = Grey, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(recent, key = { it.id }) { b ->
                Column(Modifier.fillMaxWidth().clickable { open(b.id) }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(b.title.ifEmpty { b.isbn.ifEmpty { b.lccn } }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(statusLine(b, system), color = Grey, fontSize = 13.sp, fontFamily = callFont(b, system))
                }
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
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.border(1.dp, Color.Black)
            .background(if (selected) Color.Black else Color.White)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) Color.White else Color.Black, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun statusLine(b: Book, system: CallNumber.System): String = when (b.status) {
    Status.LOOKING -> stringResource(R.string.looking_up)
    Status.OFFLINE -> stringResource(R.string.offline)
    Status.NOT_FOUND -> b.callNumber(system).ifEmpty { stringResource(R.string.not_found) }
    Status.FOUND -> listOf(b.callNumber(system).ifEmpty { stringResource(R.string.no_call_number) }, b.author)
        .filter { it.isNotEmpty() }.joinToString("  ·  ")
}

private fun callFont(b: Book, system: CallNumber.System) =
    if (b.status == Status.FOUND && b.callNumber(system).isNotEmpty()) FontFamily.Monospace else FontFamily.Default

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
    val modifier = Modifier.fillMaxWidth().height(150.dp)

    when (event) {
        null -> Text(
            stringResource(if (findMode) R.string.hint_find else R.string.hint_add),
            color = Grey, modifier = modifier.padding(top = 8.dp),
        )

        Event.NotIsbn -> Text(stringResource(R.string.not_isbn), modifier = modifier.padding(top = 8.dp))

        is Event.Unknown -> Column(modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.not_catalogued), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(event.isbn, color = Grey, fontFamily = FontFamily.Monospace)
            TextButton(onClick = { setEvent(Event.Added(lib.addIsbn(event.isbn).id)) }) {
                Text(stringResource(R.string.add_it), color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }

        is Event.Added -> {
            val b = books.firstOrNull { it.id == event.id } ?: return
            Column(modifier.clickable { open(b.id) }.padding(top = 8.dp)) {
                Text(
                    b.title.ifEmpty { b.isbn.ifEmpty { b.lccn } }, fontSize = 20.sp,
                    fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (b.author.isNotEmpty()) Text(b.author, color = Grey, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Text(
                    statusLine(b, system).substringBefore("  ·  "),
                    fontSize = 18.sp, fontFamily = callFont(b, system),
                )
            }
        }

        is Event.Duplicate -> {
            val b = books.firstOrNull { it.id == event.id } ?: return
            Column(modifier.padding(top = 8.dp)) {
                Text(
                    if (event.copyAdded) stringResource(R.string.copy_added, b.copies)
                    else stringResource(R.string.already_have) + "  " + stringResource(R.string.copies, b.copies),
                    fontWeight = FontWeight.Bold, fontSize = 18.sp,
                )
                Text(b.title.ifEmpty { b.isbn }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!event.copyAdded) TextButton(onClick = {
                    lib.addCopy(b.id); setEvent(Event.Duplicate(b.id, copyAdded = true))
                }) { Text(stringResource(R.string.add_copy), color = Color.Black, fontWeight = FontWeight.Bold) }
            }
        }

        is Event.Place -> {
            val i = shelf.indexOfFirst { it.book.id == event.id }
            if (i < 0) return
            val p = shelf[i]
            Column(modifier.clickable { open(p.book.id) }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.position, p.position), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.of_total, shelf.size), color = Grey, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    p.book.callNumber(system).ifEmpty { stringResource(R.string.no_call_number) } + "  ·  " + p.book.title,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shelf.getOrNull(i - 1)?.let { stringResource(R.string.after, it.book.title) }
                        ?: stringResource(R.string.first_on_shelf),
                    color = Grey, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shelf.getOrNull(i + 1)?.let { stringResource(R.string.before, it.book.title) }
                        ?: stringResource(R.string.last_on_shelf),
                    color = Grey, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
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

    val shown = remember(shelf, query, attention, system) {
        val q = query.trim().lowercase()
        shelf.filter { p ->
            val b = p.book
            (!attention || b.status != Status.FOUND || b.callNumber(system).isEmpty()) &&
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
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildList {
                    add(stringResource(R.string.summary, shelf.size))
                    if (looking > 0) add(stringResource(R.string.summary_looking, looking))
                    if (missing > 0) add(stringResource(R.string.summary_missing, missing))
                    if (offline > 0) add(stringResource(R.string.summary_offline, offline))
                }.joinToString("  ·  "),
                color = Grey, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            if (missing + offline > 0) TextButton(onClick = { lib.retryAll() }) {
                Text(stringResource(R.string.retry), color = Color.Black)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.filter)) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Choice(stringResource(R.string.needs_attention), attention, Modifier.width(120.dp)) { attention = !attention }
        }

        if (shelf.isEmpty()) {
            Text(stringResource(R.string.empty_shelf), color = Grey, modifier = Modifier.padding(16.dp))
        }

        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(shown.size, key = { shown[it].book.id }) { i ->
                val p = shown[i]
                if (!p.classified && (i == 0 || shown[i - 1].classified)) {
                    Text(
                        stringResource(if (system == CallNumber.System.LC) R.string.unclassified_lc else R.string.unclassified_dewey),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().background(Faint).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                ShelfRow(p, system) { open(p.book.id) }
                HorizontalDivider(color = Faint)
            }
        }
    }
}

@Composable
private fun ShelfRow(p: Placed, system: CallNumber.System, onClick: () -> Unit) {
    val b = p.book
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            p.position.toString(), textAlign = TextAlign.End, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(48.dp).padding(end = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            val call = b.callNumber(system)
            if (call.isNotEmpty()) Text(call, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            Text(b.title.ifEmpty { b.isbn.ifEmpty { b.lccn } }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = buildList {
                if (b.author.isNotEmpty()) add(b.author)
                if (b.year.isNotEmpty()) add(b.year)
                if (b.copies > 1) add("×${b.copies}")
                if (b.status != Status.FOUND) add(statusLine(b, system))
            }.joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, color = Grey, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
