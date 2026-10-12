// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

//! Builds `libwnscore.so`, the Zig half of wns-reader, for Android, and
//! runs the same core code natively for `zig build test`.
//!
//! `zig build` (what Gradle's `zigBuild` task runs) cross-compiles for
//! `aarch64-linux-android.<api>` against the NDK's sysroot and installs
//! the library to `<prefix>/arm64-v8a/`, the layout Gradle's jniLibs
//! source directory expects. The Daylight DC-1 is arm64, so that is the
//! only ABI built.
//!
//! **Why the NDK is needed at all.** gw-yomi compiles SQLite and stb, C
//! needs a libc, and the JNI header is the NDK's; Zig ships glibc and musl but not Android's bionic. So the build
//! points Zig at the NDK's sysroot with a generated libc paths file, and
//! two NDK quirks have to be worked around by hand:
//!
//! - Zig passes clang an *unversioned* triple, and NDK r30's
//!   `sys/cdefs.h` refuses to compile without the API level that a
//!   versioned triple would define. `android_defines` supplies it.
//! - `TranslateC` takes no libc file, so the JNI header is
//!   translated by running `zig translate-c --libc` directly
//!   (`translateAndroid`).

const std = @import("std");

const default_api_level: u32 = 33;

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{});
    const api_level = b.option(u32, "api", "Android API level to build against (default 33, the Daylight's Android 13)") orelse default_api_level;
    const ndk_option = b.option([]const u8, "ndk", "Android NDK directory (default: $ANDROID_NDK_HOME)");


    // ---- Android library ----------------------------------------------

    const android_step = b.step("android", "Build libwnscore.so for arm64 Android (the default)");
    b.default_step = android_step;

    const ndk_dir = ndk_option orelse b.graph.environ_map.get("ANDROID_NDK_HOME");
    if (ndk_dir) |ndk| {
        const android_target = b.resolveTargetQuery(.{
            .cpu_arch = .aarch64,
            .os_tag = .linux,
            .abi = .android,
            .android_api_level = api_level,
        });
        const sysroot = b.pathJoin(&.{ ndk, "toolchains/llvm/prebuilt/linux-x86_64/sysroot" });
        const ndk_paths = NdkPaths.init(b, sysroot, api_level);
        const android_defines = androidDefines(b, api_level);

        const android_c = translateAndroid(b, b.path("src/android.h"), ndk_paths, api_level, android_defines);
        const yomi = b.dependency("gw_yomi", .{ .target = android_target, .optimize = optimize }).module("gw_yomi");

        const jni_mod = b.createModule(.{
            .root_source_file = b.path("src/jni.zig"),
            .target = android_target,
            .optimize = optimize,
            .link_libc = true,
        });
        jni_mod.addImport("gw_yomi", yomi);
        jni_mod.addImport("android_c", android_c);
        addFixtures(b, jni_mod, &.{probe_fixture});
        jni_mod.addLibraryPath(.{ .cwd_relative = ndk_paths.lib_dir });
        jni_mod.linkSystemLibrary("log", .{});

        const lib = b.addLibrary(.{
            .name = "wnscore",
            .linkage = .dynamic,
            .root_module = jni_mod,
        });
        lib.setLibCFile(ndk_paths.libc_file);
        // Android 15+ devices can use 16 KB pages; aligning for them costs
        // nothing on the Daylight's 4 KB pages.
        lib.link_z_max_page_size = 16 * 1024;

        const install = b.addInstallArtifact(lib, .{
            .dest_dir = .{ .override = .{ .custom = "arm64-v8a" } },
        });
        android_step.dependOn(&install.step);
    } else {
        android_step.dependOn(&b.addFail("the Android build needs the NDK: pass -Dndk=<dir> or set ANDROID_NDK_HOME").step);
    }

    // ---- Host tests -----------------------------------------------------

    const host_target = b.graph.host;
    const tests_mod = b.createModule(.{
        .root_source_file = b.path("src/tests.zig"),
        .target = host_target,
        .optimize = optimize,
        .link_libc = true,
    });
    tests_mod.addImport("gw_yomi", b.dependency("gw_yomi", .{ .target = host_target, .optimize = optimize }).module("gw_yomi"));
    addFixtures(b, tests_mod, &test_fixtures);
    const tests = b.addTest(.{ .root_module = tests_mod });
    const test_step = b.step("test", "Run the native code's tests on the host");
    test_step.dependOn(&b.addRunArtifact(tests).step);
}

const Fixture = struct { name: []const u8, path: []const u8 };

const probe_fixture: Fixture = .{ .name = "probe_cbz", .path = "testdata/probe.cbz" };
const test_fixtures = [_]Fixture{
    probe_fixture,
    .{ .name = "mini_dict_zip", .path = "testdata/mini-dict.zip" },
    .{ .name = "mini_freq_zip", .path = "testdata/mini-freq.zip" },
};

/// Makes testdata/ files `@embedFile`-able by name.
fn addFixtures(b: *std.Build, mod: *std.Build.Module, fixtures: []const Fixture) void {
    for (fixtures) |f| mod.addAnonymousImport(f.name, .{ .root_source_file = b.path(f.path) });
}

/// The pieces of the NDK sysroot the build points at, and the libc paths
/// file Zig reads them from.
const NdkPaths = struct {
    /// Bionic's `libc.so`, `crtbegin_so.o` and friends for one API level.
    lib_dir: []const u8,
    libc_file: std.Build.LazyPath,

    fn init(b: *std.Build, sysroot: []const u8, api_level: u32) NdkPaths {
        const include_dir = b.pathJoin(&.{ sysroot, "usr/include" });
        const arch_include_dir = b.pathJoin(&.{ sysroot, "usr/include/aarch64-linux-android" });
        const lib_dir = b.fmt("{s}/usr/lib/aarch64-linux-android/{d}", .{ sysroot, api_level });
        // Zig 0.17 wants every field present, and `cc_dir` non-empty on
        // Linux; the NDK keeps its crtbegin objects beside libc.
        const contents = b.fmt(
            \\include_dir={s}
            \\sys_include_dir={s}
            \\crt_dir={s}
            \\cc_dir={s}
            \\msvc_lib_dir=
            \\kernel32_lib_dir=
            \\darwin_sdk_dir=
            \\
        , .{ include_dir, arch_include_dir, lib_dir, lib_dir });
        const files = b.addWriteFiles();
        return .{
            .lib_dir = lib_dir,
            .libc_file = files.add("ndk-libc.txt", contents),
        };
    }
};

fn androidDefines(b: *std.Build, api_level: u32) []const []const u8 {
    return b.allocator.dupe([]const u8, &.{
        b.fmt("-D__ANDROID_API__={d}", .{api_level}),
        b.fmt("-D__ANDROID_MIN_SDK_VERSION__={d}", .{api_level}),
    }) catch @panic("OOM");
}

/// Translates `header` for Android with the same libc paths file the
/// compile uses. A Run step rather than `addTranslateC`, because
/// `TranslateC` has no libc-file option and, without one, falls back to
/// the host's glibc headers.
fn translateAndroid(
    b: *std.Build,
    header: std.Build.LazyPath,
    paths: NdkPaths,
    api_level: u32,
    defines: []const []const u8,
) *std.Build.Module {
    const run = b.addSystemCommand(&.{ b.graph.zig_exe, "translate-c" });
    run.addArgs(&.{ "-target", b.fmt("aarch64-linux-android.{d}", .{api_level}), "-lc", "--libc" });
    run.addFileArg(paths.libc_file);
    run.addArgs(defines);
    run.addFileArg(header);
    return b.createModule(.{ .root_source_file = run.captureStdOut(.{ .basename = "translated.zig" }) });
}
