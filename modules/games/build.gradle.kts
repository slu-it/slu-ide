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
    // Deliberate module API dependency: a game is shown as an EditorTab (ADR 0110).
    api(project(":modules:editor-area"))
    // Deliberate module dependency: the best times and the last board size are kept in the shared data file (field
    // `minesweeper`, ADR 0101).
    implementation(project(":modules:project-data"))
    // Deliberate module dependency: the preferences are written on a named, logged thread (NamedThreads).
    implementation(project(":modules:background-tasks"))
    // Reads and writes the preferences (JSON).
    implementation(libs.jackson.databind)
    implementation(libs.spring.context)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
