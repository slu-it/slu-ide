plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // Decodes the workflow parser's JSON with Jackson 3's tree model. No version here: the Spring Boot BOM, which the
    // root build applies to every module, manages it (as for http-client).
    implementation(libs.jackson.databind)
    // Reads local actions' manifests (action.yml) as a node tree, scalars kept as text, never constructing types.
    // No version here either: the Spring Boot BOM manages it.
    implementation(libs.snakeyaml)
}
