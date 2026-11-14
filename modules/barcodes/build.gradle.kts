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
    // Deliberate module API dependency: the Barcodes tool is shown as an EditorTab (ADR 0110, ADR 0174).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: it adds "Show Selection as Barcode" to the code editors (ADR 0174).
    api(project(":modules:file-viewers"))
    // Deliberate module API dependency: the last format and options live in the shared data file (ADR 0101, ADR 0174).
    api(project(":modules:project-data"))
    // The read of an image runs on a named thread (NamedThreads, ADR 0060, ADR 0174).
    implementation(project(":modules:background-tasks"))
    implementation(libs.spring.context)
    // The `barcodes` field of the shared data file is a JSON node.
    implementation(libs.jackson.databind)
    // Generates and reads the barcodes; only ZXing core, without the AWT-based javase module (ADR 0174).
    implementation(libs.zxing.core)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
