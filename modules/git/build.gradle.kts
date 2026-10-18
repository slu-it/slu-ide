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
    // Deliberate module API dependency: the .git folder is watched to refresh the status (ADR 0013).
    api(project(":modules:file-watcher"))
    // Deliberate module API dependency: git's tools run only in folders the user trusts (ADR 0053); GitTrustCheck is a
    // ProjectTrustCheck.
    api(project(":modules:project-trust"))
    implementation(libs.spring.context)
}
