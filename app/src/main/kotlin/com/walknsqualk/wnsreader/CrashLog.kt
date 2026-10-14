// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

package com.walknsqualk.wnsreader

import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

/**
 * Writes the stack trace of an uncaught exception to
 * /sdcard/wns-reader/last-crash.txt before the app dies as it would
 * anyway, so a crash on the tablet can be read without adb. A panic in
 * the Zig core aborts the process without passing through here; that
 * one only shows in `adb logcat -s wnscore`.
 */
object CrashLog {
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val file = Paths.crashLog
                file.parentFile?.mkdirs()
                file.writeText("${LocalDateTime.now()} on thread ${thread.name}\n\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
