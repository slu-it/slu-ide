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
    // Deliberate module API dependency: the docker executable and its environment are looked up in the login shell
    // (loginShellPrints, ADR 0087, ADR 0117), and Docker takes the window's Terminals to open container shells as
    // terminal tabs (ADR 0118).
    api(project(":modules:terminal"))
    // Deliberate module API dependency: the Docker tab is shown as an EditorTab, opened from the Services menu
    // (ADR 0110, ADR 0118).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: the Inspect tabs show the JSON in a read-only, highlighted CodeDisplay
    // (CodeEditors, Highlighters), and ComposeFileViewer is a public SourceFileViewer, NamedFileViewer and
    // RunnableFileViewer showing a CodeEditor with a gutter column (ADR 0119).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: Compose commands run with a CommandRunner in a RunPane, through the window's
    // CommandRunSession, like shell scripts (ADR 0088, ADR 0119).
    implementation(project(":modules:command-runs"))
    // Deliberate module API dependency: DockerSettings stores the executable with SettingsStore and adds a
    // SettingsPage (ADR 0067).
    api(project(":modules:settings"))
    // Deliberate module API dependency: the session's loads and actions run on logged threads (ADR 0060), and Docker
    // takes the window's BackgroundTasks for its long operations (pull, prune, remove image, create; ADR 0061).
    api(project(":modules:background-tasks"))
    // Deliberate module API dependency: ComposeStore keeps the Compose options of each file in the project data
    // (ProjectDataStore, ADR 0090).
    api(project(":modules:project-data"))
    implementation(libs.spring.context)
    // Finds the line of each service in a Compose file from the YAML node marks (serviceLines), as the YAML preview
    // reads YAML (ADR 0079).
    implementation(libs.snakeyaml)
    // Reads the JSON the docker and podman CLIs print.
    implementation(libs.jackson.databind)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
