package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.BoundEditorHarness
import dev.sluide.codeintelligence.NavigationHistory
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.languageservers.ServerState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The window's navigation over bound editors with hosts of their own: which handles remove which registration, the
 * editor with the focus, where a jump or a step goes (the editor, its host, the first editor's host, the fallback)
 * and what the answers say, and why a server cannot be asked in each of its states.
 */
class NavigationTest {
    @TempDir
    lateinit var folder: Path

    private val harnesses = mutableListOf<BoundEditorHarness<NavigationServer>>()

    @AfterEach
    fun close() = harnesses.forEach { it.close() }

    private class OpenHost(
        private val accepts: Boolean = true,
    ) : FileViewHost {
        val opened = mutableListOf<SourcePosition>()

        override fun openFile(file: Path) = false

        override fun openFile(
            file: Path,
            line: Int,
            column: Int,
        ): Boolean {
            opened += SourcePosition(file, line, column)
            return accepts
        }

        override fun openWebPage(uri: URI) {}
    }

    private val text = "abc\ndef\nghi\n"

    private fun bound(
        name: String,
        host: FileViewHost,
    ): BoundEditorHarness<NavigationServer> {
        val dir = folder.resolve(name).createDirectories()
        return BoundEditorHarness(dir, text, { NavigationServer() }, host = host).also { harnesses += it }
    }

    private fun navigation(
        bindings: List<DocumentBinding>,
        fallback: FileViewHost? = null,
    ) = Navigation(NavigationHistory(exists = { true }), { bindings }, { fallback }).apply { isFocused = { true } }

    private fun noActions() =
        object : BindingNavigation {
            override fun goToDefinition(offset: Int) {}

            override fun findUsages(offset: Int) {}
        }

    @Test
    fun `closing a registration removes it, but not one that replaced it`() {
        val one = bound("one", OpenHost())
        val navigation = navigation(listOf(one.binding))
        val first = noActions()
        val second = noActions()
        val firstHandle = navigation.register(one.binding, first)
        assertSame(first, navigation.actionsOf(one.binding))
        val secondHandle = navigation.register(one.binding, second)
        firstHandle.close()
        assertSame(second, navigation.actionsOf(one.binding), "the replaced registration's handle leaves the new one")
        secondHandle.close()
        assertNull(navigation.actionsOf(one.binding))

        val structure = {}
        val replacement = {}
        val structureHandle = navigation.registerStructure(one.binding, structure)
        assertSame(structure, navigation.structureOf(one.binding))
        val replacementHandle = navigation.registerStructure(one.binding, replacement)
        structureHandle.close()
        assertSame(replacement, navigation.structureOf(one.binding))
        replacementHandle.close()
        assertNull(navigation.structureOf(one.binding))
    }

    @Test
    fun `the focused editor is the first open one that has the focus`() {
        val closed = bound("closed", OpenHost())
        val unfocused = bound("unfocused", OpenHost())
        val focused = bound("focused", OpenHost())
        val navigation = navigation(listOf(closed.binding, unfocused.binding, focused.binding))
        navigation.isFocused = { it === closed.binding || it === focused.binding }
        closed.onFx { closed.binding.close() }
        assertSame(focused.binding, navigation.focused())
        navigation.isFocused = { false }
        assertNull(navigation.focused())
    }

    @Test
    fun `a jump says whether the file was shown, and records where it came from`() {
        val accepting = bound("accepting", OpenHost())
        val refusing = bound("refusing", OpenHost(accepts = false))
        val navigation = navigation(listOf(accepting.binding, refusing.binding))
        val elsewhere = folder.resolve("elsewhere.py")
        assertTrue(navigation.jump(accepting.binding, SourcePosition(elsewhere, 1, 2)))
        assertFalse(navigation.jump(refusing.binding, SourcePosition(elsewhere, 1, 2)))
        val origin = SourcePosition(accepting.binding.file, 0, 0)
        assertEquals(listOf(origin, SourcePosition(refusing.binding.file, 0, 0)), navigation.history.places.first)
    }

    @Test
    fun `a jump in the focused editor's own file moves its caret and goes through no host`() {
        val host = OpenHost()
        val one = bound("one", host)
        val navigation = navigation(listOf(one.binding))
        val target = SourcePosition(one.binding.file, 2, 1)
        assertTrue(navigation.jump(one.binding, target))
        assertEquals(9, one.editor.caret)
        assertEquals(emptyList(), host.opened)
    }

    @Test
    fun `a jump goes through the host unless the focused editor shows the file that is open`() {
        val host = OpenHost()
        val one = bound("one", host)
        val navigation = navigation(listOf(one.binding))
        val own = SourcePosition(one.binding.file, 2, 1)

        navigation.isFocused = { false }
        assertTrue(navigation.jump(one.binding, own), "an editor without the focus")
        navigation.isFocused = { true }
        assertTrue(navigation.jump(one.binding, SourcePosition(folder.resolve("other.py"), 0, 0)), "another file")
        one.onFx { one.binding.close() }
        assertTrue(navigation.jump(one.binding, own), "a closed editor")
        assertEquals(0, one.editor.caret, "the editor's caret was not moved")
        assertEquals(listOf(own, SourcePosition(folder.resolve("other.py"), 0, 0), own), host.opened)
    }

    @Test
    fun `the host that shows a file is the editor's own, else the first editor's, else the fallback`() {
        val firstHost = OpenHost()
        val secondHost = OpenHost()
        val fallbackHost = OpenHost()
        val first = bound("first", firstHost)
        val second = bound("second", secondHost)
        val target = SourcePosition(folder.resolve("t.py"), 3, 4)

        val navigation = navigation(listOf(first.binding, second.binding), fallbackHost)
        assertTrue(navigation.open(second.binding, target))
        assertEquals(listOf(target), secondHost.opened, "from an editor: its own host")
        assertTrue(navigation.open(null, target))
        assertEquals(listOf(target), firstHost.opened, "without one: the first editor's host")
        assertEquals(emptyList(), fallbackHost.opened)

        val withoutEditors = navigation(emptyList(), fallbackHost)
        assertTrue(withoutEditors.open(null, target))
        assertEquals(listOf(target), fallbackHost.opened)
        assertFalse(navigation(emptyList()).open(null, target), "nowhere to show it")
    }

    @Test
    fun `an editor that closed is not jumped from, the file opens through the host without a record`() {
        val host = OpenHost()
        val one = bound("one", host)
        val navigation = navigation(listOf(one.binding))
        one.onFx { one.binding.close() }
        val target = SourcePosition(folder.resolve("t.py"), 0, 0)
        assertTrue(navigation.open(one.binding, target))
        assertEquals(listOf(target), host.opened)
        assertEquals(emptyList(), navigation.history.places.first)
    }

    @Test
    fun `back and forward say whether there was a place, and a step records the caret as it is now`() {
        val one = bound("one", OpenHost())
        val navigation = navigation(listOf(one.binding))
        assertFalse(navigation.back())
        assertFalse(navigation.forward())

        val file = one.binding.file
        assertTrue(navigation.jump(one.binding, SourcePosition(file, 2, 1)))
        // The user moved on from where the jump arrived before going back.
        one.editor.moveCaret(5)
        assertTrue(navigation.back())
        assertEquals(0, one.editor.caret)
        assertEquals(listOf(SourcePosition(file, 1, 1)), navigation.history.places.second)
        assertTrue(navigation.forward())
        assertEquals(5, one.editor.caret)
        assertFalse(navigation.forward())
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource(
        "Running, true, ",
        "Running, false, not supported",
        "Starting, true, server starting",
        "Restarting, false, server starting",
        "Stopped, true, server not started",
        "Unavailable, true, server not installed",
        "Untrusted, true, project not trusted",
        "Failed, true, server stopped",
    )
    fun `a server in each state says why it cannot be asked`(
        state: String,
        supported: Boolean,
        reason: String?,
    ) {
        val server =
            when (state) {
                "Running" -> ServerState.Running
                "Starting" -> ServerState.Starting
                "Restarting" -> ServerState.Restarting(2)
                "Stopped" -> ServerState.Stopped
                "Unavailable" -> ServerState.Unavailable
                "Untrusted" -> ServerState.Untrusted
                else -> ServerState.Failed("exited")
            }
        assertEquals(reason, navigationBlocker(server, supported))
    }
}
