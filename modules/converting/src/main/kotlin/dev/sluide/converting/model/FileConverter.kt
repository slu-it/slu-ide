package dev.sluide.converting.model

import dev.sluide.converting.ConversionResult
import dev.sluide.converting.FileConversion
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.OptionValues
import dev.sluide.converting.TextFormat
import dev.sluide.converting.convert
import java.nio.file.Path

/** Converts whole files for the explorer's "Convert to" (ADR 0115). Pure: callable on any thread. */
internal class FileConverter(
    families: List<FormatFamily<*>>,
) {
    private val families = Families(families)

    /** The other formats of the family of [file]'s extension, if that family converts files; else none. */
    fun targets(file: Path): List<TextFormat> {
        val source = sourceOf(file) ?: return emptyList()
        return families.familyOf(source).formats.filter { it.id != source.id }
    }

    fun convert(
        file: Path,
        target: TextFormat,
        options: OptionValues,
    ): FileConversion {
        val name = file.fileName?.toString() ?: file.toString()
        val source = sourceOf(file) ?: return FileConversion.Failed("$name is not a file of a data format")
        val text = readSmallText(file).getOrElse { return FileConversion.Failed(it.message ?: "Cannot read $name") }
        return when (val result = families.familyOf(source).convert(text, source, target, options)) {
            is ConversionResult.Converted -> {
                FileConversion.Converted(result.text, result.notes)
            }

            is ConversionResult.ReadFailed -> {
                val at = result.offset?.let { lineColumn(text, it) }
                val where = at?.let { "Line ${it.first}, column ${it.second}: " }.orEmpty()
                FileConversion.Failed("$name cannot be read as ${source.name}. $where${result.message}")
            }

            is ConversionResult.WriteFailed -> {
                FileConversion.Failed("$name cannot be written as ${target.name}. ${result.message}")
            }
        }
    }

    private fun sourceOf(file: Path): TextFormat? {
        val extension =
            file.fileName
                ?.toString()
                ?.substringAfterLast('.', "")
                ?.ifEmpty { null } ?: return null
        val format = families.formatOf(extension) ?: return null
        return format.takeIf { families.familyOf(it).convertsFiles }
    }
}
