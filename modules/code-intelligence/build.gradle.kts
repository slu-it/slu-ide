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
    // Deliberate module API dependency, in the editor family: CodeIntelligence is a CodeEditorExtension, and its
    // features work through ExtensibleCodeEditor (ADR 0108, ADR 0141, ADR 0145).
    api(project(":modules:file-viewers"))
    // Deliberate module API dependency: CodeIntelligence takes the window's LanguageServers, and the features use
    // lsp4j's protocol types through the sessions (ADR 0142, ADR 0145).
    api(project(":modules:language-servers"))
    // Deliberate module dependency: Install in the banner shows the download as a row of the window's BackgroundTasks;
    // Don't Ask Again is written on a logged NamedThreads thread (ADR 0060, ADR 0061).
    implementation(project(":modules:background-tasks"))
    // Deliberate module API dependency: Don't Ask Again of the install banner is kept per language in the shared data
    // file (field `codeIntelligence`); InstallPrompts takes the ProjectDataStore (ADR 0101, ADR 0145).
    api(project(":modules:project-data"))
    implementation(libs.spring.context)
    // Hover docs arrive as Markdown: commonmark parses it into blocks the popups show as JavaFX text (ADR 0147).
    implementation(libs.commonmark)

    // Logic tests drive bindings, banners and restarts over in-process fake language servers.
    testImplementation(testFixtures(project(":modules:language-servers")))
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
