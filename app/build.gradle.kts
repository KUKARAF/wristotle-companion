import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing config. Three sources, in order of precedence:
//   1. Environment variables (used by CI — Codeberg Actions decodes the
//      base64 keystore secret into a temp file and exports the rest).
//   2. keystore.properties in the project root (gitignored — local dev).
//   3. No config — release builds fall back to the debug signing key so
//      `./gradlew assembleRelease` still works for smoke testing,
//      producing an APK that installs but can't be Play-/Codeberg-released.
val releaseSigning: Map<String, String>? = run {
    val storeFile = System.getenv("WRISTOTLE_KEYSTORE_FILE")
    val storePassword = System.getenv("WRISTOTLE_KEYSTORE_PASSWORD")
    val keyAlias = System.getenv("WRISTOTLE_KEY_ALIAS")
    val keyPassword = System.getenv("WRISTOTLE_KEY_PASSWORD")
    if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
        mapOf(
            "storeFile" to storeFile,
            "storePassword" to storePassword,
            "keyAlias" to keyAlias,
            "keyPassword" to keyPassword,
        )
    } else {
        val local = rootProject.file("keystore.properties")
        if (local.exists()) {
            val props = Properties().apply { local.inputStream().use(::load) }
            mapOf(
                "storeFile" to props.getProperty("storeFile"),
                "storePassword" to props.getProperty("storePassword"),
                "keyAlias" to props.getProperty("keyAlias"),
                "keyPassword" to props.getProperty("keyPassword"),
            )
        } else null
    }
}

android {
    namespace = "com.lazydevs.wristotle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazydevs.wristotle"
        minSdk = 24
        targetSdk = 36
        // CI overrides these from the tag name (e.g. v1.2.3 → versionName
        // "1.2.3", versionCode = 10203 via the 2-digits-per-component scheme).
        // Local builds use the fallback so manual debug installs always work.
        versionCode = System.getenv("WRISTOTLE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("WRISTOTLE_VERSION_NAME") ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Restrict the APK to arm64-v8a. The :speech-whisper module
            // already filters here, but the ONNX Runtime AAR (pulled in via
            // :speech-nlu) ships .so files for x86_64 / x86 / armeabi-v7a /
            // arm64-v8a — without this app-level filter, the APK ships all
            // four variants of libonnxruntime.so (~75 MB combined) when only
            // arm64-v8a is loadable on the target devices. Adding the filter
            // here drops the unused variants and shrinks the APK by ~56 MB.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        releaseSigning?.let {
            create("release") {
                storeFile = file(it["storeFile"]!!)
                storePassword = it["storePassword"]
                keyAlias = it["keyAlias"]
                keyPassword = it["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            // R8 + resource shrinking. AGP's bundled
            // proguard-android-optimize.txt + Compose/Room/Kotlin's per-
            // library consumer rules cover the bulk of what we need; our
            // own proguard-rules.pro holds the project-specific keep rules
            // (JNI bridges, PebbleKit2 reflection paths, etc).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use release signing when configured; otherwise leave the
            // default debug signing so local `assembleRelease` still works
            // for quick checks. CI builds will always have it configured.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig surfaces VERSION_NAME / VERSION_CODE / BUILD_TYPE
        // into Kotlin source — DiagnosticsBuilder reads them when
        // assembling the bug-report bundle. AGP 8+ no longer enables
        // BuildConfig generation by default.
        buildConfig = true
    }
    testOptions {
        // Make `android.util.Log` calls no-op rather than throw the
        // "Method not mocked" RuntimeException in JVM unit tests.
        // Lets pure-function code that happens to log (e.g. AppIndex)
        // be unit-tested without dragging in Robolectric.
        unitTests.isReturnDefaultValues = true
        // Cap the forked test JVM so the medium runner has headroom
        // for AGP's on-demand build-tools install + the Gradle daemon
        // + Kotlin daemon. Default is unbounded → cumulative
        // (1.5 GB gradle daemon + 1 GB Kotlin daemon + ≥1 GB SDK
        // install + ≥512 MB test JVM) overshoots ~4 GB on
        // codeberg-medium and the kernel OOM-kills mid-build (v0.15.0
        // hit this twice). 512 MB is plenty for our pure-JUnit cases.
        unitTests.all { test ->
            test.maxHeapSize = "512m"
        }
    }

    // AGP's `stripReleaseDebugSymbols` task can't strip these `.so` files —
    // most are prebuilt in their upstream AAR (ONNX runtime, AndroidX
    // graphics-path, datastore, OpenMP, libc++_shared) and the rest are
    // built locally by the speech-whisper CMake (whisper / ggml / our JNI
    // bridge) with sections NDK 30's strip tool doesn't recognise.
    //
    // Listing them in keepDebugSymbols declares the intent explicitly
    // (we know strip can't touch them — packaging them as-is is fine) and
    // silences the warning noise on every release build. No size impact:
    // they were going in unstripped anyway; this just makes it intentional.
    packaging {
        jniLibs {
            keepDebugSymbols += setOf(
                "**/libandroidx.graphics.path.so",
                "**/libc++_shared.so",
                "**/libdatastore_shared_counter.so",
                "**/libggml.so",
                "**/libggml-base.so",
                "**/libggml-cpu.so",
                "**/libomp.so",
                "**/libonnxruntime.so",
                "**/libonnxruntime4j_jni.so",
                "**/libwhisper.so",
                "**/libwristotle_speech.so",
            )
        }
    }
}

// Ktor (pulled in by the MCP client) brings slf4j-api 2.x, and
// prettytime-nlp ships an older slf4j shaded inside its own jar — D8
// then fails with `Duplicate class org.slf4j.*`. Drop Ktor's slf4j-api
// since the shaded copy already satisfies the API references at
// runtime. Logging from Ktor is verbose-debug only and isn't load-bearing.
configurations.all {
    exclude(group = "org.slf4j", module = "slf4j-api")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.pebblekit)
    implementation(libs.prettytime.nlp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.zip4j)
    implementation(project(":speech"))
    implementation(project(":speech-whisper"))
    implementation(project(":speech-nlu"))
    // MCP client (phase A of AskAgent) — official SDK over Ktor's OkHttp engine.
    // Pure-package under app/.../mcp/; no separate module per the
    // module-vs-package check (single consumer, no NDK, no model lifecycle).
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // org.json is bundled with Android but absent from the empty android.jar
    // used by unit tests. Pulling the upstream JVM artifact lets the backup
    // codec tests exercise the real JSONObject behaviour (vs. the no-op stubs
    // that `unitTests.isReturnDefaultValues = true` would otherwise hand back).
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}