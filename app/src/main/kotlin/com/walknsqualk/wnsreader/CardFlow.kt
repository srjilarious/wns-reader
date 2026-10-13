// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

private enum class CardStage { CROP, PREVIEW }

/**
 * Making one card, gw-read's way: frame a picture from the page (when a
 * field takes one), then check every field before it goes to AnkiDroid.
 * [note] is the card as [CardMaker.noteInput] built it. [onDone] gets a
 * message for the reader to show, or null when cancelled.
 */
@Composable
fun CardFlow(
    prefs: Prefs,
    bookId: Int,
    page: LoadedPage,
    bubble: Block,
    note: JSONObject,
    onDone: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rendered = remember(note) { runCatching { CardMaker.render(note) } }
    val card = rendered.getOrNull()

    var stage by remember { mutableStateOf(if (card?.uses("image") == true) CardStage.CROP else CardStage.PREVIEW) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var audio by remember { mutableStateOf<CardAudio>(CardAudio.Off) }
    var adding by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(card) {
        if (card != null && card.uses("audio") && card.audioUrl.isNotEmpty()) {
            audio = CardAudio.Checking
            audio = CardMaker.fetchAudio(prefs, card.audioUrl)
        }
    }

    fun add() {
        val ready = card ?: return
        adding = true
        problem = null
        scope.launch {
            when (val outcome = CardMaker.add(context, prefs, ready, image, audio)) {
                is AddOutcome.Added -> onDone("Added to ${outcome.deck}")
                is AddOutcome.Failed -> {
                    problem = outcome.message
                    adding = false
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) add() else problem = "AnkiDroid access wasn't granted."
    }

    fun requestAdd() {
        val anki = AnkiDroid(context)
        when {
            !anki.installed() -> problem = "AnkiDroid isn't installed."
            !anki.hasPermission() -> permission.launch(AddContentApi.READ_WRITE_PERMISSION)
            else -> add()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(Unit) { awaitEachGesture { do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed }) } },
    ) {
        when {
            card == null -> Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Couldn't build the card: ${rendered.exceptionOrNull()?.message}", fontSize = 18.sp)
                Button(onClick = { onDone(null) }) { Text("Close", fontSize = 18.sp) }
            }
            stage == CardStage.CROP -> CropView(
                prefs = prefs,
                bookId = bookId,
                page = page,
                bubble = bubble,
                onAccept = { jpeg ->
                    image = jpeg
                    stage = CardStage.PREVIEW
                },
                onSkip = {
                    image = null
                    stage = CardStage.PREVIEW
                },
                onCancel = { onDone(null) },
            )
            else -> PreviewView(
                prefs = prefs,
                card = card,
                image = image,
                audio = audio,
                adding = adding,
                problem = problem,
                onRecrop = if (card.uses("image")) ({ stage = CardStage.CROP }) else null,
                onAdd = ::requestAdd,
                onCancel = { onDone(null) },
            )
        }
    }
}

/**
 * The page, fitted, with the card's picture box over it. Dragging inside
 * the box moves it, dragging near an edge or corner resizes it; gw-yomi's
 * `crop` decides which (`handleAt`) and keeps the box on the page
 * (`dragged`), in page-image pixels.
 */
@Composable
private fun CropView(
    prefs: Prefs,
    bookId: Int,
    page: LoadedPage,
    bubble: Block,
    onAccept: (ByteArray) -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var box by remember { mutableStateOf(NativeCore.initialCrop(bubble, page.ocrWidth, page.ocrHeight)) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var encoding by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // Page-image (OCR) pixels to view pixels and back.
    fun scale(): Float {
        if (viewSize.width == 0 || viewSize.height == 0) return 1f
        return min(viewSize.width.toFloat() / page.ocrWidth, viewSize.height.toFloat() / page.ocrHeight)
    }

    fun origin() = Offset((viewSize.width - page.ocrWidth * scale()) / 2f, (viewSize.height - page.ocrHeight * scale()) / 2f)

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "Frame the card's picture: drag the box, or its edges and corners.",
            modifier = Modifier.padding(16.dp),
            fontSize = 18.sp,
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val s = scale()
                        val start = box
                        val at = (down.position - origin()) / s
                        val tolerance = with(density) { 28.dp.toPx() } / s
                        val handle = NativeCore.cropHandleAt(start, at.x.roundToInt(), at.y.roundToInt(), tolerance.roundToInt())
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val delta = (change.position - down.position) / s
                            box = NativeCore.dragCrop(start, handle, delta.x.roundToInt(), delta.y.roundToInt(), page.ocrWidth, page.ocrHeight)
                            change.consume()
                        } while (change.pressed)
                    }
                },
        ) {
            val s = scale()
            val o = origin()
            drawImage(
                image = page.image,
                dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
                dstSize = IntSize((page.ocrWidth * s).roundToInt(), (page.ocrHeight * s).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            val topLeft = Offset(o.x + box[0] * s, o.y + box[1] * s)
            val size = Size(box[2] * s, box[3] * s)
            // Dim everything outside the box.
            val shade = Color(0x88000000)
            drawRect(shade, Offset(0f, 0f), Size(this.size.width, topLeft.y))
            drawRect(shade, Offset(0f, topLeft.y + size.height), Size(this.size.width, this.size.height - topLeft.y - size.height))
            drawRect(shade, Offset(0f, topLeft.y), Size(topLeft.x, size.height))
            drawRect(shade, Offset(topLeft.x + size.width, topLeft.y), Size(this.size.width - topLeft.x - size.width, size.height))
            drawRect(Color.White, topLeft, size, style = Stroke(width = 3.dp.toPx()))
            val knob = 14.dp.toPx()
            listOf(
                topLeft,
                Offset(topLeft.x + size.width, topLeft.y),
                Offset(topLeft.x, topLeft.y + size.height),
                Offset(topLeft.x + size.width, topLeft.y + size.height),
            ).forEach { corner ->
                drawRect(Color.White, Offset(corner.x - knob / 2, corner.y - knob / 2), Size(knob, knob))
                drawRect(Color.Black, Offset(corner.x - knob / 2, corner.y - knob / 2), Size(knob, knob), style = Stroke(width = 2.dp.toPx()))
            }
        }
        if (failed) Text("Couldn't cut the picture out of this page.", modifier = Modifier.padding(horizontal = 16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onCancel, enabled = !encoding) { Text("Cancel", fontSize = 18.sp) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSkip, enabled = !encoding) { Text("No picture", fontSize = 18.sp) }
            Button(
                onClick = {
                    encoding = true
                    failed = false
                    scope.launch {
                        val jpeg = withContext(Dispatchers.IO) {
                            NativeCore.encodeCrop(bookId, page.index, box, prefs.ankiImageMaxWidth, prefs.ankiImageMaxHeight, prefs.ankiImageQuality)
                        }
                        encoding = false
                        if (jpeg != null) onAccept(jpeg) else failed = true
                    }
                },
                enabled = !encoding,
            ) { Text(if (encoding) "Cutting…" else "Use picture", fontSize = 18.sp) }
        }
    }
}

/** Every field as it will be filled, then Add. */
@Composable
private fun PreviewView(
    prefs: Prefs,
    card: RenderedCard,
    image: ByteArray?,
    audio: CardAudio,
    adding: Boolean,
    problem: String?,
    onRecrop: (() -> Unit)?,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val picture = remember(image) { image?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader("New card", onBack = null)
        Text(
            "${prefs.ankiModel}  ·  deck ${prefs.ankiDeck}",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 16.sp,
            color = Color.DarkGray,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            card.fields.forEach { field ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(field.field, modifier = Modifier.width(160.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    when (field.source) {
                        "image" -> if (picture != null) {
                            Image(
                                bitmap = picture,
                                contentDescription = "Card picture",
                                modifier = Modifier.heightIn(max = 240.dp),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text("(no picture)", fontSize = 17.sp, color = Color.DarkGray)
                        }
                        "audio" -> Text(
                            when (audio) {
                                CardAudio.Off -> "(no audio source)"
                                CardAudio.Checking -> "looking for a clip…"
                                is CardAudio.Found -> "clip found"
                                CardAudio.Missing -> "no clip for this word"
                                is CardAudio.Failed -> "couldn't fetch the clip: ${audio.message}"
                            },
                            fontSize = 17.sp,
                        )
                        else -> Text(field.preview.ifEmpty { "—" }, fontSize = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = Color.LightGray)
            }
        }
        problem?.let { Text(it, modifier = Modifier.padding(16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onCancel, enabled = !adding) { Text("Cancel", fontSize = 18.sp) }
            if (onRecrop != null) OutlinedButton(onClick = onRecrop, enabled = !adding) { Text("Picture…", fontSize = 18.sp) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onAdd, enabled = !adding && audio != CardAudio.Checking) {
                Text(if (adding) "Adding…" else "Add card", fontSize = 18.sp)
            }
        }
    }
}
