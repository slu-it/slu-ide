plugins {
    alias(libs.plugins.kotlin.jvm)
}

// A pitest plugin (docs/architecture.md, "Build tools"): the mutation runs put it on pitest's own class path, which
// brings pitest-entry, so it is compileOnly here. No Spring, no JavaFX.
dependencies {
    compileOnly(libs.pitest.entry)
    testImplementation(libs.pitest.entry)
}
