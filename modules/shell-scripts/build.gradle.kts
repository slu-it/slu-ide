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
    // Deliberate module API dependency: ShellScriptFileViewer is a public SourceFileViewer, ExtensionFileViewer,
    // ShebangViewer and RunnableFileViewer showing a CodeEditor (ADR 0089).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: scripts run with a CommandRunner in a RunPane, through the window's
    // CommandRunSession; arguments are split and quoted with ShellWords (ADR 0088).
    implementation(project(":modules:command-runs"))
    // The Run button's icon: Material Design Icons, like the HTTP client's (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)
    implementation(libs.spring.context)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
