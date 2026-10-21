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
    // Deliberate module dependency: code views edit text with its line endings and changes on disk (text-editing).
    implementation(project(":modules:text-editing"))
    // Code views: RichTextFX renders, RSyntaxTextArea only provides its lexers (ADR 0005).
    implementation(libs.richtextfx)
    implementation(libs.rsyntaxtextarea)
    // Toolbar icons of the find bar and the diff views: Material Design Icons, like the file explorer's (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
