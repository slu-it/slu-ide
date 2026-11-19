plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Guesses the type of a text by hand-written rules (ADR 0187): a plain library without Spring, JavaFX or third-party
// libraries, so that any module can name pasted text. No dependencies.
