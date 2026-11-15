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
    // Deliberate module API dependency, in the editor family: FindInFiles is a CodeEditorExtension and takes
    // FileViewers; the preview is a read-only CodeEditors editor with a highlighter from FileViewers (ADR 0108,
    // ADR 0177).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: the result list's file rows show file-type icons (ADR 0012, ADR 0177).
    implementation(project(":modules:file-icons"))
    // Deliberate module dependency: matching is the find bar's TextSearch with its SearchOptions, and files are read as
    // the editors read them (isTextSample, decodeText, LineEnding) (ADR 0098, ADR 0177).
    implementation(project(":modules:text-editing"))
    // Deliberate module dependency: the walk and the preview loads run on logged NamedThreads threads (ADR 0060).
    implementation(project(":modules:background-tasks"))

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
