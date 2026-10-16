package com.wanderwildwood.shoka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.mudita.mmd.ThemeMMD
import com.wanderwildwood.shoka.ui.ShelfApp
import com.wanderwildwood.shoka.ui.monochrome

class MainActivity : ComponentActivity() {
    private val library: Library by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ThemeMMD(colorScheme = monochrome) { ShelfApp(library) } }
    }
}
