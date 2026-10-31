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
    // Deliberate module API dependency: the Converting tool is shown as an EditorTab (ADR 0110, ADR 0115).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: the sides are in-memory code editors highlighted per format, and a
    // TextFormat names its SyntaxHighlighter (ADR 0115).
    api(project(":modules:file-viewers"))
    // The sides use the editor font; Converting takes FontSettings.
    api(project(":modules:settings"))
    // The data family is a Spring bean, collected with the families of other modules (ADR 0115).
    implementation(libs.spring.context)
    // The data tree between data formats (JsonNode) and the JSON format.
    implementation(libs.jackson.databind)
    // Reads and writes TOML in the data family.
    implementation(libs.jackson.dataformat.toml)
    // Reads and writes YAML in the data family, as the YAML preview reads it (ADR 0079).
    implementation(libs.snakeyaml)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    // The UI tests read and edit the sides' code areas.
    testImplementation(libs.richtextfx)
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
