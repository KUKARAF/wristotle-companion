plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lazydevs.wristotle.speech.nlu"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        ndk { abiFilters += "arm64-v8a" }   // mirror :speech-whisper for consistency
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // No-op `android.util.Log` so pure-Kotlin code that happens to
        // log (e.g. ExampleBank's cap-reached path) tests on the JVM
        // without dragging in Robolectric. Mirrors :app.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":speech"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}
