plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.javafx)
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.controls")
}

springBoot {
    mainClass = "dev.sluide.ide.SluIdeLauncher"
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(project(":modules:file-explorer"))
}
