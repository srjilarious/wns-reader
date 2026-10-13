// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import org.json.JSONObject

/**
 * The Zig core, libwnscore.so (native/src/jni.zig).
 *
 * Every call passes and returns UTF-8 byte arrays rather than Strings:
 * JNI's string functions use "modified UTF-8", which mangles characters
 * outside the BMP, and Japanese text has real ones (𠮷). Results are
 * JSON; a failed call returns `{"error": "<name>"}`, which the wrappers
 * here turn into a [NativeException].
 *
 * The calls do file and database work, so call them off the main thread.
 */
object NativeCore {
    init {
        System.loadLibrary("wnscore")
    }

    @JvmStatic private external fun version(): ByteArray
    @JvmStatic private external fun runDiagnostics(scratchDir: ByteArray, booksDir: ByteArray?): ByteArray
    @JvmStatic private external fun listDirectory(path: ByteArray): ByteArray
    @JvmStatic private external fun openBook(path: ByteArray): ByteArray
    @JvmStatic private external fun closeBook(book: Int)
    @JvmStatic private external fun readPage(book: Int, index: Int): ByteArray?
    @JvmStatic private external fun pageText(book: Int, index: Int): ByteArray
    @JvmStatic private external fun blockAt(book: Int, index: Int, x: Int, y: Int): Int
    @JvmStatic private external fun openDictionaries(root: ByteArray, order: ByteArray): ByteArray
    @JvmStatic private external fun dictionaryStatus(): ByteArray
    @JvmStatic private external fun lookup(text: ByteArray, at: Int): ByteArray
    @JvmStatic private external fun installBegin(zip: ByteArray, root: ByteArray): ByteArray
    @JvmStatic private external fun installStep(): ByteArray
    @JvmStatic private external fun installCancel()
    @JvmStatic private external fun defaults(): ByteArray
    @JvmStatic private external fun openAiCache(path: ByteArray): ByteArray
    @JvmStatic private external fun aiPrepare(question: ByteArray): ByteArray
    @JvmStatic private external fun aiFinish(reply: ByteArray): ByteArray
    @JvmStatic private external fun cardFields(note: ByteArray): ByteArray
    @JvmStatic private external fun cropInitial(bubble: IntArray, pageW: Int, pageH: Int): IntArray?
    @JvmStatic private external fun cropHandle(box: IntArray, x: Int, y: Int, tolerance: Int): Int
    @JvmStatic private external fun cropDrag(start: IntArray, handle: Int, dx: Int, dy: Int, pageW: Int, pageH: Int): IntArray?
    @JvmStatic private external fun cropEncode(book: Int, index: Int, box: IntArray, maxW: Int, maxH: Int, quality: Int): ByteArray?

    private fun String.utf8() = toByteArray(Charsets.UTF_8)

    private fun ByteArray.json() = JSONObject(toString(Charsets.UTF_8))

    fun versionInfo(): JSONObject = version().json()

    fun diagnostics(scratchDir: String, booksDir: String?): JSONObject =
        runDiagnostics(scratchDir.utf8(), booksDir?.utf8()).json()

    fun list(dir: String): List<LibraryEntry> = LibraryEntry.listFrom(listDirectory(dir.utf8()).json())

    fun open(path: String): BookInfo = BookInfo.from(openBook(path.utf8()).json())

    fun close(book: Int) = closeBook(book)

    /** The page image file's bytes, or null if it can't be read. */
    fun page(book: Int, index: Int): ByteArray? = readPage(book, index)

    fun text(book: Int, index: Int): PageText = PageText.from(pageText(book, index).json())

    /** The OCR bubble under image pixel (x, y), or null. */
    fun bubbleAt(book: Int, index: Int, x: Int, y: Int): Int? = blockAt(book, index, x, y).takeIf { it >= 0 }

    fun openDictionaries(root: String, order: String): DictionaryStatus =
        DictionaryStatus.from(openDictionaries(root.utf8(), order.utf8()).json())

    fun dictionaries(): DictionaryStatus = DictionaryStatus.from(dictionaryStatus().json())

    /** Looks up what [text] could start with at codepoint [at]. */
    fun lookup(text: String, at: Int): LookupResult = LookupResult.from(lookup(text.utf8(), at).json())

    fun beginInstall(zip: String, root: String): InstallProgress = InstallProgress.from(installBegin(zip.utf8(), root.utf8()).json())

    fun stepInstall(): InstallProgress = InstallProgress.from(installStep().json())

    fun cancelInstall() = installCancel()

    /** gw-read's Anki and AI defaults and the AI providers; see native/src/cards.zig. */
    fun defaultSettings(): JSONObject = defaults().json().orThrow()

    fun openAnswerCache(path: String) {
        openAiCache(path.utf8()).json().orThrow()
    }

    /** See native/src/assistant.zig `writePrepare`. */
    fun prepareQuestion(question: JSONObject): JSONObject = aiPrepare(question.toString().utf8()).json().orThrow()

    /** See native/src/assistant.zig `writeFinish`. */
    fun finishQuestion(reply: JSONObject): JSONObject = aiFinish(reply.toString().utf8()).json().orThrow()

    /** See native/src/cards.zig `writeFields`. */
    fun renderCard(note: JSONObject): JSONObject = cardFields(note.toString().utf8()).json().orThrow()

    // Crop boxes are [x, y, w, h] in page-image pixels (native/src/cards.zig).

    fun initialCrop(bubble: Block, pageW: Int, pageH: Int): IntArray =
        cropInitial(intArrayOf(bubble.x1, bubble.y1, bubble.x2 - bubble.x1, bubble.y2 - bubble.y1), pageW, pageH)
            ?: intArrayOf(0, 0, pageW, pageH)

    /** What a drag from (x, y) grabs: an opaque handle for [dragCrop]. */
    fun cropHandleAt(box: IntArray, x: Int, y: Int, tolerance: Int): Int = cropHandle(box, x, y, tolerance)

    fun dragCrop(start: IntArray, handle: Int, dx: Int, dy: Int, pageW: Int, pageH: Int): IntArray =
        cropDrag(start, handle, dx, dy, pageW, pageH) ?: start

    /** The box cut out of the page as a JPEG, or null if it can't be. */
    fun encodeCrop(book: Int, index: Int, box: IntArray, maxW: Int, maxH: Int, quality: Int): ByteArray? =
        cropEncode(book, index, box, maxW, maxH, quality)
}
