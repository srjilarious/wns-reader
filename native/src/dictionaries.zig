// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! The app's dictionaries: installing a Yomitan zip, opening what's
//! installed, and looking words up -- gw-yomi's `dict` underneath.
//!
//! **Two slots, as in gw-read.** One term dictionary (Jitendex) and an
//! optional frequency list (JPDB, BCCWJ, ...); gw-read's `dictionary` and
//! `frequency_dictionary`. Which slot a zip goes in is read off its
//! contents: term banks make it a term dictionary, frequency banks alone
//! a frequency list.
//!
//! **Installed means one SQLite file.** gw-read points at a directory the
//! user unzipped by hand and builds `index.sqlite3` beside the JSON. Here
//! the zip is unpacked into a staging directory in app storage, indexed
//! there, and only `index.sqlite3` is kept, in `<root>/<slot>/`; the JSON
//! is deleted. Jitendex's term banks are several hundred MB that nothing
//! reads again once indexed. Opening an installed slot is then
//! `dict.openOrBeginBuild` finding a finished build.
//!
//! **Installing is stepped** so Kotlin can show progress: `Install.step`
//! does one small unit -- a few zip entries, or one bank file through
//! `dict.Builder` -- and reports where it is.

const std = @import("std");
const yomi = @import("gw_yomi");
const dict = yomi.dict;

pub const Slot = enum {
    terms,
    frequency,

    fn dirName(self: Slot) []const u8 {
        return @tagName(self);
    }
};

/// Where an `Install` stages its unpacked JSON, under the root.
const staging_dir_name = ".staging";

/// Zip entries extracted per `Install.step`. Term banks are a few MB
/// each, so this keeps a step well under a second on the tablet.
const entries_per_step = 4;

// ---- open dictionaries ------------------------------------------------------

/// The open dictionaries. Lookups and installs come from different Kotlin
/// threads, and a `dict.Dict` reuses its prepared statements, so every
/// use goes through `mutex`.
pub const Library = struct {
    alloc: std.mem.Allocator,
    io: std.Io,
    mutex: std.Io.Mutex = .init,
    terms: ?dict.Dict = null,
    frequency: ?dict.Dict = null,
    order: dict.FrequencyOrder = .rank,

    /// Closes whatever is open and opens what's installed under `root`.
    pub fn reopen(self: *Library, root: []const u8, order: dict.FrequencyOrder) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        self.closeLocked();
        self.order = order;
        self.terms = openSlot(self.alloc, self.io, root, .terms);
        self.frequency = openSlot(self.alloc, self.io, root, .frequency);
    }

    /// Closes one slot, before an install replaces its file.
    pub fn closeSlot(self: *Library, slot: Slot) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const d = switch (slot) {
            .terms => &self.terms,
            .frequency => &self.frequency,
        };
        if (d.*) |*open| open.deinit();
        d.* = null;
    }

    fn closeLocked(self: *Library) void {
        if (self.terms) |*d| d.deinit();
        if (self.frequency) |*d| d.deinit();
        self.terms = null;
        self.frequency = null;
    }

    /// `{terms: {installed, title}, frequency: {installed, title}, order}`.
    pub fn writeStatus(self: *Library, w: *std.Io.Writer) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);

        var s: std.json.Stringify = .{ .writer = w };
        try s.beginObject();
        inline for (.{ .{ "terms", &self.terms }, .{ "frequency", &self.frequency } }) |slot| {
            try s.objectField(slot[0]);
            try s.beginObject();
            try s.objectField("installed");
            try s.write(slot[1].* != null);
            try s.objectField("title");
            try s.write(if (slot[1].*) |*d| d.title else "");
            try s.endObject();
        }
        try s.objectField("order");
        try s.write(@tagName(self.order));
        try s.endObject();
    }

    /// Looks up what `text` could start with at codepoint `at`.
    ///
    /// Offsets cross the JNI boundary in codepoints, never bytes or
    /// UTF-16 units: Kotlin counts UTF-16, Zig works in UTF-8 bytes, and
    /// a codepoint means the same character to both.
    ///
    /// Writes `{found: false}`, or `{found: true, start, groups: [...]}`
    /// where each group is one word (gw-yomi's `Match.groups`) with the
    /// fields its best hit supplies -- see `writeHit`.
    pub fn writeLookup(self: *Library, alloc: std.mem.Allocator, text: []const u8, at: usize, w: *std.Io.Writer) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);

        var s: std.json.Stringify = .{ .writer = w };
        const terms = &(self.terms orelse {
            try s.write(.{ .found = false, .no_dictionary = true });
            return;
        });
        const start = codepointToByte(text, at);
        const match = (try dict.lookup(
            alloc,
            terms,
            if (self.frequency) |*f| f else null,
            self.order,
            text[start..],
        )) orelse {
            try s.write(.{ .found = false });
            return;
        };
        defer match.deinit(alloc);

        try s.beginObject();
        try s.objectField("found");
        try s.write(true);
        try s.objectField("start");
        try s.write(at);
        try s.objectField("groups");
        try s.beginArray();
        for (match.groups) |g| {
            const primary = match.hits[g.primary];
            try s.beginObject();
            try writeHit(&s, text[start..], primary);
            try s.objectField("spellings");
            try s.beginArray();
            for (g.others) |o| {
                const other = match.hits[o].entry;
                try s.write(.{ .term = other.term, .reading = other.reading });
            }
            try s.endArray();
            try s.endObject();
        }
        try s.endArray();
        try s.endObject();
    }
};

/// One hit's fields: `term`, `reading`, `length` (codepoints of the text
/// it covers), `reason` (the deinflection chain, or null), `frequency`
/// (the list's own display string, else the number, else null), `tags`
/// on the headword, and `senses`, each `{text, tags}`.
fn writeHit(s: *std.json.Stringify, text: []const u8, hit: dict.Hit) !void {
    const e = hit.entry;
    try s.objectField("term");
    try s.write(e.term);
    try s.objectField("reading");
    try s.write(e.reading);
    try s.objectField("length");
    try s.write(codepointCount(text[0..@min(hit.source_len, text.len)]));
    try s.objectField("reason");
    try s.write(hit.reason);
    try s.objectField("frequency");
    if (hit.frequency_display.len > 0) {
        try s.write(hit.frequency_display);
    } else if (hit.frequency) |f| {
        try s.print("\"{d}\"", .{f});
    } else {
        try s.write(null);
    }
    try s.objectField("tags");
    try writeTags(s, e.tags);
    try s.objectField("senses");
    try s.beginArray();
    for (e.glossary, 0..) |gloss, i| {
        try s.beginObject();
        try s.objectField("text");
        try s.write(gloss);
        try s.objectField("tags");
        try writeTags(s, if (i < e.sense_tags.len) e.sense_tags[i] else &.{});
        try s.endObject();
    }
    try s.endArray();
}

fn writeTags(s: *std.json.Stringify, tags: []const dict.Tag) !void {
    try s.beginArray();
    for (tags) |t| try s.write(.{ .label = t.label, .category = t.category });
    try s.endArray();
}

fn openSlot(alloc: std.mem.Allocator, io: std.Io, root: []const u8, slot: Slot) ?dict.Dict {
    const path = std.fs.path.join(alloc, &.{ root, slot.dirName() }) catch return null;
    defer alloc.free(path);
    // Only open a slot that has an index already: `openOrBeginBuild`
    // would otherwise create an empty one.
    var dir = std.Io.Dir.cwd().openDir(io, path, .{}) catch return null;
    defer dir.close(io);
    dir.access(io, dict.db_file_name, .{}) catch return null;

    switch (dict.openOrBeginBuild(alloc, io, path) catch |err| {
        std.log.warn("dictionary {s}: {s}", .{ path, @errorName(err) });
        return null;
    }) {
        .ready => |d| {
            var opened = d;
            // A term dictionary with no entries, or a frequency list with
            // no frequencies, is as good as none.
            const usable = switch (slot) {
                .terms => !dict.isEmpty(&opened),
                .frequency => dict.hasFrequency(&opened),
            };
            if (!usable) {
                opened.deinit();
                return null;
            }
            return opened;
        },
        // Built by an older schema, and the JSON it was built from is
        // gone: the slot needs installing again.
        .building => |b| {
            var builder = b;
            builder.deinit();
            std.log.warn("dictionary {s} needs reinstalling (index schema changed)", .{path});
            return null;
        },
    }
}

// ---- installing -------------------------------------------------------------

pub const Install = struct {
    alloc: std.mem.Allocator,
    io: std.Io,
    slot: Slot,
    root: []u8,
    staging: []u8,

    zip_file: std.Io.File,
    read_buf: []u8,
    reader: std.Io.File.Reader,
    /// The zip entries worth extracting: the index and the bank files.
    entries: std.ArrayList(std.zip.Iterator.Entry) = .empty,
    extracted: usize = 0,
    builder: ?dict.Builder = null,
    phase: Phase = .extracting,

    pub const Phase = enum { extracting, indexing, done };

    /// Opens the zip at `zip_path` and decides its slot. Heap-allocated:
    /// `reader` points into `read_buf` and the entries are read through it.
    pub fn begin(alloc: std.mem.Allocator, io: std.Io, zip_path: []const u8, root: []const u8) !*Install {
        const self = try alloc.create(Install);
        errdefer alloc.destroy(self);

        const zip_file = try std.Io.Dir.cwd().openFile(io, zip_path, .{ .mode = .read_only });
        errdefer zip_file.close(io);
        const read_buf = try alloc.alloc(u8, 64 * 1024);
        errdefer alloc.free(read_buf);

        self.* = .{
            .alloc = alloc,
            .io = io,
            .slot = .terms,
            .root = try alloc.dupe(u8, root),
            .staging = undefined,
            .zip_file = zip_file,
            .read_buf = read_buf,
            .reader = zip_file.reader(io, read_buf),
        };
        errdefer alloc.free(self.root);
        errdefer self.entries.deinit(alloc);

        var has_terms = false;
        var has_meta = false;
        var it = try std.zip.Iterator.init(&self.reader);
        var name_buf: [std.fs.max_path_bytes]u8 = undefined;
        while (try it.next()) |entry| {
            const name = entry.getFilename(&self.reader, &name_buf, .{}) catch continue;
            // Yomitan zips are flat; a nested file is someone's extra.
            if (std.mem.indexOfScalar(u8, name, '/') != null) continue;
            if (isBank(name, "term_bank_")) {
                has_terms = true;
            } else if (isBank(name, "term_meta_bank_")) {
                has_meta = true;
            } else if (!isBank(name, "tag_bank_") and !std.ascii.eqlIgnoreCase(name, "index.json")) {
                continue;
            }
            try self.entries.append(alloc, entry);
        }
        if (has_terms) {
            self.slot = .terms;
        } else if (has_meta) {
            self.slot = .frequency;
        } else {
            return error.NotAYomitanDictionary;
        }

        // A staging tree left by an interrupted install is stale.
        self.staging = try std.fs.path.join(alloc, &.{ root, staging_dir_name });
        errdefer alloc.free(self.staging);
        std.Io.Dir.cwd().deleteTree(io, self.staging) catch {};
        try std.Io.Dir.cwd().createDirPath(io, self.staging);
        return self;
    }

    pub fn deinit(self: *Install) void {
        if (self.builder) |*b| b.deinit();
        self.entries.deinit(self.alloc);
        self.zip_file.close(self.io);
        self.alloc.free(self.read_buf);
        std.Io.Dir.cwd().deleteTree(self.io, self.staging) catch {};
        self.alloc.free(self.staging);
        self.alloc.free(self.root);
        self.alloc.destroy(self);
    }

    /// One unit of work. Call until `phase` is `.done`.
    pub fn step(self: *Install) !void {
        switch (self.phase) {
            .extracting => try self.extractSome(),
            .indexing => {
                const b = &self.builder.?;
                if (!b.isDone()) return b.step();
                // `finish` consumes the builder and opens the result; the
                // install only wants the file, so close it straight away.
                var built = try b.finish();
                self.builder = null;
                built.deinit();
                try self.keepIndex();
                self.phase = .done;
            },
            .done => {},
        }
    }

    fn extractSome(self: *Install) !void {
        var staging = try std.Io.Dir.cwd().openDir(self.io, self.staging, .{});
        defer staging.close(self.io);
        var name_buf: [std.fs.max_path_bytes]u8 = undefined;
        const end = @min(self.extracted + entries_per_step, self.entries.items.len);
        for (self.entries.items[self.extracted..end]) |entry| {
            try entry.extract(&self.reader, .{}, &name_buf, staging);
        }
        self.extracted = end;
        if (self.extracted < self.entries.items.len) return;

        switch (try dict.openOrBeginBuild(self.alloc, self.io, self.staging)) {
            .building => |b| self.builder = b,
            // A fresh staging directory is never already built.
            .ready => |d| {
                var opened = d;
                opened.deinit();
                return error.UnexpectedBuiltIndex;
            },
        }
        self.phase = .indexing;
    }

    /// Moves the finished `index.sqlite3` into the slot's directory,
    /// replacing what was there.
    fn keepIndex(self: *Install) !void {
        const cwd = std.Io.Dir.cwd();
        const slot_dir = try std.fs.path.join(self.alloc, &.{ self.root, self.slot.dirName() });
        defer self.alloc.free(slot_dir);
        cwd.deleteTree(self.io, slot_dir) catch {};
        try cwd.createDirPath(self.io, slot_dir);

        const from = try std.fs.path.join(self.alloc, &.{ self.staging, dict.db_file_name });
        defer self.alloc.free(from);
        const to = try std.fs.path.join(self.alloc, &.{ slot_dir, dict.db_file_name });
        defer self.alloc.free(to);
        try std.Io.Dir.rename(cwd, from, cwd, to, self.io);
    }

    /// `{slot, phase, done, total}`: entries during extraction, bank
    /// files during indexing.
    pub fn writeProgress(self: *Install, w: *std.Io.Writer) !void {
        var done: usize = 0;
        var total: usize = 0;
        switch (self.phase) {
            .extracting => {
                done = self.extracted;
                total = self.entries.items.len;
            },
            .indexing => {
                const b = &self.builder.?;
                done = b.file_idx;
                total = b.totalFiles();
            },
            .done => {},
        }
        var s: std.json.Stringify = .{ .writer = w };
        try s.write(.{ .slot = @tagName(self.slot), .phase = @tagName(self.phase), .done = done, .total = total });
    }
};

fn isBank(name: []const u8, prefix: []const u8) bool {
    return std.mem.startsWith(u8, name, prefix) and std.ascii.endsWithIgnoreCase(name, ".json");
}

// ---- codepoints ---------------------------------------------------------------

/// Byte offset of codepoint `index` in `text`, clamped to its length. An
/// invalid byte counts as one codepoint, the way gw-yomi's mokuro helpers
/// count it.
pub fn codepointToByte(text: []const u8, index: usize) usize {
    var i: usize = 0;
    var n: usize = 0;
    while (i < text.len and n < index) : (n += 1) i = yomi.mokuro.charEnd(text, i);
    return i;
}

pub fn codepointCount(text: []const u8) usize {
    return yomi.mokuro.charCount(text);
}
