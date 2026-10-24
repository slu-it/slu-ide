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
    // Deliberate module API dependency: GradleSession takes the window's CommandRunSession, and the view runs tasks and
    // loads with a CommandRunner in a RunPane (ADR 0088).
    api(project(":modules:command-runs"))
    // The session's thread is one of NamedThreads (ADR 0060).
    implementation(project(":modules:background-tasks"))
    // Deliberate module dependency: the global gradle check is loginShellFinds.
    implementation(project(":modules:terminal"))
    // Deliberate module API dependency: the viewers of build files are public SourceFileViewers and NamedFileViewers
    // showing a CodeEditor (ADR 0047).
    api(project(":modules:file-viewers"))
    // The task list is JSON written by the init script (ADR 0085).
    implementation(libs.jackson.databind)
    implementation(libs.spring.context)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
