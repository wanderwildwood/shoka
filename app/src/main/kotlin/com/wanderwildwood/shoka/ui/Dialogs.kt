package com.wanderwildwood.shoka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wanderwildwood.shoka.Book
import com.wanderwildwood.shoka.CallNumber
import com.wanderwildwood.shoka.Found
import com.wanderwildwood.shoka.Isbn
import com.wanderwildwood.shoka.Lccn
import com.wanderwildwood.shoka.Library
import com.wanderwildwood.shoka.Lookup
import com.wanderwildwood.shoka.R
import com.wanderwildwood.shoka.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

private val Grey = Color(0xFF6B6B6B)

@Composable
private fun Action(label: String, bold: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = Color.Black, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
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
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color.White,
        title = { Text(stringResource(R.string.manual_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; bad = false }, singleLine = true,
                    label = { Text(stringResource(R.string.manual_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    isError = bad,
                )
                if (bad) Text(stringResource(R.string.manual_bad), color = Color.Black, fontSize = 13.sp)
            }
        },
        confirmButton = {
            Action(stringResource(R.string.add), bold = true) {
                val isbn = Isbn.normalize(text)
                val lccn = if (isbn == null) Lccn.normalize(text) else null
                when {
                    isbn != null -> { done(isbn, null); dismiss() }
                    lccn != null -> {
                        val id = (lib.findLccn(lccn) ?: lib.addLccn(lccn)).id
                        done(null, id); dismiss()
                    }
                    else -> bad = true
                }
            }
        },
        dismissButton = { Action(stringResource(R.string.cancel), onClick = dismiss) },
    )
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

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color.White,
        title = { Text(stringResource(R.string.search_heading), fontSize = 18.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text(stringResource(R.string.field_title)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                OutlinedTextField(
                    value = author, onValueChange = { author = it }, singleLine = true,
                    label = { Text(stringResource(R.string.field_author)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Spacer(Modifier.padding(4.dp))
                when {
                    busy -> Text(stringResource(R.string.searching), color = Grey)
                    offline -> Text(stringResource(R.string.search_offline), color = Grey)
                    results?.isEmpty() == true -> Text(stringResource(R.string.search_none), color = Grey)
                }
                results?.takeIf { it.isNotEmpty() }?.let { list ->
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(list) { f ->
                            Column(
                                Modifier.fillMaxWidth().clickable {
                                    added(lib.addFound(f).id, false); dismiss()
                                }.padding(vertical = 8.dp)
                            ) {
                                Text(f.title, fontWeight = FontWeight.Medium)
                                Text(
                                    listOf(f.author, f.year, f.lc).filter { it.isNotEmpty() }.joinToString("  ·  "),
                                    color = Grey, fontSize = 13.sp,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
                if (results != null && title.isNotBlank()) {
                    Action(stringResource(R.string.add_by_hand)) {
                        added(lib.addByHand(title, author).id, true); dismiss()
                    }
                }
            }
        },
        confirmButton = {
            Action(stringResource(R.string.search), bold = true) {
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
        },
        dismissButton = { Action(stringResource(R.string.close), onClick = dismiss) },
    )
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
        lc = lc.trim().uppercase(), ddc = ddc.trim(), copies = copies,
        status = if (book.status == Status.NOT_FOUND && (lc.isNotBlank() || ddc.isNotBlank())) Status.FOUND else book.status,
        source = if (lc != book.lc || ddc != book.ddc) "edited" else book.source,
    )

    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Color.White,
        title = { Text(stringResource(R.string.edit_heading)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(title, { title = it }, R.string.field_title)
                Field(author, { author = it }, R.string.field_author)
                Field(year, { year = it }, R.string.field_year, KeyboardType.Number)
                Field(lc, { lc = it }, R.string.field_lc)
                if (lc.isNotBlank() && CallNumber.lcKey(lc) == null) {
                    Text(stringResource(R.string.lc_invalid), color = Grey, fontSize = 12.sp)
                }
                Field(ddc, { ddc = it }, R.string.field_dewey)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.field_copies))
                    Spacer(Modifier.width(12.dp))
                    Action("−") { if (copies > 1) copies-- }
                    Text(copies.toString(), fontWeight = FontWeight.Bold)
                    Action("+") { copies++ }
                }
                val ids = listOfNotNull(
                    book.isbn.takeIf { it.isNotEmpty() }?.let { "ISBN $it" },
                    book.lccn.takeIf { it.isNotEmpty() }?.let { "LCCN $it" },
                ).joinToString("  ·  ")
                if (ids.isNotEmpty()) Text(ids, color = Grey, fontSize = 13.sp)
                if (book.source.isNotEmpty()) Text(stringResource(R.string.source, book.source), color = Grey, fontSize = 13.sp)
                Row {
                    if (book.isbn.isNotEmpty() || book.lccn.isNotEmpty()) {
                        Action(stringResource(R.string.look_again)) { lib.update(edited()); lib.retry(book.id); dismiss() }
                    }
                    Spacer(Modifier.weight(1f))
                    Action(stringResource(if (armed) R.string.delete_armed else R.string.delete), bold = armed) {
                        if (armed) { lib.delete(book.id); dismiss() } else armed = true
                    }
                }
            }
        },
        confirmButton = { Action(stringResource(R.string.save), bold = true) { lib.update(edited()); dismiss() } },
        dismissButton = { Action(stringResource(R.string.cancel), onClick = dismiss) },
    )
}

@Composable
private fun Field(value: String, set: (String) -> Unit, label: Int, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = set, singleLine = true,
        label = { Text(stringResource(label)) },
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}
