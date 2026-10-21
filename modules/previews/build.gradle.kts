plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    // javafx.web: the previews and the image view are WebViews (ADRs 0016, 0017).
    modules("javafx.controls", "javafx.web")
}

dependencies {
    // Deliberate module API dependency: the previews are FileViewers whose source side is the public CodeEditors and
    // whose highlighting comes from Highlighters (ADR 0069).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: exports and image downloads run on logged threads and as window tasks,
    // ADRs 0060, 0061.
    implementation(project(":modules:background-tasks"))
    // Deliberate module dependency: the source side of a preview knows the comment syntax of a file (CommentSyntax).
    implementation(project(":modules:text-editing"))
    implementation(libs.spring.context)
    // Markdown preview: commonmark renders HTML, jsoup sanitises it (ADR 0016).
    implementation(libs.bundles.commonmark)
    implementation(libs.jsoup)
    // Jupyter notebooks are JSON (ADR 0017).
    implementation(libs.jackson.databind)
    // Toolbar icons: Material Design Icons, like the file explorer's (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
    // The tests read the source's RichTextFX area (SourceArea.kt).
    testImplementation(libs.richtextfx)
}
