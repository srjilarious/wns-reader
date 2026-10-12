// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One directory under the books folder: subfolders to browse into, then
 * books (image folders and archives) to open. The root is /sdcard/Manga.
 */
@Composable
fun LibraryScreen(
    dir: String,
    isRoot: Boolean,
    prefs: Prefs,
    onRequestAllFiles: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onUp: () -> Unit,
    onDictionaries: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (isRoot) "Library" else File(dir).name,
            onBack = if (isRoot) null else onUp,
        ) {
            TextButton(onClick = onDictionaries) { Text("Dictionaries", fontSize = 18.sp) }
            TextButton(onClick = onDiagnostics) { Text("Diagnostics", fontSize = 18.sp) }
        }
        AllFilesGate(onRequestAllFiles) {
            Listing(dir, prefs, onOpenFolder, onOpenBook)
        }
    }
}

private sealed interface ListingState {
    data object Loading : ListingState
    data object Missing : ListingState
    data class Failed(val message: String) : ListingState
    data class Loaded(val entries: List<LibraryEntry>) : ListingState
}

@Composable
private fun Listing(dir: String, prefs: Prefs, onOpenFolder: (String) -> Unit, onOpenBook: (String) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    var state by remember(dir) { mutableStateOf<ListingState>(ListingState.Loading) }

    LaunchedEffect(dir, reload) {
        state = withContext(Dispatchers.IO) {
            when {
                !File(dir).isDirectory -> ListingState.Missing
                else -> try {
                    ListingState.Loaded(NativeCore.list(dir))
                } catch (e: NativeException) {
                    ListingState.Failed(e.message ?: "unknown error")
                }
            }
        }
    }

    when (val current = state) {
        ListingState.Loading -> Text("Loading…", modifier = Modifier.padding(32.dp), fontSize = 18.sp)
        ListingState.Missing -> Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("$dir doesn't exist yet.", fontSize = 20.sp)
            Text("Books go there: CBZ files, or folders of page images with their .mokuro file beside them.", fontSize = 18.sp)
            Button(onClick = {
                File(dir).mkdirs()
                reload++
            }) { Text("Create it", fontSize = 18.sp) }
        }
        is ListingState.Failed -> Text("Couldn't list $dir: ${current.message}", modifier = Modifier.padding(32.dp), fontSize = 18.sp)
        is ListingState.Loaded -> if (current.entries.isEmpty()) {
            Text("No books here yet.", modifier = Modifier.padding(32.dp), fontSize = 18.sp)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(current.entries, key = { it.path }) { entry ->
                    EntryRow(entry, prefs) {
                        if (entry.kind == EntryKind.FOLDER) onOpenFolder(entry.path) else onOpenBook(entry.path)
                    }
                    HorizontalDivider(color = Color.LightGray)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: LibraryEntry, prefs: Prefs, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when (entry.kind) {
                EntryKind.FOLDER -> "▸"
                EntryKind.BOOK, EntryKind.ARCHIVE -> "■"
            },
            modifier = Modifier.width(24.dp),
            fontSize = 22.sp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 20.sp,
                fontWeight = if (entry.kind == EntryKind.FOLDER) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.kind != EntryKind.FOLDER && prefs.hasPosition(entry.path)) {
                Text("page ${prefs.lastPage(entry.path) + 1}", fontSize = 16.sp, color = Color.DarkGray)
            }
        }
        if (entry.ocr) Badge("OCR")
    }
}

@Composable
fun Badge(label: String) {
    Text(
        label,
        modifier = Modifier
            .border(1.dp, Color.Black)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 14.sp,
    )
}
