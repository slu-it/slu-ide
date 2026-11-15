package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.generated.GeneratedFolderFilter
import javafx.scene.control.TreeItem
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeSearchFilterTest {
    @TempDir
    lateinit var root: Path

    private fun path(relative: String): Path = root.resolve(relative)

    private fun filter(vararg matches: String) = TreeSearchFilter(matches.map(::path), listOf(root, path("extra-root")))

    @ParameterizedTest(name = "{0} shows: {1}")
    @CsvSource(
        ".,true", // a root
        "extra-root,true", // another root, without a match below it
        "src/App.kt,true", // a match
        "src,true", // the folder holding a match
        "docs,true", // a folder hit
        "docs/guide/intro.md,true", // below a folder hit
        "kept/deep/file.txt,true", // an extra
        "kept,true", // the folder holding an extra
        "kept/deep/other.txt,false", // beside an extra
        "kept-folder/inner/x.txt,true", // below a kept folder
        "src/Other.kt,false", // beside a match
        "lib,false",
        "extra-root/x.txt,false",
    )
    fun `shows roots, matches, their folders, extras with theirs, and everything below a folder hit or a kept folder`(
        entry: String,
        shown: Boolean,
    ) {
        val filter = filter("src/App.kt", "docs")
        filter.keep(path("kept/deep/file.txt"))
        filter.keep(path("kept-folder"))

        assertEquals(shown, filter.shows(path(entry).normalize()))
    }

    @Test
    fun `extras last until they are reset, the matches stay`() {
        val filter = filter("src/App.kt")
        filter.keep(path("kept/file.txt"))
        assertTrue(filter.shows(path("kept")))
        assertEquals(setOf(path("kept/file.txt")), filter.kept)

        filter.resetExtras()

        assertFalse(filter.shows(path("kept/file.txt")))
        assertFalse(filter.shows(path("kept")))
        assertTrue(filter.shows(path("src/App.kt")))
        assertEquals(emptySet(), filter.kept)
    }

    @Test
    fun `a compacted row is a folder hit when a middle folder of its chain matches, a matched file is none`() {
        path("src/main/kotlin/App.kt").createParentDirectories().createFile()
        val top = topLevel(FileTreeItem(root, compact = { true }))
        val row = top.children.single() as FileTreeItem
        val file = row.children.single() as FileTreeItem
        assertEquals(listOf("src", "src/main", "src/main/kotlin").map(::path), row.chain)

        assertTrue(filter("src/main").isFolderHit(row))
        assertFalse(filter("src/main/kotlin/App.kt").isFolderHit(row))
        assertFalse(filter("src/main/kotlin/App.kt").isFolderHit(file))
    }

    /** Puts [top] below a hidden root, like the explorer's top-level folders. */
    private fun topLevel(top: FileTreeItem): FileTreeItem = top.also { TreeItem<Path>().children.add(it) }

    private var search: TreeSearchFilter? = null

    private fun searched(generated: GeneratedFolderFilter? = null) =
        topLevel(FileTreeItem(root, generated = generated, searchFilter = { search }))

    private fun FileTreeItem.names() = children.map { it.value.name }

    private fun FileTreeItem.child(name: String) = children.first { it.value.name == name } as FileTreeItem

    @Test
    fun `the tree lists only what the filter shows, and a folder hit with its whole content`() {
        listOf("src/App.kt", "src/Other.kt", "lib/Lib.kt", "docs/guide/intro.md", "docs/notes.txt", "README.md")
            .forEach { path(it).createParentDirectories().createFile() }
        search = filter("src/App.kt", "docs")

        val top = searched()

        assertEquals(listOf("docs", "src"), top.names())
        assertEquals(listOf("App.kt"), top.child("src").names())
        // Nothing below the folder hit matches: only the folder hit rule shows these.
        val docs = top.child("docs")
        assertEquals(listOf("guide", "notes.txt"), docs.names())
        assertEquals(listOf("intro.md"), docs.child("guide").names())
    }

    @Test
    fun `refresh keeps filtering, and shows a new entry once the filter matches it`() {
        path("src/App.kt").createParentDirectories().createFile()
        path("docs/notes.txt").createParentDirectories().createFile()
        search = filter("src/App.kt", "docs")
        val top = searched()
        val src = top.child("src")
        val docs = top.child("docs")
        assertEquals(listOf("App.kt"), src.names())
        docs.children

        path("src/New.kt").createFile()
        path("docs/new.txt").createFile()
        path("other").createDirectories()
        top.refresh()
        src.refresh()
        docs.refresh()

        assertEquals(listOf("docs", "src"), top.names())
        assertEquals(listOf("App.kt"), src.names())
        assertEquals(listOf("new.txt", "notes.txt"), docs.names())

        search = filter("src/App.kt", "src/New.kt", "docs")
        src.refresh()

        assertEquals(listOf("App.kt", "New.kt"), src.names())
    }

    @Test
    fun `generated folders are left out first, even when they match`() {
        path("package.json").createFile()
        path("node_modules/pkg/index.js").createParentDirectories().createFile()
        path("src/App.kt").createParentDirectories().createFile()
        search = filter("node_modules", "src/App.kt")

        assertEquals(listOf("node_modules", "src"), searched().names())
        assertEquals(listOf("src"), searched(GeneratedFolderFilter { true }).names())
    }

    @Test
    fun `a git change does not list a folder again for a generated entry the search leaves out`() {
        path("package.json").createFile()
        path("node_modules").createDirectories()
        path("src/App.kt").createParentDirectories().createFile()
        search = filter("src/App.kt")
        var lookups = 0
        val generated = GeneratedFolderFilter { false }
        val top =
            topLevel(
                FileTreeItem(
                    root,
                    generated = generated,
                    searchFilter = { search },
                    libraryRoots = {
                        lookups++
                        null
                    },
                ),
            )
        assertEquals(listOf("src"), top.names())
        lookups = 0

        generated.ignoredByGit = { false }
        top.gitIgnoresChanged()

        // Listing again looks every row's library kind up again.
        assertEquals(0, lookups)
        assertEquals(listOf("src"), top.names())
    }
}
