// DMC Custom Module - build configuration
// This module contains all DMC-specific code. It is NOT part of upstream
// kilo-jetbrains, so it will never conflict during upstream sync.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Access upstream DTOs and services
    implementation(project(":shared"))
    implementation(project(":backend"))

    // Bundled dependencies (must not rely on platform-bundled libs)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(project(":frontend"))
}

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        resources.srcDir("src/main/resources")
    }
}
