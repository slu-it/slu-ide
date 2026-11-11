plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    // javafx.web: the book is one page in a WebView (ADR 0165).
    modules("javafx.controls", "javafx.web")
}

dependencies {
    // Deliberate module API dependency: the reader is a FileViewer shown as a file view (ADR 0165).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: the book's WebView uses the previews' web kit, DocumentZoom and TextMarks (ADR 0165).
    implementation(project(":modules:previews"))
    // Deliberate module API dependency: EpubFileViewer takes the ProjectDataStore, which keeps the reading position as
    // per-file data (ADR 0101, ADR 0165).
    api(project(":modules:project-data"))
    // Deliberate module dependency: parsing and the position writes run on logged threads (NamedThreads, ADR 0060).
    implementation(project(":modules:background-tasks"))
    // Deliberate module dependency: Read Aloud in the reader, through the window's ReadAloud and its surface API
    // (ADR 0164, ADR 0165).
    implementation(project(":modules:speech"))
    implementation(libs.spring.context)
    // Parses the chapters (XHTML) and writes the sanitised page (ADR 0165).
    implementation(libs.jsoup)
    // Toolbar icons: Material Design Icons, like the other toolbars (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
