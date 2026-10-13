import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.walknsqualk.wnsreader"
    compileSdk = 37
    // The NDK provides bionic's headers and libraries for the Zig build
    // (native/build.zig); nothing is compiled with ndk-build or CMake.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.walknsqualk.wnsreader"
        // The Daylight DC-1 runs Android 13.
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.ankidroid.api)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}

/**
 * Runs `zig build` in native/ and installs libwnscore.so to
 * `<outputDir>/arm64-v8a/`, which each variant picks up as a generated
 * jniLibs directory. Inputs are the native sources, so Gradle skips the
 * task when nothing under native/ changed (and Zig's own cache makes a
 * rerun cheap anyway).
 */
abstract class ZigBuildTask : DefaultTask() {
    @get:Internal
    abstract val nativeDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeSources: ConfigurableFileCollection

    @get:Input
    abstract val zigExecutable: Property<String>

    @get:Input
    abstract val optimize: Property<String>

    @get:Input
    abstract val ndkDirectory: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        execOperations.exec {
            workingDir = nativeDir.get().asFile
            commandLine(
                zigExecutable.get(),
                "build",
                "-Doptimize=${optimize.get()}",
                "-Dndk=${ndkDirectory.get()}",
                "--prefix",
                out.absolutePath,
            )
        }
    }
}

val nativeProjectDir = rootProject.layout.projectDirectory.dir("native")
// native/ depends on gw-yomi as a sibling checkout (native/build.zig.zon).
val gwYomiDir = rootProject.layout.projectDirectory.dir("../gw-yomi")

androidComponents {
    onVariants { variant ->
        val taskName = "zigBuild" + variant.name.replaceFirstChar { it.uppercase() }
        val zigBuild = tasks.register<ZigBuildTask>(taskName) {
            nativeDir.set(nativeProjectDir)
            nativeSources.from(
                fileTree(nativeProjectDir) {
                    include("build.zig", "build.zig.zon", "src/**", "testdata/**")
                },
                fileTree(gwYomiDir) {
                    include("build.zig", "build.zig.zon", "src/**")
                },
            )
            zigExecutable.set(providers.gradleProperty("wns.zig").orElse("zig"))
            optimize.set(providers.gradleProperty("wns.zigOptimize").orElse("ReleaseSafe"))
            ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(zigBuild, ZigBuildTask::outputDir)
    }
}
