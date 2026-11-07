plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    // Server states and installer availability are JavaFX observables (base); results and states reach the FX thread
    // with Platform.runLater (graphics).
    modules("javafx.base", "javafx.graphics")
}

dependencies {
    // Consumers use lsp4j's protocol types (positions, locations, completion items) through the session's API
    // (ADR 0142).
    api(libs.lsp4j)
    // Deliberate module API dependency: ServerInstaller.install takes the window's BackgroundTasks; process start,
    // trust waits, stderr readers and shutdown run on logged NamedThreads (ADR 0060, ADR 0061).
    api(project(":modules:background-tasks"))
}
