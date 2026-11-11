plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // Parses the chapters (XHTML) and writes the sanitised page (ADR 0165).
    implementation(libs.jsoup)
}
