package dev.sluide.coloring

import dev.sluide.coloring.color.CssColor
import dev.sluide.coloring.color.CssColorParser
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedColorsTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var store: ProjectDataStore
    private lateinit var colors: SavedColors
    private lateinit var folder: Path
    private val mapper = JsonMapper()
    private val red = CssColor.fromRgb8(255, 0, 0)
    private val blue = CssColor.fromRgb8(59, 130, 246)

    @BeforeEach
    fun setUp() {
        store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        colors = SavedColors(store)
        folder = Files.createDirectories(dir.resolve("work/proj"))
    }

    private fun seed(
        json: String,
        f: Path? = folder,
    ) {
        val node = mapper.readTree(json)
        if (f == null) store.updateShared { it.set("colors", node) } else store.update(f) { it.set("colors", node) }
    }

    private fun stored(f: Path? = folder) = (if (f == null) store.readShared() else store.read(f))?.get("colors")

    @Test
    fun `saves in order as CSS text`() {
        assertTrue(colors.save(folder, blue))
        assertTrue(colors.save(folder, red))

        assertEquals(listOf(blue, red), colors.saved(folder))
        assertEquals("""{"saved":["#3b82f6","#ff0000"]}""", stored().toString())
    }

    @Test
    fun `does not save the same 8-bit color twice in another notation`() {
        assertTrue(colors.save(folder, red))

        assertFalse(colors.save(folder, CssColorParser.parse("red")!!))
        assertEquals(1, colors.saved(folder).size)
    }

    @Test
    fun `an out-of-gamut color is saved as oklch text`() {
        val vivid = assertNotNull(CssColorParser.parse("oklch(0.7 0.4 150)"))

        assertTrue(colors.save(folder, vivid))
        assertTrue(
            stored()!!
                .get("saved")
                .get(0)
                .asString()
                .startsWith("oklch("),
        )
    }

    @Test
    fun `remove drops every entry of the same 8-bit color`() {
        seed("""{"saved":["#ff0000","red","#3b82f6"]}""")

        assertTrue(colors.remove(folder, red))
        assertEquals(listOf(blue), colors.saved(folder))
        assertFalse(colors.remove(folder, red))
    }

    @Test
    fun `removing the last color removes the field`() {
        colors.save(folder, red)
        colors.remove(folder, red)

        assertNull(stored())
    }

    @Test
    fun `without a folder the colors live in the shared file`() {
        assertTrue(colors.save(null, red))

        assertEquals(listOf(red), colors.saved(null))
        assertEquals(emptyList(), colors.saved(folder))
        assertNotNull(stored(null))
    }

    @Test
    fun `two folders are independent`() {
        val other = Files.createDirectories(dir.resolve("work/other"))
        colors.save(folder, red)
        colors.save(other, blue)

        assertEquals(listOf(red), colors.saved(folder))
        assertEquals(listOf(blue), colors.saved(other))
    }

    @Test
    fun `unknown content and unparseable entries survive a write and are skipped on read`() {
        seed("""{"saved":["nonsense",5,"#ff0000"],"extra":{"a":1}}""")

        assertEquals(listOf(red), colors.saved(folder))
        assertTrue(colors.save(folder, blue))
        assertTrue(colors.remove(folder, red))

        assertEquals("""{"saved":["nonsense",5,"#3b82f6"],"extra":{"a":1}}""", stored().toString())
    }

    @Test
    fun `a field of the wrong type is refused and left alone`() {
        store.update(folder) { it.put("colors", "oops") }
        assertFalse(colors.save(folder, red))
        assertEquals("oops", stored()!!.asString())

        seed("""{"saved":"oops"}""")
        assertFalse(colors.save(folder, red))
        assertEquals("""{"saved":"oops"}""", stored().toString())
    }

    @Test
    fun `listeners are called once per change and not for refusals`() {
        var calls = 0
        val subscription = colors.onChanged { calls++ }

        colors.save(folder, red)
        assertEquals(1, calls)
        colors.save(folder, red)
        colors.remove(folder, blue)
        assertEquals(1, calls)
        colors.remove(folder, red)
        assertEquals(2, calls)

        store.update(folder) { it.put("colors", "oops") }
        colors.save(folder, red)
        assertEquals(2, calls)

        subscription.unsubscribe()
        store.update(folder) { it.remove("colors") }
        colors.save(folder, red)
        assertEquals(2, calls)
    }
}
