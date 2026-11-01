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
    // Deliberate module dependency: the docker executable and its environment are looked up in the login shell
    // (loginShellPrints, ADR 0087, ADR 0117); no terminal type is part of this module's API.
    implementation(project(":modules:terminal"))
    // Deliberate module API dependency: DockerSettings stores the executable with SettingsStore and adds a
    // SettingsPage (ADR 0067).
    api(project(":modules:settings"))
    // Deliberate module dependency: the session's loads and actions run on logged threads (ADR 0060).
    implementation(project(":modules:background-tasks"))
    implementation(libs.spring.context)
    // Reads the JSON the docker and podman CLIs print.
    implementation(libs.jackson.databind)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
