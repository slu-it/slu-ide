plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.javafx)
    `java-library`
}

javafx {
    // No JavaFX code here, but the terminal module needs its platform jars at run time, and Maven Central publishes no
    // Gradle metadata for JavaFX, so the plugin picks them (ADR 0003).
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

dependencies {
    // Draws the diagrams in-process: the MIT build, with its stdlib (C4, icons, themes) inside the jar (ADR 0193).
    implementation(libs.plantuml.mit)
    // Deliberate module dependency: Graphviz's dot is looked up through the login shell (loginShellPrints, ADR 0193).
    implementation(project(":modules:terminal"))

    // LogCapture for the log lines of a failed dot lookup and a failed render.
    testImplementation(project(":modules:test-support"))
}
