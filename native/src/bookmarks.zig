// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! Bookmarks: bubbles the reader wants to come back to, each with the
//! word that was looked up in it (if any), kept in the app's own SQLite
//! file (`wns-reader.sqlite3` in app storage) through gw-yomi's `sqlite`.
//!
//! **Keyed by the book's file name**, not its path: `Vol1.cbz` keeps its
//! bookmarks when it moves to another folder of /sdcard/Manga. Two books
//! with the same name in different folders share them, which is the
//! price of that.
//!
//! **One bookmark per bubble.** Saving a bubble that already has one
//! updates its word instead of adding a second.
//!
//! A bookmark keeps the bubble's text as well as its index, so the list
//! can show it and the reader can still find the bubble if the book's
//! `.mokuro` is regenerated and the indices shift.

const std = @import("std");
const sqlite = @import("gw_yomi").sqlite;

/// `bookmarks({book})`: the book's path (or its name).
pub const ListQuery = struct {
    book: []const u8,
};

/// `saveBookmark(...)`. `word_start` is the codepoint of the bubble text
/// the word's lookup started at; `word` is empty when none was looked up.
pub const Save = struct {
    book: []const u8,
    page: i64,
    block: i64,
    text: []const u8,
    word: []const u8 = "",
    word_start: i64 = 0,
};

pub const Delete = struct {
    id: i64,
};

/// The key a book's bookmarks are stored under: its file (or folder) name.
pub fn bookKey(path: []const u8) []const u8 {
    return std.fs.path.basenamePosix(path);
}

pub const Store = struct {
    alloc: std.mem.Allocator,
    io: std.Io,
    mutex: std.Io.Mutex = .init,
    db: ?sqlite.Db = null,

    /// Opens (creating if needed) the database at `path`.
    pub fn open(self: *Store, path: []const u8) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.db) |*db| db.close();
        self.db = null;
        const z = try self.alloc.dupeSentinel(u8, path, 0);
        defer self.alloc.free(z);
        var db = try sqlite.Db.open(z, sqlite.OPEN_READWRITE | sqlite.OPEN_CREATE);
        errdefer db.close();
        try db.exec(
            \\CREATE TABLE IF NOT EXISTS bookmarks (
            \\  id INTEGER PRIMARY KEY,
            \\  book TEXT NOT NULL,
            \\  page INTEGER NOT NULL,
            \\  block INTEGER NOT NULL,
            \\  text TEXT NOT NULL,
            \\  word TEXT NOT NULL DEFAULT '',
            \\  word_start INTEGER NOT NULL DEFAULT 0,
            \\  created INTEGER NOT NULL,
            \\  UNIQUE (book, page, block)
            \\);
        );
        self.db = db;
    }

    pub fn close(self: *Store) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.db) |*db| db.close();
        self.db = null;
    }

    /// `{bookmarks: [{id, page, block, text, word, word_start}]}`, in page order.
    pub fn writeList(self: *Store, q: ListQuery, w: *std.Io.Writer) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        var db = self.db orelse return error.NoDatabase;
        const stmt = try db.prepare(
            "SELECT id, page, block, text, word, word_start FROM bookmarks WHERE book = ?1 ORDER BY page, id",
        );
        defer stmt.finalize();
        try stmt.bindText(1, bookKey(q.book));

        var s: std.json.Stringify = .{ .writer = w };
        try s.beginObject();
        try s.objectField("bookmarks");
        try s.beginArray();
        while (try stmt.step()) {
            try s.write(.{
                .id = stmt.columnInt64(0),
                .page = stmt.columnInt64(1),
                .block = stmt.columnInt64(2),
                .text = stmt.columnText(3),
                .word = stmt.columnText(4),
                .word_start = stmt.columnInt64(5),
            });
        }
        try s.endArray();
        try s.endObject();
    }

    /// Adds the bookmark, or updates the bubble's existing one; `{id}`.
    pub fn writeSave(self: *Store, b: Save, w: *std.Io.Writer) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        var db = self.db orelse return error.NoDatabase;
        const stmt = try db.prepare(
            "INSERT INTO bookmarks (book, page, block, text, word, word_start, created) " ++
                "VALUES (?1, ?2, ?3, ?4, ?5, ?6, strftime('%s','now')) " ++
                "ON CONFLICT (book, page, block) DO UPDATE SET " ++
                "text = excluded.text, word = excluded.word, word_start = excluded.word_start " ++
                "RETURNING id",
        );
        defer stmt.finalize();
        try stmt.bindText(1, bookKey(b.book));
        try stmt.bindInt64(2, b.page);
        try stmt.bindInt64(3, b.block);
        try stmt.bindText(4, b.text);
        try stmt.bindText(5, b.word);
        try stmt.bindInt64(6, b.word_start);
        if (!try stmt.step()) return error.Sqlite;
        const id = stmt.columnInt64(0);
        // RETURNING rows come before the write is done; finish it.
        _ = try stmt.step();

        var s: std.json.Stringify = .{ .writer = w };
        try s.write(.{ .id = id });
    }

    /// `{}`, whether or not there was such a bookmark.
    pub fn writeDelete(self: *Store, d: Delete, w: *std.Io.Writer) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        var db = self.db orelse return error.NoDatabase;
        const stmt = try db.prepare("DELETE FROM bookmarks WHERE id = ?1");
        defer stmt.finalize();
        try stmt.bindInt64(1, d.id);
        _ = try stmt.step();
        try w.writeAll("{}");
    }
};
