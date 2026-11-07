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
    // Deliberate module API dependency: TySpec is a LanguageServerSpec and TyInstaller its ServerInstaller (ADR 0142,
    // ADR 0144).
    api(project(":modules:language-servers"))
    // Deliberate module API dependency: ty is installed, updated and rolled back by a ToolInstaller; its InstallState,
    // InstalledTool and ToolRelease are in TyInstaller's API (ADR 0143, ADR 0144).
    api(project(":modules:local-models"))
    // Deliberate module dependency: Settings → Languages → Python is a settings page, and the custom ty path is stored
    // in settings.properties (ADR 0008, ADR 0067).
    implementation(project(":modules:settings"))
    // Deliberate module dependency: the smoke test and the update check run on logged NamedThreads (ADR 0060).
    implementation(project(":modules:background-tasks"))
    // Reads the GitHub release answer of the update check (JSON).
    implementation(libs.jackson.databind)
    implementation(libs.spring.context)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}

tasks.test {
    // The real-ty smoke test runs only with SLUIDE_TEST_TY: a set variable means "check this ty now", so the task is
    // never up to date then (a cached run would keep the skipped test). Its ~/.sluide/tools/ty fallback needs --rerun.
    inputs.property("sluideTestTy", providers.environmentVariable("SLUIDE_TEST_TY").orElse(""))
    outputs.upToDateWhen { System.getenv("SLUIDE_TEST_TY") == null }
}
