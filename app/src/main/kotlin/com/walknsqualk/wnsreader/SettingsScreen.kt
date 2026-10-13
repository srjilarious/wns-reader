// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What AnkiDroid has, as far as wns-reader can see. */
private data class AnkiState(
    val installed: Boolean,
    val access: Boolean,
    val decks: List<String> = emptyList(),
    val noteTypes: List<String> = emptyList(),
    /** The configured note type's fields, or null if AnkiDroid hasn't got it. */
    val fields: List<String>? = null,
)

/**
 * Everything gw-read takes from `read.conf.lua`, as a screen. Changes
 * save as they're made.
 */
@Composable
fun SettingsScreen(prefs: Prefs, onBack: () -> Unit, onDiagnostics: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack)
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReadingSettings(prefs)
            HorizontalDivider(color = Color.Black)
            AiSettings(prefs)
            HorizontalDivider(color = Color.Black)
            AnkiSettings(prefs)
            HorizontalDivider(color = Color.Black)
            OutlinedButton(onClick = onDiagnostics) { Text("Diagnostics", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun ReadingSettings(prefs: Prefs) {
    Section("Reading")
    var side by remember { mutableStateOf(prefs.panelSide) }
    Choice(
        label = "Bubble panel",
        options = PanelSide.entries.map { it to it.label },
        selected = side,
        onSelect = {
            side = it
            prefs.panelSide = it
        },
    )
    var boxes by remember { mutableStateOf(prefs.showBubbleBoxes) }
    Toggle("Outline OCR bubbles on the page", boxes) {
        boxes = it
        prefs.showBubbleBoxes = it
    }
}

@Composable
private fun AiSettings(prefs: Prefs) {
    Section("AI")
    var enabled by remember { mutableStateOf(prefs.aiEnabled) }
    Toggle("Ask an AI about bubbles (sends the bubble's OCR text to the provider)", enabled) {
        enabled = it
        prefs.aiEnabled = it
    }
    if (!enabled) return

    var providerId by remember { mutableStateOf(prefs.aiProvider) }
    val provider = prefs.defaults.providers.firstOrNull { it.id == providerId } ?: prefs.defaults.providers.first()
    Choice(
        label = "Provider",
        options = prefs.defaults.providers.map { it.id to it.label },
        selected = provider.id,
        onSelect = {
            providerId = it
            prefs.aiProvider = it
        },
    )

    // Keyed on the provider: each keeps its own model, endpoint and key.
    var model by remember(provider.id) { mutableStateOf(prefs.aiModel(provider.id)) }
    Field("Model", model, placeholder = provider.model) {
        model = it
        prefs.setAiModel(provider.id, it.trim())
    }
    var endpoint by remember(provider.id) { mutableStateOf(prefs.aiEndpoint(provider.id)) }
    Field("Endpoint", endpoint, placeholder = provider.endpoint) {
        endpoint = it
        prefs.setAiEndpoint(provider.id, it.trim())
    }
    LocalNetworkAccess(endpoint.trim().ifEmpty { provider.endpoint })
    if (provider.needsKey) {
        var key by remember(provider.id) { mutableStateOf(prefs.aiKey(provider.id)) }
        Field("API key (stored encrypted on this device)", key, placeholder = "", secret = true) {
            key = it
            prefs.setAiKey(provider.id, it.trim())
        }
    }

    var style by remember { mutableStateOf(prefs.aiStyle) }
    Field("Instructions (reading level and style)", style, placeholder = prefs.defaults.aiStyle, singleLine = false) {
        style = it
        prefs.aiStyle = it
    }
    if (style != prefs.defaults.aiStyle) {
        TextButton(onClick = {
            style = prefs.defaults.aiStyle
            prefs.aiStyle = style
        }) { Text("Reset instructions", fontSize = 16.sp) }
    }

    var neighbours by remember { mutableStateOf(prefs.aiIncludeNeighbors) }
    Toggle("Include the bubbles before and after", neighbours) {
        neighbours = it
        prefs.aiIncludeNeighbors = it
    }
    var bookInfo by remember { mutableStateOf(prefs.aiIncludeBookInfo) }
    Toggle("Include the book's name and page", bookInfo) {
        bookInfo = it
        prefs.aiIncludeBookInfo = it
    }
    var cache by remember { mutableStateOf(prefs.aiUseCache) }
    Toggle("Reuse earlier answers to the same question", cache) {
        cache = it
        prefs.aiUseCache = it
    }
}

/** An Allow button for Android 17's local-network permission, when the endpoint needs it. */
@Composable
private fun LocalNetworkAccess(endpoint: String) {
    val context = LocalContext.current
    var granted by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted++ }
    val needed = remember(endpoint, granted) { LocalNetwork.needsPermission(context, endpoint) }
    if (!needed) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("That address is on the local network, which needs permission.", modifier = Modifier.weight(1f), fontSize = 18.sp)
        Button(onClick = { launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }) { Text("Allow", fontSize = 18.sp) }
    }
}

@Composable
private fun AnkiSettings(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Section("Anki cards (AnkiDroid)")

    var refresh by remember { mutableIntStateOf(0) }
    var deck by remember { mutableStateOf(prefs.ankiDeck) }
    var noteType by remember { mutableStateOf(prefs.ankiModel) }
    var mapping by remember { mutableStateOf(prefs.ankiFields) }
    var anki by remember { mutableStateOf<AnkiState?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh, noteType) {
        anki = withContext(Dispatchers.IO) {
            val droid = AnkiDroid(context)
            when {
                !droid.installed() -> AnkiState(installed = false, access = false)
                !droid.hasPermission() -> AnkiState(installed = true, access = false)
                else -> runCatching {
                    AnkiState(true, true, droid.decks(), droid.noteTypes(), droid.fields(noteType))
                }.getOrElse { AnkiState(true, true) }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val state = anki
    when {
        state == null -> Text("Checking AnkiDroid…", fontSize = 18.sp)
        !state.installed -> Text("AnkiDroid isn't installed. Cards are added through it.", fontSize = 18.sp)
        !state.access -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("wns-reader needs permission to add cards.", modifier = Modifier.weight(1f), fontSize = 18.sp)
            Button(onClick = { permission.launch(AddContentApi.READ_WRITE_PERMISSION) }) { Text("Allow", fontSize = 18.sp) }
        }
        else -> Text("Connected to AnkiDroid.", fontSize = 18.sp)
    }

    PickField("Deck", deck, state?.decks.orEmpty()) {
        deck = it
        prefs.ankiDeck = it.trim()
    }
    PickField("Note type", noteType, state?.noteTypes.orEmpty()) {
        noteType = it
        prefs.ankiModel = it.trim()
    }

    if (state?.access == true && state.fields == null && noteType.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("AnkiDroid has no note type \"$noteType\".", modifier = Modifier.weight(1f), fontSize = 18.sp)
            OutlinedButton(onClick = {
                scope.launch {
                    val fields = mapping.map { it.field }.ifEmpty { prefs.defaults.fields.map { it.field } }
                    val created = withContext(Dispatchers.IO) { runCatching { AnkiDroid(context).createNoteType(noteType.trim(), fields) }.getOrNull() }
                    problem = if (created == null) "AnkiDroid didn't create the note type." else null
                    refresh++
                }
            }) { Text("Create it", fontSize = 18.sp) }
        }
    }
    problem?.let { Text(it, fontSize = 18.sp, fontWeight = FontWeight.Bold) }

    // One row per field of the note type: which piece of the card fills it.
    Text("Fields", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    val fieldNames = state?.fields ?: mapping.map { it.field }
    val sources = listOf("") + prefs.defaults.sources
    fieldNames.forEach { name ->
        val current = mapping.firstOrNull { it.field == name }?.source.orEmpty()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, modifier = Modifier.width(200.dp), fontSize = 18.sp)
            Dropdown(current.ifEmpty { "(empty)" }, sources.map { it to it.ifEmpty { "(empty)" } }) { chosen ->
                mapping = fieldNames.mapNotNull { field ->
                    val source = if (field == name) chosen else mapping.firstOrNull { it.field == field }?.source.orEmpty()
                    source.takeIf { it.isNotEmpty() }?.let { FieldMap(field, it) }
                }
                prefs.ankiFields = mapping
            }
        }
    }

    var tags by remember { mutableStateOf(prefs.ankiTags) }
    Field("Tags (space separated)", tags, placeholder = "") {
        tags = it
        prefs.ankiTags = it
    }
    var duplicates by remember { mutableStateOf(prefs.ankiAllowDuplicates) }
    Toggle("Allow duplicate cards", duplicates) {
        duplicates = it
        prefs.ankiAllowDuplicates = it
    }
    var audio by remember { mutableStateOf(prefs.ankiAudioUrl) }
    Field("Word audio URL ({term} and {reading} are filled in; empty for none)", audio, placeholder = "") {
        audio = it
        prefs.ankiAudioUrl = it.trim()
    }
    NumberField("Picture max width (px)", prefs.ankiImageMaxWidth) { prefs.ankiImageMaxWidth = it }
    NumberField("Picture max height (px)", prefs.ankiImageMaxHeight) { prefs.ankiImageMaxHeight = it }
    NumberField("JPEG quality (1-100)", prefs.ankiImageQuality) { prefs.ankiImageQuality = it.coerceIn(1, 100) }
}

// ---- building blocks ----------------------------------------------------------

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 18.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(label, fontSize = 18.sp)
        FlowRow {
            options.forEach { (value, text) ->
                TextButton(onClick = { onSelect(value) }) {
                    Text(
                        text,
                        fontSize = 18.sp,
                        fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal,
                        textDecoration = if (value == selected) TextDecoration.Underline else TextDecoration.None,
                    )
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    secret: Boolean = false,
    singleLine: Boolean = true,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = Color.Gray) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = TextStyle(fontSize = 18.sp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = TextStyle(fontSize = 18.sp),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A text field with a menu of what AnkiDroid has, when it has anything. */
@Composable
private fun PickField(label: String, value: String, choices: List<String>, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.weight(1f)) { Field(label, value, placeholder = "", onChange = onChange) }
        if (choices.isNotEmpty()) Dropdown("Choose ▾", choices.map { it to it }, onChange)
    }
}

@Composable
private fun <T> Dropdown(label: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label, fontSize = 17.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text, fontSize = 18.sp) }, onClick = {
                    open = false
                    onSelect(value)
                })
            }
        }
    }
}
