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
    // Deliberate module API dependency: CommandRunSession takes the window's Terminals, and the runner shows each run
    // in an embedded terminal (ADR 0086, ADR 0088).
    api(project(":modules:terminal"))
    // Deliberate module API dependency: CommandRunSession takes the window's BackgroundTasks for the run rows; the
    // stop thread is one of NamedThreads (ADRs 0060, 0061).
    api(project(":modules:background-tasks"))
    // Deliberate module API dependency: the runner's activity is the tab marker of a file view (ViewActivity).
    api(project(":modules:file-viewers"))
    // Deliberate module API dependency: ArgumentHistories keeps the run-argument history of each file in the project data
    // (ADR 0090, ADR 0091).
    api(project(":modules:project-data"))
    // Deliberate module API dependency: ArgumentHistories stores whether the history sections are expanded in the
    // settings (ADR 0091).
    api(project(":modules:settings"))
    implementation(libs.spring.context)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
