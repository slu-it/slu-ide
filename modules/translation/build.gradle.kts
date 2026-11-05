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
    // Deliberate module API dependency: the Translate tool is shown as an EditorTab (ADR 0110, ADR 0126).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: both sides are TextEditors, and "Translate" joins the code editors' context
    // menu as a CodeEditorExtension (ADR 0108, ADR 0126).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: Settings → Translation is a settings page; the sides use the editor font.
    implementation(project(":modules:settings"))
    // Deliberate module dependency: model downloads are background tasks; translation runs on logged named threads
    // (ADR 0060, ADR 0061).
    implementation(project(":modules:background-tasks"))
    // Deliberate module API dependency: ModelInstaller and ModelSpecs are in its public API (ADR 0125).
    api(project(":modules:local-models"))
    // Deliberate module dependency: the text is split into sentences with SentenceRanges.
    implementation(project(":modules:text-editing"))
    // Deliberate module dependency: the last languages and quality are kept in the shared data file (field
    // `translation`, ADR 0101).
    implementation(project(":modules:project-data"))
    // Runs the Opus-MT models; the fat jar keeps the build platform's natives only (ADR 0124, ADR 0126).
    implementation(libs.onnxruntime)
    // Detects the source language; the fat jar keeps only its small models (ADR 0126).
    implementation(libs.lingua)
    // Reads the models' tokenizer and configuration (JSON).
    implementation(libs.jackson.databind)
    implementation(libs.spring.context)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    // The UI tests read the sides' text areas.
    testImplementation(libs.richtextfx)
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
