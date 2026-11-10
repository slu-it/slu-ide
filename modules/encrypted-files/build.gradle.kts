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
    // Deliberate module API dependency: EncryptedFileViewer is a public ExtensionFileViewer whose view shows the inner
    // type's view from FileViewers.contentViewerFor over decrypted FileContent (ADR 0162).
    api(project(":modules:file-viewers"))
    // Deliberate module dependency: unlocking runs Argon2 on a logged thread (NamedThreads, ADR 0060).
    implementation(project(":modules:background-tasks"))
    // Argon2id derives the key of a password slot (ADR 0162); the JDK has no Argon2.
    implementation(libs.bcprov)
    implementation(libs.spring.context)
    // The locked pane's and the strip's icons: Material Design Icons, like the other toolbars (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
    // The tests open Markdown inside an encrypted file: the Markdown preview's ContentFileViewer.
    testImplementation(project(":modules:previews"))
    // The tests read the inner views' RichTextFX areas.
    testImplementation(libs.richtextfx)
}
