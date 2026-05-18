# Project-specific ProGuard / R8 rules.
#
# Bundled rules — `proguard-android-optimize.txt` plus the consumer rules
# packaged with AndroidX / Compose / Kotlin / Room / etc — cover almost
# everything. Only add rules here when R8 is stripping something the
# build process can't see (typically: classes referenced only by native
# code, JNI, reflection, or AppMessage-style serialised dictionaries).

# ── JNI bridges into native libraries ────────────────────────────────────────
#
# `WhisperNative` and (in the Vosk hybrid branch) the upcoming Vosk-side
# bridges have their methods declared `external fun` — invoked from C/C++
# via JNI lookup by name. R8 has no way to see the call edges and would
# otherwise rename / drop them. Keep the wrapper classes and all their
# members.
-keep class com.lazydevs.wristotle.speech.whisper.WhisperNative { *; }
-keep class com.lazydevs.wristotle.speech.whisper.WhisperNative$Companion { *; }

# ── ONNX Runtime ─────────────────────────────────────────────────────────────
#
# onnxruntime-android already ships consumer ProGuard rules, but pinning
# the JNI entry types here is cheap insurance against an upstream
# regression breaking our :speech-nlu embedder at runtime.
-keep class ai.onnxruntime.** { *; }

# ── PebbleKit2 / Forgejo serialisation paths ─────────────────────────────────
#
# Inbound AppMessage `PebbleDictionary` entries are decoded reflectively
# inside io.rebble.pebblekit2. Keeping the public API shape avoids
# surprise NoSuchMethodError when R8 prunes a getter the library calls.
-keep class io.rebble.pebblekit2.** { *; }

# ── Compose Previews ─────────────────────────────────────────────────────────
#
# `@Preview` Composables aren't referenced from production code — R8 will
# strip them and the preview surface in Android Studio stops working.
# Keep all functions annotated with the preview marker.
-keep,allowobfuscation,allowoptimization @androidx.compose.ui.tooling.preview.Preview class * { *; }
-keepclassmembers class * {
    @androidx.compose.ui.tooling.preview.Preview <methods>;
}

# ── Suppress harmless "missing class" complaints ─────────────────────────────
#
# Both references below come from optional fallback code paths in
# transitively-pulled libraries; the runtime check would fail and the
# library skips down its alternate path. R8 sees a static reference and
# wants the class to exist or it fails the build. -dontwarn opts those
# out of the missing-class check without keeping the unreachable code.
#
#   sun.misc.Perf — OpenJDK internal, referenced by prettytime-nlp's
#                   shaded backport. Not present on the Android runtime.
#   org.slf4j.impl.StaticLoggerBinder — SLF4J facade looks for a
#                   binding impl at startup; we don't ship one (Android
#                   uses android.util.Log throughout) and SLF4J degrades
#                   to no-op when the binder is absent.
-dontwarn sun.misc.**
-dontwarn org.slf4j.**
-dontwarn org.ocpsoft.prettytime.shade.**

# ── Crash log readability ────────────────────────────────────────────────────
#
# Keep file + line info in stack traces (small APK cost, much better
# debuggability for any user-reported crashes).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
