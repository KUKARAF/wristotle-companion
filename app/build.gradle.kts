import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
    }
    testOptions {
        // Make `android.util.Log` calls no-op rather than throw the
        // "Method not mocked" RuntimeException in JVM unit tests.
        // Lets pure-function code that happens to log (e.g. AppIndex)
        // be unit-tested without dragging in Robolectric.
        unitTests.isReturnDefaultValues = true
    }
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
    implementation(project(":speech"))
    implementation(project(":speech-whisper"))
    implementation(project(":speech-nlu"))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}