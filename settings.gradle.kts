// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2025-2026 Lazy Devs

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// Composite build: the generic sports-data library lives in its own repo,
// vendored here as a git submodule at ./sportskapi. Gradle substitutes any
// "com.lazydevs.sportskapi:sportskapi" dependency with this included build.
includeBuild("sportskapi")

rootProject.name = "Wristotle"
include(":app")
include(":speech")
include(":speech-whisper")
include(":wristotle-core")