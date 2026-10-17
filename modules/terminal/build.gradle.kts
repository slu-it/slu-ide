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
    // Deliberate module API dependency: terminals use the terminal font and scroll speeds from the settings (ADR 0008).
    api(project(":modules:settings"))
    implementation(libs.spring.context)
    implementation(libs.pty4j)
    implementation(libs.jediterm.core)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
