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
}
