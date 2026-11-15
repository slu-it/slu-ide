package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.tree.FileTreeItem
import javafx.scene.control.TextField
import javafx.scene.control.TreeItem
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.testfx.api.FxRobot
import org.testfx.framework.junit5.ApplicationExtension
import org.testfx.framework.junit5.Start
import org.testfx.util.WaitForAsyncUtils
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Narrowing the explorer's tree to the matches of a search and restoring it. Needs a display, see `uiTest`. */
@Tag("ui")
@ExtendWith(ApplicationExtension::class)
class FileExplorerViewSearchUiTest : FileExplorerViewUiFixture() {
    private lateinit var other: TextField

    @Start
    fun start(stage: Stage) {
        other = TextField()
        startView(stage, configure = {
            it.askName = { _, _, _ -> "Created.kt" }
            it.confirmMove = { true }
        }, wrap = { VBox(other, it) })
    }

    private fun p(relative: String): Path = project.resolve(relative)

    private fun open(robot: FxRobot) {
        listOf(
            "src/main/App.kt",
            "src/main/Other.kt",
            "src/test/AppTest.kt",
            "docs/guide/intro.md",
            "docs/notes.txt",
            "lib/Lib.kt",
            "README.md",
        ).forEach { p(it).createParentDirectories().createFile() }
        robot.interact { view.open(project) }
    }

    /** The loaded item of the entry [name], anywhere in the tree. */
    private fun item(name: String): FileTreeItem =
        generateSequence(listOf<TreeItem<Path>>(view.projectNode)) { level ->
            level.flatMap { if ((it as FileTreeItem).isLoaded) it.children else emptyList() }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<FileTreeItem>().first { it.value.name == name }

    private fun selected(): List<Path> =
        view.selectionModel.selectedItems
            .filterNotNull()
            .map { it.value }

    @Test
    fun `a search shows the matches with their folders expanded, a matched folder collapsed with its whole content`(
        robot: FxRobot,
    ) {
        open(robot)
        assertEquals(listOf(project.name, "docs", "lib", "src", "README.md"), renderedNames(robot))

        robot.interact { view.applySearch(listOf(p("src/main/App.kt"), p("docs"))) }

        assertTrue(view.searchActive)
        assertEquals(listOf(project.name, "docs", "src", "main", "App.kt"), renderedNames(robot))
        assertEquals(listOf(project), view.searchRoots())
        assertEquals(listOf("docs", "lib", "src", "README.md").map(::p), view.shownEntries(project))

        robot.interact { item("docs").isExpanded = true }

        assertEquals(listOf(project.name, "docs", "guide", "notes.txt", "src", "main", "App.kt"), renderedNames(robot))
    }

    @Test
    fun `ending the search expands what was expanded before and reveals the selection, the focus stays`(
        robot: FxRobot,
    ) {
        open(robot)
        robot.interact {
            item("lib").isExpanded = true
            other.requestFocus()
        }
        robot.interact { view.applySearch(listOf(p("src/main/App.kt"), p("docs/guide/intro.md"))) }
        assertEquals(listOf(project.name, "docs", "guide", "intro.md", "src", "main", "App.kt"), renderedNames(robot))
        robot.interact { view.selectOnly(item("App.kt")) }

        robot.interact { view.applySearch(null) }

        assertFalse(view.searchActive)
        assertEquals(
            listOf(project.name, "docs", "lib", "Lib.kt", "src", "main", "App.kt", "Other.kt", "test", "README.md"),
            renderedNames(robot),
        )
        assertEquals(listOf(p("src/main/App.kt")), selected())
        assertTrue(other.isFocused)
    }

    @Test
    fun `a revealed entry joins the results until the extras are reset`(robot: FxRobot) {
        open(robot)
        robot.interact { view.applySearch(listOf(p("src/main/App.kt"))) }

        var found = false
        robot.interact { found = view.reveal(p("lib/Lib.kt")) }

        assertTrue(found)
        val withExtra = listOf(project.name, "lib", "Lib.kt", "src", "main", "App.kt")
        assertEquals(withExtra, renderedNames(robot))
        assertEquals(listOf(p("lib/Lib.kt")), selected())

        // A run of the same query again keeps it, a new query does not.
        robot.interact { view.applySearch(listOf(p("src/main/App.kt"))) }
        assertEquals(withExtra, renderedNames(robot))
        assertEquals(listOf(p("lib/Lib.kt")), selected())

        robot.interact {
            view.resetSearchExtras()
            view.applySearch(listOf(p("src/main/App.kt")))
        }
        assertEquals(listOf(project.name, "src", "main", "App.kt"), renderedNames(robot))
        assertEquals(emptyList(), selected())

        // An entry not found is not kept: its folder does not join the results.
        robot.interact { found = view.reveal(p("lib/Missing.kt")) }
        assertFalse(found)
        robot.interact { view.applySearch(listOf(p("src/main/App.kt"))) }
        assertEquals(listOf(project.name, "src", "main", "App.kt"), renderedNames(robot))
    }

    @Test
    fun `a matched folder holding a deeper match is expanded and shows only the matches inside`(robot: FxRobot) {
        open(robot)

        robot.interact { view.applySearch(listOf(p("src/main"), p("src/main/App.kt"))) }

        assertEquals(listOf(project.name, "src", "main", "App.kt"), renderedNames(robot))
    }

    @Test
    fun `a search run again sets the expansion of a matched folder that starts or stops holding matches`(
        robot: FxRobot,
    ) {
        open(robot)
        robot.interact { view.applySearch(listOf(p("src/main/App.kt"))) }
        assertEquals(listOf(project.name, "src", "main", "App.kt"), renderedNames(robot))

        robot.interact { view.applySearch(listOf(p("src/main"), p("src/main/App.kt"))) }
        assertEquals(listOf(project.name, "src", "main", "App.kt"), renderedNames(robot))

        robot.interact { view.applySearch(listOf(p("src/main"))) }
        assertEquals(listOf(project.name, "src", "main"), renderedNames(robot))

        robot.interact { view.applySearch(listOf(p("src/main"), p("src/main/App.kt"))) }
        assertEquals(listOf(project.name, "src", "main", "App.kt"), renderedNames(robot))
    }

    @Test
    fun `a compacted row with a matched middle folder is a folder hit and stays collapsed`(robot: FxRobot) {
        p("src/main/kotlin/App.kt").createParentDirectories().createFile()
        p("README.md").createFile()
        robot.interact {
            explorerSettings.compactFolders.set(true)
            view.open(project)
        }
        assertEquals(listOf(project.name, "src/main/kotlin", "README.md"), renderedNames(robot))

        robot.interact { view.applySearch(listOf(p("src/main"))) }
        assertEquals(listOf(project.name, "src/main/kotlin"), renderedNames(robot))

        // Also as a new match of a search run again.
        robot.interact { view.applySearch(listOf(p("README.md"))) }
        robot.interact { view.applySearch(listOf(p("README.md"), p("src/main"))) }
        assertEquals(listOf(project.name, "src/main/kotlin", "README.md"), renderedNames(robot))
    }

    @Test
    fun `a compacted row whose last folder matches and holds matches is expanded with only them`(robot: FxRobot) {
        listOf("App.kt", "AppConfig.kt", "Other.kt").forEach {
            p("src/main/kotlin/app/$it").createParentDirectories().createFile()
        }
        robot.interact {
            explorerSettings.compactFolders.set(true)
            view.open(project)
        }

        val app = "src/main/kotlin/app"
        robot.interact { view.applySearch(listOf(p(app), p("$app/App.kt"), p("$app/AppConfig.kt"))) }

        assertEquals(listOf(project.name, app, "App.kt", "AppConfig.kt"), renderedNames(robot))
    }

    @Test
    fun `entries the explorer creates or moves while searching join the results and are selected`(robot: FxRobot) {
        open(robot)
        robot.interact { view.applySearch(listOf(p("src/main/App.kt"))) }

        robot.interact { view.create(item("main"), folder = false) }

        assertEquals(listOf(project.name, "src", "main", "App.kt", "Created.kt"), renderedNames(robot))
        assertEquals(listOf(p("src/main/Created.kt")), selected())

        robot.interact { view.move(listOf(item("App.kt")), item("src")) }

        assertEquals(listOf(project.name, "src", "main", "Created.kt", "App.kt"), renderedNames(robot))
        assertEquals(listOf(p("src/App.kt")), selected())
    }

    @Test
    fun `files appearing on disk while searching stay hidden, below a matched folder they show`(robot: FxRobot) {
        open(robot)
        val changed = CopyOnWriteArrayList<Path>()
        var invalidations = 0
        robot.interact {
            view.onFilesChanged = { changed.addAll(it) }
            view.searchInvalidated.addListener { invalidations++ }
            view.applySearch(listOf(p("src/main/App.kt"), p("docs")))
            item("docs").isExpanded = true
        }

        p("src/main/New.kt").createFile()
        p("docs/new.txt").createFile()
        // Each folder's listing is updated before its change is reported.
        eventually(robot) { assertTrue(p("src/main/New.kt") in changed && p("docs/new.txt") in changed, "$changed") }
        WaitForAsyncUtils.waitForFxEvents()

        assertEquals(
            listOf(project.name, "docs", "guide", "new.txt", "notes.txt", "src", "main", "App.kt"),
            renderedNames(robot),
        )
        assertTrue(invalidations > 0, "the search is told to run again")
    }

    @Test
    fun `the search is told to run again after Refresh and after the Compact and Hide Generated settings changed`(
        robot: FxRobot,
    ) {
        open(robot)
        var invalidations = 0
        robot.interact {
            view.searchInvalidated.addListener { invalidations++ }
            view.applySearch(listOf(p("src/main/App.kt")))
        }

        robot.interact { view.refreshAll() }
        assertEquals(1, invalidations)
        robot.interact { explorerSettings.compactFolders.set(true) }
        assertEquals(2, invalidations)
        robot.interact { explorerSettings.hideGeneratedFolders.set(!explorerSettings.hideGeneratedFolders.get()) }
        assertEquals(3, invalidations)
        // Still filtered after listing again.
        assertEquals(listOf(project.name, "src", "main", "App.kt"), renderedNames(robot))
    }

    @Test
    fun `a search run again keeps the selection, the user's expansion and the expansion from before the search`(
        robot: FxRobot,
    ) {
        open(robot)
        robot.interact { item("lib").isExpanded = true }
        robot.interact { view.applySearch(listOf(p("src/main/App.kt"), p("docs"))) }
        robot.interact {
            item("docs").isExpanded = true
            view.selectOnly(item("App.kt"))
        }

        robot.interact { view.applySearch(listOf(p("src/main/App.kt"), p("docs"), p("src/test/AppTest.kt"))) }

        assertEquals(
            listOf(project.name, "docs", "guide", "notes.txt", "src", "main", "App.kt", "test", "AppTest.kt"),
            renderedNames(robot),
        )
        assertEquals(listOf(p("src/main/App.kt")), selected())

        robot.interact { view.applySearch(null) }

        assertEquals(
            listOf(project.name, "docs", "lib", "Lib.kt", "src", "main", "App.kt", "Other.kt", "test", "README.md"),
            renderedNames(robot),
        )
        assertEquals(listOf(p("src/main/App.kt")), selected())
    }
}
