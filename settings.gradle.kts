pluginManagement {
    // Aliyun is a useful accelerator for local builds in mainland China, but it is an extra
    // network hop (and occasionally stale/slow) on GitHub runners. CI therefore resolves from
    // the official repositories only; local builds keep the mirrors.
    //
    // The mirrors come FIRST on purpose: Gradle tries the repositories in order and fails
    // the whole resolution on the first unreachable host instead of falling through to the
    // next one. With google() first, a build that needs a not-yet-cached artifact dies on
    // dl.google.com even though the mirror has it.
    val useChinaMirrors = System.getenv("CI").isNullOrBlank()
    repositories {
        if (useChinaMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    val useChinaMirrors = System.getenv("CI").isNullOrBlank()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useChinaMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// Gradle dependency verification: every resolved artifact is checked against a
// pinned SHA-256 in gradle/verification-metadata.xml. JitPack builds artifacts
// from GitHub sources on demand, which is a materially weaker trust anchor than
// Maven Central — without this, a compromised upstream lands in the APK silently.
//
// Gradle 9 moved this off the Settings `dependencyVerification { }` block: the
// mode is now a per-configuration switch on ResolutionStrategy, and the metadata
// file itself carries the trusted keys. The switch lives in app/build.gradle.kts.
rootProject.name = "Mihomo"
include(":app")
