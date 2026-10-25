plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // The project data is a Jackson 3 tree (`ObjectNode`) in the public API, so `api`. No version here: the Spring Boot
    // BOM, which the root build applies to every module, manages it (as for http-client).
    api(libs.jackson.databind)
    // ProjectDataStore is a Spring bean that collects the modules' LegacyProjectData migrations.
    implementation(libs.spring.context)
}
