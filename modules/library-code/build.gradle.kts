plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars. Only properties.
    version = libs.versions.javafx.get()
    modules("javafx.base")
}

dependencies {
    // Deliberate module API dependency: "Open library code read-only" is stored in settings.properties, and
    // LibraryCodeSettings takes the SettingsStore (ADR 0008, ADR 0150).
    api(project(":modules:settings"))
    implementation(libs.spring.context)
}
