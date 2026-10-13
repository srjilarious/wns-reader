// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! Anki cards: what goes in each field, and the page crop that becomes
//! the card's picture -- gw-yomi's `anki` and `crop`, as gw-read mines
//! them. Kotlin hands the result to AnkiDroid's `AddContentApi`.
//!
//! **Fields.** `writeFields` builds gw-yomi's `anki.Note` from what the
//! panel showed and renders every mapped field: the HTML `fieldValue`
//! gives (escaped, furigana aligned, senses as jidoujisho lays them out)
//! and the one-line `previewValue` for the preview screen. `image` and
//! `audio` fields render empty here; Kotlin fills them with the tags
//! AnkiDroid returns when it stores the media.
//!
//! **Offsets** arrive in codepoints, as everywhere on the JNI boundary.
//!
//! **Crop.** The box lives in the page image's own pixels (mokuro's
//! coordinates). Kotlin draws it and reports drags; `crop.handleAt`,
//! `crop.dragged` and `crop.initial` decide what the box does, and
//! `crop.encodeJpeg` cuts it out of the page file -- the same rules gw-read's
//! crop mode follows.

const std = @import("std");
const yomi = @import("gw_yomi");
const anki = yomi.anki;
const crop = yomi.crop;
const ai = yomi.ai;
const dictionaries = @import("dictionaries.zig");

pub const FieldMap = struct { field: []const u8, source: []const u8 };

/// A card as the panel captured it.
pub const NoteInput = struct {
    /// "word" (from a dictionary lookup) or "sentence" (the whole bubble,
    /// from the AI answer with no word picked).
    kind: []const u8 = "word",
    term: []const u8 = "",
    reading: []const u8 = "",
    sentence: []const u8,
    /// The looked-up span of `sentence`, in codepoints.
    span_start: usize = 0,
    span_end: usize = 0,
    glossary: []const []const u8 = &.{},
    ai: []const u8 = "",
    book: []const u8 = "",
    page: usize = 0,
    audio_template: []const u8 = "",
    fields: []const FieldMap,
};

/// Everything the settings screen starts from: gw-read's Anki and AI
/// defaults and the AI providers, so they're defined once.
pub fn writeDefaults(w: *std.Io.Writer) !void {
    var s: std.json.Stringify = .{ .writer = w };
    try s.beginObject();

    try s.objectField("anki");
    try s.beginObject();
    try s.objectField("deck");
    try s.write(anki.default_deck);
    try s.objectField("model");
    try s.write(anki.default_model);
    try s.objectField("fields");
    try s.beginArray();
    for (anki.default_fields) |f| try s.write(.{ .field = f.field, .source = @tagName(f.source) });
    try s.endArray();
    try s.objectField("sources");
    try s.beginArray();
    for (anki.Source.all) |src| try s.write(@tagName(src));
    try s.endArray();
    try s.objectField("audio_url");
    try s.write(anki.jpod_audio_url);
    try s.objectField("jpod_missing_md5");
    try s.write(anki.jpod_missing_md5);
    const defaults: crop.EncodeOptions = .{};
    try s.objectField("image_max_width");
    try s.write(defaults.max_w);
    try s.objectField("image_max_height");
    try s.write(defaults.max_h);
    try s.objectField("image_quality");
    try s.write(defaults.quality);
    try s.endObject();

    try s.objectField("ai");
    try s.beginObject();
    try s.objectField("style");
    try s.write(ai.default_style);
    // The HTTP providers; the `claude` CLI isn't there on Android.
    try s.objectField("providers");
    try s.beginArray();
    for ([_]ai.Provider{ .anthropic, .openai, .ollama }) |p| {
        try s.write(.{
            .id = @tagName(p),
            .label = p.label(),
            .model = p.defaultModel(),
            .endpoint = p.defaultEndpoint(),
            .needs_key = p.needsKey(),
        });
    }
    try s.endArray();
    try s.endObject();

    try s.endObject();
}

/// `{audio_url, fields: [{field, source, value, preview}]}` for `in`.
pub fn writeFields(alloc: std.mem.Allocator, in: NoteInput, w: *std.Io.Writer) !void {
    const kind: anki.Note.Kind = if (std.mem.eql(u8, in.kind, "sentence")) .sentence else .word;
    var note = try anki.Note.create(alloc, .{
        .kind = kind,
        .term = in.term,
        .reading = in.reading,
        .sentence = in.sentence,
        .span_start = dictionaries.codepointToByte(in.sentence, in.span_start),
        .span_end = dictionaries.codepointToByte(in.sentence, in.span_end),
        .glossary = in.glossary,
        .ai = in.ai,
        .book = in.book,
        .page = in.page,
        .audio_template = in.audio_template,
    });
    defer note.deinit(alloc);

    var s: std.json.Stringify = .{ .writer = w };
    try s.beginObject();
    try s.objectField("audio_url");
    try s.write(note.audio_url);
    try s.objectField("fields");
    try s.beginArray();
    for (in.fields) |f| {
        const source = anki.Source.parse(f.source) orelse continue;
        const value = try anki.fieldValue(alloc, &note, source);
        defer alloc.free(value);
        const preview = try anki.previewValue(alloc, &note, source);
        defer alloc.free(preview);
        try s.write(.{ .field = f.field, .source = f.source, .value = value, .preview = preview });
    }
    try s.endArray();
    try s.endObject();
}

// ---- crop -------------------------------------------------------------------

pub const Rect = struct { x: i64, y: i64, w: i64, h: i64 };

fn toCrop(r: Rect) crop.Rect {
    return .{ .x = r.x, .y = r.y, .w = r.w, .h = r.h };
}

fn fromCrop(r: crop.Rect) Rect {
    return .{ .x = r.x, .y = r.y, .w = r.w, .h = r.h };
}

/// The box a card starts with around `bubble`, on a page `page_w` x `page_h`.
pub fn initialBox(bubble: Rect, page_w: i64, page_h: i64) Rect {
    return fromCrop(crop.initial(toCrop(bubble), .{ .w = page_w, .h = page_h }));
}

/// What a drag starting at image pixel (`x`, `y`) grabs: a corner or edge
/// within `tolerance` pixels of it, else the whole box.
pub fn handleAt(box: Rect, x: i64, y: i64, tolerance: i64) crop.Handle {
    return crop.handleAt(toCrop(box), x, y, tolerance, tolerance);
}

/// `start` after dragging `handle` by (`dx`, `dy`) image pixels, kept on
/// the page and at least `crop.min_side` on a side.
pub fn dragBox(start: Rect, handle: crop.Handle, dx: i64, dy: i64, page_w: i64, page_h: i64) Rect {
    return fromCrop(crop.dragged(toCrop(start), handle, dx, dy, .{ .w = page_w, .h = page_h }));
}

pub fn encode(alloc: std.mem.Allocator, page: []const u8, box: Rect, max_w: u32, max_h: u32, quality: u8) !crop.Encoded {
    return crop.encodeJpeg(alloc, page, toCrop(box), .{ .max_w = max_w, .max_h = max_h, .quality = quality });
}
