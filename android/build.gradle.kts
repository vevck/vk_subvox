buildscript {
    // Put the Android Gradle plugin on the root classpath (same classloader as the Kotlin
    // plugin) only when an Android SDK is configured. Must mirror settings.gradle.kts.
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        rootDir.resolve("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
    if (hasAndroidSdk) {
        repositories { google(); mavenCentral() }
        dependencies { classpath("com.android.tools.build:gradle:8.9.1") }
    }
}

plugins {
    kotlin("jvm") version "2.1.20" apply false
    kotlin("android") version "2.1.20" apply false
}
