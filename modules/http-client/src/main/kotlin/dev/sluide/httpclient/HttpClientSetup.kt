package dev.sluide.httpclient

import dev.sluide.httpclient.env.EnvironmentFiles
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Creates a minimal HTTP client setup in a folder: two environment files, a `.gitignore` line and an example. */
object HttpClientSetup {
    const val EXAMPLE = "example.http"
    private const val GITIGNORE = ".gitignore"
    private const val PUBLIC_TEXT = "{\n  \"local\": {\n    \"host\": \"https://example.com\"\n  }\n}\n"
    private const val PRIVATE_TEXT = "{\n  \"local\": {}\n}\n"
    private const val EXAMPLE_TEXT = "### Example request\nGET {{host}}\nAccept: text/html\n"
    private const val IGNORE_LINE = EnvironmentFiles.PRIVATE

    /** Creates the files that are missing in [folder]; never overwrites. Throws [IOException] on other disk errors. */
    fun create(folder: Path): SetupResult {
        val publicEnv = folder.resolve(EnvironmentFiles.PUBLIC)
        val privateEnv = folder.resolve(EnvironmentFiles.PRIVATE)
        val gitignore = folder.resolve(GITIGNORE)
        val example = folder.resolve(EXAMPLE)
        val written =
            listOf(
                publicEnv to writeNew(publicEnv, PUBLIC_TEXT),
                privateEnv to writeNew(privateEnv, PRIVATE_TEXT),
                gitignore to ensureIgnored(gitignore),
                example to writeNew(example, EXAMPLE_TEXT),
            )
        return SetupResult(
            example = example,
            created = written.filter { it.second }.map { it.first },
            kept = written.filterNot { it.second }.map { it.first },
        )
    }

    /** True when the file was written, false when it already existed. */
    private fun writeNew(
        file: Path,
        text: String,
    ): Boolean =
        try {
            Files.writeString(file, text, StandardOpenOption.CREATE_NEW)
            true
        } catch (_: FileAlreadyExistsException) {
            false
        } catch (e: IOException) {
            throw IOException("Cannot create ${file.fileName}: ${reason(e)}.", e)
        }

    /**
     * True when the line was added or the file created, false when the file lists the private file already. The file
     * is read and appended as bytes, so its encoding stays as it is.
     */
    private fun ensureIgnored(gitignore: Path): Boolean =
        try {
            try {
                Files.writeString(gitignore, "$IGNORE_LINE\n", StandardOpenOption.CREATE_NEW)
                true
            } catch (_: FileAlreadyExistsException) {
                appendIfMissing(gitignore)
            }
        } catch (e: IOException) {
            throw IOException("Cannot update ${gitignore.fileName}: ${reason(e)}.", e)
        }

    private fun appendIfMissing(gitignore: Path): Boolean {
        val text = String(Files.readAllBytes(gitignore), Charsets.ISO_8859_1)
        if (text.lines().any { it.trim() == IGNORE_LINE || it.trim() == "/$IGNORE_LINE" }) return false
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val separator = if (text.isNotEmpty() && !text.endsWith("\n")) newline else ""
        val line = "$separator$IGNORE_LINE$newline"
        Files.write(gitignore, line.toByteArray(Charsets.ISO_8859_1), StandardOpenOption.APPEND)
        return true
    }

    private fun reason(e: IOException): String =
        when (e) {
            is NoSuchFileException -> "the folder does not exist"
            is AccessDeniedException -> "permission denied"
            is FileSystemException -> e.reason ?: e.message ?: e.javaClass.simpleName
            else -> e.message ?: e.javaClass.simpleName
        }

    /** What [create] did; both lists keep the order public env, private env, .gitignore, example. */
    data class SetupResult(
        val example: Path,
        val created: List<Path>,
        val kept: List<Path>,
    ) {
        /** null when nothing was kept, else e.g. "Left as they were: http-client.env.json, example.http". */
        val note: String? =
            if (kept.isEmpty()) null else "Left as they were: " + kept.joinToString(", ") { it.fileName.toString() }
    }
}
