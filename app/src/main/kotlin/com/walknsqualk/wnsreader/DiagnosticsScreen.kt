// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class CheckResult(val name: String, val ok: Boolean, val detail: String)

private data class Report(val version: List<Pair<String, String>>, val checks: List<CheckResult>, val error: String?)

private fun runReport(scratchDir: String, booksDir: String?): Report {
    val versionJson = NativeCore.versionInfo()
    val version = versionJson.keys().asSequence().map { it to versionJson.optString(it) }.toList()

    val diagnostics = NativeCore.diagnostics(scratchDir, booksDir)
    if (diagnostics.has("error")) {
        return Report(version, emptyList(), diagnostics.getString("error"))
    }
    val array = diagnostics.getJSONArray("checks")
    val checks = (0 until array.length()).map { i ->
        val check = array.getJSONObject(i)
        CheckResult(check.getString("name"), check.getBoolean("ok"), check.getString("detail"))
    }
    return Report(version, checks, null)
}

/**
 * Phase 0's screen, kept: what libwnscore.so reports about itself and
 * the result of each check in native/src/diag.zig.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val scratchDir = LocalContext.current.cacheDir.absolutePath
    var runCount by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<Report?>(null) }

    LaunchedEffect(runCount) {
        val books = if (Environment.isExternalStorageManager()) Paths.books.absolutePath else null
        report = withContext(Dispatchers.IO) {
            try {
                runReport(scratchDir, books)
            } catch (e: Throwable) {
                Report(emptyList(), emptyList(), e.toString())
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Diagnostics", onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = report
            if (current == null) {
                Text("Running…", fontSize = 18.sp)
            } else {
                current.version.forEach { (key, value) ->
                    Row {
                        Text(key, modifier = Modifier.width(120.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(value, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                HorizontalDivider(color = Color.Black)
                current.error?.let { Text("Native call failed: $it", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                current.checks.forEach { check ->
                    Column {
                        Text((if (check.ok) "PASS  " else "FAIL  ") + check.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(check.detail, fontSize = 18.sp)
                    }
                }
            }
            Button(onClick = { runCount++ }) { Text("Run again", fontSize = 18.sp) }
        }
    }
}
