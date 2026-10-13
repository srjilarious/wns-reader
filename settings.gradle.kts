pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // AnkiDroid's AddContentApi is only published there.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.ankidroid") }
        }
    }
}

rootProject.name = "wns-reader"
include(":app")
