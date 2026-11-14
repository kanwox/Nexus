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
    }
}

// Gradle dependency verification: every resolved artifact is checked against a
// pinned SHA-256 in gradle/verification-metadata.xml whenever that file exists,
// guarding against compromised or tampered upstream artifacts.
rootProject.name = "Mihomo"
include(":app")
