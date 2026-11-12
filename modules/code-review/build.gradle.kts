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
    // Deliberate module API dependency: the review rows carry DiffKind, LineChange and HighlightSpan, and files are
    // prepared with a SyntaxHighlighter and the word diff (ADR 0168).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: the files pane shows file-type icons (ADR 0012, ADR 0168). Declared with the
    // module so that the window's packages do not touch this file.
    implementation(project(":modules:file-icons"))
    // Deliberate module dependency: each review window loads on its own logged NamedThreads thread (ADR 0060,
    // ADR 0168). Declared with the module for the window's packages.
    implementation(project(":modules:background-tasks"))
    // Deliberate module dependency: the diff uses the editor font (FontSettings, ADR 0168). Declared with the module
    // for the window's packages.
    implementation(project(":modules:settings"))
    // Deliberate module dependency: the layout and Hide whitespace are remembered in the shared data file (field
    // `review`, ADR 0101, ADR 0168). Declared with the module for the window's packages.
    implementation(project(":modules:project-data"))
    implementation(libs.spring.context)
    // The diff list is a VirtualFlow with variable row heights (ADR 0168).
    implementation(libs.flowless)
    // Toolbar and header icons: Material Design Icons, like the other toolbars (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
