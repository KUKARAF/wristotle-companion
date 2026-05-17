plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lazydevs.wristotle.speech.whisper"
    compileSdk = 36
    // Pinned to a complete NDK install (28.2.13676358 is corrupted, missing
    // source.properties). Bump if/when 28.2 is reinstalled cleanly.
    ndkVersion = "30.0.14904198"

    defaultConfig {
        minSdk = 24
        ndk {
            // Only ship arm64-v8a — all modern Pebble-companion-friendly phones.
            // Add x86_64 here if you need emulator support; armeabi-v7a if you need
            // to ship to 32-bit devices.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":speech"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
