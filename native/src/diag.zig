// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! The diagnostics screen: one check per piece of the platform the app
//! depends on, run the same way the real code uses it. Phase 0's proof
//! that the toolchain works on the tablet, kept as a screen to come back
//! to when something misbehaves on a device.
//!
//! - **sqlite**: gw-yomi's SQLite, opened `:memory:`, with a non-BMP kanji
//!   (𠮷) round-tripped through a bound parameter. That character is also
//!   a JNI check -- see `jni.zig` on why every string crosses the boundary
//!   as UTF-8 bytes, never `jstring`.
//! - **archive**: `probe.cbz` (testdata/) written to the app's scratch
//!   directory and opened with gw-yomi's `archive`, its first page
//!   decoded with stb_image and its mokuro sidecar parsed. File IO, zip,
//!   inflate, the C image code and the OCR parse, all on bionic.
//! - **books**: the user's manga folder listed by real path, which only
//!   works once the app holds all-files access.
//!
//! Everything here also runs on the host (`zig build test`), so a
//! failure on the tablet that doesn't reproduce there points at the
//! platform, not the code.

const std = @import("std");
const builtin = @import("builtin");
const yomi = @import("gw_yomi");
const sqlite = yomi.sqlite;

pub const version = "0.1.0";

/// The comic archive the archive check opens: two PNG pages stored out
/// of order and a stub mokuro sidecar. See testdata/make_probe_cbz.py.
const probe_cbz = @embedFile("probe_cbz");
const probe_name = "probe.cbz";

extern fn sqlite3_libversion() [*:0]const u8;
// gw-yomi's stb unit (src/image_io.c), linked in with the package.
extern fn gwr_image_decode(data: [*]const u8, len: c_int, w: *c_int, h: *c_int) ?[*]u8;
extern fn gwr_image_free(pixels: [*]u8) void;

/// What the app reports about itself: which build of the core is loaded.
pub fn writeVersion(w: *std.Io.Writer) !void {
    var s: std.json.Stringify = .{ .writer = w };
    try s.beginObject();
    try s.objectField("core");
    try s.write(version);
    try s.objectField("zig");
    try s.write(builtin.zig_version_string);
    try s.objectField("target");
    try s.print("\"{s}-{s}-{s}\"", .{ @tagName(builtin.cpu.arch), @tagName(builtin.os.tag), @tagName(builtin.abi) });
    try s.objectField("optimize");
    try s.write(@tagName(builtin.mode));
    try s.objectField("sqlite");
    try s.write(std.mem.span(sqlite3_libversion()));
    try s.endObject();
}

pub const Check = struct {
    name: []const u8,
    ok: bool,
    detail: []const u8,
};

pub const DiagnosticsInput = struct {
    /// A directory the core may write to: the app's cache dir.
    scratch_dir: []const u8,
    /// The manga folder, or null when the app has no all-files access
    /// yet (the check then reports that rather than failing to open it).
    books_dir: ?[]const u8,
};

/// Runs every check and writes `{"checks": [{name, ok, detail}, ...]}`.
/// A failing check is reported, never returned as an error: the screen
/// should show every result, not stop at the first.
pub fn writeDiagnostics(alloc: std.mem.Allocator, io: std.Io, in: DiagnosticsInput, w: *std.Io.Writer) !void {
    var arena_state: std.heap.ArenaAllocator = .init(alloc);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    const checks = [_]Check{
        sqliteCheck(arena),
        archiveCheck(arena, io, in.scratch_dir),
        booksCheck(arena, io, in.books_dir),
    };

    var s: std.json.Stringify = .{ .writer = w };
    try s.beginObject();
    try s.objectField("checks");
    try s.write(checks);
    try s.endObject();
}

// ---- sqlite ---------------------------------------------------------------

const sqlite_sample = "𠮷野家で食べた";

fn sqliteCheck(arena: std.mem.Allocator) Check {
    const detail = sqliteRoundTrip(arena) catch |err| return .{
        .name = "sqlite",
        .ok = false,
        .detail = std.fmt.allocPrint(arena, "failed: {s}", .{@errorName(err)}) catch "failed",
    };
    return .{ .name = "sqlite", .ok = true, .detail = detail };
}

fn sqliteRoundTrip(arena: std.mem.Allocator) ![]const u8 {
    var db = try sqlite.Db.open(":memory:", sqlite.OPEN_READWRITE | sqlite.OPEN_CREATE);
    defer db.close();
    try db.exec("CREATE TABLE t (term TEXT)");

    const insert = try db.prepare("INSERT INTO t (term) VALUES (?1)");
    defer insert.finalize();
    try insert.bindText(1, sqlite_sample);
    _ = try insert.step();

    const select = try db.prepare("SELECT term FROM t");
    defer select.finalize();
    if (!try select.step()) return error.SqliteSelect;
    const got = select.columnText(0);
    if (!std.mem.eql(u8, got, sqlite_sample)) return error.SqliteMismatch;

    return std.fmt.allocPrint(arena, "SQLite {s}, read back \"{s}\"", .{ std.mem.span(sqlite3_libversion()), got });
}

// ---- archive --------------------------------------------------------------

fn archiveCheck(arena: std.mem.Allocator, io: std.Io, scratch_dir: []const u8) Check {
    const detail = archiveRoundTrip(arena, io, scratch_dir) catch |err| return .{
        .name = "archive",
        .ok = false,
        .detail = std.fmt.allocPrint(arena, "failed: {s}", .{@errorName(err)}) catch "failed",
    };
    return .{ .name = "archive", .ok = true, .detail = detail };
}

fn archiveRoundTrip(arena: std.mem.Allocator, io: std.Io, scratch_dir: []const u8) ![]const u8 {
    var dir = try std.Io.Dir.cwd().openDir(io, scratch_dir, .{});
    defer dir.close(io);
    try dir.writeFile(io, .{ .sub_path = probe_name, .data = probe_cbz });

    const path = try std.fs.path.join(arena, &.{ scratch_dir, probe_name });
    const archive = try yomi.archive.open(arena, io, path);
    defer archive.deinit();
    if (archive.count() != 2) return error.WrongPageCount;

    const sidecar = (try archive.readMokuro(arena)) orelse return error.MissingMokuro;
    var volume = try yomi.mokuro.parse(arena, sidecar);
    defer volume.deinit();
    const ocr_page = volume.pageFor(archive.pages.items[0].name) orelse return error.MissingOcrPage;

    const png = try archive.readPage(arena, 0);
    var w: c_int = 0;
    var h: c_int = 0;
    const pixels = gwr_image_decode(png.ptr, @intCast(png.len), &w, &h) orelse return error.ImageDecode;
    gwr_image_free(pixels);
    // 001.png is 3x2; anything else means the page order or the decode is wrong.
    if (w != 3 or h != 2) return error.WrongImageSize;

    return std.fmt.allocPrint(arena, "{d} pages, {d} OCR blocks on {s}, decoded to {d}x{d}", .{
        archive.count(), ocr_page.blocks.len, archive.pages.items[0].name, w, h,
    });
}

// ---- books ----------------------------------------------------------------

fn booksCheck(arena: std.mem.Allocator, io: std.Io, books_dir: ?[]const u8) Check {
    const path = books_dir orelse return .{
        .name = "books",
        .ok = false,
        .detail = "no all-files access yet",
    };
    const detail = listBooks(arena, io, path) catch |err| return .{
        .name = "books",
        .ok = false,
        .detail = std.fmt.allocPrint(arena, "{s}: {s}", .{ path, @errorName(err) }) catch "failed",
    };
    return .{ .name = "books", .ok = true, .detail = detail };
}

fn listBooks(arena: std.mem.Allocator, io: std.Io, path: []const u8) ![]const u8 {
    var dir = try std.Io.Dir.cwd().openDir(io, path, .{ .iterate = true });
    defer dir.close(io);

    var archives: usize = 0;
    var folders: usize = 0;
    var sidecars: usize = 0;
    var it = dir.iterate();
    while (try it.next(io)) |entry| {
        if (entry.name.len == 0 or entry.name[0] == '.') continue;
        if (entry.kind == .directory) {
            folders += 1;
        } else if (endsWithIgnoreCase(entry.name, ".cbz") or endsWithIgnoreCase(entry.name, ".zip")) {
            archives += 1;
        } else if (endsWithIgnoreCase(entry.name, ".mokuro")) {
            sidecars += 1;
        }
    }
    return std.fmt.allocPrint(arena, "{s}: {d} archives, {d} folders, {d} .mokuro files", .{ path, archives, folders, sidecars });
}

fn endsWithIgnoreCase(name: []const u8, suffix: []const u8) bool {
    return name.len >= suffix.len and std.ascii.eqlIgnoreCase(name[name.len - suffix.len ..], suffix);
}

// ---- tests ----------------------------------------------------------------

test "every diagnostic passes on the host" {
    const alloc = std.testing.allocator;
    const io = std.testing.io;

    var tmp = std.testing.tmpDir(.{ .iterate = true });
    defer tmp.cleanup();
    var path_buf: [std.fs.max_path_bytes]u8 = undefined;
    const tmp_path = path_buf[0..try tmp.dir.realPath(io, &path_buf)];

    var out: std.Io.Writer.Allocating = .init(alloc);
    defer out.deinit();
    // The scratch directory doubles as a "books" folder holding probe.cbz.
    try writeDiagnostics(alloc, io, .{ .scratch_dir = tmp_path, .books_dir = tmp_path }, &out.writer);

    const Report = struct { checks: []const Check };
    const parsed = try std.json.parseFromSlice(Report, alloc, out.written(), .{});
    defer parsed.deinit();
    try std.testing.expectEqual(3, parsed.value.checks.len);
    for (parsed.value.checks) |check| {
        if (!check.ok) std.debug.print("{s}: {s}\n", .{ check.name, check.detail });
        try std.testing.expect(check.ok);
    }
    try std.testing.expect(std.mem.indexOf(u8, parsed.value.checks[2].detail, "1 archives") != null);
}

test "books check reports missing access instead of failing" {
    const check = booksCheck(std.testing.allocator, std.testing.io, null);
    try std.testing.expect(!check.ok);
    try std.testing.expectEqualStrings("no all-files access yet", check.detail);
}

test "version names the core and sqlite" {
    var out: std.Io.Writer.Allocating = .init(std.testing.allocator);
    defer out.deinit();
    try writeVersion(&out.writer);
    try std.testing.expect(std.mem.indexOf(u8, out.written(), "\"core\":\"0.1.0\"") != null);
    try std.testing.expect(std.mem.indexOf(u8, out.written(), "\"sqlite\":\"3.") != null);
}
