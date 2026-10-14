// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PanelView { DICTIONARY, AI }

private sealed interface AiState {
    data object Idle : AiState
    data object Asking : AiState
    data class Done(val outcome: AiOutcome) : AiState
}

/**
 * The selected bubble's text, what a tap on it finds in the dictionary,
 * and what the AI says about it -- gw-read's OCR dialog, lookup panel and
 * AI panel in one.
 *
 * Tapping a character looks up what the text could start with there
 * (gw-yomi's Yomitan-style scan and deinflection), lists the words found
 * best first, and underlines the span the focused word covers. The AI
 * view asks about the whole bubble, with the focused word as the
 * highlight, once per bubble (gw-yomi's cache answers repeats). Card
 * makes an Anki card from the focused word, or from the whole bubble
 * when only the AI answer is up. Mark bookmarks the bubble with the
 * focused word; opened from a bookmark ([restore]), the panel looks that
 * word up again.
 */
@Composable
fun BubblePanel(
    text: String,
    position: Int,
    count: Int,
    side: PanelSide,
    aiEnabled: Boolean,
    aiLabel: String,
    bookmarked: Boolean,
    restore: Bookmark?,
    askAi: suspend (highlight: String?, fresh: Boolean) -> AiOutcome,
    onSideChange: (PanelSide) -> Unit,
    onAddCard: (CardRequest) -> Unit,
    onToggleBookmark: (word: String?, wordStart: Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var lookup by remember(text) { mutableStateOf<LookupResult?>(null) }
    var focused by remember(text) { mutableIntStateOf(0) }
    var view by remember(text) { mutableStateOf(PanelView.DICTIONARY) }
    var ai by remember(text) { mutableStateOf<AiState>(AiState.Idle) }

    fun lookUp(at: Int) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { NativeCore.lookup(text, at) }.getOrNull() } ?: return@launch
            lookup = result
            focused = 0
            view = PanelView.DICTIONARY
        }
    }

    // Opened from a bookmark: look its word up again and focus it.
    LaunchedEffect(text, restore) {
        val mark = restore?.takeIf { it.word.isNotEmpty() } ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { runCatching { NativeCore.lookup(text, mark.wordStart) }.getOrNull() } ?: return@LaunchedEffect
        lookup = result
        focused = result.groups.indexOfFirst { it.term == mark.word }.coerceAtLeast(0)
        view = PanelView.DICTIONARY
    }

    val current = lookup
    val word = current?.takeIf { it.found }?.let { it.groups.getOrNull(focused) ?: it.groups.first() }
    val highlight = word?.let { current.start until current.start + it.length }
    // The looked-up span as written in the bubble: the AI's "highlighted" word.
    val highlightText = highlight?.let { range ->
        val codepoints = text.codePointCount(0, text.length)
        text.substring(
            text.offsetByCodePoints(0, range.first.coerceIn(0, codepoints)),
            text.offsetByCodePoints(0, (range.last + 1).coerceIn(0, codepoints)),
        )
    }
    val aiAnswer = ((ai as? AiState.Done)?.outcome as? AiOutcome.Answer)?.text

    fun ask(fresh: Boolean) {
        ai = AiState.Asking
        scope.launch { ai = AiState.Done(askAi(highlightText, fresh)) }
    }

    fun addCard() {
        val request = when {
            word != null -> CardRequest(
                kind = "word",
                term = word.term,
                reading = word.reading,
                spanStart = current.start,
                spanEnd = current.start + word.length,
                glossary = word.senses.map { it.text },
                ai = aiAnswer.orEmpty(),
            )
            aiAnswer != null -> CardRequest("sentence", text, "", 0, 0, emptyList(), aiAnswer)
            else -> return
        }
        onAddCard(request)
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (position >= 0) "Bubble ${position + 1} of $count" else "Bubble",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
                fontSize = 16.sp,
            )
            TextButton(onClick = onClose) { Text("Close", fontSize = 18.sp) }
        }
        // Wraps onto more lines when the panel is narrow.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevious, enabled = position > 0) { Text("‹ Prev", fontSize = 18.sp) }
            TextButton(onClick = onNext, enabled = position >= 0 && position + 1 < count) { Text("Next ›", fontSize = 18.sp) }
            ViewTab("Dictionary", selected = view == PanelView.DICTIONARY) { view = PanelView.DICTIONARY }
            if (aiEnabled) {
                ViewTab("AI", selected = view == PanelView.AI) {
                    view = PanelView.AI
                    if (ai == AiState.Idle) ask(fresh = false)
                }
            }
            TextButton(onClick = ::addCard, enabled = word != null || aiAnswer != null) { Text("+ Card", fontSize = 18.sp) }
            TextButton(onClick = { onToggleBookmark(word?.term, if (word != null) current.start else 0) }) {
                Text(if (bookmarked) "★ Marked" else "☆ Mark", fontSize = 18.sp)
            }
            PinMenu(side, onSideChange)
        }
        HorizontalDivider(color = Color.LightGray)

        BubbleText(
            text = text,
            highlight = highlight,
            onCharTap = ::lookUp,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
        HorizontalDivider(color = Color.Black)

        when (view) {
            PanelView.AI -> AiView(ai, aiLabel, onAskAgain = { ask(fresh = true) }, modifier = Modifier.weight(1f))
            PanelView.DICTIONARY -> when {
                current == null -> Hint("Tap a word to look it up.")
                current.noDictionary -> Hint("No dictionary installed. Add one from Library › Dictionaries.")
                !current.found -> Hint("Nothing found there.")
                else -> LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(current.groups) { i, group ->
                        WordEntry(group, expanded = i == focused, onClick = { focused = i })
                        HorizontalDivider(color = Color.LightGray)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            fontSize = 18.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (selected) TextDecoration.Underline else TextDecoration.None,
        )
    }
}

/** The panel's "Pin" menu: which edge of the screen it sits on. */
@Composable
private fun PinMenu(side: PanelSide, onSideChange: (PanelSide) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Pin: ${side.label} ▾", fontSize = 16.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PanelSide.entries.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label, fontSize = 18.sp, fontWeight = if (choice == side) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        open = false
                        onSideChange(choice)
                    },
                )
            }
        }
    }
}

@Composable
private fun AiView(state: AiState, label: String, onAskAgain: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            AiState.Idle -> {}
            AiState.Asking -> Text("Asking $label…", fontSize = 18.sp, color = Color.DarkGray)
            is AiState.Done -> when (val outcome = state.outcome) {
                is AiOutcome.Answer -> {
                    Text(outcome.text, fontSize = 19.sp, lineHeight = 28.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (outcome.cached) Text("From the answer cache", fontSize = 15.sp, color = Color.DarkGray)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onAskAgain) { Text("Ask again", fontSize = 16.sp) }
                    }
                }
                is AiOutcome.Failure -> {
                    Text(outcome.message, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onAskAgain) { Text("Try again", fontSize = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, modifier = Modifier.padding(16.dp), fontSize = 18.sp, color = Color.DarkGray)
}

/**
 * The bubble's text, large, with [highlight] (a codepoint range)
 * underlined. A tap reports the codepoint under the finger: offsets go to
 * the Zig core in codepoints, so a non-BMP kanji is one character on
 * both sides.
 */
@Composable
private fun BubbleText(text: String, highlight: IntRange?, onCharTap: (Int) -> Unit, modifier: Modifier) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val annotated = buildAnnotatedString {
        append(text)
        if (highlight != null && !highlight.isEmpty()) {
            val codepoints = text.codePointCount(0, text.length)
            val start = text.offsetByCodePoints(0, highlight.first.coerceIn(0, codepoints))
            val end = text.offsetByCodePoints(0, (highlight.last + 1).coerceIn(0, codepoints))
            addStyle(SpanStyle(background = Color(0xFFD8D8D8), textDecoration = TextDecoration.Underline), start, end)
        }
    }
    Text(
        annotated,
        fontSize = 30.sp,
        lineHeight = 46.sp,
        onTextLayout = { layout = it },
        modifier = modifier.pointerInput(text) {
            detectTapGestures { position ->
                layout?.let { l -> codepointAt(l, text, position)?.let(onCharTap) }
            }
        },
    )
}

/** The codepoint whose glyph box contains [position], or null. */
private fun codepointAt(layout: TextLayoutResult, text: String, position: Offset): Int? {
    var utf16 = 0
    var codepoint = 0
    while (utf16 < text.length) {
        if (layout.getBoundingBox(utf16).contains(position)) return codepoint
        utf16 += Character.charCount(text.codePointAt(utf16))
        codepoint++
    }
    return null
}

/**
 * One word: headword and reading, frequency and how it was deinflected,
 * its tags, and -- when it's the focused one -- every sense.
 */
@Composable
private fun WordEntry(group: WordGroup, expanded: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(group.term, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (group.reading.isNotEmpty() && group.reading != group.term) {
                Text(group.reading, fontSize = 20.sp)
            }
        }
        val meta = listOfNotNull(group.frequency?.let { "freq $it" }, group.reason?.let { "← $it" })
        if (meta.isNotEmpty()) Text(meta.joinToString("   "), fontSize = 16.sp, color = Color.DarkGray)
        if (group.tags.isNotEmpty()) Tags(group.tags)

        if (!expanded) {
            group.senses.firstOrNull()?.let {
                Text(it.text, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            return@Column
        }
        group.senses.forEachIndexed { i, sense ->
            Row {
                Text("${i + 1}.", modifier = Modifier.width(32.dp), fontSize = 18.sp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (sense.tags.isNotEmpty()) Tags(sense.tags)
                    Text(sense.text, fontSize = 18.sp)
                }
            }
        }
        if (group.spellings.isNotEmpty()) {
            Text("Also written " + group.spellings.joinToString("、") { it.first }, fontSize = 16.sp, color = Color.DarkGray)
        }
    }
}

@Composable
private fun Tags(tags: List<Tag>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { Badge(it.label) }
    }
}
