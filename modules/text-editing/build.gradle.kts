plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

dependencies {
    implementation(libs.ec4j.core)
}
