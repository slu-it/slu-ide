plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // Deliberate module API dependency: MavenTrustCheck is a ProjectTrustCheck (ADR 0053, ADR 0179).
    api(project(":modules:project-trust"))
    // Deliberate module dependency: the project JDK is JAVA_HOME of the user's login shell (loginShellPrints,
    // ADR 0087, ADR 0179).
    implementation(project(":modules:terminal"))
    implementation(libs.spring.context)
}
