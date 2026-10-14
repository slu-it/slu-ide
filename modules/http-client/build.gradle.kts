plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    // The file viewers' API uses JavaFX types (FileView.node), which the compiler must see here as well.
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

dependencies {
    // Deliberate module API dependency: .http files are shown in the file viewers' code views, with their syntax
    // highlighters for request bodies and scripts.
    api(project(":modules:file-viewers"))
    implementation(libs.spring.context)
    implementation(libs.jackson.databind)
}
