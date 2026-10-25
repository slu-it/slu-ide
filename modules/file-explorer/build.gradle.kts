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
    // Deliberate module dependency: deletes run on a logged thread, ADR 0060.
    implementation(project(":modules:background-tasks"))
    // Deliberate module API dependency: loaded folders are watched for changes (ADR 0006).
    api(project(":modules:file-watcher"))
    // Deliberate module API dependency: the tree uses the explorer font from the settings, and ExplorerSettings stores
    // Compact Middle Folders with SettingsStore (ADR 0008, 0096).
    api(project(":modules:settings"))
    implementation(libs.spring.context)
    implementation(libs.ikonli.materialdesign2)
    // Deliberate module API dependency: the file-type icons and their colours are shared with the git log (ADR 0012,
    // 0047), and ExplorerRoot.folderIcons takes FileIcon.
    api(project(":modules:file-icons"))

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
