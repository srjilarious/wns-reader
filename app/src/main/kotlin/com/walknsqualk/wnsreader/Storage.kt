// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Where things live. Books and dictionary downloads are in shared
 * storage, read by real path under all-files access, so they can be
 * synced or copied in from a computer. Installed dictionaries are in app
 * storage: a SQLite index is faster there and nothing else needs it.
 */
object Paths {
    private val shared: File get() = Environment.getExternalStorageDirectory()

    /** The books folder: /sdcard/Manga. */
    val books: File get() = File(shared, "Manga")

    /** Where Yomitan dictionary zips are put to be installed. */
    val dictionaryDownloads: File get() = File(shared, "wns-reader/dictionaries")

    /** Installed dictionaries (`terms/`, `frequency/`). */
    fun installedDictionaries(context: Context): File = File(context.filesDir, "dictionaries")
}

/** Small settings and reading positions, in SharedPreferences. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("wns-reader", Context.MODE_PRIVATE)

    fun lastPage(bookPath: String): Int = prefs.getInt("page:$bookPath", 0)

    fun setLastPage(bookPath: String, page: Int) = prefs.edit().putInt("page:$bookPath", page).apply()

    fun hasPosition(bookPath: String): Boolean = prefs.contains("page:$bookPath")

    /** "rank" (lower is more common) or "count" -- see gw-yomi's `FrequencyOrder`. */
    var frequencyOrder: String
        get() = prefs.getString("frequency_order", "rank") ?: "rank"
        set(value) = prefs.edit().putString("frequency_order", value).apply()

    var showBubbleBoxes: Boolean
        get() = prefs.getBoolean("show_boxes", true)
        set(value) = prefs.edit().putBoolean("show_boxes", value).apply()
}
