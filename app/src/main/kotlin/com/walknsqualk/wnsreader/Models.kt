// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import org.json.JSONArray
import org.json.JSONObject

// The shapes the Zig core's JSON comes back in (native/src/*.zig), parsed
// once here so the screens deal in plain Kotlin values.

/** Thrown for a native call that answered `{"error": ...}`. */
class NativeException(message: String) : Exception(message)

internal fun JSONObject.orThrow(): JSONObject {
    if (has("error")) throw NativeException(getString("error"))
    return this
}

private inline fun <T> JSONArray.map(transform: (Int) -> T): List<T> = (0 until length()).map(transform)

private fun JSONArray.strings(): List<String> = map { getString(it) }

enum class EntryKind { FOLDER, BOOK, ARCHIVE }

data class LibraryEntry(val name: String, val path: String, val kind: EntryKind, val ocr: Boolean) {
    companion object {
        fun listFrom(json: JSONObject): List<LibraryEntry> {
            val entries = json.orThrow().getJSONArray("entries")
            return entries.map { i ->
                val e = entries.getJSONObject(i)
                LibraryEntry(
                    name = e.getString("name"),
                    path = e.getString("path"),
                    kind = EntryKind.valueOf(e.getString("kind").uppercase()),
                    ocr = e.getBoolean("ocr"),
                )
            }
        }
    }
}

data class BookInfo(val id: Int, val title: String, val pages: List<String>, val ocr: Boolean, val ocrPages: Int) {
    companion object {
        fun from(json: JSONObject): BookInfo {
            json.orThrow()
            return BookInfo(
                id = json.getInt("book"),
                title = json.getString("title"),
                pages = json.getJSONArray("pages").strings(),
                ocr = json.getBoolean("ocr"),
                ocrPages = json.getInt("ocr_pages"),
            )
        }
    }
}

/** One OCR bubble. `box` is x1, y1, x2, y2 in the page image's pixels. */
data class Block(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val vertical: Boolean, val text: String)

/**
 * A page's OCR. `width`/`height` are the image size mokuro measured,
 * which the boxes are in (0 when the page has no OCR). `order` is the
 * blocks' indices in reading order.
 */
data class PageText(val width: Int, val height: Int, val blocks: List<Block>, val order: List<Int>) {
    companion object {
        val EMPTY = PageText(0, 0, emptyList(), emptyList())

        fun from(json: JSONObject): PageText {
            json.orThrow()
            val blocks = json.getJSONArray("blocks")
            if (blocks.length() == 0) return EMPTY
            val order = json.getJSONArray("order")
            return PageText(
                width = json.getInt("width"),
                height = json.getInt("height"),
                blocks = blocks.map { i ->
                    val b = blocks.getJSONObject(i)
                    val box = b.getJSONArray("box")
                    Block(box.getInt(0), box.getInt(1), box.getInt(2), box.getInt(3), b.getBoolean("vertical"), b.getString("text"))
                },
                order = order.map { order.getInt(it) },
            )
        }
    }
}

data class Tag(val label: String, val category: String)

data class Sense(val text: String, val tags: List<Tag>)

/**
 * One word a lookup found. `length` is how many codepoints of the bubble
 * text it covers, from the lookup's start; `reason` is the deinflection
 * chain ("negative, past"), null for a word as written.
 */
data class WordGroup(
    val term: String,
    val reading: String,
    val length: Int,
    val reason: String?,
    val frequency: String?,
    val tags: List<Tag>,
    val senses: List<Sense>,
    val spellings: List<Pair<String, String>>,
)

/** A lookup's answer. `start` is the codepoint the lookup began at. */
data class LookupResult(val found: Boolean, val noDictionary: Boolean, val start: Int, val groups: List<WordGroup>) {
    companion object {
        private fun tags(array: JSONArray): List<Tag> = array.map { i ->
            val t = array.getJSONObject(i)
            Tag(t.getString("label"), t.getString("category"))
        }

        fun from(json: JSONObject): LookupResult {
            json.orThrow()
            if (!json.getBoolean("found")) {
                return LookupResult(false, json.optBoolean("no_dictionary"), 0, emptyList())
            }
            val groups = json.getJSONArray("groups")
            return LookupResult(
                found = true,
                noDictionary = false,
                start = json.getInt("start"),
                groups = groups.map { i ->
                    val g = groups.getJSONObject(i)
                    val senses = g.getJSONArray("senses")
                    val spellings = g.getJSONArray("spellings")
                    WordGroup(
                        term = g.getString("term"),
                        reading = g.getString("reading"),
                        length = g.getInt("length"),
                        reason = if (g.isNull("reason")) null else g.getString("reason"),
                        frequency = if (g.isNull("frequency")) null else g.getString("frequency"),
                        tags = tags(g.getJSONArray("tags")),
                        senses = senses.map { s ->
                            val sense = senses.getJSONObject(s)
                            Sense(sense.getString("text"), tags(sense.getJSONArray("tags")))
                        },
                        spellings = spellings.map { s ->
                            val sp = spellings.getJSONObject(s)
                            sp.getString("term") to sp.getString("reading")
                        },
                    )
                },
            )
        }
    }
}

data class SlotStatus(val installed: Boolean, val title: String)

data class DictionaryStatus(val terms: SlotStatus, val frequency: SlotStatus, val order: String) {
    companion object {
        private fun slot(json: JSONObject) = SlotStatus(json.getBoolean("installed"), json.getString("title"))

        fun from(json: JSONObject): DictionaryStatus {
            json.orThrow()
            return DictionaryStatus(slot(json.getJSONObject("terms")), slot(json.getJSONObject("frequency")), json.getString("order"))
        }
    }
}

/** Where a dictionary install is: `done` of `total` entries or bank files. */
data class InstallProgress(val slot: String, val phase: String, val done: Int, val total: Int) {
    val finished get() = phase == "done"

    companion object {
        fun from(json: JSONObject): InstallProgress {
            json.orThrow()
            return InstallProgress(json.getString("slot"), json.getString("phase"), json.getInt("done"), json.getInt("total"))
        }
    }
}
