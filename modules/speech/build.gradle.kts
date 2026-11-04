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
    // Deliberate module API dependency: the Read Aloud tool is shown as an EditorTab (ADR 0110, ADR 0124).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: the tab's text is a TextEditor, and "Read Aloud" joins the code editors'
    // context menu as a CodeEditorExtension (ADR 0108, ADR 0124).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: one playback app-wide with the media player (PlaybackCoordinator), and the same
    // speeds (PlaybackSpeeds) (ADR 0123, ADR 0124).
    implementation(project(":modules:media"))
    // Deliberate module dependency: espeak-ng is looked up through the login shell (loginShellPrints, ADR 0124).
    implementation(project(":modules:terminal"))
    // Deliberate module dependency: the model download is a background task; synthesis runs on logged named threads
    // (ADR 0060, ADR 0061).
    implementation(project(":modules:background-tasks"))
    // Deliberate module dependency: Settings → Speech (espeak-ng path, model variant) is a settings page.
    implementation(project(":modules:settings"))
    // Deliberate module dependency: the last voice and speed are kept in the shared data file (field `speech`,
    // ADR 0101).
    implementation(project(":modules:project-data"))
    // Runs the Kokoro model; the fat jar keeps the build platform's natives only (ADR 0124).
    implementation(libs.onnxruntime)
    // Reads the Kokoro vocabulary (JSON).
    implementation(libs.jackson.databind)
    implementation(libs.spring.context)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    // The UI tests read the styles of the editor's text area.
    testImplementation(libs.richtextfx)
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
