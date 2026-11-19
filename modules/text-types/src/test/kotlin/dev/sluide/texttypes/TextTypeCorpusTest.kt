package dev.sluide.texttypes

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every sample of `src/test/resources/text-types/` (layout: its README) detected with the built-in rules alone. */
class TextTypeCorpusTest {
    private val detector = TextTypeDetector()

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    fun `a sample is detected as the type of its folder`(sample: String) {
        val file = root.resolve(sample)
        val expected = checkNotNull(TextTypes.byExtension(file.parent.name)) { "Unknown folder of $sample" }
        assertEquals(expected, detector.detect(file.readText()))
    }

    @Test
    fun `every type the rules detect has at least three samples, and there are negatives`() {
        val counts = samples().groupingBy { it.substringBefore('/') }.eachCount()
        val detected = listOf("md", "xml", "html", "svg", "css", "js", "ts", "kt", "java", "py", "sh", "sql", "mmd")
        (detected + listOf("bicep", "diff", "txt")).forEach { folder ->
            assertTrue((counts[folder] ?: 0) >= 3, "$folder has ${counts[folder]} samples")
        }
    }

    companion object {
        private val root: Path =
            Paths.get(checkNotNull(TextTypeCorpusTest::class.java.getResource("/text-types/README.md")).toURI()).parent

        @JvmStatic
        fun samples(): List<String> =
            Files.walk(root).use { paths ->
                paths
                    .filter { it.extension == "sample" }
                    .map { root.relativize(it).toString().replace('\\', '/') }
                    .sorted()
                    .toList()
            }
    }
}
