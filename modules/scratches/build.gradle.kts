plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

dependencies {
    implementation(libs.spring.context)
    // Deliberate module dependency: the type picker shows the explorer's file-type icons (ADR 0047).
    implementation(project(":modules:file-icons"))

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
