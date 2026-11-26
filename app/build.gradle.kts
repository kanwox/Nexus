import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
}

// ---------------------------------------------------------------------------
// Release signing configuration
//
// INTENTIONAL DESIGN (do not report as a hardcoded secret / key leak in reviews):
// Keeping `app/nexus-release.jks` and its fallback credentials in the repository is
// deliberate so local offline builds and CI releases work out of the box with a
// consistent signature. Callers who need a private key can still override them via
// `local.properties` or `RELEASE_*` environment variables.
//
// The inputs are read through the Gradle Provider API (not File.inputStream() and
// not System.getenv()) so that they become tracked configuration-cache inputs.
// Otherwise a changed password or keystore path silently reuses a stale
// configuration-cache entry and the build signs with the OLD credentials.
//
// The resolution ORDER and the hardcoded fallback values are unchanged:
//   local.properties  ->  RELEASE_* environment variable  ->  built-in fallback
// ---------------------------------------------------------------------------
val localPropertiesText: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .getOrElse("")

val signingProperties: Properties = Properties().apply {
    if (localPropertiesText.isNotBlank()) {
        localPropertiesText.reader().use { reader -> load(reader) }
    }
}
/** Reads a signing value from local.properties, then the environment, then [fallback]. */
fun signingValue(
    propertyKey: String,
    envKey: String,
    fallback: String? = null,
): String? {
    val fromProperties = signingProperties.getProperty(propertyKey)
    if (!fromProperties.isNullOrBlank()) return fromProperties
    val fromEnv = providers.environmentVariable(envKey).orNull
    if (!fromEnv.isNullOrBlank()) return fromEnv
    return fallback?.takeIf { it.isNotBlank() }
}

val embeddedKeystore = file("nexus-release.jks")
val releaseStoreFile = signingValue("releaseStoreFile", "RELEASE_STORE_FILE")
    ?.let { file(it) } ?: embeddedKeystore.takeIf { it.exists() }
val releaseKeyAlias = signingValue("releaseKeyAlias", "RELEASE_KEY_ALIAS", "nexus") ?: "nexus"
val releaseKeyPassword = signingValue("releaseKeyPassword", "RELEASE_KEY_PASSWORD", "34FbaR6f58zuReU98JlHb96fsMU65Szz")
    ?: "34FbaR6f58zuReU98JlHb96fsMU65Szz"
val releaseStorePassword = signingValue("releaseStorePassword", "RELEASE_STORE_PASSWORD", "34FbaR6f58zuReU98JlHb96fsMU65Szz")
    ?: "34FbaR6f58zuReU98JlHb96fsMU65Szz"

// A password must be resolvable for BOTH the keystore and the key; a keystore file
// without usable credentials cannot produce a signed release artifact.
val hasReleasePassword = !releaseKeyPassword.isNullOrBlank() && !releaseStorePassword.isNullOrBlank()
val hasReleaseSigning = (releaseStoreFile != null) && releaseStoreFile.exists() && hasReleasePassword

android {
    namespace = "com.github.mihomo.android"
    // Compose 1.12.x requires compiling against API 37; the build-tools version is
    // pinned so AGP never resolves a different default (and tries to download it).
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.github.mihomo.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 28
        versionName = "1.0.8"

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
            // R8 shrinking is enabled. The first minified release (v1.0.4-Alpha.5) crashed
            // at startup: libbridge.so's JNI_OnLoad looks up kotlinx.coroutines methods by
            // name, which R8 had stripped — fixed with an explicit keep rule in
            // proguard-rules.pro. Note an x86_64 emulator cannot verify this path (the
            // arm64-only Go core dies there under translation regardless); verify on a
            // real device. If a device ever regresses, flip both flags back to false.
            isMinifyEnabled = true
            isShrinkResources = true
            // NOTE: there is deliberately NO debug-signing fallback here. A release APK
            // signed with the debug key is indistinguishable from a real release for a
            // user who installs it, and it would be published by CI as a "release".
            // When release signing is not configured the build fails loudly in
            // `validateReleaseSigning` (wired into `preReleaseBuild`) instead.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
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
            // Compresses arm64-v8a native libraries inside the APK (keeping release APK ~32 MiB
            // instead of ~85 MiB). Installed extraction still unpacks 16 KB ELF-aligned .so files,
            // trading ~57 MB of installed disk storage for smaller APK downloads.
            useLegacyPackaging = true
        }
    }

    lint {
        // QUERY_ALL_PACKAGES is legitimately required: the per-app proxy feature
        // enumerates the installed applications to build the allow/deny list handed
        // to VpnService.Builder.addAllowed/DisallowedApplication. The rule is
        // disabled here (rather than suppressed in the manifest) so the reason stays
        // next to the build configuration instead of being hidden in the manifest.
        disable += "QueryAllPackagesPermission"
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose (versions aligned through Compose BOM; keeps foundation/animation/ripple in sync with ui 1.12.1)
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines & Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")

    // Network & Config
    // okhttp 5.x is source-compatible with the 4.x API surface this app uses
    // (Request/Response/RequestBody/MediaType/OkHttpClient.Builder are unchanged).
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.yaml:snakeyaml:2.7")
    // Maintained QuickJS binding; unlike the previous engine it supports interrupting
    // native evaluation (evaluationTimeoutMillis), so an infinite-loop script can no longer
    // leak a spinning thread past its timeout.
    implementation("io.github.dokar3:quickjs-kt-android:1.0.15")

    // Background subscription auto-update scheduling
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // QR subscription import: zxing decodes, CameraX feeds the analyzer
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2") {
        // QrScanScreen uses PreviewView only; exclude unused video capture and legacy AppCompat/Fragment transitives.
        exclude(group = "androidx.camera", module = "camera-video")
        exclude(group = "androidx.appcompat", module = "appcompat")
        exclude(group = "androidx.fragment", module = "fragment")
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

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


// Enforces the executable part of UI_GUIDELINES.md: feature UI must not reintroduce raw
// font sizes or hex colors now that DesignTokens/LoonTextStyles/LoonTypeScale exist.
// The theme package is the only place allowed to contain literal values.
//
// The patterns deliberately cover the forms the previous pair of regexes missed:
// a conditional font size (`fontSize = if (c) 11.5.sp else 13.sp`), a raw
// lineHeight / letterSpacing literal, `Color( 0x…)` with whitespace, and the
// named Compose colors (`Color.White` and friends) that no token can be derived from.
val uiTokenPatterns: List<Pair<Regex, String>> = listOf(
    Regex("""\bfontSize\s*=\s*[^,)\n]*?\d+(\.\d+)?\s*\.\s*sp""") to
        "raw fontSize literal (use LoonTextStyles/LoonTypeScale)",
    Regex("""\bfontSize\s*=\s*\d+(\.\d+)?\b""") to
        "raw fontSize literal (use LoonTextStyles/LoonTypeScale)",
    Regex("""\blineHeight\s*=\s*[^,)\n]*?\d+(\.\d+)?\s*\.\s*sp""") to
        "raw lineHeight literal (use LoonLineHeight)",
    Regex("""\bletterSpacing\s*=\s*[^,)\n]*?\d+(\.\d+)?\s*\.\s*sp""") to
        "raw letterSpacing literal (use LoonTextStyles)",
    Regex("""\bColor\s*\(\s*0x""") to
        "raw Color(0x…) literal (use a theme token)",
    Regex("""\bColor\.(White|Black|Red|Green|Blue|Gray|Grey|Yellow|Cyan|Magenta)\b""") to
        "named Compose color (use a theme token)",
)

val checkUiTokens = tasks.register("checkUiTokens") {
    group = "verification"
    description = "Fails when feature UI contains raw colors or font sizes instead of theme tokens."
    // The whole source tree (except theme) is scanned incrementally so raw colors and
    // font sizes cannot reappear anywhere outside the theme package.
    val sourceRoot = layout.projectDirectory.dir("src/main/java/com/github/mihomo/android")
    val sourceFiles = sourceRoot.asFileTree.matching {
        include("**/*.kt")
        exclude("**/theme/**")
    }
    inputs.files(sourceFiles)
        .withPropertyName("sourceFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // The pattern list is copied into the task's own local scope: capturing the
    // script-level list directly would serialize a Gradle script object reference,
    // which the configuration cache rejects.
    val patterns: List<Pair<Regex, String>> = uiTokenPatterns
    // Declared as a plain List<String> so Gradle can track it as an input value.
    inputs.property("patterns", patterns.map { (regex, label) -> "${regex.pattern}::$label" })
    val reportFile = layout.buildDirectory.file("reports/ui-tokens-check.txt")
    outputs.file(reportFile).withPropertyName("reportFile")
    doLast {
        fun stripCommentsAndLiterals(source: String): String {
            val n = source.length
            val out = StringBuilder(n)
            var i = 0

            fun scanString(triple: Boolean, scanCode: (Boolean) -> Unit) {
                while (i < n) {
                    if (triple) {
                        if (i + 2 < n && source[i] == '"' && source[i + 1] == '"' && source[i + 2] == '"') {
                            if (i + 3 < n && source[i + 3] == '"') {
                                out.append(' '); i++
                                continue
                            }
                            out.append("   "); i += 3
                            return
                        }
                    } else {
                        if (source[i] == '\\') {
                            out.append(' '); i++
                            if (i < n) {
                                out.append(if (source[i] == '\n') '\n' else ' ')
                                i++
                            }
                            continue
                        }
                        if (source[i] == '"') {
                            out.append(' '); i++
                            return
                        }
                        if (source[i] == '\n') {
                            out.append('\n'); i++
                            return
                        }
                    }
                    if (source[i] == '$' && i + 1 < n && source[i + 1] == '{') {
                        out.append('$'); out.append('{'); i += 2
                        scanCode(true)
                        continue
                    }
                    out.append(if (source[i] == '\n') '\n' else ' ')
                    i++
                }
            }

            fun scanCode(stopAtClosingBrace: Boolean) {
                var braceDepth = if (stopAtClosingBrace) 1 else 0
                while (i < n) {
                    val ch = source[i]
                    if (ch == '/' && i + 1 < n && source[i + 1] == '/') {
                        out.append("  "); i += 2
                        while (i < n && source[i] != '\n') {
                            out.append(' '); i++
                        }
                        continue
                    }
                    if (ch == '/' && i + 1 < n && source[i + 1] == '*') {
                        out.append("  "); i += 2
                        var depth = 1
                        while (i < n && depth > 0) {
                            if (i + 1 < n && source[i] == '/' && source[i + 1] == '*') {
                                out.append("  "); i += 2; depth++
                            } else if (i + 1 < n && source[i] == '*' && source[i + 1] == '/') {
                                out.append("  "); i += 2; depth--
                            } else {
                                out.append(if (source[i] == '\n') '\n' else ' '); i++
                            }
                        }
                        continue
                    }
                    if (ch == '\'') {
                        out.append(' '); i++
                        if (i < n && source[i] == '\\') {
                            out.append(' '); i++
                        }
                        while (i < n && source[i] != '\'' && source[i] != '\n') {
                            out.append(' '); i++
                        }
                        if (i < n && source[i] == '\'') {
                            out.append(' '); i++
                        }
                        continue
                    }
                    if (ch == '"') {
                        if (i + 2 < n && source[i + 1] == '"' && source[i + 2] == '"') {
                            out.append("   "); i += 3
                            scanString(true, ::scanCode)
                        } else {
                            out.append(' '); i++
                            scanString(false, ::scanCode)
                        }
                        continue
                    }
                    if (stopAtClosingBrace) {
                        if (ch == '{') {
                            braceDepth++
                        } else if (ch == '}') {
                            braceDepth--
                            out.append(ch); i++
                            if (braceDepth == 0) return
                            continue
                        }
                    }
                    out.append(ch); i++
                }
            }

            scanCode(false)
            return out.toString()
        }

        val root = sourceRoot.asFile
        val violations = mutableListOf<String>()
        var scannedCount = 0
        root.walkTopDown()
            .filter {
                it.isFile && it.extension == "kt" &&
                    // The theme package is the only place allowed to hold literal values.
                    "theme" !in it.toRelativeString(root).replace('\\', '/').split('/')
            }
            .forEach { file ->
                scannedCount++
                val text = stripCommentsAndLiterals(file.readText())
                patterns.forEach { (regex, label) ->
                    regex.findAll(text).forEach { match ->
                        val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                        violations += "${file.toRelativeString(root)}:$line: $label"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "UI token violations (UI_GUIDELINES.md §3):\n" + violations.joinToString("\n")
            )
        }
        val outFile = reportFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText("OK: scanned $scannedCount Kotlin files with 0 UI token violations.\n")
    }
}
tasks.named("check").configure { dependsOn(checkUiTokens) }
// Every build enforces the token contract, not just the full `check` lifecycle.
tasks.named("preBuild").configure { dependsOn(checkUiTokens) }

// A release artifact must be signed with the release key. The old build silently fell
// back to the debug key, which produces an APK that looks like a release but carries a
// publicly known signature — and CI would publish it as one.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    group = "verification"
    description = "Fails the release build when release signing is not configured."
    // Both values are copied into the task's own scope: capturing the script-level
    // value (or the `gradle` object) directly would serialize a Gradle script object
    // reference, which the configuration cache rejects.
    val signingConfigured: Boolean = hasReleaseSigning
    val requestedTasks: List<String> = gradle.startParameter.taskNames.toList()
    onlyIf { requestedTasks.any { it.contains("Release", ignoreCase = true) } }
    doLast {
        if (!signingConfigured) {
            throw GradleException(
                "Release signing is not configured. Set releaseStoreFile / releaseKeyAlias / " +
                    "releaseKeyPassword / releaseStorePassword in local.properties or via the " +
                    "RELEASE_* environment variables. The release build refuses to fall back to " +
                    "the debug signing key."
            )
        }
    }
}
tasks.matching { it.name.startsWith("assemble") && it.name.contains("Release") }
    .configureEach { dependsOn(validateReleaseSigning) }
