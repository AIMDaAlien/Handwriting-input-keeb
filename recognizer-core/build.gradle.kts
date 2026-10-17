// Pure-JVM Kotlin module. No Android dependency on purpose: everything here
// (ink model, preprocessing, rasterization, commit policy, recognizer API)
// must be unit-testable on a laptop or CI box without an Android SDK.
// Kotlin 2.1.0 matches FUTO Keyboard's build so this drops into the fork as-is.
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

group = "org.aim.handwriting"
version = "0.0.1"

// Emit Java 17 bytecode on whatever JDK is present (17 or newer). FUTO itself
// targets 1.8; 17 is fine for a library consumed by its Kotlin 2.1 build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
