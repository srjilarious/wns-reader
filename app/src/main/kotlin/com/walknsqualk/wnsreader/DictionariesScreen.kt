// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface InstallState {
    data object Idle : InstallState
    data class Running(val zip: String, val progress: InstallProgress?) : InstallState
    data class Failed(val zip: String, val message: String) : InstallState
    data class Finished(val zip: String, val slot: String) : InstallState
}

/**
 * What's installed, and the Yomitan zips waiting in
 * /sdcard/wns-reader/dictionaries to be installed from.
 *
 * Installing unpacks the zip into app storage, indexes it into SQLite
 * (gw-yomi's `dict.Builder`, one bank file per step) and keeps only the
 * index. A term dictionary replaces the term dictionary; a frequency
 * list replaces the frequency list.
 */
@Composable
fun DictionariesScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val root = remember { Paths.installedDictionaries(context).absolutePath }

    var status by remember { mutableStateOf<DictionaryStatus?>(null) }
    var zips by remember { mutableStateOf<List<File>>(emptyList()) }
    var install by remember { mutableStateOf<InstallState>(InstallState.Idle) }
    var installJob by remember { mutableStateOf<Job?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var order by remember { mutableStateOf(prefs.frequencyOrder) }
    val installing = install is InstallState.Running

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            Paths.dictionaryDownloads.mkdirs()
            zips = Paths.dictionaryDownloads.listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
            status = runCatching { NativeCore.dictionaries() }.getOrNull()
        }
    }

    // An install can take minutes for Jitendex; keep the screen on, and
    // abandon the install rather than leave it half-done if the screen goes.
    DisposableEffect(installing) {
        view.keepScreenOn = installing
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (installJob?.isActive == true) {
                installJob?.cancel()
                NativeCore.cancelInstall()
            }
        }
    }
    BackHandler(enabled = installing) { }

    fun startInstall(zip: File) {
        installJob = scope.launch {
            install = InstallState.Running(zip.name, null)
            install = withContext(Dispatchers.IO) {
                try {
                    var progress = NativeCore.beginInstall(zip.absolutePath, root)
                    while (!progress.finished && isActive) {
                        withContext(Dispatchers.Main) { install = InstallState.Running(zip.name, progress) }
                        progress = NativeCore.stepInstall()
                    }
                    NativeCore.openDictionaries(root, prefs.frequencyOrder)
                    InstallState.Finished(zip.name, progress.slot)
                } catch (e: NativeException) {
                    // Whatever was there before may have been closed for
                    // the swap; reopen what's still installed.
                    runCatching { NativeCore.openDictionaries(root, prefs.frequencyOrder) }
                    InstallState.Failed(zip.name, e.message ?: "unknown error")
                }
            }
            refresh++
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Dictionaries", onBack = if (installing) null else onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Installed")
            SlotRow("Terms", status?.terms)
            SlotRow("Frequency", status?.frequency)

            Text("Frequency numbers count", fontSize = 18.sp)
            listOf("rank" to "up from 1 (JPDB, BCCWJ, Innocent Corpus ranks)", "count" to "occurrences (higher is more common)")
                .forEach { (choice, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = order == choice,
                            enabled = !installing,
                            onClick = {
                                order = choice
                                prefs.frequencyOrder = choice
                                scope.launch {
                                    status = withContext(Dispatchers.IO) { NativeCore.openDictionaries(root, choice) }
                                }
                            },
                        )
                        Text(label, fontSize = 18.sp)
                    }
                }

            HorizontalDivider(color = Color.Black)
            Section("Install")
            Text(
                "Put Yomitan dictionary zips in ${Paths.dictionaryDownloads.absolutePath}: " +
                    "a term dictionary such as Jitendex, and optionally a frequency list such as JPDB's.",
                fontSize = 18.sp,
            )

            when (val state = install) {
                is InstallState.Running -> InstallProgressView(state, onCancel = {
                    installJob?.cancel()
                    scope.launch(Dispatchers.IO) {
                        NativeCore.cancelInstall()
                        runCatching { NativeCore.openDictionaries(root, prefs.frequencyOrder) }
                    }
                    install = InstallState.Idle
                    refresh++
                })
                is InstallState.Failed -> Text("Installing ${state.zip} failed: ${state.message}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                is InstallState.Finished -> Text("Installed ${state.zip} as the ${state.slot} dictionary.", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                InstallState.Idle -> {}
            }

            if (zips.isEmpty()) {
                Text("No .zip files there yet.", fontSize = 18.sp, color = Color.DarkGray)
            }
            zips.forEach { zip ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(zip.name, fontSize = 18.sp)
                        Text("%.1f MB".format(zip.length() / 1_048_576.0), fontSize = 15.sp, color = Color.DarkGray)
                    }
                    OutlinedButton(onClick = { startInstall(zip) }, enabled = !installing) { Text("Install", fontSize = 18.sp) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun SlotRow(label: String, slot: SlotStatus?) {
    Row {
        Text(label, modifier = Modifier.width(140.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            when {
                slot == null -> "…"
                slot.installed -> slot.title.ifEmpty { "(untitled)" }
                else -> "not installed"
            },
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun InstallProgressView(state: InstallState.Running, onCancel: () -> Unit) {
    val p = state.progress
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Installing ${state.zip}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val phase = when (p?.phase) {
            null -> "Opening…"
            "extracting" -> "Unpacking ${p.done} of ${p.total} files"
            "indexing" -> "Indexing ${p.done} of ${p.total} files"
            else -> "Finishing…"
        }
        Text(phase, fontSize = 18.sp)
        if (p != null && p.total > 0) {
            LinearProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
        }
        Button(onClick = onCancel) { Text("Cancel", fontSize = 18.sp) }
    }
}
