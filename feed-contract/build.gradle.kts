plugins {
    id("java-library")
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

group = "cloud.trotter.feed"
version = "0.0.0-local"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        // #841: the root's subprojects {} -Werror gate does not reach an included build.
        allWarningsAsErrors.set(true)
    }
}

// The one pin file (ADR-0013 §8) ships on the classpath as `/feed-pins.json`; workflows read
// `feed-contract/pins/feed-pins.json` directly.
sourceSets {
    main {
        resources.srcDir("pins")
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

// mirrors the root subprojects block (#841/#1093), which does not reach an included build
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = false
    }
    if (JavaVersion.current() >= JavaVersion.VERSION_24) {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
    }
}
