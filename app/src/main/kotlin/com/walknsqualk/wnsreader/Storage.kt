// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.content.Context
import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where things live. Books and dictionary downloads are in shared
 * storage, read by real path under all-files access, so they can be
 * synced or copied in from a computer. Installed dictionaries and the AI
 * answer cache are in app storage.
 */
object Paths {
    private val shared: File get() = Environment.getExternalStorageDirectory()

    /** The books folder: /sdcard/Manga. */
    val books: File get() = File(shared, "Manga")

    /** Where Yomitan dictionary zips are put to be installed. */
    val dictionaryDownloads: File get() = File(shared, "wns-reader/dictionaries")

    /** Installed dictionaries (`terms/`, `frequency/`). */
    fun installedDictionaries(context: Context): File = File(context.filesDir, "dictionaries")

    /** gw-yomi's AI answer cache. */
    fun answerCache(context: Context): File = File(context.filesDir, "ai-cache.sqlite3")

    /** The app's own database: bookmarks. */
    fun appDatabase(context: Context): File = File(context.filesDir, "wns-reader.sqlite3")

    /** Where an uncaught crash's stack trace is written ([CrashLog]). */
    val crashLog: File get() = File(shared, "wns-reader/last-crash.txt")
}

/** Which edge of the screen the bubble panel is pinned to. */
enum class PanelSide(val label: String) {
    LEFT("Left"),
    RIGHT("Right"),
    TOP("Top"),
    BOTTOM("Bottom"),
}

/** One AI provider the settings offer, with gw-read's defaults for it. */
data class AiProvider(val id: String, val label: String, val model: String, val endpoint: String, val needsKey: Boolean)

/** A note field and what fills it (gw-read's `anki_fields`). */
data class FieldMap(val field: String, val source: String)

/**
 * gw-read's defaults for everything on the settings screen, from the Zig
 * core (native/src/cards.zig `writeDefaults`), so they're defined once.
 */
class Defaults(json: JSONObject) {
    private val anki = json.getJSONObject("anki")
    private val ai = json.getJSONObject("ai")

    val deck: String = anki.getString("deck")
    val model: String = anki.getString("model")
    val fields: List<FieldMap> = fieldMaps(anki.getJSONArray("fields"))
    val sources: List<String> = anki.getJSONArray("sources").let { a -> (0 until a.length()).map { a.getString(it) } }
    val audioUrl: String = anki.getString("audio_url")
    val missingAudioMd5: String = anki.getString("jpod_missing_md5")
    val imageMaxWidth: Int = anki.getInt("image_max_width")
    val imageMaxHeight: Int = anki.getInt("image_max_height")
    val imageQuality: Int = anki.getInt("image_quality")

    val aiStyle: String = ai.getString("style")
    val providers: List<AiProvider> = ai.getJSONArray("providers").let { a ->
        (0 until a.length()).map { i ->
            val p = a.getJSONObject(i)
            AiProvider(p.getString("id"), p.getString("label"), p.getString("model"), p.getString("endpoint"), p.getBoolean("needs_key"))
        }
    }

    companion object {
        fun fieldMaps(array: JSONArray): List<FieldMap> = (0 until array.length()).map { i ->
            val f = array.getJSONObject(i)
            FieldMap(f.getString("field"), f.getString("source"))
        }

        fun fieldMapsJson(fields: List<FieldMap>): JSONArray =
            JSONArray(fields.map { JSONObject().put("field", it.field).put("source", it.source) })
    }
}

/**
 * Settings and reading positions, in SharedPreferences. Anything unset
 * reads as gw-read's default. API keys are kept apart, encrypted
 * ([SecretStore]).
 */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("wns-reader", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    val defaults: Defaults by lazy { Defaults(NativeCore.defaultSettings()) }

    private fun string(key: String, default: String) = prefs.getString(key, null) ?: default
    private fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    private fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    private fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    private fun int(key: String, default: Int) = prefs.getInt(key, default)
    private fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    // ---- reading ----

    fun lastPage(bookPath: String): Int = prefs.getInt("page:$bookPath", 0)

    fun setLastPage(bookPath: String, page: Int) = putInt("page:$bookPath", page)

    fun hasPosition(bookPath: String): Boolean = prefs.contains("page:$bookPath")

    /** "rank" (lower is more common) or "count" -- see gw-yomi's `FrequencyOrder`. */
    var frequencyOrder: String
        get() = string("frequency_order", "rank")
        set(value) = putString("frequency_order", value)

    var showBubbleBoxes: Boolean
        get() = bool("show_boxes", true)
        set(value) = putBool("show_boxes", value)

    var panelSide: PanelSide
        get() = runCatching { PanelSide.valueOf(string("panel_side", PanelSide.RIGHT.name)) }.getOrDefault(PanelSide.RIGHT)
        set(value) = putString("panel_side", value.name)

    // ---- AI (gw-read's ai_*) ----

    var aiEnabled: Boolean
        get() = bool("ai_enabled", false)
        set(value) = putBool("ai_enabled", value)

    var aiProvider: String
        get() = string("ai_provider", defaults.providers.first().id)
        set(value) = putString("ai_provider", value)

    fun provider(): AiProvider = defaults.providers.firstOrNull { it.id == aiProvider } ?: defaults.providers.first()

    /** Per provider, so switching back and forth keeps each one's choice. Empty means the default. */
    fun aiModel(provider: String): String = string("ai_model:$provider", "")

    fun setAiModel(provider: String, value: String) = putString("ai_model:$provider", value)

    fun aiEndpoint(provider: String): String = string("ai_endpoint:$provider", "")

    fun setAiEndpoint(provider: String, value: String) = putString("ai_endpoint:$provider", value)

    fun aiKey(provider: String): String = secrets.get("ai_key:$provider") ?: ""

    fun setAiKey(provider: String, value: String) = secrets.put("ai_key:$provider", value)

    var aiStyle: String
        get() = string("ai_style", defaults.aiStyle)
        set(value) = putString("ai_style", value)

    var aiIncludeNeighbors: Boolean
        get() = bool("ai_neighbors", true)
        set(value) = putBool("ai_neighbors", value)

    var aiIncludeBookInfo: Boolean
        get() = bool("ai_book_info", false)
        set(value) = putBool("ai_book_info", value)

    var aiUseCache: Boolean
        get() = bool("ai_cache", true)
        set(value) = putBool("ai_cache", value)

    // ---- Anki (gw-read's anki_*) ----

    var ankiDeck: String
        get() = string("anki_deck", defaults.deck)
        set(value) = putString("anki_deck", value)

    var ankiModel: String
        get() = string("anki_model", defaults.model)
        set(value) = putString("anki_model", value)

    var ankiFields: List<FieldMap>
        get() = prefs.getString("anki_fields", null)?.let { Defaults.fieldMaps(JSONArray(it)) } ?: defaults.fields
        set(value) = putString("anki_fields", Defaults.fieldMapsJson(value).toString())

    var ankiTags: String
        get() = string("anki_tags", "wns-reader")
        set(value) = putString("anki_tags", value)

    var ankiAllowDuplicates: Boolean
        get() = bool("anki_allow_duplicates", false)
        set(value) = putBool("anki_allow_duplicates", value)

    var ankiAudioUrl: String
        get() = string("anki_audio_url", defaults.audioUrl)
        set(value) = putString("anki_audio_url", value)

    var ankiImageMaxWidth: Int
        get() = int("anki_image_max_width", defaults.imageMaxWidth)
        set(value) = putInt("anki_image_max_width", value)

    var ankiImageMaxHeight: Int
        get() = int("anki_image_max_height", defaults.imageMaxHeight)
        set(value) = putInt("anki_image_max_height", value)

    var ankiImageQuality: Int
        get() = int("anki_image_quality", defaults.imageQuality)
        set(value) = putInt("anki_image_quality", value)
}

/**
 * Small secrets (API keys) encrypted with an AES key that lives in the
 * Android Keystore and never leaves it. The ciphertext and its IV are
 * kept in their own SharedPreferences file.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("wns-reader-secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    /** Null when unset, or when it can't be decrypted (the key was lost with an app data wipe). */
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "wns-reader-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
