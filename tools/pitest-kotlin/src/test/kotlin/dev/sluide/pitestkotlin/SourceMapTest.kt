package dev.sluide.pitestkotlin

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceMapTest {
    // As the Kotlin compiler writes it for InlineCollections (javap -v), with a KotlinDebug stratum whose lines map too.
    private val collections =
        """
        SMAP
        InlineCollections.kt
        Kotlin
        *S Kotlin
        *F
        + 1 InlineCollections.kt
        dev/sluide/pitestkotlin/fixtures/InlineCollections
        + 2 _Collections.kt
        kotlin/collections/CollectionsKt___CollectionsKt
        *L
        1#1,16:1
        777#2:17
        873#2,2:18
        *S KotlinDebug
        *F
        + 1 InlineCollections.kt
        dev/sluide/pitestkotlin/fixtures/InlineCollections
        *L
        6#1:17
        6#1:18,2
        9#1:20
        *E
        """.trimIndent()

    @ParameterizedTest
    @CsvSource(
        "1, InlineCollections.kt, dev/sluide/pitestkotlin/fixtures/InlineCollections.kt, 1",
        "16, InlineCollections.kt, dev/sluide/pitestkotlin/fixtures/InlineCollections.kt, 16",
        "17, _Collections.kt, kotlin/collections/_Collections.kt, 777",
        "18, _Collections.kt, kotlin/collections/_Collections.kt, 873",
        "19, _Collections.kt, kotlin/collections/_Collections.kt, 874",
    )
    fun `maps a line to its file of the F section and its line there, by every line entry`(
        line: Int,
        name: String,
        path: String,
        sourceLine: Int,
    ) {
        val source = assertNotNull(assertNotNull(SourceMap.parse(collections)).sourceOf(line))

        assertEquals(listOf(name, path, "$sourceLine"), listOf(source.file.name, source.file.path, "${source.line}"))
    }

    @Test
    fun `maps no line outside the Kotlin stratum's entries, and none of the stratum after it`() {
        val map = assertNotNull(SourceMap.parse(collections))

        assertEquals(listOf(null, null), listOf(map.sourceOf(0), map.sourceOf(20)))
    }

    @ParameterizedTest
    @CsvSource(
        "20, 10",
        "21, 10",
        "22, 11",
        "25, 12",
        "26, ",
        "19, ",
    )
    fun `an entry covers its repeat count times its increment, the increment lines per source line`(
        line: Int,
        sourceLine: Int?,
    ) {
        val map = assertNotNull(SourceMap.parse(smap("+ 1 A.kt\nx/A", "10#1,3:20,2")))

        assertEquals(sourceLine, map.sourceOf(line)?.line)
    }

    @Test
    fun `a Kotlin stratum without line entries maps nothing, not by the next stratum's`() {
        val sourceDebug =
            "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\nx/A\n*S KotlinDebug\n*F\n+ 1 A.kt\nx/A\n*L\n1#1,10:1\n*E\n"

        assertNull(assertNotNull(SourceMap.parse(sourceDebug)).sourceOf(1))
    }

    @Test
    fun `an entry without a file id is in the file of the entry before`() {
        val map = assertNotNull(SourceMap.parse(smap("+ 1 A.kt\nx/A\n+ 2 B.kt\ny/B", "1#1,5:1\n5#2:30\n7:31")))

        assertEquals(listOf("B.kt", "7"), map.sourceOf(31).let { listOf(it?.file?.name, "${it?.line}") })
    }

    @Test
    fun `a file's path is its class's package folder and its name`() {
        val files =
            "+ 1 Comparisons.kt\nkotlin/comparisons/ComparisonsKt__ComparisonsKt\$compareBy\$2\n+ 2 Top.kt\nTopKt"
        val map = assertNotNull(SourceMap.parse(smap(files, "1#1,3:1\n4#2:4")))

        assertEquals(
            listOf("kotlin/comparisons/Comparisons.kt", "Top.kt"),
            listOf(1, 4).map { map.sourceOf(it)?.file?.path },
        )
    }

    @ParameterizedTest
    @CsvSource(
        // No Kotlin stratum.
        "'SMAP\nA.kt\nOther\n*S Other\n*F\n+ 1 A.kt\nx/A\n*L\n1#1,10:1\n*E\n'",
        // No SMAP at all.
        "''",
    )
    fun `without a Kotlin stratum there is no map`(sourceDebug: String) {
        assertNull(SourceMap.parse(sourceDebug.replace("\\n", "\n").ifEmpty { null }))
    }

    @Test
    fun `maps nothing by an entry that is not one or names no file of the F section, and maps by the others`() {
        val map = assertNotNull(SourceMap.parse(smap("+ 1 A.kt\nx/A", "1#1,x:1\n3#9:20\n5#1:9")))

        assertEquals(listOf(null, null, 5), listOf(1, 20, 9).map { map.sourceOf(it)?.line })
    }

    private fun smap(
        files: String,
        lines: String,
    ) = "SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n$files\n*L\n$lines\n*E\n"
}
