plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // Reads package.json and deno.json(c), leniently (comments, trailing commas).
    implementation(libs.jackson.databind)
    // Reads the catalogs of pnpm-workspace.yaml.
    implementation(libs.snakeyaml)
}
