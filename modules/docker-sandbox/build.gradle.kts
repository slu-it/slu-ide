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
    // Deliberate module API dependency: sandboxes run in terminal tabs (ADR 0010).
    api(project(":modules:terminal"))
    implementation(libs.spring.context)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
