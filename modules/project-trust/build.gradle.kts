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
    // Deliberate module API dependency: the trust checks run on a logged thread (ADR 0060), and ProjectTrust's public
    // constructor takes the window's BackgroundTasks (ADR 0061).
    api(project(":modules:background-tasks"))
    // Deliberate module API dependency: TrustedProjects takes the ProjectDataStore (ADR 0090).
    api(project(":modules:project-data"))
    implementation(libs.spring.context)
}
