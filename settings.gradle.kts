pluginManagement {
    // Aliyun is a useful accelerator for local builds in mainland China, but it is an extra
    // network hop (and occasionally stale/slow) on GitHub runners. CI therefore resolves from
    // the official repositories only; local builds keep the mirrors.
    val useChinaMirrors = System.getenv("CI").isNullOrBlank()
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        if (useChinaMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    val useChinaMirrors = System.getenv("CI").isNullOrBlank()
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        if (useChinaMirrors) {
            maven { url = uri("https://maven.aliyun.com/repository/public") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
        }
    }
}

rootProject.name = "Mihomo"
include(":app")
