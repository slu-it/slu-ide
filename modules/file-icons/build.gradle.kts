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
    // File icons: Material Design Icons as an icon font (ADR 0012); FontIcon is part of the module's API.
    api(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)
}
