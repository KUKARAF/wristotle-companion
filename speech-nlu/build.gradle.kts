plugins {
    alias(libs.plugins.android.library)
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // ONNX Runtime + Room/KSP deps land in Phase 2 — Phase 1 only needs the
    // stub classifier and the model-download UX scaffolding (which reuses
    // :speech-whisper's primitives).
}
