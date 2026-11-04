plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    // javafx.media: audio and video play with JavaFX Media (ADR 0123). javafx.web already requires it, so the app
    // ships it anyway: no size cost.
    modules("javafx.controls", "javafx.media")
}

dependencies {
    // Deliberate module API dependency: the media player is a FileViewer shown as a file view (ADR 0004, ADR 0123).
    api(project(":modules:file-viewers"))
    // Deliberate module API dependency: volume and mute are kept in the shared data file; MediaPreferences takes the
    // ProjectDataStore (ADR 0101, ADR 0123).
    api(project(":modules:project-data"))
    // Deliberate module dependency: the preferences are written on a logged thread (NamedThreads, ADR 0060).
    implementation(project(":modules:background-tasks"))
    implementation(libs.spring.context)
    // Control bar icons: Material Design Icons, like the other toolbars (ADR 0012).
    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
