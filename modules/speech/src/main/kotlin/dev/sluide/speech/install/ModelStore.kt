package dev.sluide.speech.install

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the Kokoro model files are kept, by default `~/.sluide/models/kokoro-v1.0`. A file counts as there when it has
 * the expected size; the checksum was verified when it was downloaded (it is not hashed again here).
 */
@Component
class ModelStore(
    val root: Path,
) {
    @Autowired
    constructor() : this(Path.of(System.getProperty("user.home"), ".sluide", "models", "kokoro-v1.0"))

    fun modelFile(v: KokoroVariant): Path = root.resolve(v.fileName)

    fun voicesFile(): Path = root.resolve(KokoroVoicesFile.FILE_NAME)

    /** Whether the model of [v] and the voices file are there. */
    fun installed(v: KokoroVariant): Boolean = missingBytes(v) == 0L

    /** The bytes still to download for [v]: its model and/or the voices file. */
    fun missingBytes(v: KokoroVariant): Long =
        (if (hasSize(modelFile(v), v.bytes)) 0 else v.bytes) +
            (if (hasSize(voicesFile(), KokoroVoicesFile.BYTES)) 0 else KokoroVoicesFile.BYTES)

    /** Deletes the model of [v], and the voices file when no variant is installed any more; blocking. */
    fun delete(v: KokoroVariant) {
        Files.deleteIfExists(modelFile(v))
        if (KokoroVariant.entries.none { hasSize(modelFile(it), it.bytes) }) Files.deleteIfExists(voicesFile())
    }

    private fun hasSize(
        file: Path,
        bytes: Long,
    ): Boolean = Files.isRegularFile(file) && Files.size(file) == bytes
}
