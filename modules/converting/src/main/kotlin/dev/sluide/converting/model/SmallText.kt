package dev.sluide.converting.model

import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * The UTF-8 text of [file] if it is at most [ConvertingModel.MAX_FILE_SIZE] bytes; else a failure whose message says
 * why not, as a sentence naming the file (too large, not found, access denied, not UTF-8).
 */
internal fun readSmallText(file: Path): Result<String> {
    val name = file.fileName?.toString() ?: file.toString()
    val reason =
        try {
            if (Files.size(file) > ConvertingModel.MAX_FILE_SIZE) return failure("$name is larger than 1 MiB")
            return Result.success(Files.readString(file))
        } catch (_: NoSuchFileException) {
            "file not found"
        } catch (_: AccessDeniedException) {
            "access denied"
        } catch (_: CharacterCodingException) {
            "not UTF-8 text"
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        }
    return failure("Cannot read $name: $reason")
}

private fun failure(message: String): Result<String> = Result.failure(IOException(message))
