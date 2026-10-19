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
    // Deliberate module dependency: the log, branch, compare, write, push, commit and history reads run on logged threads, ADR 0060.
    implementation(project(":modules:background-tasks"))
    // Deliberate module API dependency: the commits come from the git command line of the git module (ADR 0035).
    api(project(":modules:git"))
    // Deliberate module dependency: the changed files show the explorer's file-type icons (ADR 0047).
    implementation(project(":modules:file-icons"))
    implementation(libs.spring.context)
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)
    // The details' texts with links (hashes in messages, Show all): RichTextFX styles spans and hit-tests (ADR 0045).
    implementation(libs.richtextfx)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
