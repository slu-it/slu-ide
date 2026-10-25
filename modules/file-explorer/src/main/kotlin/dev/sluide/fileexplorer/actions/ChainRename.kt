package dev.sluide.fileexplorer.actions

import java.nio.file.Path

/**
 * Why [text], new names for the folders of a compacted row's [chain] joined by `/` (ADR 0096), cannot be used; `null`
 * if it can. There must be as many names as folders; each is checked like a rename in its folder
 * ([FileOperations.nameProblem]).
 */
internal fun chainNameProblem(
    text: String,
    chain: List<Path>,
): String? {
    val names = text.split('/')
    if (names.size != chain.size) {
        return "Keep the ${chain.size} folder names separated by “/”: folders cannot be added or removed here."
    }
    return names.zip(chain).firstNotNullOfOrNull { (name, folder) ->
        FileOperations.nameProblem(name, checkNotNull(folder.parent) { "$folder has no folder" }, renaming = folder)
    }
}
