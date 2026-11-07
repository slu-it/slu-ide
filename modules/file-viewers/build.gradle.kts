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
    // Deliberate module dependency: highlighting runs on logged threads, ADR 0060.
    implementation(project(":modules:background-tasks"))
    implementation(libs.spring.context)
    // Deliberate module dependency: code views read, edit and save text with its encoding (UTF-8, else Windows-1252),
    // line endings and changes on disk (text-editing). An api dependency: ExtensibleCodeEditor's positions are
    // text-editing's TextPosition (ADR 0141).
    api(project(":modules:text-editing"))
    // Deliberate module dependency: the soft wrap switch of the editors is kept in the shared data file; SoftWrap takes
    // the ProjectDataStore (ADR 0101, ADR 0130).
    implementation(project(":modules:project-data"))
    // Code views: RichTextFX renders, RSyntaxTextArea only provides its lexers (ADR 0005).
    implementation(libs.richtextfx)
    implementation(libs.rsyntaxtextarea)
    // Toolbar icons of the find bar and the diff views: Material Design Icons, like the file explorer's (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
