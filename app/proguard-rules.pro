# ===========================================================================
# Nexus ProGuard / R8 Shrinking & Optimization Rules
# ===========================================================================

# ---------------------------------------------------------------------------
# Disable Name Obfuscation (Keep original class, method, and field names)
# ---------------------------------------------------------------------------
-dontobfuscate

# ---------------------------------------------------------------------------
# General Code Quality & Attributes
# ---------------------------------------------------------------------------
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# Native Bridge (libclash.so & libbridge.so)
# Entry points and callbacks are looked up via JNI name/signature by Go & C++
# ---------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.github.kr328.clash.core.** { *; }
-keep interface com.github.kr328.clash.core.** { *; }
-keep class com.github.mihomo.android.core.** { *; }

# JNI callback interfaces
-keep class * implements com.github.kr328.clash.core.bridge.TunInterface { *; }
-keep class * implements com.github.kr328.clash.core.bridge.FetchCallback { *; }
-keep class * implements com.github.kr328.clash.core.bridge.LogcatInterface { *; }

# QuickJS JNI engine (quickjs-kt; JNI-registered natives and JS-facing bindings)
-keep class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**

# The gomobile bridge (libbridge.so) looks up coroutine types BY NAME from native code
# during JNI_OnLoad (its bind API uses CompletableDeferred, e.g. nativeLoad). R8's
# reachability analysis cannot see name-based JNI lookups and stripped interface methods,
# which crashed the first minified release (v1.0.4-Alpha.5) at startup with
# NoSuchMethodError CompletableDeferred.complete. Keep the whole package.
-keep class kotlinx.coroutines.** { *; }

# ---------------------------------------------------------------------------
# Data Models & Serialization (kotlinx.serialization)
# ---------------------------------------------------------------------------
-dontnote kotlinx.serialization.**
-keep class com.github.kr328.clash.core.model.** { *; }
-keep class com.github.mihomo.android.data.** { *; }

-keep,includedescriptorclasses class com.github.mihomo.android.**$$serializer { *; }
-keep,includedescriptorclasses class com.github.kr328.clash.core.**$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Third-Party Libraries
# ---------------------------------------------------------------------------
# SnakeYAML / OkHttp / Okio / SLF4J / kotlinx-coroutines
-dontwarn org.yaml.snakeyaml.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.**
