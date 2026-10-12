// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! The library screen's listing of one directory under the books folder.
//!
//! gw-read takes a book path on its command line and never needed this;
//! on a tablet you browse to a book. Manga collections are usually
//! nested (`Manga/<series>/<volume>`), so a directory is shown one of two
//! ways:
//!
//! - **a book**, when it holds page images itself -- the shape mokuro
//!   leaves a volume in (`Vol1/` with `Vol1.mokuro` beside it);
//! - **a folder** to browse into, otherwise.
//!
//! Archives (`.cbz`/`.zip`, plus `.cbr`/`.cb7`, which need an external
//! extractor gw-yomi's `archive.zig` can't run on Android) are always
//! books. `.mokuro` sidecars and hidden files aren't listed: a sidecar is
//! found from its book, never opened on its own.
//!
//! Entries come back in gw-yomi's natural page order (`Vol2` before
//! `Vol10`), folders first.

const std = @import("std");
const yomi = @import("gw_yomi");

pub const Kind = enum { folder, book, archive };

pub const Entry = struct {
    name: []const u8,
    path: []const u8,
    kind: Kind,
    /// Whether a `<name>.mokuro` sits beside the entry. A `.cbz` may also
    /// carry its sidecar inside, which only opening it reveals.
    ocr: bool,
};

/// How many entries of a subdirectory are looked at before deciding it
/// holds no pages. A volume's images are normally all there is in it.
const max_probe_entries = 64;

pub fn list(arena: std.mem.Allocator, io: std.Io, path: []const u8) ![]Entry {
    var dir = try std.Io.Dir.cwd().openDir(io, path, .{ .iterate = true });
    defer dir.close(io);

    var names: std.ArrayList([]const u8) = .empty;
    var kinds: std.ArrayList(std.Io.File.Kind) = .empty;
    var it = dir.iterate();
    while (try it.next(io)) |raw| {
        if (raw.name.len == 0 or raw.name[0] == '.') continue;
        try names.append(arena, try arena.dupe(u8, raw.name));
        try kinds.append(arena, raw.kind);
    }

    var entries: std.ArrayList(Entry) = .empty;
    for (names.items, kinds.items) |name, kind| {
        const full = try std.fs.path.join(arena, &.{ path, name });
        const entry_kind: Kind = switch (kind) {
            .directory => if (holdsPages(io, dir, name)) .book else .folder,
            else => if (isArchiveName(name)) .archive else continue,
        };
        // mokuro names the sidecar after the volume: `Vol1/` gets
        // `Vol1.mokuro` (a directory's name is used whole, so `Vol 1.5/`
        // keeps its `.5`) and `Vol1.cbz` gets the same.
        const base = if (kind == .directory) name else stem(name);
        const sidecar = try std.mem.concat(arena, u8, &.{ base, ".mokuro" });
        try entries.append(arena, .{
            .name = name,
            .path = full,
            .kind = entry_kind,
            .ocr = entry_kind != .folder and contains(names.items, sidecar),
        });
    }

    std.mem.sort(Entry, entries.items, {}, lessThan);
    return entries.items;
}

pub fn writeJson(alloc: std.mem.Allocator, io: std.Io, path: []const u8, w: *std.Io.Writer) !void {
    var arena_state: std.heap.ArenaAllocator = .init(alloc);
    defer arena_state.deinit();
    const entries = try list(arena_state.allocator(), io, path);

    var s: std.json.Stringify = .{ .writer = w };
    try s.beginObject();
    try s.objectField("path");
    try s.write(path);
    try s.objectField("entries");
    try s.write(entries);
    try s.endObject();
}

fn lessThan(_: void, a: Entry, b: Entry) bool {
    const a_folder = a.kind == .folder;
    const b_folder = b.kind == .folder;
    if (a_folder != b_folder) return a_folder;
    return yomi.pages.lessThan({}, a.name, b.name);
}

fn holdsPages(io: std.Io, parent: std.Io.Dir, name: []const u8) bool {
    var sub = parent.openDir(io, name, .{ .iterate = true }) catch return false;
    defer sub.close(io);
    var it = sub.iterate();
    var seen: usize = 0;
    while (it.next(io) catch null) |raw| : (seen += 1) {
        if (seen >= max_probe_entries) break;
        if (raw.kind == .file and yomi.pages.isPage(raw.name)) return true;
    }
    return false;
}

pub fn isArchiveName(name: []const u8) bool {
    const ext = std.fs.path.extension(name);
    const archives = [_][]const u8{ ".cbz", ".zip", ".cbr", ".cb7" };
    for (archives) |a| {
        if (std.ascii.eqlIgnoreCase(ext, a)) return true;
    }
    return false;
}

fn stem(name: []const u8) []const u8 {
    const ext = std.fs.path.extension(name);
    return name[0 .. name.len - ext.len];
}

fn contains(names: []const []const u8, wanted: []const u8) bool {
    for (names) |n| {
        if (std.mem.eql(u8, n, wanted)) return true;
    }
    return false;
}
