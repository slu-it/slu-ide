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
    // Deliberate module API dependency: Todos keeps the TODOs in the project data and the shared data file
    // (ADR 0090, ADR 0101).
    api(project(":modules:project-data"))
    implementation(libs.spring.context)

    // The native Markdown renderer of the TODO descriptions.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.autolink)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
