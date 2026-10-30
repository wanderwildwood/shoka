package com.wanderwildwood.shoka.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.text.TextMMD
import com.mudita.mmd.components.top_app_bar.TopAppBarMMD
import com.wanderwildwood.shoka.Library
import com.wanderwildwood.shoka.R
import kotlinx.coroutines.launch

/**
 * Behind the cog: the Nextcloud the catalogue syncs with, and the shelf order out to a
 * spreadsheet and back. What a save or a read did stays under its row until the next one.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(lib: Library, onBack: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by lib.account.collectAsStateWithLifecycle()
    val folder by lib.folder.collectAsStateWithLifecycle()
    val books by lib.books.collectAsStateWithLifecycle()
    var cloud by rememberSaveable { mutableStateOf(false) }
    var exported by rememberSaveable { mutableStateOf<String?>(null) }
    var imported by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val n = lib.export(uri)
            exported = if (n == null) context.getString(R.string.export_failed)
            else context.resources.getQuantityString(R.plurals.exported, n, n)
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val r = lib.import(uri)
            imported = when {
                r == null -> context.getString(R.string.import_failed)
                r.added == 0 && r.read == 0 -> context.getString(R.string.import_none)
                else -> context.resources.getQuantityString(R.plurals.imported, r.added, r.added) +
                    if (r.read > r.added) " " + context.resources.getQuantityString(R.plurals.import_had, r.read - r.added, r.read - r.added) else ""
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding()) {
        TopAppBarMMD(
            title = { TextMMD(text = stringResource(R.string.settings)) },
            navigationIcon = { BarButton(Icons.Back, stringResource(R.string.back), onBack) },
            actions = { BarButton(Icons.Info, stringResource(R.string.about), onAbout) },
        )
        PagedColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp)) {
            item(key = "cloud") {
                val a = account
                SettingRow(
                    stringResource(R.string.cloud_heading),
                    if (a == null) stringResource(R.string.cloud_off)
                    else stringResource(R.string.cloud_signed_in_folder, a.user, a.server.removePrefix("https://"), folder),
                ) { cloud = true }
            }
            item(key = "export") {
                SettingRow(
                    stringResource(R.string.export_csv),
                    exported ?: pluralStringResource(R.plurals.book_count, books.size, books.size),
                ) { save.launch("shelf-order.csv") }
            }
            item(key = "import") {
                SettingRow(stringResource(R.string.import_csv), imported) {
                    load.launch(arrayOf("text/*", "application/csv", "application/octet-stream"))
                }
            }
            item(key = "turn") {
                val turn by lib.turnWithPhone.collectAsStateWithLifecycle()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { lib.setTurnWithPhone(!turn) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextMMD(
                        text = stringResource(R.string.turn_with_phone), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(16.dp))
                    // The whole row takes the press.
                    SwitchMMD(checked = turn, onCheckedChange = null)
                }
            }
        }
    }

    if (cloud) CloudDialog(lib) { cloud = false }
}

/** A row that says what it is and, under it, what it is set to or what it last did. */
@Composable
private fun SettingRow(title: String, value: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        TextMMD(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (value != null) TextMMD(text = value, style = MaterialTheme.typography.labelSmall)
    }
}
