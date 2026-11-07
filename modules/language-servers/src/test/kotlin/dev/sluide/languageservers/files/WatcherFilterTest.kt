package dev.sluide.languageservers.files

import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import dev.sluide.languageservers.LspUris
import org.eclipse.lsp4j.FileSystemWatcher
import org.eclipse.lsp4j.RelativePattern
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The globs and kinds of LSP file watchers. */
class WatcherFilterTest {
    private val project = Path.of("/work/proj").toAbsolutePath()

    @ParameterizedTest(name = "{0} matches {1}: {2}")
    @CsvSource(
        "**/*.py, src/app/main.py, true",
        "**/*.py, main.py, true",
        "**/*.py, src/main.pyi, false",
        "'**/{pyproject.toml,setup.cfg}', pyproject.toml, true",
        "'**/{pyproject.toml,setup.cfg}', sub/setup.cfg, true",
        "'**/{pyproject.toml,setup.cfg}', setup.py, false",
        "**/ty.toml, deep/er/ty.toml, true",
        "**/src/**/*.py, a/src/b/c.py, true",
        "**/src/**/*.py, a/src/c.py, true",
        "**/src/**/*.py, a/lib/c.py, false",
        "**/?.py, x/a.py, true",
        "**/?.py, x/ab.py, false",
        "**/[a-c]*.py, x/beta.py, true",
        "**/[a-c]*.py, x/delta.py, false",
        "**/[!a-c]*.py, x/delta.py, true",
        "**/*.py, x/a.py/b.txt, false",
        "**/a+b(1).py, x/a+b(1).py, true",
        "**/a+b(1).py, x/aab1.py, false",
        "'**/*.{py,pyi}', x/a.pyi, true",
    )
    fun `a plain glob matches the absolute path`(
        glob: String,
        relative: String,
        expected: Boolean,
    ) {
        val filter = WatcherFilter(listOf(FileSystemWatcher(Either.forLeft(glob))))

        assertEquals(expected, filter.matches(changed(relative)))
    }

    @ParameterizedTest(name = "{1} below {0} matches {2}: {3}")
    @CsvSource(
        "'', **, main.py, true",
        "'', **, a/b/c.txt, true",
        "'', *.py, main.py, true",
        "'', *.py, src/main.py, false",
        "src, **/*.py, src/a/main.py, true",
        "src, **/*.py, lib/main.py, false",
        "src, *.toml, src/ty.toml, true",
        "'', src/**, src, true",
        "'', src/**, src/a/b.py, true",
        "'', src/**, srcx/b.py, false",
        "'', '{src/**,lib}', src, true",
        "'', '{**/a.py,b}', a.py, true",
        "'', '{**/a.py,b}', x/y/a.py, true",
        "'', 'x[!a]y', x/y, false",
        "'', 'x[!a]y', xby, true",
        "'', 'x[!a]y', xay, false",
    )
    fun `a relative pattern matches the path below its base`(
        base: String,
        glob: String,
        relative: String,
        expected: Boolean,
    ) {
        val baseUri = LspUris.of(project.resolve(base))
        val filter =
            WatcherFilter(listOf(FileSystemWatcher(Either.forRight(RelativePattern(Either.forRight(baseUri), glob)))))

        assertEquals(expected, filter.matches(changed(relative)))
    }

    @Test
    fun `a relative pattern may name a workspace folder as its base`() {
        val folder = WorkspaceFolder(LspUris.of(project), "proj")
        val filter =
            WatcherFilter(listOf(FileSystemWatcher(Either.forRight(RelativePattern(Either.forLeft(folder), "**")))))

        assertTrue(filter.matches(changed("a.py")))
        assertFalse(filter.matches(FileChange(Path.of("/elsewhere/a.py").toAbsolutePath(), FileChangeKind.CHANGED)))
    }

    @ParameterizedTest(name = "kinds {0}: created {1}, changed {2}, deleted {3}")
    @CsvSource(
        "1, true, false, false",
        "2, false, true, false",
        "4, false, false, true",
        "5, true, false, true",
        "7, true, true, true",
    )
    fun `a watcher's kinds pick the changes it hears`(
        kinds: Int,
        created: Boolean,
        changed: Boolean,
        deleted: Boolean,
    ) {
        val filter = WatcherFilter(listOf(FileSystemWatcher(Either.forLeft("**/*.py"), kinds)))
        val path = project.resolve("a.py")

        assertEquals(
            listOf(created, changed, deleted),
            FileChangeKind.entries.map { filter.matches(FileChange(path, it)) },
        )
    }

    @Test
    fun `a watcher without kinds hears every kind, no watcher hears nothing`() {
        val all = WatcherFilter(listOf(FileSystemWatcher(Either.forLeft("**/*.py"))))
        val none = WatcherFilter(emptyList())
        val path = project.resolve("a.py")

        assertTrue(FileChangeKind.entries.all { all.matches(FileChange(path, it)) })
        assertTrue(none.isEmpty)
        assertFalse(none.matches(FileChange(path, FileChangeKind.CREATED)))
    }

    private fun changed(relative: String) = FileChange(project.resolve(relative), FileChangeKind.CHANGED)
}
