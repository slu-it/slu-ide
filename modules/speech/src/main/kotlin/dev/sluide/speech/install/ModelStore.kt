package dev.sluide.speech.install

import dev.sluide.localmodels.ModelFile
import dev.sluide.localmodels.ModelSpecs
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the Kokoro model files are kept, by default `~/.sluide/models/kokoro-v1.0`, and what the installer downloads
 * for each variant (ADR 0125). A file counts as there when it has the expected size; the checksum was verified when it
 * was downloaded (it is not hashed again here).
 */
@Component
class ModelStore(
    val root: Path,
) : ModelSpecs<KokoroVariant> {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "models", "kokoro-v1.0"))

    fun modelFile(v: KokoroVariant): Path = root.resolve(v.fileName)

    fun voicesFile(): Path = root.resolve(KokoroVoicesFile.FILE_NAME)

    /** The model of [key] and the shared voices file. */
    override fun files(key: KokoroVariant): List<ModelFile> = kokoroFiles(key)

    override fun folder(key: KokoroVariant): Path = root

    /** Whether the model of [key] and the voices file are there. */
    override fun installed(key: KokoroVariant): Boolean = missingBytes(key) == 0L

    override fun taskName(
        key: KokoroVariant,
        percent: Int,
    ) = "Downloading Kokoro model $percent %"

    /** The bytes still to download for [v]: its model and/or the voices file. */
    fun missingBytes(v: KokoroVariant): Long =
        (if (hasSize(modelFile(v), v.bytes)) 0 else v.bytes) +
            (if (hasSize(voicesFile(), KokoroVoicesFile.BYTES)) 0 else KokoroVoicesFile.BYTES)

    /**
     * What a delete of [v] keeps: the voices file while another variant is installed, or while a download runs, which
     * may rely on it.
     */
    fun keptOnDelete(
        v: KokoroVariant,
        downloadsRunning: Boolean,
    ): Set<String> {
        val shared = downloadsRunning || KokoroVariant.entries.any { it != v && hasSize(modelFile(it), it.bytes) }
        return if (shared) setOf(KokoroVoicesFile.FILE_NAME) else emptySet()
    }

    private fun hasSize(
        file: Path,
        bytes: Long,
    ): Boolean = Files.isRegularFile(file) && Files.size(file) == bytes
}
