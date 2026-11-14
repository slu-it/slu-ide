plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    // TestImages: hand-built image files and headers of any claimed size, for the tests of the modules that check
    // images before they decode them.
    `java-test-fixtures`
}

// The size caps of images from outside (ADR 0175): a plain library without Spring and without JavaFX, so that every
// module that decodes images can check them first. No dependencies.
