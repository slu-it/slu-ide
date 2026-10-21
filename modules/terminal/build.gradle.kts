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
    // Deliberate module API dependency: terminals are shown as editor tabs (EditorTab).
    api(project(":modules:editor"))
    // Deliberate module API dependency: terminals use the terminal font, store their scroll settings with
    // SettingsStore and add a SettingsPage (ADR 0008, 0067).
    api(project(":modules:settings"))
    implementation(libs.spring.context)
    implementation(libs.pty4j)
    implementation(libs.jediterm.core)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
