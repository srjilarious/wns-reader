// Copyright (c) 2026 Jeff DeWall
// SPDX-License-Identifier: GPL-3.0-or-later

// The Android headers jni.zig needs, translated by build.zig's
// `translateAndroid` against the NDK sysroot.
//
// <android/log.h> is deliberately not here: it pulls in <limits.h>, and
// translate-c's search order sends the NDK's `#include_next <limits.h>`
// on through clang's copy to the host's glibc one. jni.zig declares the
// one logging function it uses by hand instead.

#include <jni.h>
