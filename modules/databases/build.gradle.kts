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
    // Deliberate module API dependency: the Databases tab is shown as an EditorTab, opened from the Services menu
    // (ADR 0118, ADR 0121).
    api(project(":modules:editor-area"))
    // Deliberate module API dependency: the consoles are embedded SQL code editors (CodeEditors.text, Highlighters)
    // and the value viewer highlights JSON and XML (ADR 0121).
    api(project(":modules:file-viewers"))
    // Deliberate module API dependency: DatabasesSettings stores page size, fetch cap and timeout with SettingsStore
    // and adds a SettingsPage (ADR 0067).
    api(project(":modules:settings"))
    // Deliberate module API dependency: JDBC work runs on logged threads (ADR 0060), connecting is a cancellable
    // background task of the window (ADR 0061).
    api(project(":modules:background-tasks"))
    // Deliberate module API dependency: ConnectionStore keeps the project's connections in the project data
    // (ProjectDataStore, ADR 0090).
    api(project(":modules:project-data"))
    // Deliberate module API dependency: saved passwords are encrypted by Secrets, which the Databases bean's
    // constructor takes (ADR 0122).
    api(project(":modules:secrets"))
    implementation(libs.spring.context)
    // Reads and writes the connections in the project data's JSON tree.
    implementation(libs.jackson.databind)
    // The bundled JDBC drivers (ADR 0121); the MariaDB connector (LGPL-2.1) stays an unmodified jar of the fat jar.
    implementation(libs.postgresql)
    implementation(libs.mariadb.java.client)

    implementation(libs.ikonli.javafx)
    implementation(libs.ikonli.materialdesign2)

    testImplementation(libs.testfx.core)
    testImplementation(libs.testfx.junit5)
}
