plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lazydevs.wristotle.speech"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // Stubbed Android framework methods (`Log.d`, `JSONObject`) return
        // sensible defaults instead of throwing — needed for the recognizer
        // unit tests to exercise code paths that log informationally.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    // Android's `org.json.*` is stubbed in JVM unit tests; bring in the
    // real impl so HttpRecognizer's response parsing can be exercised
    // without instrumented tests. Already on the runtime classpath via
    // the platform — this just resolves the test JVM stub.
    testImplementation("org.json:json:20231013")
}
