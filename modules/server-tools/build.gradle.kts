plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
    // ServerToolFakes: the tests of the language modules run their installer, page and update check over a fake
    // download and a queued executor.
    `java-test-fixtures`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    modules("javafx.controls")
    // The test fixtures build ToolInstallers, whose states are JavaFX properties.
    configurations = arrayOf("implementation", "testFixturesImplementation")
}

dependencies {
    // Deliberate module API dependency: ServerToolInstaller is a ServerInstaller and its command a ServerCommand
    // (ADR 0142, ADR 0159).
    api(project(":modules:language-servers"))
    // Deliberate module API dependency: a server tool is installed, updated and rolled back by a ToolInstaller; its
    // InstallState, InstalledTool, ToolRelease and Checksum are in this module's API (ADR 0143, ADR 0159).
    api(project(":modules:local-models"))
    // Deliberate module API dependency: ServerToolSettingsPage is a settings page and ServerPathSetting stores the
    // custom executable in settings.properties (ADR 0008, ADR 0067).
    api(project(":modules:settings"))
    // Deliberate module dependency: the smoke test and the update check run on logged NamedThreads (ADR 0060).
    implementation(project(":modules:background-tasks"))
    // Reads the server's answer to initialize in the smoke test (JSON).
    implementation(libs.jackson.databind)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
