package dev.sluide.plantuml

import org.junit.jupiter.api.Test
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The engine is created on the caller's thread (the FX thread, through `PlantUml.shared`): it must not load PlantUML
 * there. PlantUML's class initialisers start AWT and fonts, which costs time and can fail (a headless JDK throws
 * `UnsatisfiedLinkError`); the first use belongs on the render thread.
 *
 * In this JVM other tests have loaded PlantUML long ago, so the engine is created in a class loader of its own, over
 * the same class path, that asks the JDK only for the JDK's classes.
 */
class EngineLoadingTest {
    private class Isolated(
        urls: Array<URL>,
    ) : URLClassLoader(urls, getPlatformClassLoader()) {
        fun loaded(name: String) = findLoadedClass(name) != null
    }

    @Test
    fun `creating the engine loads neither the drawing nor PlantUML`() {
        val urls =
            System
                .getProperty("java.class.path")
                .split(File.pathSeparator)
                .filter { it.isNotEmpty() }
                .map { Path.of(it).toUri().toURL() }

        Isolated(urls.toTypedArray()).use { loader ->
            loader.loadClass("dev.sluide.plantuml.EngineLoadingTestKt").getMethod("createEngine").invoke(null)

            assertTrue(loader.loaded("dev.sluide.plantuml.PlantUml"), "the engine was not created in the loader")
            assertFalse(loader.loaded("dev.sluide.plantuml.engine.Drawing"))
            assertFalse(loader.loaded("net.sourceforge.plantuml.FileFormat"))
        }
    }
}

/** Runs inside [EngineLoadingTest]'s own class loader. */
fun createEngine() {
    PlantUml(GraphvizLocator({ null }, emptyList()))
}
