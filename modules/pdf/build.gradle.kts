plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    // The viewer shows the page images PDFBox renders as JavaFX images; no WebView (ADR 0192).
    modules("javafx.controls")
}

dependencies {
    // Deliberate module API dependency: the PDF viewer is a FileViewer shown as a file view (ADR 0192).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: rendering and text extraction run on logged threads (NamedThreads, ADR 0060,
    // ADR 0192).
    implementation(project(":modules:background-tasks"))
    implementation(libs.spring.context)
    // Renders the pages and extracts their text, outline and links (ADR 0192).
    implementation(libs.pdfbox)
    // Toolbar icons: Material Design Icons, like the other toolbars (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}

tasks.withType<Test>().configureEach {
    // PDFBox keeps its font cache here, not in ~/.pdfbox.cache (ADR 0192); relative to the test's working directory,
    // the module folder, so that no absolute path enters the test's cache key. PdfSetup.init creates the folder.
    systemProperty("pdfbox.fontcache", "build/tmp/pdfbox-fontcache")
}
