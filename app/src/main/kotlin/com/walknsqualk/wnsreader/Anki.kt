// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AnkiDroid through its `AddContentApi`: the decks, note types and fields
 * it has, and adding a note with media. Every call is a content-provider
 * query, so call off the main thread.
 */
class AnkiDroid(private val context: Context) {
    private val api by lazy { AddContentApi(context) }

    val packageName: String? get() = AddContentApi.getAnkiDroidPackageName(context)

    fun installed(): Boolean = packageName != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun decks(): List<String> = api.deckList?.values?.sorted().orEmpty()

    fun noteTypes(): List<String> = api.modelList?.values?.sorted().orEmpty()

    fun noteTypeId(name: String): Long? = api.modelList?.entries?.firstOrNull { it.value == name }?.key

    /** The note type's field names, in its own order; null if there's no such type. */
    fun fields(noteType: String): List<String>? = noteTypeId(noteType)?.let { id -> api.getFieldList(id)?.toList() }

    private fun deckId(name: String): Long? =
        api.deckList?.entries?.firstOrNull { it.value == name }?.key ?: api.addNewDeck(name)

    /**
     * A plain mining note type with [fields]: the sentence and word on the
     * front, everything else on the back. For when the configured type
     * (jidoujisho Kinomoto by default) isn't in AnkiDroid.
     */
    fun createNoteType(name: String, fields: List<String>): Long? {
        fun has(field: String) = fields.contains(field)
        val front = buildString {
            if (has("Sentence")) append("<div class=sentence>{{Sentence}}</div>")
            if (has("Term")) append("<div class=term>{{Term}}</div>")
        }.ifEmpty { "{{${fields.first()}}}" }
        val back = buildString {
            append("{{FrontSide}}<hr id=answer>")
            fields.filter { it != "Sentence" && it != "Term" }.forEach { field ->
                // Anki's furigana filter turns 食[た]べる into ruby text.
                val filter = if (field == "Furigana") "furigana:" else ""
                append("<div class=\"field\">{{$filter$field}}</div>")
            }
        }
        val css = ".card { font-size: 22px; text-align: center; color: black; background: white; }\n" +
            ".sentence { font-size: 28px; } .term { font-size: 40px; margin: 12px 0; }\n" +
            ".field { margin: 6px 0; }"
        return api.addNewCustomModel(name, fields.toTypedArray(), arrayOf("Mining"), arrayOf(front), arrayOf(back), css, null, null)
    }

    /**
     * Stores [bytes] in AnkiDroid's media folder and returns the tag that
     * shows it (`<img src=...>` or `[sound:...]`), or null if AnkiDroid
     * refused it. The file goes to AnkiDroid as a content URI it's given
     * read access to.
     */
    fun addMedia(bytes: ByteArray, fileName: String, kind: String): String? {
        val dir = File(context.cacheDir, "anki").apply { mkdirs() }
        val file = File(dir, fileName).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val target = packageName ?: return null
        context.grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            api.addMediaFromUri(uri, fileName.substringBeforeLast('.'), kind)
        } finally {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            file.delete()
        }
    }

    fun isDuplicate(noteTypeId: Long, firstField: String): Boolean =
        api.findDuplicateNotes(noteTypeId, firstField)?.isNotEmpty() == true

    fun addNote(noteTypeId: Long, deck: String, fields: Array<String>, tags: Set<String>): Long? {
        val deckId = deckId(deck) ?: return null
        return api.addNote(noteTypeId, deckId, fields, tags)
    }
}

/** One field as native/src/cards.zig rendered it. */
data class CardField(val field: String, val source: String, val value: String, val preview: String)

/** A card's fields, rendered, and the word audio's URL (empty when off). */
data class RenderedCard(val fields: List<CardField>, val audioUrl: String) {
    fun uses(source: String): Boolean = fields.any { it.source == source }

    companion object {
        fun from(json: JSONObject): RenderedCard {
            val fields = json.getJSONArray("fields")
            return RenderedCard(
                fields = (0 until fields.length()).map { i ->
                    val f = fields.getJSONObject(i)
                    CardField(f.getString("field"), f.getString("source"), f.getString("value"), f.getString("preview"))
                },
                audioUrl = json.getString("audio_url"),
            )
        }
    }
}

/**
 * What the panel captured for a card (gw-read's `Note.Init`): a word from
 * the dictionary, or the whole bubble when only the AI answer is up.
 * Spans are codepoints into the bubble text.
 */
data class CardRequest(
    val kind: String,
    val term: String,
    val reading: String,
    val spanStart: Int,
    val spanEnd: Int,
    val glossary: List<String>,
    val ai: String,
)

/** The word audio, once looked for. */
sealed interface CardAudio {
    data object Off : CardAudio
    data object Checking : CardAudio
    class Found(val mp3: ByteArray) : CardAudio
    data object Missing : CardAudio
    data class Failed(val message: String) : CardAudio
}

sealed interface AddOutcome {
    data class Added(val deck: String) : AddOutcome
    data class Failed(val message: String) : AddOutcome
}

/** Builds and adds cards: Zig renders the fields, AnkiDroid stores them. */
object CardMaker {
    fun noteInput(prefs: Prefs, request: CardRequest, sentence: String, book: String, page: Int): JSONObject =
        JSONObject()
            .put("kind", request.kind)
            .put("term", request.term)
            .put("reading", request.reading)
            .put("sentence", sentence)
            .put("span_start", request.spanStart)
            .put("span_end", request.spanEnd)
            .put("glossary", JSONArray(request.glossary))
            .put("ai", request.ai)
            .put("book", book)
            .put("page", page)
            .put("audio_template", prefs.ankiAudioUrl)
            .put("fields", Defaults.fieldMapsJson(prefs.ankiFields))

    fun render(note: JSONObject): RenderedCard = RenderedCard.from(NativeCore.renderCard(note))

    /**
     * Downloads the word audio. JapanesePod101 answers a word it has no
     * clip of with a fixed "not available" recording, recognised by its MD5
     * (gw-yomi's `jpod_missing_md5`); AnkiDroid has no AnkiConnect-style
     * `skipHash`, so it's checked here.
     */
    suspend fun fetchAudio(prefs: Prefs, url: String): CardAudio {
        if (url.isEmpty()) return CardAudio.Off
        return try {
            val (status, body) = Network.fetch(Request.Builder().url(url).build())
            when {
                status !in 200..299 -> CardAudio.Failed("HTTP $status")
                body.isEmpty() || Network.md5Hex(body) == prefs.defaults.missingAudioMd5 -> CardAudio.Missing
                else -> CardAudio.Found(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not only IOException: a URL OkHttp won't parse throws
            // IllegalArgumentException. No clip is never worth a crash.
            CardAudio.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun add(context: Context, prefs: Prefs, card: RenderedCard, image: ByteArray?, audio: CardAudio): AddOutcome =
        try {
            addNote(context, prefs, card, image, audio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // AnkiDroid's provider can throw (a media file it won't take, a
            // permission revoked mid-way); say so rather than crash.
            AddOutcome.Failed("AnkiDroid refused the card: ${e.message ?: e.javaClass.simpleName}")
        }

    private suspend fun addNote(context: Context, prefs: Prefs, card: RenderedCard, image: ByteArray?, audio: CardAudio): AddOutcome =
        withContext(Dispatchers.IO) {
            val anki = AnkiDroid(context)
            if (!anki.installed()) return@withContext AddOutcome.Failed("AnkiDroid isn't installed.")
            if (!anki.hasPermission()) return@withContext AddOutcome.Failed("wns-reader hasn't been allowed to add to AnkiDroid.")
            val noteTypeId = anki.noteTypeId(prefs.ankiModel)
                ?: return@withContext AddOutcome.Failed("AnkiDroid has no note type \"${prefs.ankiModel}\". Pick or create one in Settings.")
            val noteFields = anki.fields(prefs.ankiModel).orEmpty()

            // Media file names have to be unique: AnkiDroid's media folder is flat.
            val stamp = System.currentTimeMillis()
            val imageTag = if (image != null && card.uses("image")) anki.addMedia(image, "wns-reader-$stamp.jpg", "image") else null
            val audioTag = if (audio is CardAudio.Found && card.uses("audio")) anki.addMedia(audio.mp3, "wns-reader-$stamp.mp3", "audio") else null

            val values = noteFields.map { name ->
                val field = card.fields.firstOrNull { it.field == name }
                when (field?.source) {
                    null -> ""
                    "image" -> imageTag.orEmpty()
                    "audio" -> audioTag.orEmpty()
                    else -> field.value
                }
            }.toTypedArray()
            if (values.isEmpty()) return@withContext AddOutcome.Failed("The note type has no fields.")
            if (!prefs.ankiAllowDuplicates && anki.isDuplicate(noteTypeId, values[0])) {
                return@withContext AddOutcome.Failed("Already in AnkiDroid (same ${noteFields[0]}).")
            }
            val tags = prefs.ankiTags.split(' ', '\t').filter { it.isNotBlank() }.toSet()
            anki.addNote(noteTypeId, prefs.ankiDeck, values, tags)
                ?.let { AddOutcome.Added(prefs.ankiDeck) }
                ?: AddOutcome.Failed("AnkiDroid didn't add the note.")
        }
}
