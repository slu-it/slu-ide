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
    // Deliberate module API dependency: the Codecs tool is shown as an EditorTab (ADR 0110, ADR 0112).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: it adds "Open Selection in Codecs" to the code editors (ADR 0108).
    api(project(":modules:file-viewers"))
    implementation(libs.spring.context)
    // Pretty-prints the header and payload of JWTs.
    implementation(libs.jackson.databind)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
