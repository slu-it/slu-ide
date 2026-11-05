plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    // ModelInstaller's states are JavaFX properties (base); its results reach the FX thread with Platform.runLater
    // (graphics).
    modules("javafx.base", "javafx.graphics")
}

dependencies {
    // Deliberate module API dependency: a download shows as a cancellable BackgroundTasks row (part of
    // ModelInstaller.download); downloads, watchdogs and idle unloads run on logged NamedThreads (ADR 0060,
    // ADR 0061, ADR 0125).
    api(project(":modules:background-tasks"))
}
