plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // The data family is a Spring bean, collected with the families of other modules (ADR 0115).
    implementation(libs.spring.context)
    // The data tree between data formats (JsonNode) and the JSON format.
    implementation(libs.jackson.databind)
    // Reads and writes TOML in the data family.
    implementation(libs.jackson.dataformat.toml)
    // Reads and writes CSV and TSV in the data family.
    implementation(libs.jackson.dataformat.csv)
    // Reads and writes YAML in the data family, as the YAML preview reads it (ADR 0079).
    implementation(libs.snakeyaml)
}
