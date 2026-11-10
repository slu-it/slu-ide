plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    // Argon2id derives the key of a password slot (ADR 0162); the JDK has no Argon2.
    implementation(libs.bcprov)
}
