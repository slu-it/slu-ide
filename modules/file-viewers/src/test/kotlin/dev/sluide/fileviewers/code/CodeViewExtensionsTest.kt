package dev.sluide.fileviewers.code

import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.CodeEditorExtensions
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.WindowServices
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import java.net.URI
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals

/** When a code view is extended by the [CodeEditorExtensions] of its host (ADR 0108), and when the handles close. */
class CodeViewExtensionsTest {
    private val editor = FakeEditor()
    private val extensions = CodeViewExtensions(editor)

    /** What the extensions did, in order. */
    private val events = mutableListOf<String>()

    /** Window extensions that record extending as [name] and closing its handle. */
    private fun recording(name: String) =
        CodeEditorExtensions().apply {
            add { extended ->
                events += "$name extends ${extended.file.value}"
                AutoCloseable { events += "$name closed" }
            }
        }

    /** A host of a window whose services hold [extensions], if any. */
    private fun host(extensions: CodeEditorExtensions?) =
        object : FileViewHost {
            private val services =
                WindowServices().apply { extensions?.let { register(CodeEditorExtensions::class.java, it) } }

            override fun openFile(file: Path) = false

            override fun openWebPage(uri: URI) {}

            override fun <T : Any> windowService(type: Class<T>): T? = services.get(type)
        }

    @Test
    fun `the first host with the service extends the view, later hosts change nothing`() {
        extensions.attach(host(recording("first")))
        extensions.attach(host(recording("second")))

        assertEquals(listOf("first extends a.py"), events)
    }

    @Test
    fun `no host and a host without the service do not count as the first`() {
        extensions.attach(null)
        extensions.attach(host(null))
        assertEquals(emptyList(), events)

        extensions.attach(host(recording("window")))

        assertEquals(listOf("window extends a.py"), events)
    }

    @Test
    fun `an extension that throws is logged and skipped, the others still extend and close`() {
        val window =
            CodeEditorExtensions().apply {
                add(CodeEditorExtension { error("broken") })
                add { AutoCloseable { events += "second closed" } }
            }
        val logged = logged { extensions.attach(host(window)) }

        extensions.dispose()

        assertEquals(listOf("SEVERE Could not extend the code editor of a.py"), logged)
        assertEquals(listOf("second closed"), events)
    }

    @Test
    fun `the handles are closed on dispose, one failing to close does not keep the others open`() {
        val window =
            CodeEditorExtensions().apply {
                add { AutoCloseable { events += "first closed" } }
                add { AutoCloseable { error("cannot close") } }
                add { null }
                add { AutoCloseable { events += "fourth closed" } }
            }
        extensions.attach(host(window))
        assertEquals(emptyList(), events)

        val logged = logged { extensions.dispose() }

        assertEquals(listOf("first closed", "fourth closed"), events)
        assertEquals(listOf("SEVERE Could not close an extension of the code editor of a.py"), logged)
    }

    @Test
    fun `a disposed view is not extended any more`() {
        extensions.dispose()

        extensions.attach(host(recording("window")))

        assertEquals(emptyList(), events)
    }

    /** The records logged by [CodeViewExtensions] while [action] runs, as level and message. */
    private fun logged(action: () -> Unit): List<String> {
        val records = mutableListOf<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += "${record.level} ${record.message}"
                }

                override fun flush() {}

                override fun close() {}
            }
        val logger = Logger.getLogger(CodeViewExtensions::class.java.name).apply { addHandler(handler) }
        try {
            action()
        } finally {
            logger.removeHandler(handler)
        }
        return records
    }

    private class FakeEditor : ExtensibleCodeEditor {
        override val file: ObservableValue<Path> = SimpleObjectProperty(Path.of("a.py"))
        override val lineCount = 1
        override val shownLines = IntRange.EMPTY

        override fun lineText(line: Int) = ""

        override fun onShownLinesChanged(action: () -> Unit) = Subscription.EMPTY

        override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {}

        override fun addGutterColumn(column: (paragraph: Int) -> Node?) {}

        override fun refreshGutter() {}
    }
}
