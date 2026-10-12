#!/usr/bin/env python3
# Copyright (c) 2026 Jeff DeWall
# SPDX-License-Identifier: GPL-3.0-or-later

"""Writes the fixtures native/ embeds and tests against.

- probe.cbz: two deflated PNG pages of known sizes, stored out of order,
  plus a mokuro sidecar with two bubbles on the first page. The
  diagnostics screen opens it on the device; the host tests use it too.
- mini-dict.zip: a tiny Yomitan term dictionary (食べる and friends).
- mini-freq.zip: a tiny Yomitan frequency list for it.

Run it from this directory. The outputs are committed, so this only
needs re-running if a fixture should change. Stdlib only.
"""

import json
import struct
import zipfile
import zlib


def png(width, height):
    """A grayscale PNG with a diagonal stripe."""
    rows = b""
    for y in range(height):
        rows += b"\x00" + bytes(0 if x == y else 255 for x in range(width))

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    header = struct.pack(">IIBBBBB", width, height, 8, 0, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


def write_zip(name, files):
    # A fixed timestamp keeps the output byte-identical across runs.
    with zipfile.ZipFile(name, "w", zipfile.ZIP_DEFLATED) as z:
        for path, data in files:
            info = zipfile.ZipInfo(path, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


MOKURO = {
    "version": "0.2.1",
    "title": "probe",
    "pages": [
        {
            "img_path": "001.png",
            "img_width": 3,
            "img_height": 2,
            # Two bubbles: the right one comes first in manga reading order.
            "blocks": [
                {"box": [0, 0, 2, 2], "vertical": True, "lines": ["食べな", "かったです"]},
                {"box": [2, 0, 3, 1], "vertical": True, "lines": ["今日は"]},
            ],
        },
        {"img_path": "002.png", "img_width": 4, "img_height": 5, "blocks": []},
    ],
}

TERMS = [
    # [term, reading, definitionTags, rules, score, glossary, sequence, termTags]
    ["食べる", "たべる", "v1", "v1", 0, ["to eat"], 1, ""],
    ["今日", "きょう", "n", "", 0, ["today", "this day"], 2, ""],
    ["今", "いま", "n", "", 0, ["now"], 3, ""],
    ["日", "ひ", "n", "", 0, ["day"], 4, ""],
]

TAGS = [
    # [name, category, order, notes, score]
    ["v1", "partOfSpeech", 0, "Ichidan verb", 0],
    ["n", "partOfSpeech", 0, "noun", 0],
]

FREQS = [
    ["食べる", "freq", 500],
    ["今日", "freq", 120],
]


def main():
    write_zip("probe.cbz", [
        ("002.png", png(4, 5)),
        ("001.png", png(3, 2)),
        ("probe.mokuro", json.dumps(MOKURO, ensure_ascii=False)),
    ])
    write_zip("mini-dict.zip", [
        ("index.json", json.dumps({"title": "Mini Dictionary", "format": 3, "revision": "1"})),
        ("term_bank_1.json", json.dumps(TERMS, ensure_ascii=False)),
        ("tag_bank_1.json", json.dumps(TAGS, ensure_ascii=False)),
    ])
    write_zip("mini-freq.zip", [
        ("index.json", json.dumps({"title": "Mini Frequency", "format": 3, "revision": "1"})),
        ("term_meta_bank_1.json", json.dumps(FREQS, ensure_ascii=False)),
    ])


if __name__ == "__main__":
    main()
