// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! The JNI surface of libwnscore.so: what Kotlin's `NativeCore` object
//! calls, and the process-wide state behind it.
//!
//! **Strings cross as UTF-8 byte arrays, never `jstring`.** JNI's string
//! functions speak "modified UTF-8", which encodes a character outside
//! the BMP as two 3-byte surrogates instead of one 4-byte sequence --
//! and Japanese text has real non-BMP kanji (𠮷, 𩸽). Passing
//! `ByteArray`s and decoding with `Charsets.UTF_8` on the Kotlin side
//! sidesteps that for every call. Results are JSON, so each call is one
//! byte array in each direction; a failure is `{"error": "<name>"}`.
//!
//! **Books are small integer handles**, not pointers: `openBook` returns
//! an id from `books`, and every later call looks it up. A stale id is
//! then an error, not a use-after-free, and a page read still running on
//! one Kotlin thread holds a reference that keeps the book alive while
//! another thread closes it (`BookTable`).
//!
//! **One `std.Io` for the process**, set up in `JNI_OnLoad` and never torn
//! down: Android unloads a library only with its process.
//!
//! **Logs and panics go to logcat.** stderr goes nowhere in an app
//! process, so `std_options.logFn` and the panic handler both write
//! through `__android_log_write` under the tag `wnscore`.

const std = @import("std");
const c = @import("android_c");
const diag = @import("diag.zig");
const library = @import("library.zig");
const Book = @import("book.zig").Book;
const dictionaries = @import("dictionaries.zig");
const assistant = @import("assistant.zig");
const cards = @import("cards.zig");
const FrequencyOrder = @import("gw_yomi").dict.FrequencyOrder;

const log_tag = "wnscore";

// From <android/log.h>, which is left out of android.h (see there). The
// priorities are `android_LogPriority`, a stable NDK ABI.
extern "log" fn __android_log_write(prio: c_int, tag: [*:0]const u8, text: [*:0]const u8) c_int;
const LogPriority = enum(c_int) { debug = 3, info = 4, warn = 5, err = 6, fatal = 7 };

const gpa = std.heap.c_allocator;

var threaded: std.Io.Threaded = undefined;
var books: BookTable = .{};
var dicts: dictionaries.Library = undefined;
var helper: assistant.Assistant = undefined;
/// The install in progress, if any. One at a time: the screen that
/// starts one shows nothing else until it ends.
var install: ?*dictionaries.Install = null;
var install_mutex: std.Io.Mutex = .init;

pub const std_options: std.Options = .{
    .logFn = androidLog,
};

pub const panic = std.debug.FullPanic(androidPanic);

fn io() std.Io {
    return threaded.io();
}

export fn JNI_OnLoad(vm: ?*c.JavaVM, reserved: ?*anyopaque) c.jint {
    _ = vm;
    _ = reserved;
    threaded = .init(gpa, .{});
    dicts = .{ .alloc = gpa, .io = io() };
    helper = .{ .alloc = gpa, .io = io() };
    std.log.info("libwnscore {s} loaded", .{diag.version});
    return c.JNI_VERSION_1_6;
}

// ---- diagnostics -------------------------------------------------------------

/// `version(): ByteArray` -- JSON naming the core, Zig and SQLite versions.
export fn Java_com_walknsqualk_wnsreader_NativeCore_version(env: *c.JNIEnv, _: c.jclass) c.jbyteArray {
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    diag.writeVersion(&out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

/// `runDiagnostics(scratchDir: ByteArray, booksDir: ByteArray?): ByteArray`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_runDiagnostics(
    env: *c.JNIEnv,
    _: c.jclass,
    scratch_dir: c.jbyteArray,
    books_dir: c.jbyteArray,
) c.jbyteArray {
    const scratch = readByteArray(env, scratch_dir) catch |err| return errorJson(env, err);
    defer gpa.free(scratch);
    const books_path: ?[]u8 = if (books_dir == null) null else readByteArray(env, books_dir) catch |err| return errorJson(env, err);
    defer if (books_path) |b| gpa.free(b);

    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    diag.writeDiagnostics(gpa, io(), .{ .scratch_dir = scratch, .books_dir = books_path }, &out.writer) catch |err|
        return errorJson(env, err);
    return newByteArray(env, out.written());
}

// ---- library -----------------------------------------------------------------

/// `listDirectory(path: ByteArray): ByteArray` -- `{path, entries: [...]}`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_listDirectory(env: *c.JNIEnv, _: c.jclass, path_array: c.jbyteArray) c.jbyteArray {
    const path = readByteArray(env, path_array) catch |err| return errorJson(env, err);
    defer gpa.free(path);
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    library.writeJson(gpa, io(), path, &out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

// ---- books -------------------------------------------------------------------

/// `openBook(path: ByteArray): ByteArray` -- `{book: id, title, pages, ocr, ocr_pages}`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_openBook(env: *c.JNIEnv, _: c.jclass, path_array: c.jbyteArray) c.jbyteArray {
    const path = readByteArray(env, path_array) catch |err| return errorJson(env, err);
    defer gpa.free(path);

    const book = Book.open(gpa, io(), path) catch |err| return errorJson(env, err);
    const id = books.add(book) catch |err| {
        book.close();
        return errorJson(env, err);
    };

    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    book.writeInfo(id, &out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

/// `closeBook(book: Int)`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_closeBook(_: *c.JNIEnv, _: c.jclass, id: c.jint) void {
    books.close(id);
}

/// `readPage(book: Int, index: Int): ByteArray?` -- the image file's bytes,
/// or null if the book is gone or the page can't be read.
export fn Java_com_walknsqualk_wnsreader_NativeCore_readPage(env: *c.JNIEnv, _: c.jclass, id: c.jint, index: c.jint) c.jbyteArray {
    const book = books.acquire(id) orelse return null;
    defer books.release(id);
    const bytes = book.readPage(gpa, @intCast(@max(index, 0))) catch |err| {
        std.log.warn("page {d}: {s}", .{ index, @errorName(err) });
        return null;
    };
    defer gpa.free(bytes);
    return newByteArray(env, bytes);
}

/// `pageText(book: Int, index: Int): ByteArray` -- see `Book.writePageText`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_pageText(env: *c.JNIEnv, _: c.jclass, id: c.jint, index: c.jint) c.jbyteArray {
    const book = books.acquire(id) orelse return errorJson(env, error.NoSuchBook);
    defer books.release(id);
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    book.writePageText(gpa, @intCast(@max(index, 0)), &out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

/// `blockAt(book: Int, index: Int, x: Int, y: Int): Int` -- the bubble
/// under image pixel (x, y), or -1.
export fn Java_com_walknsqualk_wnsreader_NativeCore_blockAt(_: *c.JNIEnv, _: c.jclass, id: c.jint, index: c.jint, x: c.jint, y: c.jint) c.jint {
    const book = books.acquire(id) orelse return -1;
    defer books.release(id);
    const block = book.blockAt(@intCast(@max(index, 0)), x, y) orelse return -1;
    return @intCast(block);
}

// ---- dictionaries ------------------------------------------------------------

/// `openDictionaries(root: ByteArray, order: ByteArray): ByteArray` --
/// (re)opens what's installed under `root`; returns the status.
export fn Java_com_walknsqualk_wnsreader_NativeCore_openDictionaries(env: *c.JNIEnv, _: c.jclass, root_array: c.jbyteArray, order_array: c.jbyteArray) c.jbyteArray {
    const root = readByteArray(env, root_array) catch |err| return errorJson(env, err);
    defer gpa.free(root);
    const order_name = readByteArray(env, order_array) catch |err| return errorJson(env, err);
    defer gpa.free(order_name);

    dicts.reopen(root, FrequencyOrder.parse(order_name) orelse .rank);
    return dictionaryStatus(env);
}

/// `dictionaryStatus(): ByteArray` -- see `Library.writeStatus`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_dictionaryStatus(env: *c.JNIEnv, _: c.jclass) c.jbyteArray {
    return dictionaryStatus(env);
}

fn dictionaryStatus(env: *c.JNIEnv) c.jbyteArray {
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    dicts.writeStatus(&out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

/// `lookup(text: ByteArray, at: Int): ByteArray` -- see `Library.writeLookup`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_lookup(env: *c.JNIEnv, _: c.jclass, text_array: c.jbyteArray, at: c.jint) c.jbyteArray {
    const text = readByteArray(env, text_array) catch |err| return errorJson(env, err);
    defer gpa.free(text);
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    dicts.writeLookup(gpa, text, @intCast(@max(at, 0)), &out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

/// `installBegin(zip: ByteArray, root: ByteArray): ByteArray` -- starts
/// installing a Yomitan zip and returns its first progress report. The
/// slot it will replace is closed first, so its file can be swapped.
export fn Java_com_walknsqualk_wnsreader_NativeCore_installBegin(env: *c.JNIEnv, _: c.jclass, zip_array: c.jbyteArray, root_array: c.jbyteArray) c.jbyteArray {
    const zip = readByteArray(env, zip_array) catch |err| return errorJson(env, err);
    defer gpa.free(zip);
    const root = readByteArray(env, root_array) catch |err| return errorJson(env, err);
    defer gpa.free(root);

    install_mutex.lockUncancelable(io());
    defer install_mutex.unlock(io());
    if (install) |old| {
        old.deinit();
        install = null;
    }
    const started = dictionaries.Install.begin(gpa, io(), zip, root) catch |err| return errorJson(env, err);
    dicts.closeSlot(started.slot);
    install = started;
    return installProgress(env, started);
}

/// `installStep(): ByteArray` -- one unit of work, then the progress.
/// Once the phase is `done` the install is over and freed; reopen the
/// dictionaries to use it.
export fn Java_com_walknsqualk_wnsreader_NativeCore_installStep(env: *c.JNIEnv, _: c.jclass) c.jbyteArray {
    install_mutex.lockUncancelable(io());
    defer install_mutex.unlock(io());
    const current = install orelse return errorJson(env, error.NoInstallRunning);
    current.step() catch |err| {
        current.deinit();
        install = null;
        return errorJson(env, err);
    };
    const report = installProgress(env, current);
    if (current.phase == .done) {
        current.deinit();
        install = null;
    }
    return report;
}

/// `installCancel()` -- abandons the install, leaving its slot empty.
export fn Java_com_walknsqualk_wnsreader_NativeCore_installCancel(_: *c.JNIEnv, _: c.jclass) void {
    install_mutex.lockUncancelable(io());
    defer install_mutex.unlock(io());
    if (install) |current| current.deinit();
    install = null;
}

fn installProgress(env: *c.JNIEnv, current: *dictionaries.Install) c.jbyteArray {
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    current.writeProgress(&out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

// ---- settings, AI and cards ------------------------------------------------

/// `defaults(): ByteArray` -- see `cards.writeDefaults`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_defaults(env: *c.JNIEnv, _: c.jclass) c.jbyteArray {
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    cards.writeDefaults(&out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

/// `openAiCache(path: ByteArray): ByteArray` -- `{}` or an error.
export fn Java_com_walknsqualk_wnsreader_NativeCore_openAiCache(env: *c.JNIEnv, _: c.jclass, path_array: c.jbyteArray) c.jbyteArray {
    const path = readByteArray(env, path_array) catch |err| return errorJson(env, err);
    defer gpa.free(path);
    helper.openCache(path) catch |err| return errorJson(env, err);
    return newByteArray(env, "{}");
}

/// `aiPrepare(question: ByteArray): ByteArray` -- see `Assistant.writePrepare`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_aiPrepare(env: *c.JNIEnv, _: c.jclass, json_array: c.jbyteArray) c.jbyteArray {
    return jsonCall(assistant.Question, env, json_array, struct {
        fn call(q: assistant.Question, w: *std.Io.Writer) !void {
            try helper.writePrepare(gpa, q, w);
        }
    }.call);
}

/// `aiFinish(reply: ByteArray): ByteArray` -- see `Assistant.writeFinish`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_aiFinish(env: *c.JNIEnv, _: c.jclass, json_array: c.jbyteArray) c.jbyteArray {
    return jsonCall(assistant.Reply, env, json_array, struct {
        fn call(r: assistant.Reply, w: *std.Io.Writer) !void {
            try helper.writeFinish(gpa, r, w);
        }
    }.call);
}

/// `cardFields(note: ByteArray): ByteArray` -- see `cards.writeFields`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_cardFields(env: *c.JNIEnv, _: c.jclass, json_array: c.jbyteArray) c.jbyteArray {
    return jsonCall(cards.NoteInput, env, json_array, struct {
        fn call(in: cards.NoteInput, w: *std.Io.Writer) !void {
            try cards.writeFields(gpa, in, w);
        }
    }.call);
}

/// `cropInitial(bubble: IntArray, pageW: Int, pageH: Int): IntArray` --
/// boxes are `[x, y, w, h]` in page-image pixels.
export fn Java_com_walknsqualk_wnsreader_NativeCore_cropInitial(env: *c.JNIEnv, _: c.jclass, bubble: c.jintArray, page_w: c.jint, page_h: c.jint) c.jintArray {
    const box = readBox(env, bubble) orelse return null;
    return newBox(env, cards.initialBox(box, page_w, page_h));
}

/// `cropHandle(box: IntArray, x: Int, y: Int, tolerance: Int): Int` --
/// what a drag from (x, y) grabs, as a `crop.Handle` ordinal.
export fn Java_com_walknsqualk_wnsreader_NativeCore_cropHandle(env: *c.JNIEnv, _: c.jclass, box_array: c.jintArray, x: c.jint, y: c.jint, tolerance: c.jint) c.jint {
    const box = readBox(env, box_array) orelse return 0;
    return @intFromEnum(cards.handleAt(box, x, y, tolerance));
}

/// `cropDrag(start: IntArray, handle: Int, dx: Int, dy: Int, pageW: Int, pageH: Int): IntArray`.
export fn Java_com_walknsqualk_wnsreader_NativeCore_cropDrag(env: *c.JNIEnv, _: c.jclass, start_array: c.jintArray, handle: c.jint, dx: c.jint, dy: c.jint, page_w: c.jint, page_h: c.jint) c.jintArray {
    const start = readBox(env, start_array) orelse return null;
    const h = std.enums.fromInt(@import("gw_yomi").crop.Handle, handle) orelse .move;
    return newBox(env, cards.dragBox(start, h, dx, dy, page_w, page_h));
}

/// `cropEncode(book: Int, index: Int, box: IntArray, maxW: Int, maxH: Int, quality: Int): ByteArray?`
/// -- the box cut out of the page, downscaled and JPEG-encoded.
export fn Java_com_walknsqualk_wnsreader_NativeCore_cropEncode(env: *c.JNIEnv, _: c.jclass, id: c.jint, index: c.jint, box_array: c.jintArray, max_w: c.jint, max_h: c.jint, quality: c.jint) c.jbyteArray {
    const box = readBox(env, box_array) orelse return null;
    const book = books.acquire(id) orelse return null;
    defer books.release(id);
    const page = book.readPage(gpa, @intCast(@max(index, 0))) catch return null;
    defer gpa.free(page);
    const encoded = cards.encode(gpa, page, box, @intCast(@max(max_w, 0)), @intCast(@max(max_h, 0)), @intCast(std.math.clamp(quality, 1, 100))) catch |err| {
        std.log.warn("crop: {s}", .{@errorName(err)});
        return null;
    };
    defer gpa.free(encoded.jpeg);
    return newByteArray(env, encoded.jpeg);
}

/// Parses `json_array` as a `T` and hands it to `call`, which writes the
/// JSON reply. Unknown fields are ignored so Kotlin can send a superset.
fn jsonCall(comptime T: type, env: *c.JNIEnv, json_array: c.jbyteArray, comptime call: fn (T, *std.Io.Writer) anyerror!void) c.jbyteArray {
    const json = readByteArray(env, json_array) catch |err| return errorJson(env, err);
    defer gpa.free(json);
    const parsed = std.json.parseFromSlice(T, gpa, json, .{ .ignore_unknown_fields = true }) catch |err| return errorJson(env, err);
    defer parsed.deinit();
    var out: std.Io.Writer.Allocating = .init(gpa);
    defer out.deinit();
    call(parsed.value, &out.writer) catch |err| return errorJson(env, err);
    return newByteArray(env, out.written());
}

fn readBox(env: *c.JNIEnv, array: c.jintArray) ?cards.Rect {
    if (array == null or env.*.*.GetArrayLength.?(env, array) != 4) return null;
    var v: [4]c.jint = undefined;
    env.*.*.GetIntArrayRegion.?(env, array, 0, 4, &v);
    return .{ .x = v[0], .y = v[1], .w = v[2], .h = v[3] };
}

fn newBox(env: *c.JNIEnv, box: cards.Rect) c.jintArray {
    const array = env.*.*.NewIntArray.?(env, 4);
    if (array == null) return null;
    const v = [4]c.jint{ @intCast(box.x), @intCast(box.y), @intCast(box.w), @intCast(box.h) };
    env.*.*.SetIntArrayRegion.?(env, array, 0, 4, &v);
    return array;
}

// ---- book handles --------------------------------------------------------------

/// Open books by id, reference-counted so that closing a book another
/// thread is still reading from waits for that read to finish.
const BookTable = struct {
    mutex: std.Io.Mutex = .init,
    entries: std.AutoHashMapUnmanaged(c.jint, Slot) = .empty,
    next_id: c.jint = 1,

    const Slot = struct {
        book: *Book,
        refs: u32 = 0,
        closing: bool = false,
    };

    fn add(self: *BookTable, book: *Book) !c.jint {
        self.mutex.lockUncancelable(io());
        defer self.mutex.unlock(io());
        const id = self.next_id;
        try self.entries.put(gpa, id, .{ .book = book });
        self.next_id += 1;
        return id;
    }

    fn acquire(self: *BookTable, id: c.jint) ?*Book {
        self.mutex.lockUncancelable(io());
        defer self.mutex.unlock(io());
        const slot = self.entries.getPtr(id) orelse return null;
        if (slot.closing) return null;
        slot.refs += 1;
        return slot.book;
    }

    fn release(self: *BookTable, id: c.jint) void {
        self.mutex.lockUncancelable(io());
        const slot = self.entries.getPtr(id) orelse {
            self.mutex.unlock(io());
            return;
        };
        slot.refs -= 1;
        const done = slot.closing and slot.refs == 0;
        const book = slot.book;
        if (done) _ = self.entries.remove(id);
        self.mutex.unlock(io());
        if (done) book.close();
    }

    fn close(self: *BookTable, id: c.jint) void {
        self.mutex.lockUncancelable(io());
        const slot = self.entries.getPtr(id) orelse {
            self.mutex.unlock(io());
            return;
        };
        slot.closing = true;
        const done = slot.refs == 0;
        const book = slot.book;
        if (done) _ = self.entries.remove(id);
        self.mutex.unlock(io());
        if (done) book.close();
    }
};

// ---- JNI helpers ------------------------------------------------------------

fn readByteArray(env: *c.JNIEnv, array: c.jbyteArray) ![]u8 {
    const len: usize = @intCast(env.*.*.GetArrayLength.?(env, array));
    const bytes = try gpa.alloc(u8, len);
    env.*.*.GetByteArrayRegion.?(env, array, 0, @intCast(len), @ptrCast(bytes.ptr));
    return bytes;
}

/// Null only when the JVM is out of memory, in which case it has already
/// thrown OutOfMemoryError for Kotlin to see.
fn newByteArray(env: *c.JNIEnv, bytes: []const u8) c.jbyteArray {
    const array = env.*.*.NewByteArray.?(env, @intCast(bytes.len));
    if (array == null) return null;
    env.*.*.SetByteArrayRegion.?(env, array, 0, @intCast(bytes.len), @ptrCast(bytes.ptr));
    return array;
}

/// `{"error": "<name>"}`, so a failed call still hands Kotlin JSON.
fn errorJson(env: *c.JNIEnv, err: anyerror) c.jbyteArray {
    std.log.err("native call failed: {s}", .{@errorName(err)});
    var buf: [128]u8 = undefined;
    const json = std.fmt.bufPrint(&buf, "{{\"error\":\"{s}\"}}", .{@errorName(err)}) catch "{\"error\":\"?\"}";
    return newByteArray(env, json);
}

// ---- logcat -------------------------------------------------------------------

fn androidLog(
    comptime level: std.log.Level,
    comptime scope: @EnumLiteral(),
    comptime format: []const u8,
    args: anytype,
) void {
    const priority: LogPriority = switch (level) {
        .err => .err,
        .warn => .warn,
        .info => .info,
        .debug => .debug,
    };
    const prefix = if (scope == .default) "" else @tagName(scope) ++ ": ";
    var buf: [1024]u8 = undefined;
    // Truncated rather than dropped: a cut-off line still says what failed.
    const msg = std.fmt.bufPrintSentinel(&buf, prefix ++ format, args, 0) catch blk: {
        buf[buf.len - 1] = 0;
        break :blk buf[0 .. buf.len - 1 :0];
    };
    _ = __android_log_write(@intFromEnum(priority), log_tag, msg.ptr);
}

fn androidPanic(msg: []const u8, first_trace_addr: ?usize) noreturn {
    _ = first_trace_addr;
    var buf: [1024]u8 = undefined;
    const line = std.fmt.bufPrintSentinel(&buf, "panic: {s}", .{msg}, 0) catch "panic (message too long)";
    _ = __android_log_write(@intFromEnum(LogPriority.fatal), log_tag, line.ptr);
    std.process.abort();
}
