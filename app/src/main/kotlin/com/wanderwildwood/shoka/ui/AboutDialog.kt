package com.wanderwildwood.shoka.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD
import com.wanderwildwood.shoka.BuildConfig
import com.wanderwildwood.shoka.R

/**
 * What this is, what it sends where, its licence and what it is built with, and where its
 * source is. The words are a list that pages, so the dialog fits any screen; Close stays under it.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    EInkDialog(onDismiss = onDismiss) {
        PagedColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            item {
                TextMMD(
                    text = stringResource(R.string.about_title, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            item { Para(stringResource(R.string.about_what)) }
            item { Heading(stringResource(R.string.about_privacy_heading)) }
            item { Para(stringResource(R.string.about_privacy_lookups), top = 4.dp) }
            item { Para(stringResource(R.string.about_privacy_cloud)) }
            item { Para(stringResource(R.string.about_privacy_phone)) }
            item { Heading(stringResource(R.string.about_terms_heading)) }
            item { Para(stringResource(R.string.about_terms), top = 4.dp) }
            item { Heading(stringResource(R.string.about_open_source_heading)) }
            item { Para(stringResource(R.string.about_bundled), top = 4.dp) }
            item { Para("github.com/wanderwildwood/shoka") }
            item { Spacer(Modifier.height(10.dp)); Llama() }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButtonMMD(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { TextMMD(text = stringResource(R.string.about_close), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Heading(text: String) {
    TextMMD(
        text = text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 14.dp),
    )
}

@Composable
private fun Para(text: String, top: androidx.compose.ui.unit.Dp = 10.dp) {
    TextMMD(text = text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = top))
}

/**
 * A llama at the foot of the About, and its three words, which open the page a donation goes to.
 *
 * Straight to the checkout: the Donate button on the site only leads there anyway. The short
 * square.link form, which is what the site itself links to, so a regenerated checkout follows
 * it. A phone with nothing that opens a web address says so rather than doing nothing.
 */
@Composable
private fun Llama() {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://square.link/u/AGu8oT10")),
                    )
                }.onFailure {
                    Toast.makeText(context, context.getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
                }
            },
    ) {
        Image(
            painter = painterResource(R.drawable.llama),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        TextMMD(text = stringResource(R.string.about_feed_the_llamas), style = MaterialTheme.typography.labelSmall)
    }
}
