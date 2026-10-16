package com.wanderwildwood.shoka.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.text_field.TextFieldMMD
import com.wanderwildwood.shoka.Account
import com.wanderwildwood.shoka.Book
import com.wanderwildwood.shoka.CallNumber
import com.wanderwildwood.shoka.Found
import com.wanderwildwood.shoka.Isbn
import com.wanderwildwood.shoka.Lccn
import com.wanderwildwood.shoka.Library
import com.wanderwildwood.shoka.LoginFlow
import com.wanderwildwood.shoka.Lookup
import com.wanderwildwood.shoka.R
import com.wanderwildwood.shoka.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import zxingcpp.BarcodeReader

@Composable
private fun Title(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun Note(text: String) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun Field(
    value: String,
    set: (String) -> Unit,
    label: Int,
    type: KeyboardType = KeyboardType.Text,
    caps: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    secret: Boolean = false,
) {
    TextFieldMMD(
        value = value, onValueChange = set, singleLine = true,
        label = { TextMMD(text = stringResource(label), style = MaterialTheme.typography.labelSmall) },
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = caps),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/**
 * An ISBN typed in (a barcode too worn to read, or a book with the ISBN only inside), or
 * an LCCN from the copyright page of an older book. [done] gets the ISBN-13 to treat as a
 * scan, or the id of a book added by LCCN.
 */
@Composable
fun ManualDialog(lib: Library, dismiss: () -> Unit, done: (String?, Long?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    EInkDialog(onDismiss = dismiss) {
        Title(stringResource(R.string.manual_title))
        Field(text, { text = it; bad = false }, R.string.manual_label, KeyboardType.Ascii, KeyboardCapitalization.None)
        if (bad) Note(stringResource(R.string.manual_bad))
        Spacer(Modifier.height(8.dp))
        DialogButton(stringResource(R.string.add), filled = true) {
            val isbn = Isbn.normalize(text)
            val lccn = if (isbn == null) Lccn.normalize(text) else null
            when {
                isbn != null -> { done(isbn, null); dismiss() }
                lccn != null -> {
                    done(null, (lib.findLccn(lccn) ?: lib.addLccn(lccn)).id); dismiss()
                }
                else -> bad = true
            }
        }
        DialogButton(stringResource(R.string.cancel), onClick = dismiss)
    }
}

/** For books with no ISBN: search the LoC by title and author, or add the book by hand. */
@Composable
fun SearchDialog(lib: Library, dismiss: () -> Unit, added: (Long, Boolean) -> Unit) {
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Found>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    EInkDialog(onDismiss = dismiss) {
        Title(stringResource(R.string.search_heading))
        Field(title, { title = it }, R.string.field_title, caps = KeyboardCapitalization.Words)
        Field(author, { author = it }, R.string.field_author, caps = KeyboardCapitalization.Words)
        Spacer(Modifier.height(6.dp))
        when {
            busy -> Note(stringResource(R.string.searching))
            offline -> Note(stringResource(R.string.search_offline))
            results?.isEmpty() == true -> Note(stringResource(R.string.search_none))
        }
        results?.takeIf { it.isNotEmpty() }?.let { list ->
            LazyColumnMMD(Modifier.heightIn(max = 220.dp), isScrollbarVisible = false) {
                items(list.size) { i ->
                    val f = list[i]
                    Column(
                        Modifier.fillMaxWidth().clickable { added(lib.addFound(f).id, false); dismiss() }
                            .padding(vertical = 6.dp)
                    ) {
                        TextMMD(text = f.title, fontWeight = FontWeight.Medium, maxLines = 2)
                        Note(listOf(f.author, f.year, f.lc).filter { it.isNotEmpty() }.joinToString("  ·  "))
                    }
                    HorizontalDividerMMD(thickness = 1.dp)
                }
            }
        }
        if (results != null && title.isNotBlank()) {
            Link(stringResource(R.string.add_by_hand), bold = false) {
                added(lib.addByHand(title, author).id, true); dismiss()
            }
        }
        Spacer(Modifier.height(6.dp))
        DialogButton(stringResource(R.string.search), filled = true) {
            busy = true; offline = false
            scope.launch {
                results = try {
                    Lookup.search(title, author)
                } catch (e: IOException) {
                    offline = true; emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                busy = false
            }
        }
        DialogButton(stringResource(R.string.close), onClick = dismiss)
    }
}

/** Everything about one book, editable; corrections here are what the shelf order uses. */
@Composable
fun EditDialog(lib: Library, book: Book, dismiss: () -> Unit) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author) }
    var year by remember(book.id) { mutableStateOf(book.year) }
    var lc by remember(book.id) { mutableStateOf(book.lc) }
    var ddc by remember(book.id) { mutableStateOf(book.ddc) }
    var copies by remember(book.id) { mutableStateOf(book.copies) }
    var fiction by remember(book.id) { mutableStateOf(book.fiction) }
    var armed by remember { mutableStateOf(false) }

    // Fill in whatever a lookup brings back while the dialog is open, if not yet typed over.
    LaunchedEffect(book) {
        if (title.isEmpty()) title = book.title
        if (author.isEmpty()) author = book.author
        if (year.isEmpty()) year = book.year
        if (lc.isEmpty()) lc = book.lc
        if (ddc.isEmpty()) ddc = book.ddc
    }
    // An armed delete disarms itself, so a stray tap later finds nothing live.
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }

    fun edited() = book.copy(
        title = title.trim(), author = author.trim(), year = year.trim(),
        lc = lc.trim().replace(Regex("""\s+"""), " "), ddc = ddc.trim(), copies = copies, fiction = fiction,
        status = if (book.status == Status.NOT_FOUND && (lc.isNotBlank() || ddc.isNotBlank())) Status.FOUND else book.status,
        source = if (lc != book.lc || ddc != book.ddc) "edited" else book.source,
    )

    EInkDialog(onDismiss = dismiss) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Field(title, { title = it }, R.string.field_title)
            Field(author, { author = it }, R.string.field_author)
            Field(year, { year = it }, R.string.field_year, KeyboardType.Number)
            Field(lc, { lc = it }, R.string.field_lc, caps = KeyboardCapitalization.Characters)
            if (lc.isNotBlank() && CallNumber.lcKey(lc) == null) Note(stringResource(R.string.lc_invalid))
            Field(ddc, { ddc = it }, R.string.field_dewey, KeyboardType.Decimal)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextMMD(text = stringResource(R.string.field_copies))
                Spacer(Modifier.weight(1f))
                Link("−") { if (copies > 1) copies-- }
                TextMMD(text = copies.toString(), fontWeight = FontWeight.Bold)
                Link("+") { copies++ }
            }
            // Follows the numbers as they are typed, until set here against them.
            val guess = Book(id = 0, lc = lc, ddc = ddc).fictionGuess
            val isFiction = fiction ?: guess
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextMMD(text = stringResource(R.string.field_fiction))
                Spacer(Modifier.weight(1f))
                Link(stringResource(if (isFiction) R.string.yes else R.string.no)) {
                    fiction = (!isFiction).takeIf { it != guess }
                }
            }
            val ids = listOfNotNull(
                book.isbn.takeIf { it.isNotEmpty() }?.let { "ISBN $it" },
                book.lccn.takeIf { it.isNotEmpty() }?.let { "LCCN $it" },
            ).joinToString("  ·  ")
            if (ids.isNotEmpty()) Note(ids)
            if (book.source.isNotEmpty()) Note(stringResource(R.string.source, book.source))
            Spacer(Modifier.height(8.dp))
            // Two to a row, so the whole dialog fits a 480x800 screen without scrolling.
            Row {
                Pair(stringResource(R.string.save), Modifier.weight(1f), filled = true) { lib.update(edited()); dismiss() }
                Pair(stringResource(R.string.cancel), Modifier.weight(1f), onClick = dismiss)
            }
            Row {
                if (book.isbn.isNotEmpty() || book.lccn.isNotEmpty()) {
                    Pair(stringResource(R.string.look_again), Modifier.weight(1f)) {
                        lib.update(edited()); lib.retry(book.id); dismiss()
                    }
                }
                Pair(stringResource(if (armed) R.string.delete_armed else R.string.delete), Modifier.weight(1f), filled = armed) {
                    if (armed) { lib.delete(book.id); dismiss() } else armed = true
                }
            }
        }
    }
}

/** The overflow: syncing, and the spreadsheet in and out. */
@Composable
fun MenuDialog(lib: Library, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cloud by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val exported = stringResource(R.string.exported)
    val exportFailed = stringResource(R.string.export_failed)
    val importFailed = stringResource(R.string.import_failed)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = lib.export(uri)
            Toast.makeText(context, if (ok) exported else exportFailed, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val n = lib.import(uri)
            val msg = if (n == null) importFailed else context.getString(R.string.imported, n)
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            dismiss()
        }
    }

    if (cloud) {
        CloudDialog(lib) { cloud = false }
        return
    }
    if (about) {
        AboutDialog(onDismiss = { about = false })
        return
    }
    EInkDialog(onDismiss = dismiss) {
        Title(stringResource(R.string.menu))
        DialogButton(stringResource(R.string.cloud_heading)) { cloud = true }
        DialogButton(stringResource(R.string.export_csv)) { save.launch("shelf-order.csv") }
        DialogButton(stringResource(R.string.import_csv)) {
            load.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
        }
        DialogButton(stringResource(R.string.about)) { about = true }
        DialogButton(stringResource(R.string.close), onClick = dismiss)
    }
}

/**
 * Nextcloud: sign in through the server's own page when this phone has a browser, or with
 * an app password typed in when it doesn't (the Kompakt may not).
 */
@Composable
fun CloudDialog(lib: Library, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by lib.account.collectAsStateWithLifecycle()
    val folder by lib.folder.collectAsStateWithLifecycle()
    val state by lib.syncState.collectAsStateWithLifecycle()
    val last by lib.lastSync.collectAsStateWithLifecycle()

    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var newFolder by remember(folder) { mutableStateOf(folder) }
    val noBrowser = stringResource(R.string.cloud_no_browser)
    val unreachable = stringResource(R.string.cloud_unreachable)
    val notLogin = stringResource(R.string.cloud_qr_not_login)
    var qr by remember { mutableStateOf(false) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { qr = it }

    EInkDialog(onDismiss = dismiss) {
        Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState())) {
            Title(stringResource(R.string.cloud_heading))
            val a = account
            if (a != null) {
                Note(stringResource(R.string.cloud_signed_in, a.user, a.server.removePrefix("https://")))
                Note(
                    when (state) {
                        Library.SyncState.SYNCING -> stringResource(R.string.cloud_syncing)
                        Library.SyncState.FAILED -> stringResource(R.string.cloud_failed)
                        else -> if (last == 0L) "" else stringResource(
                            R.string.cloud_last, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
                        )
                    }
                )
                Spacer(Modifier.height(6.dp))
                Field(newFolder, { newFolder = it }, R.string.cloud_folder, caps = KeyboardCapitalization.None)
                Note(stringResource(R.string.cloud_folder_note))
                Spacer(Modifier.height(6.dp))
                if (newFolder.trim().trim('/') != folder) {
                    DialogButton(stringResource(R.string.cloud_use_folder), filled = true) { lib.setFolder(newFolder) }
                }
                DialogButton(stringResource(R.string.cloud_sync_now), filled = true) { lib.syncNow() }
                DialogButton(stringResource(R.string.cloud_sign_out)) { lib.signOut() }
            } else {
                Note(stringResource(R.string.cloud_explain))
                Spacer(Modifier.height(6.dp))
                if (qr) {
                    Note(stringResource(R.string.cloud_qr_note))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(180.dp).clipToBounds().background(Color.Black)) {
                        Scanner(Modifier.fillMaxSize(), torch = false, formats = setOf(BarcodeReader.Format.QR_CODE)) { code ->
                            val got = LoginFlow.fromQr(code.text)
                            if (got == null) problem = notLogin else { qr = false; lib.signIn(got) }
                        }
                    }
                    problem?.let { Note(it) }
                    Spacer(Modifier.height(6.dp))
                    DialogButton(stringResource(R.string.cancel)) { qr = false; problem = null }
                    return@Column
                }
                Field(server, { server = it; problem = null }, R.string.cloud_server, KeyboardType.Uri, KeyboardCapitalization.None)
                if (typed) {
                    Field(user, { user = it }, R.string.cloud_user, caps = KeyboardCapitalization.None)
                    Field(password, { password = it }, R.string.cloud_app_password, KeyboardType.Password, KeyboardCapitalization.None, secret = true)
                    Note(stringResource(R.string.cloud_app_password_note))
                }
                problem?.let { Note(it) }
                if (waiting) Note(stringResource(R.string.cloud_waiting))
                Spacer(Modifier.height(6.dp))
                if (!typed) {
                    DialogButton(stringResource(R.string.cloud_sign_in), filled = true) {
                        if (server.isBlank() || waiting) return@DialogButton
                        scope.launch {
                            val started = runCatching { withContext(Dispatchers.IO) { LoginFlow.start(server) } }.getOrNull()
                            if (started == null) { problem = unreachable; return@launch }
                            val opened = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(started.loginUrl)))
                            }.isSuccess
                            if (!opened) { problem = noBrowser; typed = true; return@launch }
                            waiting = true
                            // The server gives twenty minutes to finish signing in.
                            repeat(600) {
                                delay(2000)
                                val got = runCatching { withContext(Dispatchers.IO) { LoginFlow.poll(started) } }.getOrNull()
                                if (got != null) { waiting = false; lib.signIn(got); return@launch }
                            }
                            waiting = false
                        }
                    }
                    DialogButton(stringResource(R.string.cloud_scan_qr)) {
                        problem = null
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) qr = true
                        else askCamera.launch(Manifest.permission.CAMERA)
                    }
                    DialogButton(stringResource(R.string.cloud_type_password)) { typed = true }
                } else {
                    DialogButton(stringResource(R.string.cloud_sign_in), filled = true) {
                        if (server.isBlank() || user.isBlank() || password.isEmpty()) return@DialogButton
                        lib.signIn(Account(LoginFlow.normalise(server), user.trim(), password.trim()))
                    }
                }
            }
            DialogButton(stringResource(R.string.close), onClick = dismiss)
        }
    }
}

/** A full-width button inside a dialog. */
@Composable
fun DialogButton(text: String, filled: Boolean = false, onClick: () -> Unit) {
    Choice(text, filled, Modifier.fillMaxWidth().padding(vertical = 3.dp), onClick)
}

/** A [DialogButton] that shares its row with one other. */
@Composable
private fun Pair(text: String, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    Choice(text, filled, modifier.padding(3.dp), onClick)
}

