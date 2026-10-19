plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    version = libs.versions.javafx.get()
    modules("javafx.graphics")
}

dependencies {
    // Deliberate module dependency: the trust checks run on a logged thread, ADR 0060.
    implementation(project(":modules:background-tasks"))
    implementation(libs.spring.context)
}
