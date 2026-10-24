import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

// Release signing can be loaded from embedded app/nexus-release.jks,
// with optional overrides from local.properties or RELEASE_* environment variables.
val signingProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    (signingProperties.getProperty(propertyKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val embeddedKeystore = file("nexus-release.jks")
val releaseStoreFile = signingValue("releaseStoreFile", "RELEASE_STORE_FILE")
    ?.let { file(it) } ?: embeddedKeystore.takeIf { it.exists() }
val releaseKeyAlias = signingValue("releaseKeyAlias", "RELEASE_KEY_ALIAS") ?: "nexus"
val releaseKeyPassword = signingValue("releaseKeyPassword", "RELEASE_KEY_PASSWORD") ?: "34FbaR6f58zuReU98JlHb96fsMU65Szz"
val releaseStorePassword = signingValue("releaseStorePassword", "RELEASE_STORE_PASSWORD") ?: "34FbaR6f58zuReU98JlHb96fsMU65Szz"
val hasReleaseSigning = (releaseStoreFile != null) && releaseStoreFile.exists()

android {
    namespace = "com.github.mihomo.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.mihomo.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.4-Alpha.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinking/obfuscation stays off: it crashed on startup with the native core
            // when last tried on device. The keep rules in proguard-rules.pro are kept ready
            // (native bridge callbacks, quickjs-kt, kotlinx.serialization). To re-enable:
            // flip both flags below, assemble a release APK and smoke-test on a REAL device —
            // an x86_64 emulator cannot run the arm64-only Go core, so it cannot verify this.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
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
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    
    // Compose
    implementation("androidx.compose.ui:ui:1.7.5")
    implementation("androidx.compose.ui:ui-graphics:1.7.5")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.5")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    
    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Network & Config
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.2")
    // Maintained QuickJS binding; unlike the previous engine it supports interrupting
    // native evaluation (evaluationTimeoutMillis), so an infinite-loop script can no longer
    // leak a spinning thread past its timeout.
    implementation("io.github.dokar3:quickjs-kt-android:1.0.15")

    debugImplementation("androidx.compose.ui:ui-tooling:1.7.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.5")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
}

// The Android artifact's native library cannot load on the desktop JVM, so local unit
// tests substitute the JVM variant of the same version (see ConfigScriptEngineTest for
// real-engine tests, including the infinite-loop interruption).
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.15"))
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    ignoreFailures = false
}
