// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val loader = remember(book.id) { PageLoader(book.id, 2 * max(metrics.widthPixels, metrics.heightPixels)) }
    val lastPage = book.pages.size - 1

    var index by remember { mutableIntStateOf(prefs.lastPage(path).coerceIn(0, lastPage)) }
    var page by remember { mutableStateOf<LoadedPage?>(null) }
    var pageFailed by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var chrome by remember { mutableStateOf(false) }
    var showBoxes by remember { mutableStateOf(prefs.showBubbleBoxes) }
    var panelSide by remember { mutableStateOf(prefs.panelSide) }
    // The card being made, over everything else, and the note shown after.
    var card by remember { mutableStateOf<Pair<JSONObject, Block>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var marks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
    var showMarks by remember { mutableStateOf(false) }
    // A bookmark on another page being jumped to: its bubble is selected
    // once that page has loaded.
    var jump by remember { mutableStateOf<Bookmark?>(null) }
    // The bookmark the selected bubble was opened from, so the panel looks
    // its word up again. Any other way of selecting a bubble clears it.
    var restore by remember { mutableStateOf<Bookmark?>(null) }

    fun select(block: Int?) {
        selected = block
        restore = null
    }

    suspend fun loadMarks(): List<Bookmark> = withContext(Dispatchers.IO) {
        runCatching { NativeCore.bookmarks(path) }.getOrDefault(emptyList())
    }

    LaunchedEffect(path) { marks = loadMarks() }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3000)
            notice = null
        }
    }

    LaunchedEffect(index) {
        prefs.setLastPage(path, index)
        select(null)
        val loaded = loader.load(index)
        pageFailed = loaded == null
        if (loaded != null) {
            page = loaded
            jump?.takeIf { it.page == index }?.let { mark ->
                selected = mark.blockIn(loaded.text)
                restore = mark
            }
        }
        jump = null
        // The next page, so turning to it is instant.
        if (index < lastPage) launch { loader.load(index + 1) }
    }

    fun goTo(target: Int) {
        index = target.coerceIn(0, lastPage)
    }

    fun jumpTo(mark: Bookmark) {
        showMarks = false
        chrome = false
        val current = page?.takeIf { it.index == index }
        if (mark.page == index && current != null) {
            selected = mark.blockIn(current.text)
            restore = mark
        } else {
            jump = mark
            goTo(mark.page)
        }
    }

    fun deleteMark(mark: Bookmark) {
        scope.launch {
            val failure = withContext(Dispatchers.IO) { runCatching { NativeCore.deleteBookmark(mark.id) }.exceptionOrNull() }
            if (failure != null) notice = "Couldn't delete the bookmark: ${failure.message}"
            marks = loadMarks()
        }
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
    val markHere = selected?.let { block -> shown?.let { p -> marks.firstOrNull { it.page == index && it.blockIn(p.text) == block } } }

    BackHandler(enabled = showMarks) { showMarks = false }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The panel lies over the page, pinned to whichever edge the user
        // picked (the right by default). The page keeps its size and zoom;
        // it's panned so the selected bubble is in what the panel leaves
        // uncovered (see PageView).
        // A share of the screen, but never so little the panel can't
        // hold its buttons, nor so much it hides the page.
        val panelWidth = (maxWidth * PANEL_SHARE).coerceAtLeast(minOf(PANEL_MIN_WIDTH, maxWidth * PANEL_MAX_SHARE))
        val panelHeight = (maxHeight * PANEL_SHARE).coerceAtLeast(minOf(PANEL_MIN_HEIGHT, maxHeight * PANEL_MAX_SHARE))
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val panelW = with(LocalDensity.current) { panelWidth.toPx() }
        val panelH = with(LocalDensity.current) { panelHeight.toPx() }
        val uncovered = if (bubble == null) {
            null
        } else {
            when (panelSide) {
                PanelSide.LEFT -> Rect(panelW, 0f, viewW, viewH)
                PanelSide.RIGHT -> Rect(0f, 0f, viewW - panelW, viewH)
                PanelSide.TOP -> Rect(0f, panelH, viewW, viewH)
                PanelSide.BOTTOM -> Rect(0f, 0f, viewW, viewH - panelH)
            }
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
                                hit != null -> select(hit)
                                // With the panel up, a tap off the bubbles
                                // only closes it; the next one turns the page.
                                selected != null -> select(null)
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
            fun neighbour(offset: Int): String? = text.order.getOrNull(position + offset)?.let { text.blocks.getOrNull(it)?.text }
            val panel: @Composable (Modifier) -> Unit = { modifier ->
                BubblePanel(
                    text = bubble.text,
                    position = position,
                    count = text.order.size,
                    side = panelSide,
                    aiEnabled = prefs.aiEnabled,
                    aiLabel = prefs.provider().label,
                    bookmarked = markHere != null,
                    restore = restore,
                    askAi = { highlight, fresh ->
                        AiClient.ask(
                            context,
                            prefs,
                            AiQuestion(
                                dialog = bubble.text,
                                previous = if (position >= 0) neighbour(-1) else null,
                                next = if (position >= 0) neighbour(1) else null,
                                highlight = highlight,
                                title = book.title,
                                page = index + 1,
                            ),
                            fresh,
                        )
                    },
                    onSideChange = {
                        panelSide = it
                        prefs.panelSide = it
                    },
                    onAddCard = { request ->
                        card = CardMaker.noteInput(prefs, request, bubble.text, book.title, index + 1) to bubble
                    },
                    onToggleBookmark = { word, wordStart ->
                        val existing = markHere
                        val block = selected!!
                        val pageIndex = index
                        scope.launch {
                            val failure = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (existing != null) {
                                        NativeCore.deleteBookmark(existing.id)
                                    } else {
                                        NativeCore.saveBookmark(path, pageIndex, block, bubble.text, word.orEmpty(), wordStart)
                                    }
                                }.exceptionOrNull()
                            }
                            notice = when {
                                failure != null -> "Couldn't save the bookmark: ${failure.message}"
                                existing != null -> "Bookmark removed"
                                else -> "Bookmarked"
                            }
                            marks = loadMarks()
                        }
                    },
                    onPrevious = { if (position > 0) select(text.order[position - 1]) },
                    onNext = { if (position + 1 < text.order.size) select(text.order[position + 1]) },
                    onClose = { select(null) },
                    modifier = modifier,
                )
            }
            // Solid, and it takes every touch that lands on it: none reach
            // the page underneath.
            val overlay = Modifier
                .background(Color.White)
                .pointerInput(Unit) { awaitEachGesture { do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed }) } }
            when (panelSide) {
                PanelSide.LEFT -> Row(modifier = overlay.width(panelWidth).fillMaxHeight().align(Alignment.CenterStart)) {
                    panel(Modifier.weight(1f).fillMaxHeight())
                    VerticalDivider(color = Color.Black)
                }
                PanelSide.RIGHT -> Row(modifier = overlay.width(panelWidth).fillMaxHeight().align(Alignment.CenterEnd)) {
                    VerticalDivider(color = Color.Black)
                    panel(Modifier.weight(1f).fillMaxHeight())
                }
                PanelSide.TOP -> Column(modifier = overlay.height(panelHeight).fillMaxWidth().align(Alignment.TopCenter)) {
                    panel(Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = Color.Black)
                }
                PanelSide.BOTTOM -> Column(modifier = overlay.height(panelHeight).fillMaxWidth().align(Alignment.BottomCenter)) {
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
                bookmarkCount = marks.size,
                onBack = onBack,
                onBookmarks = { showMarks = true },
                onToggleBoxes = {
                    showBoxes = !showBoxes
                    prefs.showBubbleBoxes = showBoxes
                },
                onGoTo = ::goTo,
            )
        }

        if (showMarks) {
            BookmarkList(
                marks = marks,
                onJump = ::jumpTo,
                onDelete = ::deleteMark,
                onClose = { showMarks = false },
            )
        }

        notice?.let {
            Text(
                it,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color.Black)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 18.sp,
            )
        }

        card?.let { (note, cardBubble) ->
            shown?.let { current ->
                CardFlow(
                    prefs = prefs,
                    bookId = book.id,
                    page = current,
                    bubble = cardBubble,
                    note = note,
                    onDone = { message ->
                        card = null
                        notice = message
                    },
                )
            }
        }
    }
}

/**
 * How much of the screen the bubble panel takes, beside or above/below
 * the page: [PANEL_SHARE] of it, at least the minimum size, at most
 * [PANEL_MAX_SHARE].
 */
private const val PANEL_SHARE = 0.42f
private const val PANEL_MAX_SHARE = 0.85f
private val PANEL_MIN_WIDTH = 360.dp
private val PANEL_MIN_HEIGHT = 320.dp

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
 * null when it's closed. While it's set, the page is panned (zoom
 * untouched) to bring the selected bubble into it: up or down to its
 * middle, but sideways only as far as it takes to get the whole bubble
 * in view, if it isn't already. The pan limits loosen to allow that,
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
    // How far inside the uncovered area's sides a bubble is brought.
    val bubbleMargin = with(density) { 16.dp.toPx() }
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
            val s = scale()
            val o = origin()
            val left = o.x + focus.x1 / ocrPerImageX * s
            val right = o.x + focus.x2 / ocrPerImageX * s
            val centreY = o.y + (focus.y1 + focus.y2) / 2f / ocrPerImageY * s
            val minX = uncovered.left + bubbleMargin
            val maxX = uncovered.right - bubbleMargin
            val dx = when {
                // Too wide to fit: centre it.
                right - left > maxX - minX -> uncovered.center.x - (left + right) / 2f
                left < minX -> minX - left
                right > maxX -> maxX - right
                else -> 0f
            }
            pan = clampPan(pan + Offset(dx, uncovered.center.y - centreY))
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

/** The bar shown over the page by a tap in the middle: title, bookmarks, page, jump. */
@Composable
private fun ReaderChrome(
    title: String,
    index: Int,
    pageCount: Int,
    showBoxes: Boolean,
    bookmarkCount: Int,
    onBack: () -> Unit,
    onBookmarks: () -> Unit,
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
            TextButton(onClick = onBookmarks) { Text(if (bookmarkCount > 0) "Bookmarks ($bookmarkCount)" else "Bookmarks", fontSize = 18.sp) }
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

/**
 * The book's bookmarks, over the whole reader: page, word and bubble
 * text. A tap jumps there and opens the bubble's panel.
 */
@Composable
private fun BookmarkList(marks: List<Bookmark>, onJump: (Bookmark) -> Unit, onDelete: (Bookmark) -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .pointerInput(Unit) { awaitEachGesture { do { val event = awaitPointerEvent() } while (event.changes.any { it.pressed }) } },
    ) {
        ScreenHeader("Bookmarks", onBack = onClose)
        if (marks.isEmpty()) {
            Text(
                "No bookmarks yet. Open a bubble and tap ☆ Mark in its panel.",
                modifier = Modifier.padding(24.dp),
                fontSize = 18.sp,
                color = Color.DarkGray,
            )
            return@Column
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(marks, key = { it.id }) { mark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(mark) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("p. ${mark.page + 1}", modifier = Modifier.width(80.dp), fontSize = 18.sp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (mark.word.isNotEmpty()) Text(mark.word, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(mark.text, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onDelete(mark) }) { Text("Delete", fontSize = 16.sp) }
                }
                HorizontalDivider(color = Color.LightGray)
            }
        }
    }
}
