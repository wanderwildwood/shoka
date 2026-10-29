package com.wanderwildwood.shoka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.lifecycle.lifecycleScope
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.shoka.ui.ShelfApp
import com.wanderwildwood.shoka.ui.monochrome
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val library: Library by viewModels()

    // Sideways when the phone is: the Kompakt never turns a screen by itself. The manifest keeps
    // the activity through a turn, so the camera and what is on screen carry on.
    private val turner = TurnWithPhone(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        turner.set(library.turnWithPhone.value)
        lifecycleScope.launch {
            // Read again whenever the setting changes, so the switch takes effect at once.
            library.turnWithPhone.collect { turner.set(it) }
        }
        setContent {
            // A blinking cursor redraws the panel twice a second for as long as a field has focus.
            CompositionLocalProvider(LocalCursorBlinkEnabled provides false) {
                // On its side the status bar goes, for the height; a swipe from the edge shows it
                // for a moment. Upright it is back. Set again on every turn and fresh start.
                val sideways = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                DisposableEffect(sideways) {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (sideways) controller.hide(WindowInsetsCompat.Type.statusBars())
                    else controller.show(WindowInsetsCompat.Type.statusBars())
                    onDispose { }
                }
                ThemeMMD(colorScheme = monochrome) { ShelfApp(library) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        turner.resume()
    }

    override fun onPause() {
        turner.pause()
        super.onPause()
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
