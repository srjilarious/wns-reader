// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A decoded page and its OCR. [image] may be downsampled; [ocrWidth] and
 * [ocrHeight] are the size the OCR boxes are measured in (mokuro's, or
 * the image file's own when the page has no OCR).
 */
class LoadedPage(val index: Int, val image: ImageBitmap, val ocrWidth: Int, val ocrHeight: Int, val text: PageText)

/**
 * Reads pages from the Zig core and decodes them with Android's decoder,
 * keeping the last few. [maxSide] caps the decoded size: twice the
 * screen is enough to zoom into, and a scan several times larger would
 * only cost memory.
 */
class PageLoader(private val book: Int, private val maxSide: Int) {
    private val cache = LruCache<Int, LoadedPage>(4)

    suspend fun load(index: Int): LoadedPage? = withContext(Dispatchers.IO) {
        cache.get(index)?.let { return@withContext it }
        val bytes = NativeCore.page(book, index) ?: return@withContext null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@withContext null

        val text = try {
            NativeCore.text(book, index)
        } catch (e: NativeException) {
            PageText.EMPTY
        }
        val ocrWidth = if (text.width > 0) text.width else bounds.outWidth
        val ocrHeight = if (text.height > 0) text.height else bounds.outHeight
        LoadedPage(index, bitmap.asImageBitmap(), ocrWidth, ocrHeight, text).also { cache.put(index, it) }
    }
}

@Composable
fun ReaderScreen(path: String, prefs: Prefs, onBack: () -> Unit) {
    var book by remember(path) { mutableStateOf<BookInfo?>(null) }
    var openError by remember(path) { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            try {
                book = NativeCore.open(path)
            } catch (e: NativeException) {
                openError = e.message
            }
        }
    }
    DisposableEffect(path) {
        onDispose { book?.let { NativeCore.close(it.id) } }
    }
    FullScreen()

    val current = book
    when {
        openError != null -> Column {
            ScreenHeader(path.substringAfterLast('/'), onBack)
            Text("Couldn't open this book: $openError", modifier = Modifier.padding(32.dp), fontSize = 18.sp)
        }
        current == null -> Text("Opening…", modifier = Modifier.padding(32.dp), fontSize = 18.sp)
        else -> Reader(current, path, prefs, onBack)
    }
}

/**
 * Hides the status and navigation bars while the reader is showing; a
 * swipe from the edge brings them back for a moment.
 */
@Composable
private fun FullScreen() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun Reader(book: BookInfo, path: String, prefs: Prefs, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val metrics = LocalContext.current.resources.displayMetrics
    val loader = remember(book.id) { PageLoader(book.id, 2 * max(metrics.widthPixels, metrics.heightPixels)) }
    val lastPage = book.pages.size - 1

    var index by remember { mutableIntStateOf(prefs.lastPage(path).coerceIn(0, lastPage)) }
    var page by remember { mutableStateOf<LoadedPage?>(null) }
    var pageFailed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var chrome by remember { mutableStateOf(false) }
    var showBoxes by remember { mutableStateOf(prefs.showBubbleBoxes) }

    LaunchedEffect(index) {
        prefs.setLastPage(path, index)
        selected = null
        val loaded = loader.load(index)
        pageFailed = loaded == null
        if (loaded != null) page = loaded
        // The next page, so turning to it is instant.
        if (index < lastPage) launch { loader.load(index + 1) }
    }

    fun goTo(target: Int) {
        index = target.coerceIn(0, lastPage)
    }

    // Manga reads right to left: the left side of the screen is forward.
    fun tapZone(xFraction: Float) {
        when {
            xFraction < 0.3f -> goTo(index + 1)
            xFraction > 0.7f -> goTo(index - 1)
            else -> chrome = !chrome
        }
    }

    val shown = page?.takeIf { it.index == index }
    val bubble = selected?.let { shown?.text?.blocks?.getOrNull(it) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // The panel lies over the page: on the left in landscape, along the
        // bottom in portrait. The page keeps its size and zoom; it's
        // panned so the selected bubble sits in the middle of what the
        // panel leaves uncovered.
        val panelWidth = maxWidth * 0.42f
        val panelHeight = maxHeight * 0.45f
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val uncovered = when {
            bubble == null -> null
            landscape -> Rect(viewW * 0.42f, 0f, viewW, viewH)
            else -> Rect(0f, 0f, viewW, viewH * 0.55f)
        }

        when {
            shown != null -> PageView(
                page = shown,
                selected = selected,
                showBoxes = showBoxes,
                uncovered = uncovered,
                onTap = { imageX, imageY, xFraction ->
                    if (shown.text.blocks.isEmpty()) {
                        tapZone(xFraction)
                    } else {
                        scope.launch {
                            val hit = withContext(Dispatchers.IO) { NativeCore.bubbleAt(book.id, index, imageX, imageY) }
                            when {
                                hit != null -> selected = hit
                                // With the panel up, a tap off the bubbles
                                // dismisses it rather than turning the page.
                                selected != null -> selected = null
                                else -> tapZone(xFraction)
                            }
                        }
                    }
                },
                // Dragging the page rightward brings the next one in.
                onSwipe = { dx -> goTo(if (dx > 0) index + 1 else index - 1) },
            )
            pageFailed -> Text("Page ${index + 1} couldn't be decoded.", modifier = Modifier.align(Alignment.Center), fontSize = 18.sp)
            else -> Text("Loading…", modifier = Modifier.align(Alignment.Center), fontSize = 18.sp)
        }

        if (bubble != null) {
            val text = shown!!.text
            val position = text.order.indexOf(selected!!)
            val panel: @Composable (Modifier) -> Unit = { modifier ->
                BubblePanel(
                    text = bubble.text,
                    position = position,
                    count = text.order.size,
                    onPrevious = { if (position > 0) selected = text.order[position - 1] },
                    onNext = { if (position + 1 < text.order.size) selected = text.order[position + 1] },
                    onClose = { selected = null },
                    modifier = modifier,
                )
            }
            // Solid, and it takes every touch that lands on it: none reach
            // the page underneath.
            val overlay = Modifier
                .background(Color.White)
                .pointerInput(Unit) { awaitEachGesture { do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed }) } }
            if (landscape) {
                Row(modifier = overlay.width(panelWidth).fillMaxHeight().align(Alignment.CenterStart)) {
                    panel(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(color = Color.Black)
                }
            } else {
                Column(modifier = overlay.height(panelHeight).fillMaxWidth().align(Alignment.BottomCenter)) {
                    HorizontalDivider(color = Color.Black)
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }

        if (chrome) {
            ReaderChrome(
                title = book.title,
                index = index,
                pageCount = book.pages.size,
                showBoxes = showBoxes,
                onBack = onBack,
                onToggleBoxes = {
                    showBoxes = !showBoxes
                    prefs.showBubbleBoxes = showBoxes
                },
                onGoTo = ::goTo,
            )
        }
    }
}

/**
 * One page, fitted to the view, with pinch zoom and pan. Gestures are
 * handled in one loop rather than with stacked detectors, so a pinch, a
 * pan, a swipe and a tap can't each claim the same touch:
 *
 * - two fingers zoom (1x-5x) around their centre and pan;
 * - one finger pans while zoomed in, or while the panel is up;
 * - otherwise, a mostly-horizontal one-finger drag is a page-turn swipe;
 * - a touch that doesn't move is a tap, reported in OCR pixel
 *   coordinates along with how far across the view it was.
 *
 * [uncovered] is the part of the view the bubble panel leaves visible,
 * null when it's closed. While it's set, the selected bubble is panned
 * to its centre (zoom untouched), the pan limits loosen to allow that,
 * and closing the panel puts the page back where it was.
 */
@Composable
private fun PageView(
    page: LoadedPage,
    selected: Int?,
    showBoxes: Boolean,
    uncovered: Rect?,
    onTap: (imageX: Int, imageY: Int, xFraction: Float) -> Unit,
    onSwipe: (dx: Float) -> Unit,
) {
    // The gesture loop below restarts only when the page does; these keep
    // it calling the latest callbacks in between.
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val currentUncovered by rememberUpdatedState(uncovered)
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 80.dp.toPx() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(page.index) { mutableFloatStateOf(1f) }
    var pan by remember(page.index) { mutableStateOf(Offset.Zero) }
    // Where the page was before the panel moved it.
    var panBeforePanel by remember(page.index) { mutableStateOf<Offset?>(null) }

    val imageW = page.image.width.toFloat()
    val imageH = page.image.height.toFloat()

    fun scale(z: Float = zoom): Float {
        if (viewSize.width == 0 || viewSize.height == 0) return 1f
        return min(viewSize.width / imageW, viewSize.height / imageH) * z
    }

    fun centered(z: Float = zoom) = Offset((viewSize.width - imageW * scale(z)) / 2f, (viewSize.height - imageH * scale(z)) / 2f)

    fun clampPan(p: Offset, z: Float = zoom): Offset {
        val region = currentUncovered
        if (region != null) {
            // Panel up: anywhere that keeps the uncovered area's centre on
            // the page, which is what centring any bubble needs.
            val c = centered(z)
            val s = scale(z)
            val centre = region.center
            return Offset(
                p.x.coerceIn(centre.x - imageW * s - c.x, centre.x - c.x),
                p.y.coerceIn(centre.y - imageH * s - c.y, centre.y - c.y),
            )
        }
        val spareX = max(0f, (imageW * scale(z) - viewSize.width) / 2f)
        val spareY = max(0f, (imageH * scale(z) - viewSize.height) / 2f)
        return Offset(p.x.coerceIn(-spareX, spareX), p.y.coerceIn(-spareY, spareY))
    }

    fun origin() = centered() + pan

    // OCR pixels per decoded-image pixel (the image may be downsampled).
    val ocrPerImageX = page.ocrWidth / imageW
    val ocrPerImageY = page.ocrHeight / imageH

    val focus = selected?.let { page.text.blocks.getOrNull(it) }
    LaunchedEffect(focus, uncovered, viewSize) {
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        if (focus != null && uncovered != null) {
            if (panBeforePanel == null) panBeforePanel = pan
            val bubbleCentre = Offset((focus.x1 + focus.x2) / 2f / ocrPerImageX, (focus.y1 + focus.y2) / 2f / ocrPerImageY)
            val onScreen = origin() + bubbleCentre * scale()
            pan = clampPan(pan + (uncovered.center - onScreen))
        } else if (uncovered == null) {
            panBeforePanel?.let { pan = clampPan(it) }
            panBeforePanel = null
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            .pointerInput(page.index) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multiTouch = false
                    var moved = false
                    var drag = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            multiTouch = true
                            val centroid = event.calculateCentroid()
                            val newZoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                            // Keep the image point under the fingers where it is.
                            val imagePoint = (centroid - origin()) / scale()
                            val newOrigin = centroid - imagePoint * scale(newZoom) + event.calculatePan()
                            zoom = newZoom
                            pan = clampPan(newOrigin - centered(newZoom), newZoom)
                            event.changes.forEach { it.consume() }
                        } else if (pressed.size == 1) {
                            val change = pressed[0]
                            drag += change.positionChange()
                            if (drag.getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved && (zoom > 1f || currentUncovered != null)) {
                                pan = clampPan(pan + change.positionChange())
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (multiTouch) return@awaitEachGesture
                    if (!moved) {
                        val imagePoint = (down.position - origin()) / scale()
                        currentOnTap(
                            (imagePoint.x * ocrPerImageX).roundToInt(),
                            (imagePoint.y * ocrPerImageY).roundToInt(),
                            down.position.x / viewSize.width,
                        )
                    } else if (zoom <= 1f && currentUncovered == null && abs(drag.x) > swipeThreshold && abs(drag.x) > abs(drag.y)) {
                        currentOnSwipe(drag.x)
                    }
                }
            },
    ) {
        val s = scale()
        val o = origin()
        drawImage(
            image = page.image,
            dstOffset = IntOffset(o.x.roundToInt(), o.y.roundToInt()),
            dstSize = IntSize((imageW * s).roundToInt(), (imageH * s).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        page.text.blocks.forEachIndexed { i, block ->
            val isSelected = i == selected
            if (!showBoxes && !isSelected) return@forEachIndexed
            val topLeft = Offset(o.x + block.x1 / ocrPerImageX * s, o.y + block.y1 / ocrPerImageY * s)
            val size = Size((block.x2 - block.x1) / ocrPerImageX * s, (block.y2 - block.y1) / ocrPerImageY * s)
            if (isSelected) {
                drawRect(Color(0x22000000), topLeft, size)
                drawRect(Color.Black, topLeft, size, style = Stroke(width = 3.dp.toPx()))
            } else {
                drawRect(Color(0x66000000), topLeft, size, style = Stroke(width = 1.dp.toPx()))
            }
        }
    }
}

/** The bar shown over the page by a tap in the middle: title, page, jump. */
@Composable
private fun ReaderChrome(
    title: String,
    index: Int,
    pageCount: Int,
    showBoxes: Boolean,
    onBack: () -> Unit,
    onToggleBoxes: () -> Unit,
    onGoTo: (Int) -> Unit,
) {
    var slider by remember(index) { mutableFloatStateOf(index.toFloat()) }
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Library", fontSize = 18.sp) }
            Text(title, modifier = Modifier.weight(1f).padding(horizontal = 8.dp), fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onToggleBoxes) { Text(if (showBoxes) "Hide boxes" else "Show boxes", fontSize = 18.sp) }
        }
        if (pageCount > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${slider.roundToInt() + 1} / $pageCount", modifier = Modifier.width(110.dp), fontSize = 18.sp)
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { onGoTo(slider.roundToInt()) },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
