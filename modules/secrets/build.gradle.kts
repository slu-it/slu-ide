plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // Maven Central publishes no Gradle metadata for JavaFX, so the plugin picks the platform jars.
    // The Settings → Secrets page is built in this module.
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

dependencies {
    // Deliberate module API dependency: the Settings → Secrets page is a public SettingsPage bean (ADR 0067, 0122).
    api(project(":modules:settings"))
    // Secrets and the settings page are Spring beans.
    implementation(libs.spring.context)
    // The macOS Keychain backend calls Security.framework through JNA (ADR 0122); the same version pty4j brings.
    implementation(libs.jna)
    // CoreFoundation helpers (CFString, CFData, CFDictionary) for that backend, so only Security is mapped by hand.
    implementation(libs.jna.platform)

    // Forget All runs on a NamedThreads thread (ADR 0060).
    implementation(project(":modules:background-tasks"))

    // The Settings → Secrets page has a TestFX wiring test.
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
