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
    // Deliberate module API dependency: the Coloring tool is shown as an EditorTab (ADR 0109, ADR 0110).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: it extends the code editors with gutter swatches and a menu item (ADR 0108).
    api(project(":modules:file-viewers"))
    // Deliberate module API dependency: saved colors live in the project data (ADR 0090, ADR 0109).
    api(project(":modules:project-data"))
    implementation(libs.spring.context)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
