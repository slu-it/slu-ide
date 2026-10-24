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
    // Deliberate module API dependency: GradleTrustCheck is a ProjectTrustCheck (ADR 0053).
    api(project(":modules:project-trust"))
    // Deliberate module API dependency: the run and load rows (BackgroundTasks) and NamedThreads in the view, ADRs
    // 0060, 0061.
    api(project(":modules:background-tasks"))
    // Deliberate module dependency: the global gradle check is loginShellFinds, and the view runs Gradle in embedded
    // terminals.
    implementation(project(":modules:terminal"))
    // The task list is JSON written by the init script (ADR 0085).
    implementation(libs.jackson.databind)
    implementation(libs.spring.context)
}
