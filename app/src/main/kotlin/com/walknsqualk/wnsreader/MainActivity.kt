// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The app's screens, kept as a back stack. */
sealed interface Screen {
    data class Library(val dir: String) : Screen
    data class Reader(val path: String) : Screen
    data object Dictionaries : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Open the installed dictionaries once, up front; the reader looks
        // words up through whatever is open.
        val dictionaryRoot = Paths.installedDictionaries(this).absolutePath
        val answerCache = Paths.answerCache(this).absolutePath
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { NativeCore.openDictionaries(dictionaryRoot, prefs.frequencyOrder) }
            runCatching { NativeCore.openAnswerCache(answerCache) }
        }

        setContent {
            WnsTheme {
                // The app targets edge-to-edge; keep content clear of the
                // status and navigation bars (the reader hides them).
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    App(prefs, onRequestAllFiles = ::requestAllFilesAccess)
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }
}

@Composable
private fun App(prefs: Prefs, onRequestAllFiles: () -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Library(Paths.books.absolutePath)) }
    fun push(screen: Screen) = stack.add(screen)
    fun pop() = stack.removeAt(stack.lastIndex)

    BackHandler(enabled = stack.size > 1) { pop() }

    when (val screen = stack.last()) {
        is Screen.Library -> LibraryScreen(
            dir = screen.dir,
            isRoot = stack.size == 1,
            prefs = prefs,
            onRequestAllFiles = onRequestAllFiles,
            onOpenFolder = { push(Screen.Library(it)) },
            onOpenBook = { push(Screen.Reader(it)) },
            onUp = { pop() },
            onDictionaries = { push(Screen.Dictionaries) },
            onSettings = { push(Screen.Settings) },
        )
        is Screen.Reader -> ReaderScreen(path = screen.path, prefs = prefs, onBack = { pop() })
        Screen.Dictionaries -> DictionariesScreen(prefs = prefs, onBack = { pop() })
        Screen.Settings -> SettingsScreen(prefs = prefs, onBack = { pop() }, onDiagnostics = { push(Screen.Diagnostics) })
        Screen.Diagnostics -> DiagnosticsScreen(onBack = { pop() })
    }
}

/**
 * Black on white with no tinted surfaces: the Daylight's reflective
 * display has no colour worth relying on, and contrast is what reads.
 */
@Composable
fun WnsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color.Black,
            onPrimary = Color.White,
            secondary = Color.Black,
            onSecondary = Color.White,
            background = Color.White,
            onBackground = Color.Black,
            surface = Color.White,
            onSurface = Color.Black,
            surfaceVariant = Color.White,
            onSurfaceVariant = Color.Black,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color.White,
            surfaceContainerHighest = Color.White,
            outline = Color.Black,
        ),
        content = content,
    )
}
