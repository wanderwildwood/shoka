package com.wanderwildwood.shoka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.wanderwildwood.shoka.ui.ShelfApp

class MainActivity : ComponentActivity() {
    private val library: Library by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ShelfApp(library) }
    }
}
