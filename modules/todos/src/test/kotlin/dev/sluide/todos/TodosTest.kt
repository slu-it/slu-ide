package dev.sluide.todos

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodosTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private lateinit var todos: Todos
    private lateinit var folder: Path
    private val mapper = JsonMapper()

    @BeforeEach
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        todos = Todos(store)
        folder = Files.createDirectories(dir.resolve("work/proj"))
    }

    private fun summaries(
        scope: TodoScope,
        f: Path? = folder,
    ) = todos.list(scope, f).map { it.summary }

    private fun seed(
        scope: TodoScope,
        json: String,
    ) {
        val array = mapper.readTree(json)
        if (scope == TodoScope.SHARED) {
            store.updateShared { it.set("todos", array) }
        } else {
            store.update(folder) { it.set("todos", array) }
        }
    }

    private fun entry(
        id: String,
        extra: String = "",
    ) = """{"id":"$id","summary":"s$id","description":"d$id"$extra}"""

    @Test
    fun `add puts the new TODO at the top and list keeps the display order`() {
        val first = assertNotNull(todos.add(TodoScope.SHARED, null, "one", "desc"))
        todos.add(TodoScope.SHARED, null, "two", "")
        assertEquals(listOf("two", "one"), summaries(TodoScope.SHARED, null))
        assertEquals(Todo(first.id, "one", "desc"), todos.list(TodoScope.SHARED, null)[1])
        assertEquals(emptyList(), todos.list(TodoScope.PROJECT, folder))
    }

    @Test
    fun `edit in place keeps the position`() {
        seed(TodoScope.PROJECT, "[${entry("a")},${entry("b")}]")
        assertTrue(todos.edit("b", TodoScope.PROJECT, TodoScope.PROJECT, folder, "new", "text", null))
        assertEquals(listOf(Todo("a", "sa", "da"), Todo("b", "new", "text")), todos.list(TodoScope.PROJECT, folder))
    }

    @Test
    fun `edit across scopes lands on top of the target, leaves the source and carries unknown fields`() {
        seed(TodoScope.PROJECT, "[${entry("a")},${entry("b", ""","extra":{"x":1}""")}]")
        seed(TodoScope.SHARED, "[${entry("c")}]")
        assertTrue(todos.edit("b", TodoScope.PROJECT, TodoScope.SHARED, folder, "moved", "m", null))
        assertEquals(listOf("moved", "sc"), summaries(TodoScope.SHARED))
        assertEquals(listOf("sa"), summaries(TodoScope.PROJECT))
        val shared = store.readShared()?.get("todos")?.get(0)
        assertEquals(1, shared?.get("extra")?.get("x")?.intValue())
    }

    @Test
    fun `edit of a missing id adds it on top with its id`() {
        seed(TodoScope.SHARED, "[${entry("a")}]")
        assertTrue(todos.edit("gone", TodoScope.SHARED, TodoScope.SHARED, null, "back", "", null))
        assertEquals(Todo("gone", "back", ""), todos.list(TodoScope.SHARED, null).first())
        assertTrue(todos.edit("gone2", TodoScope.PROJECT, TodoScope.SHARED, folder, "back2", "", null))
        assertEquals(listOf("back2", "back", "sa"), summaries(TodoScope.SHARED))
    }

    @ParameterizedTest
    @CsvSource(
        "c,0,c a b d",
        "a,2,b c a d",
        "a,3,b c d a",
        "a,99,b c d a",
        "d,-5,d a b c",
        "b,1,a b c d",
        "zz,0,a b c d",
    )
    fun `move puts the TODO before the one now at the index`(
        id: String,
        index: Int,
        expected: String,
    ) {
        seed(TodoScope.PROJECT, "[${listOf("a", "b", "c", "d").joinToString(",") { entry(it) }}]")
        assertTrue(todos.move(TodoScope.PROJECT, folder, id, TodoPlace(null, index)))
        assertEquals(expected.split(" ").map { "s$it" }, summaries(TodoScope.PROJECT))
    }

    @Test
    fun `move skips unlisted entries and keeps them in place`() {
        seed(TodoScope.SHARED, """[${entry("a")},5,${entry("b")},{"summary":"no id"}]""")
        assertTrue(todos.move(TodoScope.SHARED, null, "a", TodoPlace(null, 1)))
        assertEquals(listOf("sb", "sa"), summaries(TodoScope.SHARED, null))
        val array = store.readShared()?.get("todos")
        assertEquals(4, array?.size())
        assertEquals(5, array?.get(0)?.intValue())
        assertEquals("no id", array?.get(3)?.get("summary")?.stringValue())
    }

    @Test
    fun `delete removes the TODO, also when missing, and drops the empty field`() {
        seed(TodoScope.PROJECT, "[${entry("a")}]")
        assertTrue(todos.delete(TodoScope.PROJECT, folder, "zz"))
        assertEquals(1, todos.list(TodoScope.PROJECT, folder).size)
        assertTrue(todos.delete(TodoScope.PROJECT, folder, "a"))
        assertFalse(store.read(folder)!!.has("todos"))
    }

    @Test
    fun `unknown top-level and entry fields and malformed entries are kept`() {
        store.updateShared { it.put("other", "keep") }
        seed(TodoScope.SHARED, """["text",{"id":7},${entry("a", ""","note":"n"""")}]""")
        todos.add(TodoScope.SHARED, null, "new", "")
        todos.edit("a", TodoScope.SHARED, TodoScope.SHARED, null, "changed", "", null)
        val root = store.readShared()!!
        assertEquals("keep", root.get("other").stringValue())
        val array = root.get("todos")
        assertEquals(4, array.size())
        assertEquals("text", array.get(1).stringValue())
        assertEquals(7, array.get(2).get("id").intValue())
        assertEquals("n", array.get(3).get("note").stringValue())
        assertEquals(listOf("new", "changed"), summaries(TodoScope.SHARED, null))
    }

    @Test
    fun `missing summary and description read as empty`() {
        seed(TodoScope.SHARED, """[{"id":"a"}]""")
        assertEquals(listOf(Todo("a", "", "")), todos.list(TodoScope.SHARED, null))
    }

    @Test
    fun `PROJECT without a folder is empty and writes fail`() {
        assertEquals(emptyList(), todos.list(TodoScope.PROJECT, null))
        assertNull(todos.add(TodoScope.PROJECT, null, "x", ""))
        assertFalse(todos.move(TodoScope.PROJECT, null, "a", TodoPlace(null, 0)))
        assertFalse(todos.delete(TodoScope.PROJECT, null, "a"))
        assertFalse(todos.edit("a", TodoScope.SHARED, TodoScope.PROJECT, null, "x", "", null))
    }

    @Test
    fun `listeners get the scope and the normalized folder, both scopes on a scope change`() {
        val events = mutableListOf<Pair<TodoScope, Path?>>()
        val subscription = todos.onChanged { scope, path -> events += scope to path }
        val crooked = folder.resolve("../proj")
        val added = assertNotNull(todos.add(TodoScope.PROJECT, crooked, "x", ""))
        todos.add(TodoScope.SHARED, null, "y", "")
        assertEquals(listOf(TodoScope.PROJECT to folder.toAbsolutePath().normalize(), TodoScope.SHARED to null), events)
        events.clear()
        todos.edit(added.id, TodoScope.PROJECT, TodoScope.SHARED, crooked, "x", "", null)
        assertEquals(setOf(TodoScope.PROJECT, TodoScope.SHARED), events.map { it.first }.toSet())
        events.clear()
        subscription.unsubscribe()
        todos.delete(TodoScope.SHARED, null, added.id)
        assertEquals(emptyList(), events)
    }

    @Test
    fun `a failed write notifies nobody and a failed target write leaves the source untouched`() {
        seed(TodoScope.PROJECT, "[${entry("a")}]")
        Files.writeString(dir.resolve("shared.json"), "{ not json")
        val events = mutableListOf<TodoScope>()
        todos.onChanged { scope, _ -> events += scope }
        assertNull(todos.add(TodoScope.SHARED, null, "x", ""))
        assertFalse(todos.edit("a", TodoScope.PROJECT, TodoScope.SHARED, folder, "x", "", null))
        assertEquals(emptyList(), events)
        assertEquals(listOf(Todo("a", "sa", "da")), todos.list(TodoScope.PROJECT, folder))
        assertEquals("{ not json", Files.readString(dir.resolve("shared.json")))
    }

    @Test
    fun `a todos field that is no array is left alone and writes fail`() {
        store.updateShared { it.put("todos", "not a list") }
        val events = mutableListOf<TodoScope>()
        todos.onChanged { scope, _ -> events += scope }
        assertEquals(emptyList(), todos.list(TodoScope.SHARED, null))
        assertNull(todos.add(TodoScope.SHARED, null, "x", ""))
        assertFalse(todos.delete(TodoScope.SHARED, null, "x"))
        assertEquals("not a list", store.readShared()?.get("todos")?.stringValue())
        assertEquals(emptyList(), events)
    }

    @Test
    fun `a failed removal from the source after the target was saved leaves the TODO in both scopes`() {
        seed(TodoScope.PROJECT, "[${entry("a")}]")
        val file = Files.list(dir.resolve("projects")).use { it.toList().single() }
        Files.writeString(file, Files.readString(file).replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": 2"))
        val events = mutableListOf<TodoScope>()
        todos.onChanged { scope, _ -> events += scope }
        assertFalse(todos.edit("a", TodoScope.PROJECT, TodoScope.SHARED, folder, "moved", "m", null))
        assertEquals(listOf("moved"), summaries(TodoScope.SHARED))
        assertEquals(listOf("sa"), summaries(TodoScope.PROJECT))
        assertEquals(listOf(TodoScope.SHARED), events)
    }

    private fun folderJson(
        id: String,
        vararg children: String,
        extra: String = "",
    ) = """{"id":"$id","folder":"n$id","collapsed":false,"todos":[${children.joinToString(",")}]$extra}"""

    private fun tree(scope: TodoScope = TodoScope.SHARED) =
        todos.items(scope, folder).joinToString(" ") {
            if (it is TodoFolder) "${it.id}[${it.todos.joinToString("+") { t -> t.id }}]" else it.id
        }

    private fun seedTree() =
        seed(TodoScope.SHARED, "[${entry("a")},${folderJson("F", entry("b"), entry("c"))},${entry("d")}]")

    private fun unchanged(action: () -> Boolean) {
        val before = Files.readString(dir.resolve("shared.json"))
        assertFalse(action())
        assertEquals(before, Files.readString(dir.resolve("shared.json")))
    }

    @Test
    fun `items reads the tree with collapsed and list flattens it in order`() {
        seed(
            TodoScope.SHARED,
            "[${entry("a")},${folderJson("F", entry("b"), entry("c")).replace("false", "true")},${entry("d")}]",
        )
        val items = todos.items(TodoScope.SHARED, null)
        assertEquals(Todo("a", "sa", "da"), items[0])
        assertEquals(TodoFolder("F", "nF", true, listOf(Todo("b", "sb", "db"), Todo("c", "sc", "dc"))), items[1])
        assertEquals(listOf("a", "b", "c", "d"), todos.list(TodoScope.SHARED, null).map { it.id })
    }

    @Test
    fun `a non-boolean collapsed reads as false`() {
        seed(TodoScope.SHARED, """[{"id":"F","folder":"x","collapsed":"yes"}]""")
        assertEquals(TodoFolder("F", "x", false, emptyList()), todos.items(TodoScope.SHARED, null).single())
    }

    @Test
    fun `a non-boolean expanded reads as false`() {
        seed(TodoScope.SHARED, "[${entry("a", ""","expanded":"yes"""")}]")
        assertEquals(Todo("a", "sa", "da", false), todos.list(TodoScope.SHARED, null).single())
    }

    @Test
    fun `unknown fields of folders and TODOs survive unrelated writes`() {
        seed(TodoScope.SHARED, "[${folderJson("F", entry("b", ""","k":1"""), extra = ""","z":{"y":2}""")}]")
        todos.add(TodoScope.SHARED, null, "new", "")
        todos.setCollapsed(TodoScope.SHARED, null, "F", true)
        todos.move(TodoScope.SHARED, null, "b", TodoPlace("F", 0))
        val f = store.readShared()!!.get("todos").get(1)
        assertEquals(2, f.get("z").get("y").intValue())
        assertEquals(
            1,
            f
                .get("todos")
                .get(0)
                .get("k")
                .intValue(),
        )
    }

    @Test
    fun `add into a folder lands at its top and a missing folder means the root top`() {
        seedTree()
        val inside = assertNotNull(todos.add(TodoScope.SHARED, null, "in", "", "F"))
        assertEquals("F[${inside.id}+b+c]", tree().split(" ")[1])
        val root = assertNotNull(todos.add(TodoScope.SHARED, null, "out", "", "gone"))
        assertEquals(root.id, tree().split(" ")[0])
    }

    @Test
    fun `add into a folder without todos creates the array`() {
        seed(TodoScope.SHARED, """[{"id":"F","folder":"x"}]""")
        val added = assertNotNull(todos.add(TodoScope.SHARED, null, "in", "", "F"))
        assertEquals("F[${added.id}]", tree())
    }

    @Test
    fun `a folder whose todos is no array lists nothing and refuses writes into it`() {
        seed(TodoScope.SHARED, """[{"id":"F","folder":"x","todos":"odd"},${entry("a")}]""")
        assertEquals("F[] a", tree())
        unchanged { todos.add(TodoScope.SHARED, null, "in", "", "F") != null }
        unchanged { todos.move(TodoScope.SHARED, null, "a", TodoPlace("F", 0)) }
        unchanged { todos.edit("a", TodoScope.SHARED, TodoScope.SHARED, null, "x", "", "F") }
        unchanged { todos.deleteFolder(TodoScope.SHARED, null, "F", true) }
    }

    @ParameterizedTest
    @CsvSource(
        "a,F,0,F[a+b+c] d",
        "a,F,1,F[b+a+c] d",
        "a,F,9,F[b+c+a] d",
        "b,,0,b a F[c] d",
        "b,,2,a F[c] b d",
        "c,F,0,a F[c+b] d",
        "F,,2,a d F[b+c]",
        "F,,0,F[b+c] a d",
        "d,,1,a d F[b+c]",
        "zz,F,0,a F[b+c] d",
        "a,nope,0,a F[b+c] d",
    )
    fun `move puts a TODO or folder into a container`(
        id: String,
        target: String?,
        index: Int,
        expected: String,
    ) {
        seedTree()
        assertTrue(todos.move(TodoScope.SHARED, null, id, TodoPlace(target, index)))
        assertEquals(expected, tree())
    }

    @Test
    fun `a folder cannot go into a folder`() {
        seed(TodoScope.SHARED, "[${folderJson("F")},${folderJson("G")}]")
        unchanged { todos.move(TodoScope.SHARED, null, "F", TodoPlace("G", 0)) }
    }

    @Test
    fun `move keeps unlisted entries in their relative place inside a folder`() {
        seed(TodoScope.SHARED, "[${folderJson("F", entry("a"), "5", """{"id":"N","folder":"nested"}""", entry("b"))}]")
        assertTrue(todos.move(TodoScope.SHARED, null, "a", TodoPlace("F", 1)))
        val inner =
            store
                .readShared()!!
                .get("todos")
                .get(0)
                .get("todos")
        assertEquals(listOf("5", "N", "b", "a").size, inner.size())
        assertEquals(5, inner.get(0).intValue())
        assertEquals("N", inner.get(1).get("id").stringValue())
        assertEquals("b", inner.get(2).get("id").stringValue())
        assertEquals("F[b+a]", tree())
    }

    @Test
    fun `a nested folder is kept but neither listed nor found`() {
        seed(
            TodoScope.SHARED,
            "[${folderJson("F", entry("a"), """{"id":"N","folder":"nested","todos":[${entry("x")}]}""")}]",
        )
        assertEquals("F[a]", tree())
        assertTrue(todos.delete(TodoScope.SHARED, null, "N"))
        assertTrue(todos.delete(TodoScope.SHARED, null, "x"))
        assertEquals(
            2,
            store
                .readShared()!!
                .get("todos")
                .get(0)
                .get("todos")
                .size(),
        )
    }

    @Test
    fun `edit in place stays in place also inside a folder`() {
        seedTree()
        assertTrue(todos.edit("c", TodoScope.SHARED, TodoScope.SHARED, null, "new", "t", "F"))
        assertEquals("a F[b+c] d", tree())
        assertEquals("new", todos.list(TodoScope.SHARED, null)[2].summary)
    }

    @ParameterizedTest
    @CsvSource("a,F,F[a+b+c] d", "c,,c a F[b] d", "b,F,a F[b+c] d", "d,gone,d a F[b+c]")
    fun `edit with another target folder in the same scope moves it to its top`(
        id: String,
        target: String?,
        expected: String,
    ) {
        seedTree()
        assertTrue(todos.edit(id, TodoScope.SHARED, TodoScope.SHARED, null, "x", "", target))
        assertEquals(expected, tree())
    }

    @Test
    fun `edit across scopes puts it on top of the target folder there, else the root top`() {
        seed(TodoScope.PROJECT, "[${entry("p")}]")
        seedTree()
        assertTrue(todos.edit("p", TodoScope.PROJECT, TodoScope.SHARED, folder, "x", "", "F"))
        assertEquals("a F[p+b+c] d", tree())
        assertEquals(emptyList(), todos.list(TodoScope.PROJECT, folder))
        seed(TodoScope.PROJECT, "[${entry("q")}]")
        assertTrue(todos.edit("q", TodoScope.PROJECT, TodoScope.SHARED, folder, "x", "", "gone"))
        assertEquals("q", tree().split(" ")[0])
    }

    @Test
    fun `edit of a missing id lands on top of the target folder`() {
        seedTree()
        assertTrue(todos.edit("gone", TodoScope.SHARED, TodoScope.SHARED, null, "back", "", "F"))
        assertEquals("a F[gone+b+c] d", tree())
    }

    @Test
    fun `addFolder and editFolder rename in place`() {
        seedTree()
        val added = assertNotNull(todos.addFolder(TodoScope.SHARED, null, "New"))
        assertEquals(TodoFolder(added.id, "New", false, emptyList()), todos.items(TodoScope.SHARED, null)[0])
        assertTrue(todos.editFolder("F", TodoScope.SHARED, TodoScope.SHARED, null, "Renamed"))
        assertEquals("Renamed", (todos.items(TodoScope.SHARED, null)[2] as TodoFolder).name)
        assertEquals("${added.id}[] a F[b+c] d", tree())
    }

    @Test
    fun `editFolder across scopes carries contents and unknown fields and creates a missing one`() {
        seed(TodoScope.PROJECT, "[${entry("p")},${folderJson("F", entry("b", ""","k":1"""), extra = ""","z":3""")}]")
        seed(TodoScope.SHARED, "[${entry("a")}]")
        assertTrue(todos.editFolder("F", TodoScope.PROJECT, TodoScope.SHARED, folder, "Moved"))
        assertEquals("F[b] a", tree())
        assertEquals("p", tree(TodoScope.PROJECT))
        val moved = store.readShared()!!.get("todos").get(0)
        assertEquals("Moved", moved.get("folder").stringValue())
        assertEquals(3, moved.get("z").intValue())
        assertEquals(
            1,
            moved
                .get("todos")
                .get(0)
                .get("k")
                .intValue(),
        )
        assertTrue(todos.editFolder("G", TodoScope.PROJECT, TodoScope.SHARED, folder, "Fresh"))
        assertEquals("G[] F[b] a", tree())
    }

    @Test
    fun `deleteFolder drops the contents or splices all entries where the folder was`() {
        seed(TodoScope.SHARED, "[${entry("a")},${folderJson("F", entry("b"), "5", entry("c"))},${entry("d")}]")
        assertTrue(todos.deleteFolder(TodoScope.SHARED, null, "F", true))
        val array = store.readShared()!!.get("todos")
        assertEquals(5, array.size())
        assertEquals(5, array.get(2).intValue())
        assertEquals("a b c d", tree())
        seedTree()
        assertTrue(todos.deleteFolder(TodoScope.SHARED, null, "F", false))
        assertEquals("a d", tree())
    }

    @Test
    fun `setCollapsed writes and notifies`() {
        seedTree()
        val events = mutableListOf<TodoScope>()
        todos.onChanged { scope, _ -> events += scope }
        assertTrue(todos.setCollapsed(TodoScope.SHARED, null, "F", true))
        assertTrue((todos.items(TodoScope.SHARED, null)[1] as TodoFolder).collapsed)
        assertEquals(listOf(TodoScope.SHARED), events)
    }

    @Test
    fun `setExpanded writes at the root and in a folder, notifies and collapsing removes the field`() {
        seedTree()
        val events = mutableListOf<TodoScope>()
        todos.onChanged { scope, _ -> events += scope }
        assertTrue(todos.setExpanded(TodoScope.SHARED, null, "a", true))
        assertTrue(todos.setExpanded(TodoScope.SHARED, null, "b", true))
        assertEquals(listOf(true, true), listOf("a", "b").map { id -> expandedOf(id) })
        assertEquals(listOf(TodoScope.SHARED, TodoScope.SHARED), events)
        assertTrue(todos.setExpanded(TodoScope.SHARED, null, "a", false))
        assertFalse(expandedOf("a"))
        assertFalse(
            store
                .readShared()!!
                .get("todos")
                .get(0)
                .has("expanded"),
        )
        assertTrue(todos.setExpanded(TodoScope.SHARED, null, "b", false))
        assertFalse(store.readShared()!!.toString().contains("expanded"))
    }

    @Test
    fun `setExpanded of a missing id changes nothing`() {
        seedTree()
        val before = store.readShared()
        assertTrue(todos.setExpanded(TodoScope.SHARED, null, "gone", true))
        assertEquals(before, store.readShared())
    }

    @Test
    fun `the expanded flag survives edits, moves and deleting its folder with Keep TODOs`() {
        seed(TodoScope.SHARED, "[${entry("a", ""","expanded":true""")},${folderJson("F")},${folderJson("G")}]")
        assertTrue(todos.edit("a", TodoScope.SHARED, TodoScope.SHARED, null, "x", "y", null))
        assertTrue(expandedOf("a"))
        assertTrue(todos.move(TodoScope.SHARED, null, "a", TodoPlace("F", 0)))
        assertTrue(expandedOf("a"))
        assertTrue(todos.edit("a", TodoScope.SHARED, TodoScope.SHARED, null, "x", "y", "G"))
        assertTrue(expandedOf("a"))
        assertTrue(todos.deleteFolder(TodoScope.SHARED, null, "G", true))
        assertTrue(expandedOf("a"))
        assertTrue(todos.edit("a", TodoScope.SHARED, TodoScope.PROJECT, folder, "x", "y", null))
        assertTrue(todos.list(TodoScope.PROJECT, folder).single().expanded)
    }

    private fun expandedOf(id: String): Boolean =
        todos
            .items(TodoScope.SHARED, null)
            .flatMap { if (it is TodoFolder) it.todos else listOf(it) }
            .filterIsInstance<Todo>()
            .first { it.id == id }
            .expanded

    @Test
    fun `delete finds a TODO inside a folder`() {
        seedTree()
        assertTrue(todos.delete(TodoScope.SHARED, null, "b"))
        assertEquals("a F[c] d", tree())
        assertTrue(todos.delete(TodoScope.SHARED, null, "F"))
        assertEquals("a F[c] d", tree())
    }

    @ParameterizedTest
    @CsvSource("-1", "0", "5")
    fun `move into an empty folder works for any index`(index: Int) {
        seed(TodoScope.SHARED, "[${entry("a")},${folderJson("E")}]")
        assertTrue(todos.move(TodoScope.SHARED, null, "a", TodoPlace("E", index)))
        assertEquals("E[a]", tree())
    }

    @Test
    fun `a cross-scope edit of a TODO in a folder keeps its unknown fields`() {
        seed(TodoScope.PROJECT, "[${folderJson("F", entry("b", ""","k":1"""))}]")
        assertTrue(todos.edit("b", TodoScope.PROJECT, TodoScope.SHARED, folder, "x", "", null))
        assertEquals(
            1,
            store
                .readShared()!!
                .get("todos")
                .get(0)
                .get("k")
                .intValue(),
        )
        assertEquals("F[]", tree(TodoScope.PROJECT))
    }

    @Test
    fun `editFolder across scopes replaces TODOs and folders of the same ids in the target`() {
        seed(TodoScope.PROJECT, "[${folderJson("F", entry("b"))}]")
        seed(TodoScope.SHARED, "[${entry("b")},${folderJson("F", entry("x"))},${entry("a")}]")
        assertTrue(todos.editFolder("F", TodoScope.PROJECT, TodoScope.SHARED, folder, "Moved"))
        assertEquals("F[b] a", tree())
    }

    @Test
    fun `deleteFolder handles every folder with that id`() {
        seed(TodoScope.SHARED, "[${folderJson("F", entry("a"))},${folderJson("F", entry("b"))}]")
        assertTrue(todos.deleteFolder(TodoScope.SHARED, null, "F", true))
        assertEquals("a b", tree())
    }

    @Test
    fun `a failed removal of a moved folder from the source leaves it in both scopes`() {
        seed(TodoScope.PROJECT, "[${folderJson("F", entry("b"))}]")
        val file = Files.list(dir.resolve("projects")).use { it.toList().single() }
        Files.writeString(file, Files.readString(file).replace(Regex("\"version\"\\s*:\\s*1"), "\"version\": 2"))
        assertFalse(todos.editFolder("F", TodoScope.PROJECT, TodoScope.SHARED, folder, "Moved"))
        assertEquals("F[b]", tree())
        assertEquals("F[b]", tree(TodoScope.PROJECT))
    }

    @Test
    fun `editFolder in the same scope creates a missing folder and setCollapsed ignores one`() {
        seed(TodoScope.SHARED, "[${entry("a")}]")
        assertTrue(todos.editFolder("G", TodoScope.SHARED, TodoScope.SHARED, null, "Fresh"))
        assertEquals("G[] a", tree())
        assertTrue(todos.setCollapsed(TodoScope.SHARED, null, "nope", true))
        assertEquals("G[] a", tree())
    }
}
