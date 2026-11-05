package dev.sluide.translation.settings

import dev.sluide.localmodels.ModelInstaller
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import dev.sluide.translation.opusmt.OpusMtEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationSettingsPageModelTest {
    @TempDir
    lateinit var root: Path

    private val engine by lazy { OpusMtEngine(root) }
    private val installer by lazy { ModelInstaller(engine, executor = { it.run() }, dispatch = { it.run() }) }
    private var answer = true
    private val asked = ArrayList<String>()
    private val model by lazy {
        TranslationSettingsPageModel(
            engine,
            installer,
            object : TranslationSettingsDialogs {
                override fun confirmDelete(question: String): Boolean {
                    asked += question
                    return answer
                }
            },
        )
    }

    @AfterEach
    fun tearDown() {
        engine.close()
        installer.close()
    }

    private fun pair(
        code: String,
        quality: Quality = Quality.MEDIUM,
    ) = code.split('-').let { (s, t) -> PairModel(s, t, quality) }

    private fun install(m: PairModel) {
        for (file in engine.files(m)) {
            val path = engine.folder(m).resolve(file.path)
            Files.createDirectories(path.parent)
            RandomAccessFile(path.toFile(), "rw").use { it.setLength(file.bytes) }
        }
    }

    @Test
    fun `no rows and no total without models`() {
        assertTrue(model.rows().isEmpty())
        assertEquals(0L, model.totalBytes())
    }

    @Test
    fun `rows are sorted by source and target name, Medium before High, with the size of the model's files`() {
        install(pair("sv-en", Quality.HIGH))
        install(pair("de-en", Quality.HIGH))
        install(pair("de-en"))
        install(pair("en-de"))

        val rows = model.rows()

        assertEquals(
            listOf(pair("en-de"), pair("de-en"), pair("de-en", Quality.HIGH), pair("sv-en", Quality.HIGH)),
            rows.map { it.model },
        )
        val mb = Math.round(engine.files(pair("de-en")).sumOf { it.bytes } / 1_000_000.0)
        assertEquals("German → English · Medium · $mb MB", rows[1].label)
        assertTrue(rows[2].label.startsWith("German → English · High · "))
        assertTrue(rows[3].label.startsWith("Swedish → English · High · "))
    }

    @Test
    fun `the total counts a pair's shared files once`() {
        val medium = pair("de-en")
        val high = pair("de-en", Quality.HIGH)
        install(medium)
        val alone = model.totalBytes()
        assertEquals(engine.files(medium).sumOf { it.bytes }, alone)
        install(high)
        val mediumPaths = engine.files(medium).map { it.path }.toSet()
        val onlyHigh = engine.files(high).filter { it.path !in mediumPaths }.sumOf { it.bytes }
        assertEquals(alone + onlyHigh, model.totalBytes())
    }

    @Test
    fun `the question names the model and its size`() {
        val text = model.deleteQuestion(pair("de-en"))
        assertTrue(
            text.startsWith("Delete the German → English (Medium) translation model? It must be downloaded again ("),
        )
        assertTrue(text.endsWith(" MB) to translate with it."))
    }

    @Test
    fun `delete removes the files only after Delete and keeps the shared files of the other quality`() {
        val medium = pair("de-en")
        val high = pair("de-en", Quality.HIGH)
        install(medium)
        install(high)
        answer = false

        model.delete(medium)

        assertEquals(1, asked.size)
        assertTrue(engine.installed(medium))

        answer = true
        model.delete(medium)

        assertFalse(engine.installed(medium))
        assertTrue(engine.installed(high))
        assertEquals(listOf(high), model.rows().map { it.model })
    }

    @Test
    fun `a model whose delete failed after the first file stays listed as incomplete with the message`() {
        val medium = pair("de-en")
        install(medium)
        // A non-empty directory where the last file belongs cannot be deleted as a file, on any system.
        val last = engine.folder(medium).resolve(engine.files(medium).last().path)
        Files.delete(last)
        Files.createDirectories(last)
        Files.writeString(last.resolve("child"), "x")

        model.delete(medium)

        assertFalse(engine.installed(medium))
        val row = model.rows().single()
        assertEquals(medium, row.model)
        assertEquals("German → English · Medium · incomplete", row.label)
        assertTrue(row.message.orEmpty().startsWith("Cannot delete "), row.message)
    }
}
