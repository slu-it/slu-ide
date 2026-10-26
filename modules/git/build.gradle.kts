plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    modules("javafx.graphics")
}

dependencies {
    // Deliberate module API dependency: the status service runs on a logged thread (ADR 0060), and its public
    // constructor takes the window's BackgroundTasks (ADR 0061).
    api(project(":modules:background-tasks"))
    // Deliberate module API dependency: the .git folder is watched to refresh the status (ADR 0013).
    api(project(":modules:file-watcher"))
    // Deliberate module API dependency: git's tools run only in folders the user trusts (ADR 0053); GitTrustCheck is a
    // ProjectTrustCheck.
    api(project(":modules:project-trust"))
    // Deliberate module dependency: diffs decode file contents as the editor does, UTF-8 or Windows-1252 (ADR 0098).
    implementation(project(":modules:text-editing"))
    implementation(libs.spring.context)
}

tasks.named<Test>("test") {
    // The tests mostly wait on git processes, not on the CPU; forks isolate the global log sinks of the tests.
    maxParallelForks = 3
}
