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
    // Markdown images are loaded on a named thread (NamedThreads, ADR 0060, ADR 0175).
    implementation(project(":modules:background-tasks"))
    // Markdown images are read bounded and refused from their header above the pixel caps before decoding (ADR 0175).
    implementation(project(":modules:image-limits"))
    implementation(libs.spring.context)

    // The native Markdown renderer of the TODO descriptions.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.commonmark.ext.autolink)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    // Hand-built image files and bomb headers (TestImages).
    testImplementation(testFixtures(project(":modules:image-limits")))
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
