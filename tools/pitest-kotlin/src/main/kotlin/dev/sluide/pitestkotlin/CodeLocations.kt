package dev.sluide.pitestkotlin

import org.objectweb.asm.tree.ClassNode
import org.pitest.mutationtest.engine.MutationDetails

/**
 * Where the code of a class's mutants was written. A line number of a class that inlines code stands for a line of
 * whichever file the class's SMAP maps it to: its own source file, a file of ours whose inline function or lambda was
 * inlined, or a library's. KOTLIN_JUNK and LINES both judge a mutant by this location; neither relies on the other
 * having run first.
 */
internal class CodeLocations(
    clazz: ClassNode,
) {
    private val map = SourceMap.parse(clazz.sourceDebug)
    private val packagePath = clazz.name.substringBeforeLast('/', "")
    private val ownPath = pathOf(packagePath, clazz.sourceFile.orEmpty())

    /** Where [mutant]'s code was written. One whose file name is already a path (moved by KOTLIN_JUNK) stays there. */
    fun of(mutant: MutationDetails): CodeLocation {
        val file = mutant.filename
        if ('/' in file) return CodeLocation(file, mutant.lineNumber, file, own = false)
        val source =
            map?.sourceOf(mutant.lineNumber)
                ?: return CodeLocation(pathOf(packagePath, file), mutant.lineNumber, file, own = true)
        val own = source.file.path == ownPath
        return CodeLocation(source.file.path, source.line, if (own) source.file.name else source.file.path, own)
    }
}

/**
 * A mutant's code: the [path] of its file from the source root, its [line] there, and the [fileName] to report: the
 * simple name in the class's [own] source file, else the path.
 */
internal data class CodeLocation(
    val path: String,
    val line: Int,
    val fileName: String,
    val own: Boolean,
) {
    /** Code of a library inlined into the class: from a file that is neither the class's own nor one of ours. */
    val isLibraryCode: Boolean get() = !own && !path.startsWith(OUR_PACKAGES)
}

/** The source folders of our code; everything else is a library's. */
private const val OUR_PACKAGES = "dev/sluide/"

private fun pathOf(
    packagePath: String,
    fileName: String,
) = listOf(packagePath, fileName).filter(String::isNotEmpty).joinToString("/")
