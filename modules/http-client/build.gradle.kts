plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    // The file viewers' API uses JavaFX types (FileView.node), which the compiler must see here as well.
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

dependencies {
    // Deliberate module API dependency: formatting responses and token requests run on a logged thread (ADR 0060), and
    // HttpClientSession's public constructor takes the window's BackgroundTasks (ADR 0061).
    api(project(":modules:background-tasks"))
    // Deliberate module API dependency: .http files are shown in the file viewers' code views, with their syntax
    // highlighters for request bodies and scripts.
    api(project(":modules:file-viewers"))
    implementation(libs.spring.context)
    implementation(libs.jackson.databind)
    // Toolbar and gutter icons: Material Design Icons, like the file explorer's (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
    // The editor is a RichTextFX code area; the tests read its text.
    testImplementation(libs.richtextfx)
}
