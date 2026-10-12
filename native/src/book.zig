// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! An open book as the reader screen sees it: gw-yomi's `Archive` for the
//! pages and its parsed mokuro `Volume` for the text on them.
//!
//! The page *images* are handed to Kotlin as raw file bytes and decoded
//! there (`BitmapFactory`), not decoded here: Android's decoder is
//! hardware-assisted and its bitmaps are what Compose draws, so a Zig
//! decode would only add a copy. Everything about the text -- which
//! bubble a tap landed in, reading order, joining a bubble's lines --
//! is gw-yomi's, exactly as gw-read does it.
//!
//! A `Book` is used from Kotlin's IO threads, and the zip reader inside
//! `Archive` is a single seekable stream, so every call takes `mutex`.

const std = @import("std");
const yomi = @import("gw_yomi");
const mokuro = yomi.mokuro;

pub const Book = struct {
    alloc: std.mem.Allocator,
    io: std.Io,
    mutex: std.Io.Mutex = .init,
    archive: *yomi.archive.Archive,
    /// Null when the book has no sidecar, or it didn't parse.
    volume: ?mokuro.Volume,

    pub fn open(alloc: std.mem.Allocator, io: std.Io, path: []const u8) !*Book {
        const archive = try yomi.archive.open(alloc, io, path);
        errdefer archive.deinit();

        // A sidecar that can't be read or parsed costs the book its text,
        // not the book itself -- the same rule gw-read follows.
        var volume: ?mokuro.Volume = null;
        if (archive.readMokuro(alloc) catch null) |bytes| {
            defer alloc.free(bytes);
            volume = mokuro.parse(alloc, bytes) catch null;
        }
        errdefer if (volume) |*v| v.deinit();

        const self = try alloc.create(Book);
        self.* = .{ .alloc = alloc, .io = io, .archive = archive, .volume = volume };
        return self;
    }

    pub fn close(self: *Book) void {
        if (self.volume) |*v| v.deinit();
        self.archive.deinit();
        self.alloc.destroy(self);
    }

    /// `{book: id, title, pages: [name...], ocr, ocr_pages}`, `id` being
    /// the handle Kotlin will refer to the book by.
    pub fn writeInfo(self: *Book, id: i32, w: *std.Io.Writer) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);

        var s: std.json.Stringify = .{ .writer = w };
        try s.beginObject();
        try s.objectField("book");
        try s.write(id);
        try s.objectField("title");
        try s.write(bookTitle(self.archive.path));
        try s.objectField("pages");
        try s.beginArray();
        for (self.archive.pages.items) |p| try s.write(p.name);
        try s.endArray();
        try s.objectField("ocr");
        try s.write(self.volume != null);
        try s.objectField("ocr_pages");
        try s.write(if (self.volume) |*v| v.pagesWithText() else 0);
        try s.endObject();
    }

    /// The page image's bytes, as stored in the archive. Caller owns them.
    pub fn readPage(self: *Book, alloc: std.mem.Allocator, index: usize) ![]u8 {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        return self.archive.readPage(alloc, index);
    }

    /// The OCR for page `index`:
    /// `{width, height, blocks: [{box: [x1,y1,x2,y2], vertical, text}], order: [...]}`.
    /// `width`/`height` are the image size mokuro measured, which the box
    /// coordinates are in. A page with no OCR has no blocks.
    pub fn writePageText(self: *Book, alloc: std.mem.Allocator, index: usize, w: *std.Io.Writer) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);

        var s: std.json.Stringify = .{ .writer = w };
        try s.beginObject();
        const page = self.pageOcr(index) orelse {
            try s.objectField("blocks");
            try s.beginArray();
            try s.endArray();
            try s.endObject();
            return;
        };

        try s.objectField("width");
        try s.write(page.img_width);
        try s.objectField("height");
        try s.write(page.img_height);

        try s.objectField("blocks");
        try s.beginArray();
        for (page.blocks) |block| {
            const text = try mokuro.joinLines(alloc, block.lines);
            defer alloc.free(text);
            try s.beginObject();
            try s.objectField("box");
            try s.write([4]i64{ block.box.x1, block.box.y1, block.box.x2, block.box.y2 });
            try s.objectField("vertical");
            try s.write(block.vertical);
            try s.objectField("text");
            try s.write(text);
            try s.endObject();
        }
        try s.endArray();

        // Manga reads right to left; the reader has no LTR setting yet.
        const order_buf = try alloc.alloc(usize, page.blocks.len);
        defer alloc.free(order_buf);
        const bands = try alloc.alloc(u32, page.blocks.len);
        defer alloc.free(bands);
        try s.objectField("order");
        try s.write(mokuro.readingOrder(page, .rtl, order_buf, bands));

        try s.endObject();
    }

    /// The bubble under image pixel (`x`, `y`) of page `index`, or null.
    /// Nested boxes resolve to the smallest, as in gw-read.
    pub fn blockAt(self: *Book, index: usize, x: i64, y: i64) ?usize {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const page = self.pageOcr(index) orelse return null;
        return mokuro.blockAt(page, x, y);
    }

    fn pageOcr(self: *Book, index: usize) ?*const mokuro.Page {
        const volume = &(self.volume orelse return null);
        if (index >= self.archive.pages.items.len) return null;
        return volume.pageFor(self.archive.pages.items[index].name);
    }
};

/// The book's name for the title bar: the last path component, with an
/// archive extension dropped.
fn bookTitle(path: []const u8) []const u8 {
    const base = std.fs.path.basename(std.mem.trimEnd(u8, path, "/"));
    const ext = std.fs.path.extension(base);
    const archives = [_][]const u8{ ".cbz", ".cbr", ".cb7", ".zip" };
    for (archives) |a| {
        if (std.ascii.eqlIgnoreCase(ext, a)) return base[0 .. base.len - ext.len];
    }
    return base;
}
