package com.wanderwildwood.shoka

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the line on a book list says once a swipe down at its top has synced with Nextcloud:
 * "Syncing…" while it runs, then "Up to date" or that it did not go through, until the next press.
 */
sealed interface Pulled {
    data object Syncing : Pulled
    data object UpToDate : Pulled
    data object Failed : Pulled
}

/** What a sync that left [state] behind has to say to the one who pulled for it. */
fun pulledAfter(state: Library.SyncState): Pulled =
    if (state == Library.SyncState.FAILED) Pulled.Failed else Pulled.UpToDate

class PullState internal constructor(private val scope: CoroutineScope, private val lib: Library) {
    var pulled by mutableStateOf<Pulled?>(null)
        private set
    private var job: Job? = null

    /** Whether a pull does anything: only with a Nextcloud set up. */
    val available: Boolean get() = lib.account.value != null

    /** True while the line only waits for the next press, which may be a new pull. */
    private var reporting = false

    /** The same sync as Menu, Sync now. A second pull while one is running is the same pull. */
    fun pull() {
        if (job?.isActive == true && !reporting) return
        val sync = lib.syncNow() ?: return
        job?.cancel()
        reporting = false
        job = scope.launch {
            pulled = Pulled.Syncing
            sync.join()
            pulled = pulledAfter(lib.syncState.value)
            // What it came to stays until the next press: on this panel a line on a timer could
            // be gone before it had been read, and "did not go through" most of all.
            reporting = true
            com.wanderwildwood.shoka.ui.NextPress.await()
            reporting = false
            pulled = null
        }
    }
}

@Composable
fun rememberPull(lib: Library): PullState {
    val scope = rememberCoroutineScope()
    return remember(lib) { PullState(scope, lib) }
}

/** [pulled] in words. */
@Composable
fun pulledWords(pulled: Pulled?): String? = when (pulled) {
    null -> null
    Pulled.Syncing -> stringResource(R.string.pull_syncing)
    Pulled.UpToDate -> stringResource(R.string.pull_up_to_date)
    Pulled.Failed -> stringResource(R.string.pull_failed)
}
