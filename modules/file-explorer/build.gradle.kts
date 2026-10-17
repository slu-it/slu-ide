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
    // Deliberate module API dependency: loaded folders are watched for changes (ADR 0006).
    api(project(":modules:file-watcher"))
    // Deliberate module API dependency: the tree uses the explorer font from the settings (ADR 0008).
    api(project(":modules:settings"))
    implementation(libs.spring.context)
    // Deliberate module dependency: the file-type icons and their colours are shared with the git log (ADR 0012, 0047).
    implementation(project(":modules:file-icons"))

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
