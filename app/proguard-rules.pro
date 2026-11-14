# ===========================================================================
# Nexus ProGuard / R8 Shrinking & Optimization Rules
# ===========================================================================

# ---------------------------------------------------------------------------
# Obfuscation Disabled & Readable Stack Traces (已关闭混淆)
# ---------------------------------------------------------------------------
# Keep R8 shrinking and resource shrinking enabled while disabling class,
# method, and field name obfuscation (-dontobfuscate) so runtime crashes and
# JNI lookups retain original symbol names.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# Native Bridge (libclash.so & libbridge.so)
# Entry points and callbacks are looked up via JNI name/signature by Go & C++
# ---------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.github.kr328.clash.common.Global { *; }
-keep class com.github.kr328.clash.core.bridge.** { *; }
-keep interface com.github.kr328.clash.core.bridge.** { *; }

# JNI callback interfaces
-keep class * implements com.github.kr328.clash.core.bridge.TunInterface { *; }
-keep class * implements com.github.kr328.clash.core.bridge.FetchCallback { *; }
-keep class * implements com.github.kr328.clash.core.bridge.LogcatInterface { *; }

# QuickJS JNI engine (quickjs-kt; JNI-registered natives and JS-facing bindings)
-keep class com.dokar.quickjs.** { *; }
-dontwarn com.dokar.quickjs.**

# The gomobile bridge (libbridge.so) resolves kotlinx/coroutines/CompletableDeferred and
# calls complete/completeExceptionally via JNIEnv->GetMethodID. Keep only that contract
# instead of pinning the entire kotlinx.coroutines package.
-keep interface kotlinx.coroutines.CompletableDeferred {
    boolean complete(java.lang.Object);
    boolean completeExceptionally(java.lang.Throwable);
}
-keepclassmembers class * implements kotlinx.coroutines.CompletableDeferred {
    boolean complete(java.lang.Object);
    boolean completeExceptionally(java.lang.Throwable);
}

# ---------------------------------------------------------------------------
# Data Models & Serialization (kotlinx.serialization)
# ---------------------------------------------------------------------------
-dontnote kotlinx.serialization.**

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

# WorkManager pulls in Room (WorkDatabase). Room's consumer rule only keeps the
# database classes; in R8 full mode the no-arg constructor of the generated
# *_Impl class is still stripped because it is reachable only via reflection
# (Class.forName + newInstance in Room's builder). At startup the
# InitializationProvider then crashed with
# "Failed to create an instance of class androidx.work.impl.WorkDatabase"
# (InstantiationException) and the app could not open at all. Keep the
# constructors explicitly.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
