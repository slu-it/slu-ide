@file:JvmName("SluIdeLauncher")

package dev.sluide.ide

import javafx.application.Application

/**
 * Entry point of the fat jar. It must not be the [Application] subclass itself, otherwise the JVM
 * insists on JavaFX being on the module path.
 */
fun main(args: Array<String>) {
    Application.launch(SluIdeApplication::class.java, *args)
}
