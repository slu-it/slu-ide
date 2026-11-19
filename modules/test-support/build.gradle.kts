plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Generic helpers for the tests of other modules (ADR 0186): a plain library without Spring, JavaFX or test frameworks,
// used only as testImplementation, never from main code. No dependencies.
