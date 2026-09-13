# ---------------------------------------------------------------------------
# Native bridge (libclash.so / libbridge.so) -- entry points are looked up by
# JNI name, so they must survive shrinking and obfuscation.
# NOTE: shrinking is currently disabled (isMinifyEnabled = false) because the
# minified build crashes on startup with the native core. These rules are kept
# for when that is investigated further.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.github.kr328.clash.core.** { *; }
-keep interface com.github.kr328.clash.core.** { *; }
-keep class * implements com.github.kr328.clash.core.bridge.TunInterface { *; }
-keep class * implements com.github.kr328.clash.core.bridge.FetchCallback { *; }
-keep class * implements com.github.kr328.clash.core.bridge.LogcatInterface { *; }
-keepattributes *Annotation*

# ---------------------------------------------------------------------------
# kotlinx.serialization -- generated serializers are referenced reflectively.
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.github.mihomo.android.**$$serializer { *; }
-keepclassmembers class com.github.mihomo.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.github.mihomo.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# Third-party engines
# ---------------------------------------------------------------------------
# QuickJS is driven through JNI.
-keep class com.quickjs.** { *; }

# Rhino is invoked reflectively by the script engine.
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }

# SnakeYAML / Gson / OkHttp ship their own (or need no) keep rules; silence the
# optional-dependency warnings they emit.
-dontwarn org.yaml.snakeyaml.**
-dontwarn com.google.gson.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
