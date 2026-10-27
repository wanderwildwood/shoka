package com.wanderwildwood.shoka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.shoka.ui.ShelfApp
import com.wanderwildwood.shoka.ui.monochrome

class MainActivity : ComponentActivity() {
    private val library: Library by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // A blinking cursor redraws the panel twice a second for as long as a field has focus.
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                ThemeMMD(colorScheme = monochrome) { ShelfApp(library) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        library.resume()
        // Another phone's scans turn up while this one is open; nothing is asked while it is not.
        library.watch(true)
    }

    override fun onStop() {
        library.watch(false)
        super.onStop()
    }
}
