package dev.sluide.translation.opusmt

import dev.sluide.localmodels.Checksum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpusMtCatalogTest {
    private val catalog = OpusMtCatalog.load()

    @Test
    fun `the bundled catalog has 64 pairs with seven pinned files each`() {
        assertEquals(64, catalog.pairs.size)
        val pinned = Regex("https://huggingface\\.co/Xenova/opus-mt-[a-z]+-[a-z]+/resolve/[0-9a-f]{40}/.+")
        for ((pair, files) in catalog.pairs) {
            assertEquals(
                listOf("config.json", "generation_config.json", "tokenizer.json"),
                files.shared.map { it.path }.sorted(),
                pair,
            )
            assertEquals(
                listOf("medium/decoder.onnx", "medium/encoder.onnx"),
                files.medium.map { it.path }.sorted(),
                pair,
            )
            assertEquals(listOf("high/decoder.onnx", "high/encoder.onnx"), files.high.map { it.path }.sorted(), pair)
            for (file in files.shared + files.medium + files.high) {
                val sha256 = (file.checksum as? Checksum.Sha256)?.hex
                assertTrue(sha256?.matches(Regex("[0-9a-f]{64}")) == true, "$pair ${file.path}: ${file.checksum}")
                assertTrue(file.bytes > 0, "$pair ${file.path}")
                assertTrue(file.url.toString().matches(pinned), "$pair ${file.path}: ${file.url}")
                assertTrue("/opus-mt-$pair/" in file.url.toString(), "$pair ${file.path}: ${file.url}")
            }
        }
    }

    @Test
    fun `every language of the catalog has an English name`() {
        val codes = catalog.pairs.keys.flatMap { it.split('-') }
        assertEquals(emptyList(), codes.filter { it !in OpusMtLanguages.names })
    }

    @Test
    fun `sources are English and every language into English, targets English and every language from English`() {
        val sources = OpusMtLanguages.sources(catalog)
        val targets = OpusMtLanguages.targets(catalog)
        assertEquals(26, sources.size)
        assertEquals(21, targets.size)
        assertEquals(sources.sortedBy { it.name }, sources)
        assertEquals(targets.sortedBy { it.name }, targets)
        assertTrue(sources.any { it.code == "ja" && it.name == "Japanese" })
        assertTrue(targets.none { it.code == "ja" })
        assertTrue(targets.any { it.code == "ro" && it.name == "Romanian" })
        assertTrue(sources.none { it.code == "ro" })
        assertTrue(sources.any { it.code == "en" } && targets.any { it.code == "en" })
    }
}
