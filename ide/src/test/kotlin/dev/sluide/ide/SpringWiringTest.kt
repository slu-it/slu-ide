package dev.sluide.ide

import dev.sluide.editor.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.DefaultTextFileViewer
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.PythonFileViewer
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
    fun `context registers the main window and beans from all modules`() {
        SpringApplicationBuilder(SluIdeConfiguration::class.java)
            .web(WebApplicationType.NONE)
            .lazyInitialization(true)
            .run()
            .use { context ->
                val types =
                    listOf(
                        MainWindow::class.java,
                        ProjectService::class.java,
                        FileExplorerView::class.java,
                        EditorArea::class.java,
                        FileViewers::class.java,
                        DefaultTextFileViewer::class.java,
                        PythonFileViewer::class.java,
                    )
                for (type in types) {
                    assertEquals(1, context.getBeanNamesForType(type).size, "bean of type ${type.simpleName}")
                }
                // Needs no JavaFX toolkit, so the viewer chain can be created for real.
                val viewers = context.getBean(FileViewers::class.java).viewers
                assertEquals(listOf(PythonFileViewer::class, DefaultTextFileViewer::class), viewers.map { it::class })
            }
    }
}
