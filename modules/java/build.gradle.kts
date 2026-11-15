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
    // Deliberate module API dependency: MavenTrustCheck is a ProjectTrustCheck (ADR 0053, ADR 0179).
    api(project(":modules:project-trust"))
    // Deliberate module API dependency: JdtlsLaunch.command returns a ServerCommand and JdtlsInstaller is a
    // ServerInstaller (ADR 0142, ADR 0179).
    api(project(":modules:language-servers"))
    // Deliberate module API dependency: the jdtls install, update check, smoke test and settings page are the shared
    // server-tool layer's (JdtlsInstaller, JdtlsUpdates, JavaSettings and JavaSettingsPage extend its classes;
    // ADR 0159, ADR 0179).
    api(project(":modules:server-tools"))
    // Deliberate module API dependency: jdtls and Lombok are installed by ToolInstallers; their InstallState and
    // InstalledTool are in JdtlsInstaller's API (ADR 0143, ADR 0179).
    api(project(":modules:local-models"))
    // Deliberate module dependency: the custom jdtls path and the maximum memory are stored in settings.properties
    // (ADR 0008, ADR 0067).
    implementation(project(":modules:settings"))
    // Deliberate module dependency: the project JDK is JAVA_HOME of the user's login shell (loginShellPrints,
    // ADR 0087, ADR 0179).
    implementation(project(":modules:terminal"))
    // Reads the milestones index of download.eclipse.org (HTML) in the update check.
    implementation(libs.jsoup)
    implementation(libs.spring.context)

    testImplementation(testFixtures(project(":modules:server-tools")))
    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}

tasks.test {
    // The real-jdtls smoke test runs only with SLUIDE_TEST_JDTLS: a set variable means "check this jdtls now", so the
    // task is never up to date then (a cached run would keep the skipped test).
    inputs.property("sluideTestJdtls", providers.environmentVariable("SLUIDE_TEST_JDTLS").orElse(""))
    outputs.upToDateWhen { System.getenv("SLUIDE_TEST_JDTLS") == null }
}
