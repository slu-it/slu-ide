plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    // javafx.web: the Markdown preview is a WebView (ADR 0016).
    modules("javafx.controls", "javafx.web")
}

dependencies {
    implementation(libs.spring.context)
    // Code views: RichTextFX renders, RSyntaxTextArea only provides its lexers (ADR 0005).
    implementation(libs.richtextfx)
    implementation(libs.rsyntaxtextarea)
    // Markdown preview: commonmark renders HTML, jsoup sanitises it (ADR 0016).
    implementation(libs.bundles.commonmark)
    implementation(libs.jsoup)
    // Jupyter notebooks are JSON (ADR 0017).
    implementation(libs.jackson.databind)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
