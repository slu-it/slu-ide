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
        assertTrue(todos.edit("b", TodoScope.PROJECT, TodoScope.PROJECT, folder, "new", "text"))
        assertEquals(listOf(Todo("a", "sa", "da"), Todo("b", "new", "text")), todos.list(TodoScope.PROJECT, folder))
    }

    @Test
    fun `edit across scopes lands on top of the target, leaves the source and carries unknown fields`() {
        seed(TodoScope.PROJECT, "[${entry("a")},${entry("b", ""","extra":{"x":1}""")}]")
        seed(TodoScope.SHARED, "[${entry("c")}]")
        assertTrue(todos.edit("b", TodoScope.PROJECT, TodoScope.SHARED, folder, "moved", "m"))
        assertEquals(listOf("moved", "sc"), summaries(TodoScope.SHARED))
        assertEquals(listOf("sa"), summaries(TodoScope.PROJECT))
        val shared = store.readShared()?.get("todos")?.get(0)
        assertEquals(1, shared?.get("extra")?.get("x")?.intValue())
    }

    @Test
    fun `edit of a missing id adds it on top with its id`() {
        seed(TodoScope.SHARED, "[${entry("a")}]")
        assertTrue(todos.edit("gone", TodoScope.SHARED, TodoScope.SHARED, null, "back", ""))
        assertEquals(Todo("gone", "back", ""), todos.list(TodoScope.SHARED, null).first())
        assertTrue(todos.edit("gone2", TodoScope.PROJECT, TodoScope.SHARED, folder, "back2", ""))
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
        assertTrue(todos.move(TodoScope.PROJECT, folder, id, index))
        assertEquals(expected.split(" ").map { "s$it" }, summaries(TodoScope.PROJECT))
    }

    @Test
    fun `move skips unlisted entries and keeps them in place`() {
        seed(TodoScope.SHARED, """[${entry("a")},5,${entry("b")},{"summary":"no id"}]""")
        assertTrue(todos.move(TodoScope.SHARED, null, "a", 1))
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
        todos.edit("a", TodoScope.SHARED, TodoScope.SHARED, null, "changed", "")
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
        assertFalse(todos.move(TodoScope.PROJECT, null, "a", 0))
        assertFalse(todos.delete(TodoScope.PROJECT, null, "a"))
        assertFalse(todos.edit("a", TodoScope.SHARED, TodoScope.PROJECT, null, "x", ""))
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
        todos.edit(added.id, TodoScope.PROJECT, TodoScope.SHARED, crooked, "x", "")
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
        assertFalse(todos.edit("a", TodoScope.PROJECT, TodoScope.SHARED, folder, "x", ""))
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
        assertFalse(todos.edit("a", TodoScope.PROJECT, TodoScope.SHARED, folder, "moved", "m"))
        assertEquals(listOf("moved"), summaries(TodoScope.SHARED))
        assertEquals(listOf("sa"), summaries(TodoScope.PROJECT))
        assertEquals(listOf(TodoScope.SHARED), events)
    }
}
