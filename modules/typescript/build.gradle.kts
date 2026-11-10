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
    // Deliberate module API dependency: TypeScriptSpec is a LanguageServerSpec and TypeScriptInstaller its ServerInstaller;
    // DocumentGate, EnvironmentProblem, ServerCommand, WorkspaceStatus and lsp4j's ConfigurationItem are in its API
    // (ADR 0142, ADR 0160, ADR 0161).
    api(project(":modules:language-servers"))
    // Deliberate module API dependency: the TypeScript server's install, update check, smoke test and settings page
    // are the shared server-tool layer's (TypeScriptInstaller, TypeScriptUpdates, TypeScriptSettings and
    // TypeScriptSettingsPage extend its classes; ADR 0159).
    api(project(":modules:server-tools"))
    // Deliberate module API dependency: the TypeScript server's releases are ToolReleases with a Checksum and an
    // ArchiveFormat; its InstallState and InstalledTool are in TypeScriptInstaller's API (ADR 0143, ADR 0159).
    api(project(":modules:local-models"))
    // Deliberate module dependency: the custom server path is stored in settings.properties (ADR 0008, ADR 0067).
    implementation(project(":modules:settings"))
    // Reads package.json and deno.json(c), leniently (comments, trailing commas); reads the npm registry's answers.
    implementation(libs.jackson.databind)
    // Reads the catalogs of pnpm-workspace.yaml.
    implementation(libs.snakeyaml)
    implementation(libs.spring.context)

    testImplementation(testFixtures(project(":modules:server-tools")))
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}

tasks.test {
    // The real-tsc smoke test runs only with SLUIDE_TEST_TSC: a set variable means "check this tsc now", so the task is
    // never up to date then (a cached run would keep the skipped test). Its ~/.sluide/tools/typescript fallback needs
    // --rerun.
    inputs.property("sluideTestTsc", providers.environmentVariable("SLUIDE_TEST_TSC").orElse(""))
    outputs.upToDateWhen { System.getenv("SLUIDE_TEST_TSC") == null }
}
