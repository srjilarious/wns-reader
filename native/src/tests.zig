// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! Host tests for wns-reader's own Zig: the JNI layer's modules, run
//! against the fixtures in testdata/. gw-yomi tests itself.

const std = @import("std");
const diag = @import("diag.zig");
const library = @import("library.zig");
const Book = @import("book.zig").Book;
const dictionaries = @import("dictionaries.zig");

const probe_cbz = @embedFile("probe_cbz");
const mini_dict_zip = @embedFile("mini_dict_zip");
const mini_freq_zip = @embedFile("mini_freq_zip");

test {
    _ = diag;
}

/// A scratch directory and its absolute path.
const Scratch = struct {
    tmp: std.testing.TmpDir,
    path_buf: [std.fs.max_path_bytes]u8 = undefined,
    path: []const u8 = "",

    fn init(self: *Scratch) !void {
        self.tmp = std.testing.tmpDir(.{ .iterate = true });
        self.path = self.path_buf[0..try self.tmp.dir.realPath(std.testing.io, &self.path_buf)];
    }

    fn join(self: *const Scratch, alloc: std.mem.Allocator, name: []const u8) ![]u8 {
        return std.fs.path.join(alloc, &.{ self.path, name });
    }
};


test "library lists folders first, then books and archives, in natural order" {
    const io = std.testing.io;
    var arena_state: std.heap.ArenaAllocator = .init(std.testing.allocator);
    defer arena_state.deinit();
    const arena = arena_state.allocator();

    var scratch: Scratch = .{ .tmp = undefined };
    try scratch.init();
    defer scratch.tmp.cleanup();
    const d = scratch.tmp.dir;
    try d.createDirPath(io, "Series");
    try d.createDirPath(io, "Vol10");
    try d.createDirPath(io, "Vol2");
    try d.writeFile(io, .{ .sub_path = "Vol2/001.png", .data = "x" });
    try d.writeFile(io, .{ .sub_path = "Vol10/001.png", .data = "x" });
    try d.writeFile(io, .{ .sub_path = "Vol2.mokuro", .data = "{}" });
    try d.writeFile(io, .{ .sub_path = "Extra.cbz", .data = probe_cbz });
    try d.writeFile(io, .{ .sub_path = "notes.txt", .data = "" });
    try d.writeFile(io, .{ .sub_path = ".hidden.cbz", .data = "" });

    const entries = try library.list(arena, io, scratch.path);
    try std.testing.expectEqual(4, entries.len);
    try std.testing.expectEqualStrings("Series", entries[0].name);
    try std.testing.expectEqual(library.Kind.folder, entries[0].kind);
    try std.testing.expectEqualStrings("Extra.cbz", entries[1].name);
    try std.testing.expectEqual(library.Kind.archive, entries[1].kind);
    try std.testing.expectEqualStrings("Vol2", entries[2].name);
    try std.testing.expectEqual(library.Kind.book, entries[2].kind);
    try std.testing.expect(entries[2].ocr);
    try std.testing.expectEqualStrings("Vol10", entries[3].name);
    try std.testing.expect(!entries[3].ocr);
}

test "a book reports its pages, its OCR text and the bubble under a point" {
    const alloc = std.testing.allocator;
    const io = std.testing.io;

    var scratch: Scratch = .{ .tmp = undefined };
    try scratch.init();
    defer scratch.tmp.cleanup();
    try scratch.tmp.dir.writeFile(io, .{ .sub_path = "probe.cbz", .data = probe_cbz });
    const path = try scratch.join(alloc, "probe.cbz");
    defer alloc.free(path);

    const book = try Book.open(alloc, io, path);
    defer book.close();

    var info: std.Io.Writer.Allocating = .init(alloc);
    defer info.deinit();
    try book.writeInfo(7, &info.writer);
    try std.testing.expectEqualStrings(
        \\{"book":7,"title":"probe","pages":["001.png","002.png"],"ocr":true,"ocr_pages":1}
    , info.written());

    var text: std.Io.Writer.Allocating = .init(alloc);
    defer text.deinit();
    try book.writePageText(alloc, 0, &text.writer);
    const PageText = struct {
        width: u32,
        height: u32,
        blocks: []const struct { box: [4]i64, vertical: bool, text: []const u8 },
        order: []const usize,
    };
    const parsed = try std.json.parseFromSlice(PageText, alloc, text.written(), .{});
    defer parsed.deinit();
    try std.testing.expectEqual(2, parsed.value.blocks.len);
    // The bubble's lines joined back into running text.
    try std.testing.expectEqualStrings("食べなかったです", parsed.value.blocks[0].text);
    // Right to left: the right-hand bubble first.
    try std.testing.expectEqualSlices(usize, &.{ 1, 0 }, parsed.value.order);

    try std.testing.expectEqual(@as(?usize, 1), book.blockAt(0, 2, 0));
    try std.testing.expectEqual(@as(?usize, null), book.blockAt(1, 0, 0));

    const png = try book.readPage(alloc, 1);
    defer alloc.free(png);
    try std.testing.expect(std.mem.startsWith(u8, png, "\x89PNG"));
}

const LookupResult = struct {
    found: bool,
    start: usize = 0,
    groups: []const struct {
        term: []const u8,
        reading: []const u8,
        length: usize,
        reason: ?[]const u8,
        frequency: ?[]const u8,
        tags: []const struct { label: []const u8, category: []const u8 },
        senses: []const struct { text: []const u8, tags: []const struct { label: []const u8, category: []const u8 } },
        spellings: []const struct { term: []const u8, reading: []const u8 },
    } = &.{},
};

fn installZip(alloc: std.mem.Allocator, io: std.Io, scratch: *const Scratch, name: []const u8, bytes: []const u8, root: []const u8) !dictionaries.Slot {
    try scratch.tmp.dir.writeFile(io, .{ .sub_path = name, .data = bytes });
    const zip = try scratch.join(alloc, name);
    defer alloc.free(zip);
    const inst = try dictionaries.Install.begin(alloc, io, zip, root);
    defer inst.deinit();
    var steps: usize = 0;
    while (inst.phase != .done) : (steps += 1) {
        try inst.step();
        try std.testing.expect(steps < 100);
    }
    return inst.slot;
}

test "installing Yomitan zips, then a deinflecting lookup ranked by frequency" {
    const alloc = std.testing.allocator;
    const io = std.testing.io;

    var scratch: Scratch = .{ .tmp = undefined };
    try scratch.init();
    defer scratch.tmp.cleanup();
    const root = try scratch.join(alloc, "dictionaries");
    defer alloc.free(root);

    try std.testing.expectEqual(dictionaries.Slot.terms, try installZip(alloc, io, &scratch, "mini-dict.zip", mini_dict_zip, root));
    try std.testing.expectEqual(dictionaries.Slot.frequency, try installZip(alloc, io, &scratch, "mini-freq.zip", mini_freq_zip, root));

    // Only the index is kept; the staging JSON is gone.
    var root_dir = try std.Io.Dir.cwd().openDir(io, root, .{ .iterate = true });
    defer root_dir.close(io);
    try root_dir.access(io, "terms/index.sqlite3", .{});
    try std.testing.expectError(error.FileNotFound, root_dir.access(io, ".staging", .{}));

    var lib: dictionaries.Library = .{ .alloc = alloc, .io = io };
    lib.reopen(root, .rank);
    defer lib.closeSlot(.terms);
    defer lib.closeSlot(.frequency);

    var status: std.Io.Writer.Allocating = .init(alloc);
    defer status.deinit();
    try lib.writeStatus(&status.writer);
    try std.testing.expectEqualStrings(
        \\{"terms":{"installed":true,"title":"Mini Dictionary"},"frequency":{"installed":true,"title":"Mini Frequency"},"order":"rank"}
    , status.written());

    // "食べなかったです", looked up from its first character: the whole
    // thing is 食べる's polite negative past.
    var out: std.Io.Writer.Allocating = .init(alloc);
    defer out.deinit();
    try lib.writeLookup(alloc, "食べなかったです", 0, &out.writer);
    const parsed = try std.json.parseFromSlice(LookupResult, alloc, out.written(), .{});
    defer parsed.deinit();
    const result = parsed.value;
    try std.testing.expect(result.found);
    const top = result.groups[0];
    try std.testing.expectEqualStrings("食べる", top.term);
    try std.testing.expectEqualStrings("たべる", top.reading);
    try std.testing.expectEqual(8, top.length);
    try std.testing.expect(std.mem.indexOf(u8, top.reason.?, "negative") != null);
    try std.testing.expectEqualStrings("500", top.frequency.?);
    try std.testing.expectEqualStrings("to eat", top.senses[0].text);

    // Offsets are codepoints: 今日 starts at the 9th character of this text.
    out.clearRetainingCapacity();
    try lib.writeLookup(alloc, "食べなかった今日は", 6, &out.writer);
    const second = try std.json.parseFromSlice(LookupResult, alloc, out.written(), .{});
    defer second.deinit();
    try std.testing.expectEqual(6, second.value.start);
    try std.testing.expectEqualStrings("今日", second.value.groups[0].term);
    try std.testing.expectEqual(2, second.value.groups[0].length);
}

test "lookup with no dictionary says so instead of failing" {
    var lib: dictionaries.Library = .{ .alloc = std.testing.allocator, .io = std.testing.io };
    var out: std.Io.Writer.Allocating = .init(std.testing.allocator);
    defer out.deinit();
    try lib.writeLookup(std.testing.allocator, "食べる", 0, &out.writer);
    try std.testing.expectEqualStrings(
        \\{"found":false,"no_dictionary":true}
    , out.written());
}

test "a zip that isn't a Yomitan dictionary is refused" {
    const alloc = std.testing.allocator;
    const io = std.testing.io;
    var scratch: Scratch = .{ .tmp = undefined };
    try scratch.init();
    defer scratch.tmp.cleanup();
    try scratch.tmp.dir.writeFile(io, .{ .sub_path = "book.cbz", .data = probe_cbz });
    const zip = try scratch.join(alloc, "book.cbz");
    defer alloc.free(zip);
    try std.testing.expectError(error.NotAYomitanDictionary, dictionaries.Install.begin(alloc, io, zip, scratch.path));
}

test "codepoint offsets convert to byte offsets" {
    try std.testing.expectEqual(0, dictionaries.codepointToByte("𠮷野家", 0));
    try std.testing.expectEqual(4, dictionaries.codepointToByte("𠮷野家", 1));
    try std.testing.expectEqual(10, dictionaries.codepointToByte("𠮷野家", 9));
    try std.testing.expectEqual(3, dictionaries.codepointCount("𠮷野家"));
}

// A real dictionary, when WNS_TEST_DICTIONARY names its zip: installs it
// and looks up a conjugated verb. Skipped otherwise -- Jitendex is ~40 MB
// and takes a while to index.
//
//   WNS_TEST_DICTIONARY=~/Downloads/jitendex-yomitan.zip zig build test -Doptimize=ReleaseSafe
test "a real Yomitan dictionary installs and deinflects (opt-in)" {
    const alloc = std.testing.allocator;
    const io = std.testing.io;
    const zip = std.testing.environ.getAlloc(alloc, "WNS_TEST_DICTIONARY") catch return error.SkipZigTest;
    defer alloc.free(zip);

    var scratch: Scratch = .{ .tmp = undefined };
    try scratch.init();
    defer scratch.tmp.cleanup();
    const root = try scratch.join(alloc, "dictionaries");
    defer alloc.free(root);

    const started = std.Io.Clock.awake.now(io);
    const inst = try dictionaries.Install.begin(alloc, io, zip, root);
    var steps: usize = 0;
    while (inst.phase != .done) : (steps += 1) try inst.step();
    const slot = inst.slot;
    inst.deinit();
    const took = started.durationTo(std.Io.Clock.awake.now(io));
    std.debug.print("\ninstalled {s} as {s} in {d} steps, {d} ms\n", .{ zip, @tagName(slot), steps, took.toMilliseconds() });

    var lib: dictionaries.Library = .{ .alloc = alloc, .io = io };
    lib.reopen(root, .rank);
    defer lib.closeSlot(.terms);
    var out: std.Io.Writer.Allocating = .init(alloc);
    defer out.deinit();
    try lib.writeLookup(alloc, "食べなかったです", 0, &out.writer);
    const parsed = try std.json.parseFromSlice(LookupResult, alloc, out.written(), .{ .ignore_unknown_fields = true });
    defer parsed.deinit();
    std.debug.print("top hit: {s} ({s}) -- {s}\n", .{ parsed.value.groups[0].term, parsed.value.groups[0].reason orelse "-", parsed.value.groups[0].senses[0].text });
    try std.testing.expectEqualStrings("食べる", parsed.value.groups[0].term);
}
