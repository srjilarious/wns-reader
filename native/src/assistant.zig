// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! Asking an AI about a bubble: gw-yomi's `ai` builds the request and
//! reads the reply, its `ai_cache` remembers answers, and Kotlin does the
//! HTTP in between (OkHttp, so leaving the panel cancels the call).
//!
//! The round trip is two calls:
//!
//! 1. `writePrepare` turns the panel's question into a prompt and either
//!    answers it from the cache or hands back the exact request to send:
//!    URL, headers (gw-yomi's `requestHeaders`, the same ones gw-read
//!    sends) and body.
//! 2. `writeFinish` parses what came back, cleans it up for display
//!    (`ai.plainText`) and caches it under the key from step 1.
//!
//! Same prompt, same key derivation and same cache table as gw-read, so a
//! cache file could be shared between the two.

const std = @import("std");
const yomi = @import("gw_yomi");
const ai = yomi.ai;
const ai_cache = yomi.ai_cache;

/// What the panel asks. Mirrors the settings screen plus the bubble.
pub const Question = struct {
    provider: []const u8,
    /// Empty means the provider's default.
    model: []const u8 = "",
    /// Empty means the provider's default.
    endpoint: []const u8 = "",
    api_key: ?[]const u8 = null,
    /// Empty means `ai.default_style`.
    style: []const u8 = "",
    dialog: []const u8,
    previous: ?[]const u8 = null,
    next: ?[]const u8 = null,
    highlight: ?[]const u8 = null,
    title: ?[]const u8 = null,
    page: ?usize = null,
    /// False to ask again even when the cache has an answer.
    use_cache: bool = true,
};

/// What `writeFinish` needs back to parse and cache the reply.
pub const Reply = struct {
    provider: []const u8,
    model: []const u8 = "",
    status: u16,
    body: []const u8,
    key: []const u8,
    title: []const u8 = "",
    page: usize = 0,
    highlight: []const u8 = "",
};

pub const Assistant = struct {
    alloc: std.mem.Allocator,
    io: std.Io,
    mutex: std.Io.Mutex = .init,
    cache: ?ai_cache.Cache = null,

    /// Opens (creating if needed) the answer cache at `path`. Without one,
    /// every question goes to the network.
    pub fn openCache(self: *Assistant, path: []const u8) !void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.cache) |*c| c.close();
        self.cache = null;
        const z = try self.alloc.dupeSentinel(u8, path, 0);
        defer self.alloc.free(z);
        self.cache = try ai_cache.Cache.open(z);
    }

    pub fn closeCache(self: *Assistant) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        if (self.cache) |*c| c.close();
        self.cache = null;
    }

    /// `{cached: true, answer, key}` from the cache, or
    /// `{cached: false, key, url, headers: [{name, value, secret}], body}`
    /// for Kotlin to send.
    pub fn writePrepare(self: *Assistant, alloc: std.mem.Allocator, q: Question, w: *std.Io.Writer) !void {
        const provider = ai.Provider.parse(q.provider) orelse return error.UnknownProvider;
        // The `claude` CLI isn't there on Android.
        if (provider == .claude_code) return error.NotAnHttpProvider;
        const model = if (q.model.len > 0) q.model else provider.defaultModel();
        const endpoint = if (q.endpoint.len > 0) q.endpoint else provider.defaultEndpoint();
        if (provider.needsKey() and (q.api_key == null or q.api_key.?.len == 0)) return error.MissingApiKey;

        const prompt = try ai.buildPrompt(alloc, if (q.style.len > 0) q.style else ai.default_style, .{
            .dialog = q.dialog,
            .highlight = q.highlight,
            .previous = q.previous,
            .next = q.next,
            .title = q.title,
            .page = q.page,
        });
        defer prompt.deinit(alloc);
        const key = ai_cache.key(provider, model, prompt);

        var s: std.json.Stringify = .{ .writer = w };
        if (q.use_cache) {
            if (try self.cached(alloc, key)) |answer| {
                defer alloc.free(answer);
                try s.write(.{ .cached = true, .answer = answer, .key = &key });
                return;
            }
        }

        const body = try ai.buildBody(alloc, provider, model, prompt);
        defer alloc.free(body);
        var bearer_buf: [1024]u8 = undefined;
        defer std.crypto.secureZero(u8, &bearer_buf);
        var header_buf: [3]ai.Header = undefined;
        const headers = try ai.requestHeaders(provider, model, q.api_key, &bearer_buf, &header_buf);

        try s.write(.{ .cached = false, .key = &key, .url = endpoint, .headers = headers, .body = body });
    }

    /// `{answer}` (also cached), or `{failure}` with the HTTP status or the
    /// provider's own message.
    pub fn writeFinish(self: *Assistant, alloc: std.mem.Allocator, r: Reply, w: *std.Io.Writer) !void {
        const provider = ai.Provider.parse(r.provider) orelse return error.UnknownProvider;
        const answer = try ai.parseAnswer(alloc, provider, r.status, r.body);
        defer answer.deinit(alloc);

        var s: std.json.Stringify = .{ .writer = w };
        switch (answer) {
            .failure => |f| try s.write(.{ .failure = f }),
            .text => |text| {
                const plain = try ai.plainText(alloc, text);
                defer alloc.free(plain);
                if (r.key.len == @sizeOf(ai_cache.Key)) {
                    self.store(r.key[0..@sizeOf(ai_cache.Key)].*, plain, .{
                        .title = r.title,
                        .page = r.page,
                        .highlight = r.highlight,
                        .provider = provider,
                        .model = if (r.model.len > 0) r.model else provider.defaultModel(),
                    });
                }
                try s.write(.{ .answer = plain });
            },
        }
    }

    fn cached(self: *Assistant, alloc: std.mem.Allocator, key: ai_cache.Key) !?[]u8 {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const cache = &(self.cache orelse return null);
        return cache.get(alloc, key);
    }

    /// Best effort: an answer that can't be cached is still shown.
    fn store(self: *Assistant, key: ai_cache.Key, answer: []const u8, meta: ai_cache.Meta) void {
        self.mutex.lockUncancelable(self.io);
        defer self.mutex.unlock(self.io);
        const cache = &(self.cache orelse return);
        cache.put(key, answer, meta) catch |err| std.log.warn("AI cache: {s}", .{@errorName(err)});
    }
};
