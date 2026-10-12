package dev.sluide.ide

import dev.sluide.fileexplorer.FileExplorerView
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies component scanning across modules. Beans stay lazy because JavaFX controls cannot be
 * instantiated without a running toolkit (and thus a display).
 */
class SpringWiringTest {

    @Test
    fun `context registers the main window and beans from the file-explorer module`() {
        SpringApplicationBuilder(SluIdeConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .lazyInitialization(true)
            .run().use { context ->
                for (type in listOf(MainWindow::class.java, FileExplorerView::class.java, ProjectService::class.java)) {
                    assertEquals(1, context.getBeanNamesForType(type).size, "bean of type ${type.simpleName}")
                }
            }
    }
}
