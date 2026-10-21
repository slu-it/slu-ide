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
    // Deliberate module API dependency: the editor asks the file viewers for the view of a file.
    api(project(":modules:file-viewers"))
    // Deliberate module API dependency: open files are watched for changes on disk (ADR 0006).
    api(project(":modules:file-watcher"))
    // Deliberate module API dependency: file views use the editor font from the settings (ADR 0008).
    api(project(":modules:settings"))
    implementation(libs.spring.context)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
    // The UI tests open files in real previews.
    testImplementation(project(":modules:previews"))
    // Diff views are RichTextFX code areas; the tests read their text.
    testImplementation(libs.richtextfx)
}
