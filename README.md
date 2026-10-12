# wns-reader

A manga reader for Android (built for the Daylight DC-1), sharing its core
with glyphwire's `gw-read`: mokuro OCR overlays, Yomitan-style dictionary
lookup with deinflection, AI questions about a bubble, and Anki cards via
AnkiDroid.

The UI is Kotlin + Jetpack Compose. Everything else is Zig, built into
`libwnscore.so` and called over JNI.

**Status: the basic reader.** Browse /sdcard/Manga, read a book (folder
of images or `.cbz`) right to left with tap zones, swipes and pinch zoom,
tap an OCR bubble to see its text, tap a word to look it up in an
installed Yomitan dictionary with gw-read's deinflection and frequency
ranking. AI questions and Anki cards come next.

The reading core is [gw-yomi](../gw-yomi), extracted from glyphwire's
gw-read; this repo is the Android UI and the JNI layer over it.

## Where things go on the tablet

| What | Where |
|---|---|
| Books | `/sdcard/Manga/`, nested however you like. A folder of page images is a book; put its `.mokuro` beside it (`Vol1/` and `Vol1.mokuro`, mokuro's own layout). A `.cbz` can carry its `.mokuro` inside or beside it. |
| Dictionary zips | `/sdcard/wns-reader/dictionaries/`. Yomitan format, as downloaded: a term dictionary such as [Jitendex](https://jitendex.org) and optionally a frequency list such as JPDB's. Install each from Library › Dictionaries. |
| Installed dictionaries | App storage. Installing unpacks the zip, indexes it into SQLite and keeps only the index; the zip can be deleted afterwards. |

The app asks for all-files access on first launch so it can read both
folders by path.

## Layout

| Path | What |
|---|---|
| `app/` | The Android app (Kotlin, Compose). `app/build.gradle.kts` runs `zig build` per variant and packages the result as a jniLibs directory. |
| `native/` | The Zig side: `jni.zig` (the JNI surface and logcat glue), `library.zig` (listing the books folder), `book.zig` (an open book and its OCR), `dictionaries.zig` (installing and looking up), `diag.zig` (the diagnostics screen). |
| `native/testdata/` | Fixtures: `probe.cbz` with a mokuro sidecar, a tiny Yomitan term dictionary and frequency list, and the script that makes them. |

## Requirements

- A gw-yomi checkout beside this one (`../gw-yomi`); `native/build.zig.zon`
  depends on it by path until it has a published URL
- Zig 0.17.0
- Android SDK with platform 37 and NDK 30.0.16248370 (`ANDROID_HOME` set,
  or `sdk.dir` in `local.properties`, which Android Studio writes)
- JDK 21 somewhere Gradle can find it (`/usr/lib/jvm/java-21-openjdk` is
  picked up automatically); the daemon is pinned to 21 by
  `gradle/gradle-daemon-jvm.properties`

Gradle runs `zig` from `PATH`. If Android Studio's `PATH` doesn't include
it, set `wns.zig=/full/path/to/zig` in `~/.gradle/gradle.properties`.

## Building and installing

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug      # build and install on the connected device
adb logcat -s wnscore            # Zig's std.log and panics
```

The native code's tests run on the host, which is quicker for working on
the Zig side:

```sh
cd native && zig build test
# also install and query a real dictionary (about 15 s for Jitendex):
WNS_TEST_DICTIONARY=/path/to/jitendex-yomitan.zip zig build test -Doptimize=ReleaseSafe
```

Only `arm64-v8a` is built. An x86_64 emulator image from API 30 on still
runs the app through its ARM translation layer.

## Native build notes

`native/build.zig` cross-compiles against the NDK's sysroot, because
SQLite and stb need a libc and Zig doesn't ship Android's bionic. Three
quirks of Zig 0.17 + NDK r30 are handled there:

- Zig's libc paths file for Android needs `crt_dir` and `cc_dir` both set
  to the sysroot's `usr/lib/aarch64-linux-android/<api>`.
- NDK r30's headers require `__ANDROID_MIN_SDK_VERSION__`, which clang
  derives from a versioned target triple that Zig doesn't pass, so it and
  `__ANDROID_API__` are defined by hand.
- `addTranslateC` has no libc-file option and falls back to the host's
  glibc headers, so Android headers are translated by running
  `zig translate-c --libc` as a Run step. `<android/log.h>` is left out
  entirely (its `limits.h` chain still reaches the host's copy) and its
  one function is declared in `jni.zig`.

Strings cross JNI as UTF-8 byte arrays, never `jstring`: JNI's modified
UTF-8 breaks non-BMP characters such as 𠮷, which the SQLite check
round-trips on purpose.

## License

GPL-3.0-or-later. See `LICENSE`.
